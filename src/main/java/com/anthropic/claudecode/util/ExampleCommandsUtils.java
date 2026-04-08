package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Example command generation utilities.
 * Translated from src/utils/exampleCommands.ts
 *
 * Generates example commands for the welcome prompt based on
 * frequently-modified files in the project's git history.
 */
@Slf4j
public class ExampleCommandsUtils {



    // -------------------------------------------------------------------------
    // Non-core file patterns
    // Translated from NON_CORE_PATTERNS in exampleCommands.ts
    // -------------------------------------------------------------------------

    private static final List<Pattern> NON_CORE_PATTERNS = List.of(
            // Lock / dependency manifests
            Pattern.compile("(?:^|/)(?:package-lock\\.json|yarn\\.lock|bun\\.lock|bun\\.lockb"
                    + "|pnpm-lock\\.yaml|Pipfile\\.lock|poetry\\.lock|Cargo\\.lock|Gemfile\\.lock"
                    + "|go\\.sum|composer\\.lock|uv\\.lock)$"),
            // Generated / build artifacts
            Pattern.compile("\\.generated\\."),
            Pattern.compile("(?:^|/)(?:dist|build|out|target|node_modules|\\.next|__pycache__)/"),
            Pattern.compile("\\.(?:min\\.js|min\\.css|map|pyc|pyo)$"),
            // Data / docs / config extensions
            Pattern.compile("(?i)\\.(?:json|ya?ml|toml|xml|ini|cfg|conf|env|lock|txt|md|mdx"
                    + "|rst|csv|log|svg)$"),
            // Configuration / metadata
            Pattern.compile("(?:^|/)\\.?(?:eslintrc|prettierrc|babelrc|editorconfig"
                    + "|gitignore|gitattributes|dockerignore|npmrc)"),
            Pattern.compile("(?:^|/)(?:tsconfig|jsconfig|biome|vitest\\.config|jest\\.config"
                    + "|webpack\\.config|vite\\.config|rollup\\.config)\\.[a-z]+$"),
            Pattern.compile("(?:^|/)\\.(?:github|vscode|idea|claude)/"),
            // Docs / changelogs
            Pattern.compile("(?i)(?:^|/)(?:CHANGELOG|LICENSE|CONTRIBUTING|CODEOWNERS|README)"
                    + "(?:\\.[a-z]+)?$")
    );

    /**
     * Check whether a path is a "core" (hand-written, non-generated) file.
     * Translated from isCoreFile() in exampleCommands.ts
     */
    public static boolean isCoreFile(String path) {
        return NON_CORE_PATTERNS.stream().noneMatch(p -> p.matcher(path).find());
    }

    // -------------------------------------------------------------------------
    // countAndSortItems
    // Translated from countAndSortItems() in exampleCommands.ts
    // -------------------------------------------------------------------------

    /**
     * Count occurrences of items in a list and return the top N items sorted
     * by count descending, formatted as a padded string.
     * Translated from countAndSortItems() in exampleCommands.ts
     *
     * @param items list of items to count
     * @param topN  maximum number of results to return
     * @return multi-line string where each line is "  COUNT ITEM"
     */
    public static String countAndSortItems(List<String> items, int topN) {
        if (items == null || items.isEmpty()) return "";

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String item : items) {
            counts.merge(item, 1, Integer::sum);
        }

        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(topN)
                .map(e -> String.format("%6d %s", e.getValue(), e.getKey()))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    /** Convenience overload that returns top 20 items. */
    public static String countAndSortItems(List<String> items) {
        return countAndSortItems(items, 20);
    }

    // -------------------------------------------------------------------------
    // pickDiverseCoreFiles
    // Translated from pickDiverseCoreFiles() in exampleCommands.ts
    // -------------------------------------------------------------------------

