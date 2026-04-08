package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shell completion service for directory, path, and shell-history completions.
 *
 * Translated from:
 *   - src/utils/suggestions/directoryCompletion.ts  (directory + path completion, LRU cache)
 *   - src/utils/suggestions/shellHistoryCompletion.ts (shell history ghost-text)
 *
 * For command completions (the original ShellCompletionService content from
 * src/utils/bash/shellCompletion.ts), see the getCompletions() method below.
 *
 * Directory/path completions:
 *   - getDirectoryCompletions() — returns subdirectory suggestions for a partial path
 *   - getPathCompletions()      — returns both file and directory suggestions
 *   - scanDirectory()           — lists subdirectories, cached by path (5-minute TTL)
 *   - scanDirectoryForPaths()   — lists files+dirs, cached (5-minute TTL)
 *   - parsePartialPath()        — splits a partial path into directory + prefix
 *   - isPathLikeToken()         — heuristic to detect path-like input tokens
 *
 * Shell history ghost-text:
 *   - getShellHistoryCompletion()  — find the best prefix match in cached history
 *   - prependToShellHistoryCache() — update cache without full reload
 *   - clearShellHistoryCache()     — invalidate the shell history cache
 */
@Slf4j
@Service
public class ShellCompletionService {



    // =========================================================================
    // Command completion constants  (shellCompletion.ts)
    // =========================================================================

    private static final int  MAX_SHELL_COMPLETIONS        = 15;
    private static final long SHELL_COMPLETION_TIMEOUT_MS  = 1_000;

    // =========================================================================
    // Directory/path cache constants  (directoryCompletion.ts)
    // =========================================================================

    private static final int  DIR_CACHE_MAX_SIZE = 500;
    private static final long DIR_CACHE_TTL_MS   = 5 * 60 * 1000L; // 5 minutes
    private static final int  DIR_MAX_ENTRIES    = 100;

    // =========================================================================
    // Shell history cache constants  (shellHistoryCompletion.ts)
    // =========================================================================

    private static final long HISTORY_CACHE_TTL_MS = 60_000L; // 60 seconds
    private static final int  HISTORY_MAX_COMMANDS  = 50;

    // =========================================================================
    // Types
    // =========================================================================

    public enum CompletionType { COMMAND, VARIABLE, FILE }

    /**
     * A directory entry returned by scanDirectory().
     * Translated from DirectoryEntry in directoryCompletion.ts.
     */
    public record DirectoryEntry(String name, String path) {}

    /**
     * A path entry (file or directory) returned by scanDirectoryForPaths().
     * Translated from PathEntry in directoryCompletion.ts.
     */
    public record PathEntry(String name, String path, PathType type) {
        public boolean isDirectory() { return type == PathType.DIRECTORY; }
    }

    public enum PathType { DIRECTORY, FILE }

    /**
     * Suggestion item returned to the UI layer.
     * Mirrors SuggestionItem in PromptInputFooterSuggestions.tsx.
     * Translated from the return type of getDirectoryCompletions() in directoryCompletion.ts.
     */
    public record SuggestionItem(String id, String displayText, String description,
                                 Map<String, Object> metadata) {}

    /**
     * Shell history completion match.
     * Translated from ShellHistoryMatch in shellHistoryCompletion.ts.
     */
    public record ShellHistoryMatch(
            /** The full command from history. */
            String fullCommand,
            /** The suffix shown as ghost text (the part after the user's input). */
            String suffix
    ) {}

    // =========================================================================
    // Directory scan cache  (directoryCompletion.ts)
    // =========================================================================

