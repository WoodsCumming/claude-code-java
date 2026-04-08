package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;

/**
 * Context suggestions service — file/path suggestions and unified (file + MCP resource + agent)
 * suggestion generation for the prompt input.
 *
 * Translated from:
 *   - src/hooks/fileSuggestions.ts   (file/path index, cache management)
 *   - src/hooks/unifiedSuggestions.ts (multi-source ranked suggestions)
 */
@Slf4j
@Service
public class ContextSuggestionsService {



    // =========================================================================
    // fileSuggestions.ts constants
    // =========================================================================

    private static final int MAX_SUGGESTIONS = 15;
    private static final long REFRESH_THROTTLE_MS = 5_000L;

    // =========================================================================
    // fileSuggestions.ts — file index state
    // =========================================================================

    /** Lazily constructed file index (replace with actual file-index impl). */
    private volatile FileIndex fileIndex = null;
    private volatile CompletableFuture<FileIndex> fileListRefreshPromise = null;
    private volatile int cacheGeneration = 0;

    private volatile CompletableFuture<Void> untrackedFetchPromise = null;
    private List<String> cachedTrackedFiles = new ArrayList<>();
    private List<String> cachedConfigFiles = new ArrayList<>();
    private List<String> cachedTrackedDirs = new ArrayList<>();
    private volatile long lastRefreshMs = 0;
    private volatile Long lastGitIndexMtime = null;
    private volatile String loadedTrackedSignature = null;
    private volatile String loadedMergedSignature = null;

    /** Index build-complete subscribers (replaces createSignal / onIndexBuildComplete). */
    private final List<Runnable> indexBuildListeners = new CopyOnWriteArrayList<>();

    // =========================================================================
    // unifiedSuggestions.ts constants
    // =========================================================================

    private static final int MAX_UNIFIED_SUGGESTIONS = 15;
    private static final int DESCRIPTION_MAX_LENGTH = 60;

    // =========================================================================
    // Domain types
    // =========================================================================

    /**
     * A suggestion item for the prompt input footer.
     * Translated from SuggestionItem in PromptInputFooterSuggestions.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SuggestionItem {
        private String id;
        private String displayText;
        private String description;
        private Object color;    // Theme key string, may be null
        private Object metadata; // e.g. { score: double }

        public String getId() { return id; }
        public void setId(String v) { id = v; }
        public String getDisplayText() { return displayText; }
        public void setDisplayText(String v) { displayText = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
        public Object getColor() { return color; }
        public void setColor(Object v) { color = v; }
        public Object getMetadata() { return metadata; }
        public void setMetadata(Object v) { metadata = v; }
    
    }

    /**
     * Source discriminant for file suggestions.
     * Translated from FileSuggestionSource in unifiedSuggestions.ts
     */
    public record FileSuggestionSource(
            String displayText,
            String description,
            String path,
            String filename,
            Double score       // may be null
    ) {}

    /**
     * Source discriminant for MCP resource suggestions.
     * Translated from McpResourceSuggestionSource in unifiedSuggestions.ts
     */
    public record McpResourceSuggestionSource(
            String displayText,
            String description,
            String server,
            String uri,
            String name
    ) {}

    /**
     * Source discriminant for agent suggestions.
     * Translated from AgentSuggestionSource in unifiedSuggestions.ts
     */
    public record AgentSuggestionSource(
            String displayText,
            String description,
            String agentType,
            String color       // may be null
    ) {}

    /**
     * Describes a server resource from an MCP server.
     * Translated from ServerResource in mcp/types.ts
     */
    public record ServerResource(
            String server,
            String uri,
            String name,
            String description
    ) {}

    /**
     * Minimal agent definition for suggestion generation.
     * Translated from AgentDefinition in AgentTool/loadAgentsDir.ts
     */
    public record AgentDefinition(
            String agentType,
            String whenToUse
    ) {}

    /**
     * Lightweight file index interface — callers provide the actual implementation.
     * Mirrors the FileIndex native module from file-index/index.ts
     */
    public interface FileIndex {
        List<FileSearchResult> search(String query, int limit);
        CompletableFuture<Void> loadFromFileListAsync(List<String> paths);
    }

    public record FileSearchResult(String path, double score) {}

    // =========================================================================
    // fileSuggestions.ts — cache management
    // =========================================================================

    /**
     * Clear all file suggestion caches.
     * Translated from clearFileSuggestionCaches() in fileSuggestions.ts
     */
    public synchronized void clearFileSuggestionCaches() {
        fileIndex = null;
        fileListRefreshPromise = null;
        cacheGeneration++;
        untrackedFetchPromise = null;
        cachedTrackedFiles = new ArrayList<>();
        cachedConfigFiles = new ArrayList<>();
        cachedTrackedDirs = new ArrayList<>();
        lastRefreshMs = 0;
        lastGitIndexMtime = null;
        loadedTrackedSignature = null;
        loadedMergedSignature = null;
        indexBuildListeners.forEach(Runnable::run);
    }