    /**
     * Pick up to {@code want} basenames from a frequency-sorted list of paths,
     * skipping non-core files and spreading across different directories.
     * Returns an empty list if fewer than {@code want} core files are available.
     * Translated from pickDiverseCoreFiles() in exampleCommands.ts
     *
     * @param sortedPaths frequency-sorted list of relative file paths
     * @param want        number of files to pick
     * @return list of basenames, or empty if insufficient core files
     */
    public static List<String> pickDiverseCoreFiles(List<String> sortedPaths, int want) {
        List<String> picked = new ArrayList<>();
        Set<String> seenBasenames = new HashSet<>();
        Map<String, Integer> dirTally = new HashMap<>();

        for (int cap = 1; picked.size() < want && cap <= want; cap++) {
            for (String p : sortedPaths) {
                if (picked.size() >= want) break;
                if (!isCoreFile(p)) continue;

                int lastSep = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
                String base = lastSep >= 0 ? p.substring(lastSep + 1) : p;
                if (base.isEmpty() || seenBasenames.contains(base)) continue;

                String dir = lastSep >= 0 ? p.substring(0, lastSep) : ".";
                if (dirTally.getOrDefault(dir, 0) >= cap) continue;

                picked.add(base);
                seenBasenames.add(base);
                dirTally.merge(dir, 1, Integer::sum);
            }
        }

        return picked.size() >= want ? picked : Collections.emptyList();
    }

    // -------------------------------------------------------------------------
    // getExampleCommand
    // Translated from getExampleCommandFromCache() in exampleCommands.ts
    // -------------------------------------------------------------------------

    private static final List<String> STATIC_COMMANDS = List.of(
            "fix lint errors",
            "fix typecheck errors",
            "how do I log an error?",
            "create a util logging.py that..."
    );

    private static final Random RANDOM = new Random();

    /**
     * Build a random example command string.
     * If {@code exampleFiles} is non-empty, a random file from the list will be
     * used in the generated command; otherwise {@code <filepath>} is used.
     * Translated from getExampleCommandFromCache() in exampleCommands.ts
     *
     * @param exampleFiles cached list of frequently-modified file basenames
     * @return a string like {@code Try "how does Foo.java work?"}
     */
    public static String getExampleCommand(List<String> exampleFiles) {
        String frequentFile = (exampleFiles != null && !exampleFiles.isEmpty())
                ? exampleFiles.get(RANDOM.nextInt(exampleFiles.size()))
                : "<filepath>";

        List<String> commands = new ArrayList<>(STATIC_COMMANDS);
        commands.add("how does " + frequentFile + " work?");
        commands.add("refactor " + frequentFile);
        commands.add("edit " + frequentFile + " to...");
        commands.add("write a test for " + frequentFile);

        String cmd = commands.get(RANDOM.nextInt(commands.size()));
        return "Try \"" + cmd + "\"";
    }

    // -------------------------------------------------------------------------
    // refreshExampleCommands
    // Translated from refreshExampleCommands() in exampleCommands.ts
    // -------------------------------------------------------------------------

    private static final long ONE_WEEK_IN_MS = 7L * 24 * 60 * 60 * 1000;

    /**
     * Asynchronously refresh example file suggestions if they are over a week
     * old.  Returns a {@link CompletableFuture} that resolves with an updated
     * list of file basenames (or an empty list on failure).
     * Translated from refreshExampleCommands() in exampleCommands.ts
     *
     * @param lastGeneratedAt   epoch-millis timestamp of the last cache update
     * @param cachedFiles       currently cached file basenames
     * @param frequentFilesFetcher async supplier that returns a frequency-sorted
     *                          list of relative file paths from git history
     * @return CompletableFuture resolving to the refreshed (or unchanged) file list
     */
    public static CompletableFuture<List<String>> refreshExampleCommands(
            long lastGeneratedAt,
            List<String> cachedFiles,
            java.util.function.Supplier<CompletableFuture<List<String>>> frequentFilesFetcher) {

        long now = System.currentTimeMillis();

        // If not stale and we already have files, nothing to do
        if ((now - lastGeneratedAt <= ONE_WEEK_IN_MS)
                && cachedFiles != null && !cachedFiles.isEmpty()) {
            return CompletableFuture.completedFuture(cachedFiles);
        }

        return frequentFilesFetcher.get()
                .thenApply(files -> files != null && !files.isEmpty() ? files : Collections.<String>emptyList())
                .exceptionally(ex -> {
                    log.error("Failed to refresh example commands", ex);
                    return Collections.<String>emptyList();
                });
    }

    private ExampleCommandsUtils() {}
}