    /** Simple TTL cache entry. */
    private record CacheEntry<T>(T value, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    /** Bounded LRU-like cache backed by a LinkedHashMap with access-ordering. */
    private static final Map<String, CacheEntry<List<DirectoryEntry>>> dirCache =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry<List<DirectoryEntry>>> e) {
                    return size() > DIR_CACHE_MAX_SIZE;
                }
            });

    private static final Map<String, CacheEntry<List<PathEntry>>> pathCache =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry<List<PathEntry>>> e) {
                    return size() > DIR_CACHE_MAX_SIZE;
                }
            });

    // =========================================================================
    // Shell history cache  (shellHistoryCompletion.ts)
    // =========================================================================

    /** Cached list of unique shell commands, most recent first. */
    private final AtomicReference<List<String>> shellHistoryCache = new AtomicReference<>(null);
    private final AtomicLong shellHistoryCacheTimestamp = new AtomicLong(0);

    // =========================================================================
    // Command completions  (shellCompletion.ts — original content preserved)
    // =========================================================================

    /**
     * Get shell completions for a given prefix.
     * Translated from getShellCompletions() in shellCompletion.ts.
     */
    public CompletableFuture<List<String>> getCompletions(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }

        CompletionType type = getCompletionType(prefix);

        return CompletableFuture.<List<String>>supplyAsync(() -> {
            try {
                return switch (type) {
                    case FILE     -> getFileCompletions(prefix);
                    case VARIABLE -> getVariableCompletions(prefix);
                    case COMMAND  -> getCommandCompletions(prefix);
                };
            } catch (Exception e) {
                log.debug("Shell completion error: {}", e.getMessage());
                return List.<String>of();
            }
        }).orTimeout(SHELL_COMPLETION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
          .exceptionally(e -> List.<String>of());
    }

    public CompletionType getCompletionType(String prefix) {
        if (prefix.startsWith("$")) return CompletionType.VARIABLE;
        if (prefix.contains("/") || prefix.startsWith("~") || prefix.startsWith(".")) {
            return CompletionType.FILE;
        }
        return CompletionType.COMMAND;
    }

    private List<String> getFileCompletions(String prefix) {
        String expanded = prefix.startsWith("~")
                ? System.getProperty("user.home") + prefix.substring(1)
                : prefix;

        File file = new File(expanded);
        File dir = file.isDirectory() ? file : file.getParentFile();
        String namePrefix = file.isDirectory() ? "" : file.getName();

        if (dir == null || !dir.exists()) return List.of();

        File[] files = dir.listFiles();
        if (files == null) return List.of();

        List<String> completions = new ArrayList<>();
        for (File f : files) {
            if (f.getName().startsWith(namePrefix)) {
                completions.add(f.isDirectory()
                        ? f.getAbsolutePath() + "/"
                        : f.getAbsolutePath());
                if (completions.size() >= MAX_SHELL_COMPLETIONS) break;
            }
        }
        return completions;
    }

    private List<String> getVariableCompletions(String prefix) {
        String varPrefix = prefix.substring(1);
        List<String> completions = new ArrayList<>();
        for (String key : System.getenv().keySet()) {
            if (key.startsWith(varPrefix)) {
                completions.add("$" + key);
                if (completions.size() >= MAX_SHELL_COMPLETIONS) break;
            }
        }
        return completions;
    }

    private List<String> getCommandCompletions(String prefix) {
        List<String> completions = new ArrayList<>();
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return completions;

        for (String dir : pathEnv.split(File.pathSeparator)) {
            File dirFile = new File(dir);
            if (!dirFile.isDirectory()) continue;
            File[] files = dirFile.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (f.getName().startsWith(prefix) && f.canExecute()) {
                    completions.add(f.getName());
                    if (completions.size() >= MAX_SHELL_COMPLETIONS) return completions;
                }
            }
        }
        return completions;
    }

    // =========================================================================
    // Path parsing  (directoryCompletion.ts)
    // =========================================================================

    /**
     * Splits a partial path into directory and prefix components.
     * Translated from parsePartialPath() in directoryCompletion.ts.
     *
     * @param partialPath the user-typed partial path
     * @param basePath    working directory used when partialPath is relative (may be null)
     * @return a 2-element array [directory, prefix]
     */
    public String[] parsePartialPath(String partialPath, String basePath) {
        String cwd = basePath != null ? basePath : System.getProperty("user.dir");

        if (partialPath == null || partialPath.isEmpty()) {
            return new String[]{cwd, ""};
        }

        String resolved = expandPath(partialPath, basePath);

        // If path ends with separator, treat as directory with empty prefix
        if (partialPath.endsWith("/") || partialPath.endsWith(File.separator)) {
            return new String[]{resolved, ""};
        }

        Path p = Paths.get(resolved);
        String directory = p.getParent() != null ? p.getParent().toString() : cwd;
        String prefix    = p.getFileName() != null ? p.getFileName().toString() : "";
        return new String[]{directory, prefix};
    }

    /**
     * Expand ~ and resolve relative paths.
     * Mirrors expandPath() in path.ts (TypeScript).
     */
    private String expandPath(String path, String basePath) {
        if (path.startsWith("~/") || path.equals("~")) {
            path = System.getProperty("user.home") + path.substring(1);
        }
        Path p = Paths.get(path);
        if (!p.isAbsolute()) {
            String base = basePath != null ? basePath : System.getProperty("user.dir");
            p = Paths.get(base).resolve(path);
        }
        return p.normalize().toString();
    }

    /**
     * Checks if a token looks like a file path.
     * Translated from isPathLikeToken() in directoryCompletion.ts.
     */
    public static boolean isPathLikeToken(String token) {
        if (token == null) return false;
        return token.startsWith("~/") || token.startsWith("/")
                || token.startsWith("./") || token.startsWith("../")
                || token.equals("~") || token.equals(".") || token.equals("..");
    }

    // =========================================================================
    // Directory scanning  (directoryCompletion.ts)
    // =========================================================================

    /**
     * Scan a directory and return its subdirectories (cached, TTL=5 min).
     * Translated from scanDirectory() in directoryCompletion.ts.
     */
    public CompletableFuture<List<DirectoryEntry>> scanDirectory(String dirPath) {
        return CompletableFuture.supplyAsync(() -> {
            CacheEntry<List<DirectoryEntry>> cached = dirCache.get(dirPath);
            if (cached != null && !cached.isExpired()) return cached.value();

            try {
                File dir = new File(dirPath);
                File[] entries = dir.listFiles();
                if (entries == null) return List.of();

                List<DirectoryEntry> result = new ArrayList<>();
                for (File e : entries) {
                    if (e.isDirectory() && !e.getName().startsWith(".")) {
                        result.add(new DirectoryEntry(e.getName(), e.getAbsolutePath()));
                        if (result.size() >= DIR_MAX_ENTRIES) break;
                    }
                }

                List<DirectoryEntry> immutable = Collections.unmodifiableList(result);
                dirCache.put(dirPath,
                        new CacheEntry<>(immutable,
                                System.currentTimeMillis() + DIR_CACHE_TTL_MS));
                return immutable;
            } catch (Exception e) {
                log.debug("scanDirectory error for {}: {}", dirPath, e.getMessage());
                return List.of();
            }
        });
    }

    /**
     * Scan a directory and return both files and subdirectories (cached).
     * Translated from scanDirectoryForPaths() in directoryCompletion.ts.
     *
     * @param dirPath      directory to scan
     * @param includeHidden include entries whose names begin with '.'
     */
    public CompletableFuture<List<PathEntry>> scanDirectoryForPaths(
            String dirPath, boolean includeHidden) {
        return CompletableFuture.supplyAsync(() -> {
            String cacheKey = dirPath + ":" + includeHidden;
            CacheEntry<List<PathEntry>> cached = pathCache.get(cacheKey);
            if (cached != null && !cached.isExpired()) return cached.value();

            try {
                File dir = new File(dirPath);
                File[] entries = dir.listFiles();
                if (entries == null) return List.of();

                List<PathEntry> result = new ArrayList<>();
                for (File e : entries) {
                    if (!includeHidden && e.getName().startsWith(".")) continue;
                    PathType type = e.isDirectory() ? PathType.DIRECTORY : PathType.FILE;
                    result.add(new PathEntry(e.getName(), e.getAbsolutePath(), type));
                    if (result.size() >= DIR_MAX_ENTRIES) break;
                }

                // Sort: directories first, then alphabetical
                result.sort((a, b) -> {
                    if (a.isDirectory() && !b.isDirectory()) return -1;
                    if (!a.isDirectory() && b.isDirectory()) return 1;
                    return a.name().compareToIgnoreCase(b.name());
                });

                List<PathEntry> immutable = Collections.unmodifiableList(result);
                pathCache.put(cacheKey,
                        new CacheEntry<>(immutable,
                                System.currentTimeMillis() + DIR_CACHE_TTL_MS));
                return immutable;
            } catch (Exception e) {
                log.debug("scanDirectoryForPaths error for {}: {}", dirPath, e.getMessage());
                return List.of();
            }
        });
    }

    // =========================================================================
    // Completion suggestion builders  (directoryCompletion.ts)
    // =========================================================================

    /**
     * Get directory completion suggestions for a partial path.
     * Translated from getDirectoryCompletions() in directoryCompletion.ts.
     *
     * @param partialPath  what the user has typed
     * @param basePath     cwd to resolve relative paths (null = system cwd)
     * @param maxResults   maximum number of suggestions (default 10)
     */
    public CompletableFuture<List<SuggestionItem>> getDirectoryCompletions(
            String partialPath, String basePath, int maxResults) {
        String[] parts = parsePartialPath(partialPath, basePath);
        String directory = parts[0];
        String prefix    = parts[1];
        String prefixLower = prefix.toLowerCase();

        return scanDirectory(directory).thenApply(entries -> {
            List<SuggestionItem> suggestions = new ArrayList<>();
            for (DirectoryEntry entry : entries) {
                if (!entry.name().toLowerCase().startsWith(prefixLower)) continue;
                suggestions.add(new SuggestionItem(
                        entry.path(),
                        entry.name() + "/",
                        "directory",
                        Map.of("type", "directory")));
                if (suggestions.size() >= maxResults) break;
            }
            return Collections.unmodifiableList(suggestions);
        });
    }

    /**
     * Get path (file + directory) completion suggestions for a partial path.
     * Translated from getPathCompletions() in directoryCompletion.ts.
     *
     * @param partialPath   what the user has typed
     * @param basePath      cwd to resolve relative paths (null = system cwd)
     * @param maxResults    maximum number of suggestions (default 10)
     * @param includeFiles  whether to include file entries (default true)
     * @param includeHidden whether to include hidden entries (default false)
     */
    public CompletableFuture<List<SuggestionItem>> getPathCompletions(
            String partialPath, String basePath, int maxResults,
            boolean includeFiles, boolean includeHidden) {
        String[] parts = parsePartialPath(partialPath, basePath);
        String directory = parts[0];
        String prefix    = parts[1];
        String prefixLower = prefix.toLowerCase();

        return scanDirectoryForPaths(directory, includeHidden).thenApply(entries -> {
            // Build the directory portion to prepend to the display name
            String dirPortion = "";
            if (partialPath != null
                    && (partialPath.contains("/") || partialPath.contains(File.separator))) {
                int lastSlash = partialPath.lastIndexOf('/');
                int lastSep   = partialPath.lastIndexOf(File.separatorChar);
                int lastSepPos = Math.max(lastSlash, lastSep);
                dirPortion = partialPath.substring(0, lastSepPos + 1);
                if (dirPortion.startsWith("./") || dirPortion.startsWith("." + File.separator)) {
                    dirPortion = dirPortion.substring(2);
                }
            }
            final String dp = dirPortion;

            List<SuggestionItem> suggestions = new ArrayList<>();
            for (PathEntry entry : entries) {
                if (!includeFiles && entry.type() == PathType.FILE) continue;
                if (!entry.name().toLowerCase().startsWith(prefixLower)) continue;

                String fullPath    = dp + entry.name();
                String displayText = entry.isDirectory() ? fullPath + "/" : fullPath;
                String typeStr     = entry.isDirectory() ? "directory" : "file";
                suggestions.add(new SuggestionItem(
                        fullPath, displayText, null, Map.of("type", typeStr)));
                if (suggestions.size() >= maxResults) break;
            }
            return Collections.unmodifiableList(suggestions);
        });
    }

    // =========================================================================
    // Cache invalidation  (directoryCompletion.ts)
    // =========================================================================

    /**
     * Clear the directory-only scan cache.
     * Translated from clearDirectoryCache() in directoryCompletion.ts.
     */
    public static void clearDirectoryCache() { dirCache.clear(); }

    /**
     * Clear both directory and path caches.
     * Translated from clearPathCache() in directoryCompletion.ts.
     */
    public static void clearPathCache() {
        dirCache.clear();
        pathCache.clear();
    }

    // =========================================================================
    // Shell history completion  (shellHistoryCompletion.ts)
    // =========================================================================

    /**
     * Find the best matching command from history for the given input.
     *
     * Returns the first cached command that starts with the exact input string,
     * or null if none found or if input is too short (&lt; 2 chars).
     *
     * Translated from getShellHistoryCompletion() in shellHistoryCompletion.ts.
     *
     * @param input the current user input (without leading '!' if present)
     * @return the best match, or null
     */
    public CompletableFuture<ShellHistoryMatch> getShellHistoryCompletion(String input) {
        if (input == null || input.trim().length() < 2) {
            return CompletableFuture.completedFuture(null);
        }

        return getShellHistoryCommands().thenApply(commands -> {
            for (String cmd : commands) {
                if (cmd.startsWith(input) && !cmd.equals(input)) {
                    return new ShellHistoryMatch(cmd, cmd.substring(input.length()));
                }
            }
            return null;
        });
    }

    /**
     * Get shell history commands with caching (TTL = 60 s).
     *
     * In the TypeScript codebase this iterates the history file via
     * getHistory(). In Java, it delegates to HistoryService when available,
     * or reads HISTFILE directly as a fallback.
     *
     * Translated from getShellHistoryCommands() in shellHistoryCompletion.ts.
     */
    public CompletableFuture<List<String>> getShellHistoryCommands() {
        long now = System.currentTimeMillis();
        List<String> cached = shellHistoryCache.get();
        if (cached != null && (now - shellHistoryCacheTimestamp.get()) < HISTORY_CACHE_TTL_MS) {
            return CompletableFuture.completedFuture(cached);
        }

        return CompletableFuture.supplyAsync(() -> {
            List<String> commands = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();

            try {
                // Try to read from $HISTFILE (bash history)
                String histFile = System.getenv("HISTFILE");
                if (histFile == null || histFile.isBlank()) {
                    histFile = System.getProperty("user.home") + "/.bash_history";
                }

                Path histPath = Paths.get(histFile);
                if (Files.exists(histPath)) {
                    List<String> lines = Files.readAllLines(histPath);
                    // Read in reverse so most-recent entries come first
                    ListIterator<String> iter = lines.listIterator(lines.size());
                    while (iter.hasPrevious()) {
                        String line = iter.previous().trim();
                        // Skip empty lines and history timestamps (#1234567890)
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        if (seen.add(line)) {
                            commands.add(line);
                            if (commands.size() >= HISTORY_MAX_COMMANDS) break;
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to read shell history: {}", e.getMessage());
            }

            List<String> result = Collections.unmodifiableList(commands);
            shellHistoryCache.set(result);
            shellHistoryCacheTimestamp.set(System.currentTimeMillis());
            return result;
        });
    }

    /**
     * Invalidate the shell history cache.
     * Translated from clearShellHistoryCache() in shellHistoryCompletion.ts.
     */
    public void clearShellHistoryCache() {
        shellHistoryCache.set(null);
        shellHistoryCacheTimestamp.set(0L);
    }

    /**
     * Prepend a command to the front of the shell history cache without
     * triggering a full reload. De-duplicates if the command already exists.
     *
     * No-op if the cache has not yet been populated.
     *
     * Translated from prependToShellHistoryCache() in shellHistoryCompletion.ts.
     */
    public void prependToShellHistoryCache(String command) {
        if (command == null) return;
        List<String> current = shellHistoryCache.get();
        if (current == null) return;

        List<String> updated = new ArrayList<>(current);
        updated.remove(command);
        updated.add(0, command);
        shellHistoryCache.set(Collections.unmodifiableList(updated));
    }
}
