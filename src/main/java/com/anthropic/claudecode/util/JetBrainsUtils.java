package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * JetBrains IDE plugin detection utilities.
 * Translated from src/utils/jetbrains.ts
 *
 * <p>Probes the OS-specific JetBrains config directories for the Claude Code
 * JetBrains plugin ({@value #PLUGIN_PREFIX}) and caches the result per IDE type.</p>
 */
@Slf4j
public final class JetBrainsUtils {



    private static final String PLUGIN_PREFIX = "claude-code-jetbrains-plugin";

    /** Maps IDE name (lower-case) to the directory-name patterns it uses. */
    private static final Map<String, List<String>> IDE_NAME_TO_DIR_MAP = Map.ofEntries(
            Map.entry("pycharm",      List.of("PyCharm")),
            Map.entry("intellij",     List.of("IntelliJIdea", "IdeaIC")),
            Map.entry("webstorm",     List.of("WebStorm")),
            Map.entry("phpstorm",     List.of("PhpStorm")),
            Map.entry("rubymine",     List.of("RubyMine")),
            Map.entry("clion",        List.of("CLion")),
            Map.entry("goland",       List.of("GoLand")),
            Map.entry("rider",        List.of("Rider")),
            Map.entry("datagrip",     List.of("DataGrip")),
            Map.entry("appcode",      List.of("AppCode")),
            Map.entry("dataspell",    List.of("DataSpell")),
            Map.entry("aqua",         List.of("Aqua")),
            Map.entry("gateway",      List.of("Gateway")),
            Map.entry("fleet",        List.of("Fleet")),
            Map.entry("androidstudio", List.of("AndroidStudio"))
    );

    // Caches (mirrors pluginInstalledCache / pluginInstalledPromiseCache in TS)
    private static final Map<String, Boolean> pluginInstalledCache        = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<Boolean>> promiseCache = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} when the Claude Code JetBrains plugin is installed for the
     * given IDE type. Result is not cached.
     * Translated from {@code isJetBrainsPluginInstalled()} in jetbrains.ts.
     */
    public static CompletableFuture<Boolean> isJetBrainsPluginInstalled(String ideType) {
        return detectPluginDirectories(ideType).thenApplyAsync(dirs -> {
            for (String dir : dirs) {
                Path pluginPath = Path.of(dir, PLUGIN_PREFIX);
                if (Files.exists(pluginPath)) return true;
            }
            return false;
        });
    }

    /**
     * Cached/memoized variant. Subsequent calls return the same {@code CompletableFuture}.
     * Translated from {@code isJetBrainsPluginInstalledCached()} in jetbrains.ts.
     *
     * @param forceRefresh when {@code true} the cache is cleared before querying
     */
    public static CompletableFuture<Boolean> isJetBrainsPluginInstalledCached(
            String ideType, boolean forceRefresh) {
        if (forceRefresh) {
            pluginInstalledCache.remove(ideType);
            promiseCache.remove(ideType);
        }
        return promiseCache.computeIfAbsent(ideType, id ->
                isJetBrainsPluginInstalled(id).thenApply(result -> {
                    pluginInstalledCache.put(id, result);
                    return result;
                }));
    }

    /**
     * Synchronous cache read — returns {@code false} if the async probe has not yet
     * completed.
     * Translated from {@code isJetBrainsPluginInstalledCachedSync()} in jetbrains.ts.
     */
    public static boolean isJetBrainsPluginInstalledCachedSync(String ideType) {
        return pluginInstalledCache.getOrDefault(ideType, false);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Builds the candidate base directories that JetBrains IDEs use to store their
     * settings on the current OS.
     * Translated from {@code buildCommonPluginDirectoryPaths()} in jetbrains.ts.
     */
    private static List<String> buildCommonPluginDirectoryPaths(String ideName) {
        String home = System.getProperty("user.home");
        List<String> directories = new ArrayList<>();

        List<String> idePatterns = IDE_NAME_TO_DIR_MAP.get(ideName.toLowerCase(Locale.ROOT));
        if (idePatterns == null) return directories;

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        if (os.contains("mac")) {
            directories.add(home + "/Library/Application Support/JetBrains");
            directories.add(home + "/Library/Application Support");
            if ("androidstudio".equals(ideName.toLowerCase(Locale.ROOT))) {
                directories.add(home + "/Library/Application Support/Google");
            }
        } else if (os.contains("win")) {
            String appData      = Optional.ofNullable(System.getenv("APPDATA"))
                                          .orElse(home + "/AppData/Roaming");
            String localAppData = Optional.ofNullable(System.getenv("LOCALAPPDATA"))
                                          .orElse(home + "/AppData/Local");
            directories.add(appData      + "/JetBrains");
            directories.add(localAppData + "/JetBrains");
            directories.add(appData);
            if ("androidstudio".equals(ideName.toLowerCase(Locale.ROOT))) {
                directories.add(localAppData + "/Google");
            }
        } else {
            // Linux
            directories.add(home + "/.config/JetBrains");
            directories.add(home + "/.local/share/JetBrains");
            for (String pattern : idePatterns) {
                directories.add(home + "/." + pattern);
            }
            if ("androidstudio".equals(ideName.toLowerCase(Locale.ROOT))) {
                directories.add(home + "/.config/Google");
            }
        }

        return directories;
    }

    /**
     * Scans the candidate directories and returns actual plugin directory paths that exist
     * on disk.
     * Translated from {@code detectPluginDirectories()} in jetbrains.ts.
     */
    private static CompletableFuture<List<String>> detectPluginDirectories(String ideName) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> foundDirectories = new ArrayList<>();
            List<String> idePatterns = IDE_NAME_TO_DIR_MAP.get(ideName.toLowerCase(Locale.ROOT));
            if (idePatterns == null) return foundDirectories;

            List<Pattern> regexes = idePatterns.stream()
                    .map(p -> Pattern.compile("^" + Pattern.quote(p)))
                    .toList();

            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            boolean isLinux = os.contains("linux");

            for (String baseDir : buildCommonPluginDirectoryPaths(ideName)) {
                Path base = Path.of(baseDir);
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(base)) {
                    for (Path entry : stream) {
                        String entryName = entry.getFileName().toString();
                        boolean matchesPattern = regexes.stream()
                                .anyMatch(r -> r.matcher(entryName).find());
                        if (!matchesPattern) continue;

                        // Accept directories and symlinks (GNU stow users may symlink)
                        if (!Files.isDirectory(entry) && !Files.isSymbolicLink(entry)) continue;

                        if (isLinux) {
                            // Linux has no plugins sub-directory
                            foundDirectories.add(entry.toString());
                        } else {
                            Path pluginDir = entry.resolve("plugins");
                            if (Files.exists(pluginDir)) {
                                foundDirectories.add(pluginDir.toString());
                            }
                        }
                    }
                } catch (IOException e) {
                    // Ignore ENOENT / EACCES for stale or inaccessible directories
                    log.trace("Skipping inaccessible JetBrains base dir {}: {}", baseDir, e.getMessage());
                }
            }

            // Deduplicate while preserving order
            List<String> deduped = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (String d : foundDirectories) {
                if (seen.add(d)) deduped.add(d);
            }
            return deduped;
        });
    }

    private JetBrainsUtils() {}
}
