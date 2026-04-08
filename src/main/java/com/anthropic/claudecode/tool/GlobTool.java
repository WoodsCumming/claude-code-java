package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import com.anthropic.claudecode.util.PathUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.io.File;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.Data;

/**
 * Glob file pattern matching tool.
 * Translated from src/tools/GlobTool/GlobTool.ts
 *
 * Fast file pattern matching that works with any codebase size.
 * Returns matching file paths sorted by modification time.
 */
@Slf4j
@Component
public class GlobTool extends AbstractTool<GlobTool.Input, GlobTool.Output> {



    public static final String TOOL_NAME = "Glob";
    private static final int MAX_RESULTS = 100;

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getSearchHint() {
        return "find files by name pattern or wildcard";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "pattern", Map.of(
                    "type", "string",
                    "description", "The glob pattern to match files against"
                ),
                "path", Map.of(
                    "type", "string",
                    "description", "The directory to search in. If not specified, the current working directory will be used."
                )
            ),
            "required", List.of("pattern")
        );
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
            Input args,
            ToolUseContext context,
            Tool.CanUseToolFn canUseTool,
            Message.AssistantMessage parentMessage,
            Consumer<Tool.ToolProgress> onProgress) {

        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            try {
                String searchPath = args.getPath() != null
                    ? PathUtils.expandPath(args.getPath())
                    : System.getProperty("user.dir");

                Path basePath = Paths.get(searchPath);
                if (!Files.exists(basePath)) {
                    throw new IllegalArgumentException("Directory not found: " + searchPath);
                }

                List<String> matches = findGlobMatches(basePath, args.getPattern());

                // Sort by modification time (most recently modified first)
                matches.sort((a, b) -> {
                    try {
                        long aTime = Files.getLastModifiedTime(Paths.get(a)).toMillis();
                        long bTime = Files.getLastModifiedTime(Paths.get(b)).toMillis();
                        return Long.compare(bTime, aTime);
                    } catch (Exception e) {
                        return 0;
                    }
                });

                boolean truncated = matches.size() > MAX_RESULTS;
                List<String> limitedMatches = truncated
                    ? matches.subList(0, MAX_RESULTS)
                    : matches;

                long durationMs = System.currentTimeMillis() - startTime;

                Output output = Output.builder()
                    .durationMs(durationMs)
                    .numFiles(matches.size())
                    .filenames(limitedMatches)
                    .truncated(truncated)
                    .build();

                return result(output);

            } catch (Exception e) {
                log.error("Glob search failed: {}", e.getMessage());
                throw new RuntimeException("Glob search failed: " + e.getMessage(), e);
            }
        });
    }

    private List<String> findGlobMatches(Path basePath, String pattern) throws Exception {
        List<String> results = new ArrayList<>();

        // Convert glob pattern to PathMatcher
        PathMatcher matcher = FileSystems.getDefault()
            .getPathMatcher("glob:" + pattern);

        Files.walkFileTree(basePath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                // Match against relative path from base
                Path relative = basePath.relativize(file);
                if (matcher.matches(relative) || matcher.matches(file.getFileName())) {
                    results.add(file.toAbsolutePath().toString());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, java.io.IOException exc) {
                return FileVisitResult.CONTINUE; // Skip inaccessible files
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // Skip hidden directories (starting with .)
                String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                if (name.startsWith(".") && !name.equals(".")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                // Skip node_modules, target, build directories
                if (Set.of("node_modules", "target", "build", "dist", "__pycache__").contains(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return results;
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture(
            "Finding files matching: " + input.getPattern()
        );
    }

    @Override
    public boolean isReadOnly(Input input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(Input input) {
        return true;
    }

    @Override
    public SearchOrReadInfo isSearchOrReadCommand(Input input) {
        return SearchOrReadInfo.SEARCH;
    }

    @Override
    public String userFacingName(Input input) {
        return input != null && input.getPattern() != null ? input.getPattern() : TOOL_NAME;
    }

    @Override
    public String getActivityDescription(Input input) {
        return "Finding " + (input != null ? input.getPattern() : "files");
    }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        String text;
        if (content.getFilenames().isEmpty()) {
            text = "No files found matching the pattern.";
        } else {
            text = "Found " + content.getNumFiles() + " file(s)"
                + (content.isTruncated() ? " (showing first " + MAX_RESULTS + ")" : "")
                + ":\n"
                + String.join("\n", content.getFilenames());
        }

        return Map.of(
            "type", "tool_result",
            "tool_use_id", toolUseId,
            "content", text
        );
    }

    @Override
    public int getMaxResultSizeChars() {
        return 100_000;
    }

    // =========================================================================
    // Input/Output types
    // =========================================================================

    public static class Input {
        private String pattern;
        private String path;
        public Input() {}
        public String getPattern() { return pattern; }
        public void setPattern(String v) { pattern = v; }
        public String getPath() { return path; }
        public void setPath(String v) { path = v; }
        public static InputBuilder builder() { return new InputBuilder(); }
        public static class InputBuilder {
            private String pattern; private String path;
            public InputBuilder pattern(String v) { this.pattern = v; return this; }
            public InputBuilder path(String v) { this.path = v; return this; }
            public Input build() { Input i = new Input(); i.pattern = pattern; i.path = path; return i; }
        }
    }

    public static class Output {
        private long durationMs;
        private int numFiles;
        private List<String> filenames;
        private boolean truncated;
        public Output() {}
        public long getDurationMs() { return durationMs; }
        public void setDurationMs(long v) { durationMs = v; }
        public int getNumFiles() { return numFiles; }
        public void setNumFiles(int v) { numFiles = v; }
        public List<String> getFilenames() { return filenames; }
        public void setFilenames(List<String> v) { filenames = v; }
        public boolean isTruncated() { return truncated; }
        public void setTruncated(boolean v) { truncated = v; }
        public static OutputBuilder builder() { return new OutputBuilder(); }
        public static class OutputBuilder {
            private long durationMs; private int numFiles; private List<String> filenames; private boolean truncated;
            public OutputBuilder durationMs(long v) { this.durationMs = v; return this; }
            public OutputBuilder numFiles(int v) { this.numFiles = v; return this; }
            public OutputBuilder filenames(List<String> v) { this.filenames = v; return this; }
            public OutputBuilder truncated(boolean v) { this.truncated = v; return this; }
            public Output build() { Output o = new Output(); o.durationMs = durationMs; o.numFiles = numFiles; o.filenames = filenames; o.truncated = truncated; return o; }
        }
    }
}