    /**
     * Subscribe to index-build-complete events.
     * Translated from onIndexBuildComplete in fileSuggestions.ts
     *
     * @return unsubscribe runnable
     */
    public Runnable onIndexBuildComplete(Runnable listener) {
        indexBuildListeners.add(listener);
        return () -> indexBuildListeners.remove(listener);
    }

    /**
     * Content hash of a path list (FNV-1a sampled at every ~500th path).
     * Translated from pathListSignature() in fileSuggestions.ts
     */
    public static String pathListSignature(List<String> paths) {
        int n = paths.size();
        int stride = Math.max(1, n / 500);
        int h = 0x811c9dc5;
        for (int i = 0; i < n; i += stride) {
            String p = paths.get(i);
            for (int j = 0; j < p.length(); j++) {
                h = (h ^ p.charAt(j)) * 0x01000193;
            }
            h = h * 0x01000193;
        }
        // Always include the last path
        if (n > 0) {
            String last = paths.get(n - 1);
            for (int j = 0; j < last.length(); j++) {
                h = (h ^ last.charAt(j)) * 0x01000193;
            }
        }
        return n + ":" + Integer.toHexString(h >>> 0);
    }

    /**
     * Collect unique parent directory names from a list of file paths,
     * appending the platform file separator.
     * Translated from getDirectoryNames() in fileSuggestions.ts
     */
    public static List<String> getDirectoryNames(List<String> files) {
        Set<String> dirs = new LinkedHashSet<>();
        collectDirectoryNames(files, 0, files.size(), dirs);
        List<String> result = new ArrayList<>();
        dirs.forEach(d -> result.add(d + File.separator));
        return result;
    }

    /**
     * Async variant: yields periodically to avoid blocking on large lists.
     * Translated from getDirectoryNamesAsync() in fileSuggestions.ts
     */
    public CompletableFuture<List<String>> getDirectoryNamesAsync(List<String> files) {
        return CompletableFuture.supplyAsync(() -> {
            Set<String> dirs = new LinkedHashSet<>();
            for (int i = 0; i < files.size(); i++) {
                collectDirectoryNames(files, i, i + 1, dirs);
            }
            List<String> result = new ArrayList<>();
            dirs.forEach(d -> result.add(d + File.separator));
            return result;
        });
    }

    private static void collectDirectoryNames(List<String> files, int start, int end,
                                               Set<String> out) {
        for (int i = start; i < end; i++) {
            Path current = Path.of(files.get(i)).getParent();
            while (current != null && !current.toString().equals(".")
                    && !out.contains(current.toString())) {
                Path parent = current.getParent();
                if (parent == null || parent.equals(current)) break;
                out.add(current.toString());
                current = parent;
            }
        }
    }

    /**
     * Finds the longest common display-text prefix among a list of suggestion items.
     * Translated from findLongestCommonPrefix() in fileSuggestions.ts
     */
    public static String findLongestCommonPrefix(List<SuggestionItem> suggestions) {
        if (suggestions.isEmpty()) return "";
        String prefix = suggestions.get(0).getDisplayText();
        for (int i = 1; i < suggestions.size(); i++) {
            String s = suggestions.get(i).getDisplayText();
            int j = 0;
            int min = Math.min(prefix.length(), s.length());
            while (j < min && prefix.charAt(j) == s.charAt(j)) j++;
            prefix = prefix.substring(0, j);
            if (prefix.isEmpty()) return "";
        }
        return prefix;
    }

    /**
     * Generate file suggestions for a partial path query.
     * Translated from generateFileSuggestions() in fileSuggestions.ts
     *
     * @param fileIdx      the current file index (may be null — returns empty)
     * @param partialPath  partial path typed by the user
     * @param showOnEmpty  true when called from '@' insertion (show on empty query)
     * @return matching SuggestionItems (at most MAX_SUGGESTIONS)
     */
    public List<SuggestionItem> generateFileSuggestions(
            FileIndex fileIdx, String partialPath, boolean showOnEmpty) {

        if ((partialPath == null || partialPath.isBlank()) && !showOnEmpty) {
            return List.of();
        }
        if (fileIdx == null) return List.of();

        // Strip "./" or ".\" prefix
        String normalized = partialPath != null ? partialPath : "";
        if (normalized.startsWith("." + File.separator) || normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }

        try {
            List<FileSearchResult> results = fileIdx.search(normalized, MAX_SUGGESTIONS);
            return results.stream()
                    .map(r -> {
                        Map<String, Object> meta = Map.of("score", r.score());
                        return new SuggestionItem("file-" + r.path(), r.path(), null, null, meta);
                    })
                    .toList();
        } catch (Exception e) {
            log.error("generateFileSuggestions failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Apply a file suggestion — replaces the partial path in the input string.
     * Translated from applyFileSuggestion() in fileSuggestions.ts
     *
     * @param suggestionText  selected suggestion display text
     * @param input           full current input string
     * @param partialPath     the partial path that was typed
     * @param startPos        start index of the partial path in {@code input}
     * @return the new input string with the partial path replaced
     */
    public static String applyFileSuggestion(String suggestionText, String input,
                                              String partialPath, int startPos) {
        String before = input.substring(0, startPos);
        String after = input.substring(startPos + (partialPath != null ? partialPath.length() : 0));
        return before + suggestionText + after;
    }

    // =========================================================================
    // startBackgroundCacheRefresh (fileSuggestions.ts)
    // =========================================================================

    /**
     * Kick a background refresh of the file index if not already running.
     * Throttled: skips refresh unless git state changed or 5 s have elapsed.
     * Translated from startBackgroundCacheRefresh() in fileSuggestions.ts
     *
     * @param pathProvider   supplies the file list (git ls-files or ripgrep)
     * @param indexSupplier  supplies the FileIndex singleton
     */
    public synchronized void startBackgroundCacheRefresh(
            java.util.function.Supplier<CompletableFuture<List<String>>> pathProvider,
            java.util.function.Supplier<FileIndex> indexSupplier) {

        if (fileListRefreshPromise != null) return;

        if (fileIndex != null) {
            boolean timePassed = System.currentTimeMillis() - lastRefreshMs >= REFRESH_THROTTLE_MS;
            if (!timePassed) return;
        }

        int generation = cacheGeneration;
        long refreshStart = System.currentTimeMillis();
        fileIndex = indexSupplier.get();

        fileListRefreshPromise = pathProvider.get()
                .thenCompose(paths -> {
                    if (generation != cacheGeneration) {
                        return CompletableFuture.completedFuture(fileIndex);
                    }
                    String sig = pathListSignature(paths);
                    if (sig.equals(loadedTrackedSignature)) {
                        log.debug("[FileIndex] skipped index rebuild — tracked paths unchanged");
                        return CompletableFuture.completedFuture(fileIndex);
                    }
                    return fileIndex.loadFromFileListAsync(paths)
                            .thenApply(v -> {
                                loadedTrackedSignature = sig;
                                loadedMergedSignature = null;
                                return fileIndex;
                            });
                })
                .thenApply(index -> {
                    if (generation == cacheGeneration) {
                        fileListRefreshPromise = null;
                        lastRefreshMs = System.currentTimeMillis();
                        indexBuildListeners.forEach(Runnable::run);
                        log.debug("[FileIndex] cache refresh completed in {}ms",
                                System.currentTimeMillis() - refreshStart);
                    }
                    return index;
                })
                .exceptionally(error -> {
                    log.error("[FileIndex] cache refresh failed: {}", error.getMessage(), error);
                    if (generation == cacheGeneration) fileListRefreshPromise = null;
                    return fileIndex;
                });
    }

    // =========================================================================
    // unifiedSuggestions.ts
    // =========================================================================

    /**
     * Generate unified suggestions combining files, MCP resources, and agents.
     * Translated from generateUnifiedSuggestions() in unifiedSuggestions.ts
     *
     * @param query        text typed after '@'
     * @param fileIdx      current file index (may be null)
     * @param mcpResources map of server-name → list of resources
     * @param agents       loaded agent definitions
     * @param showOnEmpty  show suggestions even when query is empty
     * @return ranked list of at most MAX_UNIFIED_SUGGESTIONS items
     */
    public CompletableFuture<List<SuggestionItem>> generateUnifiedSuggestions(
            String query,
            FileIndex fileIdx,
            Map<String, List<ServerResource>> mcpResources,
            List<AgentDefinition> agents,
            boolean showOnEmpty) {

        if ((query == null || query.isBlank()) && !showOnEmpty) {
            return CompletableFuture.completedFuture(List.of());
        }

        List<SuggestionItem> fileSuggestions =
                generateFileSuggestions(fileIdx, query, showOnEmpty);

        List<FileSuggestionSource> fileSources = fileSuggestions.stream()
                .map(s -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> meta = (Map<String, Object>) s.getMetadata();
                    Double score = meta != null ? (Double) meta.get("score") : null;
                    String filename = Path.of(s.getDisplayText()).getFileName().toString();
                    return new FileSuggestionSource(
                            s.getDisplayText(), s.getDescription(),
                            s.getDisplayText(), filename, score);
                })
                .toList();

        List<McpResourceSuggestionSource> mcpSources = (mcpResources == null
                ? Collections.<McpResourceSuggestionSource>emptyList()
                : mcpResources.values().stream()
                        .flatMap(List::stream)
                        .map(r -> new McpResourceSuggestionSource(
                                r.server() + ":" + r.uri(),
                                truncateDescription(r.description() != null ? r.description()
                                        : r.name() != null ? r.name() : r.uri()),
                                r.server(), r.uri(),
                                r.name() != null ? r.name() : r.uri()))
                        .toList());

        List<AgentSuggestionSource> agentSources =
                generateAgentSuggestions(agents, query, showOnEmpty);

        // When no query — return first MAX_UNIFIED_SUGGESTIONS across all sources
        if (query == null || query.isBlank()) {
            List<SuggestionItem> all = new ArrayList<>();
            fileSources.forEach(s -> all.add(fileSourceToItem(s)));
            mcpSources.forEach(s -> all.add(mcpSourceToItem(s)));
            agentSources.forEach(s -> all.add(agentSourceToItem(s)));
            return CompletableFuture.completedFuture(
                    all.subList(0, Math.min(all.size(), MAX_UNIFIED_SUGGESTIONS)));
        }

        // Score and merge: file scores come from nucleo, MCP/agent scores from simple substring rank
        record Scored(SuggestionItem item, double score) {}
        List<Scored> scored = new ArrayList<>();

        fileSources.forEach(s -> scored.add(
                new Scored(fileSourceToItem(s), s.score() != null ? s.score() : 0.5)));

        String queryLower = query.toLowerCase();
        mcpSources.forEach(s -> {
            double sc = fuzzyScore(queryLower,
                    List.of(s.displayText(), s.name(), s.server(), s.description()));
            if (sc < 0.6) scored.add(new Scored(mcpSourceToItem(s), sc));
        });
        agentSources.forEach(s -> {
            double sc = fuzzyScore(queryLower,
                    List.of(s.displayText(), s.agentType(), s.description()));
            if (sc < 0.6) scored.add(new Scored(agentSourceToItem(s), sc));
        });

        scored.sort(Comparator.comparingDouble(Scored::score));
        List<SuggestionItem> result = scored.stream()
                .limit(MAX_UNIFIED_SUGGESTIONS)
                .map(Scored::item)
                .toList();

        return CompletableFuture.completedFuture(result);
    }

    // =========================================================================
    // generateAgentSuggestions (unifiedSuggestions.ts)
    // =========================================================================

    /**
     * Generate agent suggestions matching the query.
     * Translated from generateAgentSuggestions() in unifiedSuggestions.ts
     */
    private static List<AgentSuggestionSource> generateAgentSuggestions(
            List<AgentDefinition> agents, String query, boolean showOnEmpty) {
        if ((query == null || query.isBlank()) && !showOnEmpty) return List.of();
        if (agents == null) return List.of();

        try {
            List<AgentSuggestionSource> sources = agents.stream()
                    .map(a -> new AgentSuggestionSource(
                            a.agentType() + " (agent)",
                            truncateDescription(a.whenToUse()),
                            a.agentType(),
                            null /* color resolved at render time */))
                    .toList();

            if (query == null || query.isBlank()) return sources;

            String lower = query.toLowerCase();
            return sources.stream()
                    .filter(s -> s.agentType().toLowerCase().contains(lower)
                            || s.displayText().toLowerCase().contains(lower))
                    .toList();
        } catch (Exception e) {
            log.error("generateAgentSuggestions failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static SuggestionItem fileSourceToItem(FileSuggestionSource s) {
        Map<String, Object> meta = s.score() != null ? Map.of("score", s.score()) : null;
        return new SuggestionItem("file-" + s.path(), s.displayText(), s.description(), null, meta);
    }

    private static SuggestionItem mcpSourceToItem(McpResourceSuggestionSource s) {
        return new SuggestionItem(
                "mcp-resource-" + s.server() + "__" + s.uri(),
                s.displayText(), s.description(), null, null);
    }

    private static SuggestionItem agentSourceToItem(AgentSuggestionSource s) {
        return new SuggestionItem(
                "agent-" + s.agentType(), s.displayText(), s.description(), s.color(), null);
    }

    private static String truncateDescription(String description) {
        if (description == null) return "";
        return description.length() <= DESCRIPTION_MAX_LENGTH
                ? description
                : description.substring(0, DESCRIPTION_MAX_LENGTH);
    }

    /**
     * Simple fuzzy score: 0.0 = exact prefix match, 1.0 = no match.
     * Lower is better (mirrors Fuse.js semantics).
     */
    private static double fuzzyScore(String queryLower, List<String> fields) {
        for (String field : fields) {
            if (field == null) continue;
            String fl = field.toLowerCase();
            if (fl.equals(queryLower)) return 0.0;
            if (fl.startsWith(queryLower)) return 0.1;
            if (fl.contains(queryLower)) return 0.3;
        }
        return 1.0;
    }
}
