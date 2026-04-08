package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import com.anthropic.claudecode.util.PathUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.*;
import java.util.stream.Collectors;
import lombok.Data;

/**
 * Grep content search tool.
 * Translated from src/tools/GrepTool/GrepTool.ts
 *
 * Powerful search tool built on ripgrep (or Java regex fallback).
 * Supports full regex syntax, file type filtering, context lines.
 */
@Slf4j
@Component
public class GrepTool extends AbstractTool<GrepTool.Input, GrepTool.Output> {



    public static final String TOOL_NAME = "Grep";
    private static final int DEFAULT_HEAD_LIMIT = 250;
    private static final int MAX_HEAD_LIMIT = 10_000;

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getSearchHint() {
        return "search file contents with regex patterns";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        java.util.LinkedHashMap<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("pattern", Map.of("type", "string", "description", "The regular expression pattern to search for in file contents"));
        properties.put("path", Map.of("type", "string", "description", "File or directory to search in. Defaults to current working directory."));
        properties.put("glob", Map.of("type", "string", "description", "Glob pattern to filter files (e.g. \"*.js\", \"*.{ts,tsx}\")"));
        properties.put("output_mode", Map.of("type", "string", "enum", List.of("content", "files_with_matches", "count"), "description", "Output mode. Defaults to files_with_matches."));
        properties.put("-B", Map.of("type", "integer", "description", "Lines before match"));
        properties.put("-A", Map.of("type", "integer", "description", "Lines after match"));
        properties.put("-C", Map.of("type", "integer", "description", "Lines before and after match"));
        properties.put("context", Map.of("type", "integer", "description", "Lines before and after match"));
        properties.put("-n", Map.of("type", "boolean", "description", "Show line numbers (default true)"));
        properties.put("-i", Map.of("type", "boolean", "description", "Case insensitive search"));
        properties.put("type", Map.of("type", "string", "description", "File type to search (java, py, js, etc.)"));
        properties.put("head_limit", Map.of("type", "integer", "description", "Limit output to first N lines/entries"));
        properties.put("offset", Map.of("type", "integer", "description", "Skip first N lines/entries"));
        properties.put("multiline", Map.of("type", "boolean", "description", "Enable multiline mode"));
        return Map.of("type", "object", "properties", properties, "required", List.of("pattern"));
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

                String outputMode = args.getOutputMode() != null
                    ? args.getOutputMode()
                    : "files_with_matches";

                int headLimit = args.getHeadLimit() != null
                    ? Math.min(args.getHeadLimit(), MAX_HEAD_LIMIT)
                    : DEFAULT_HEAD_LIMIT;

                int offset = args.getOffset() != null ? args.getOffset() : 0;

                // Try to use ripgrep if available, fall back to Java regex
                String result = executeSearch(args, searchPath, outputMode, headLimit, offset);

                long durationMs = System.currentTimeMillis() - startTime;

                Output output = Output.builder()
                    .content(result)
                    .durationMs(durationMs)
                    .outputMode(outputMode)
                    .build();

                return this.result(output);

            } catch (Exception e) {
                log.error("Grep search failed: {}", e.getMessage());
                throw new RuntimeException("Grep search failed: " + e.getMessage(), e);
            }
        });
    }

    private String executeSearch(Input args, String searchPath, String outputMode,
                                  int headLimit, int offset) throws Exception {
        // Try ripgrep first
        if (isRipgrepAvailable()) {
            return executeRipgrep(args, searchPath, outputMode, headLimit, offset);
        }
        // Fall back to Java regex search
        return executeJavaSearch(args, searchPath, outputMode, headLimit, offset);
    }

    private boolean isRipgrepAvailable() {
        try {
            Process p = new ProcessBuilder("rg", "--version")
                .redirectErrorStream(true)
                .start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String executeRipgrep(Input args, String searchPath, String outputMode,
                                   int headLimit, int offset) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("rg");

        // Output format
        switch (outputMode) {
            case "content" -> cmd.add("--with-filename");
            case "files_with_matches" -> cmd.add("--files-with-matches");
            case "count" -> cmd.add("--count");
        }

        // Flags
        if (Boolean.TRUE.equals(args.getCaseInsensitive())) cmd.add("-i");
        if (Boolean.TRUE.equals(args.getMultiline())) { cmd.add("-U"); cmd.add("--multiline-dotall"); }
        if (args.getType() != null) { cmd.add("--type"); cmd.add(args.getType()); }
        if (args.getGlob() != null) { cmd.add("--glob"); cmd.add(args.getGlob()); }

        // Context lines
        int contextLines = 0;
        if (args.getContext() != null) contextLines = args.getContext();
        if (args.getContextAlias() != null) contextLines = args.getContextAlias();
        if (contextLines > 0) { cmd.add("-C"); cmd.add(String.valueOf(contextLines)); }
        if (args.getBeforeContext() != null) { cmd.add("-B"); cmd.add(String.valueOf(args.getBeforeContext())); }
        if (args.getAfterContext() != null) { cmd.add("-A"); cmd.add(String.valueOf(args.getAfterContext())); }

        // Line numbers (default true for content mode)
        if ("content".equals(outputMode) && !Boolean.FALSE.equals(args.getShowLineNumbers())) {
            cmd.add("-n");
        }

        cmd.add(args.getPattern());
        cmd.add(searchPath);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();

        // Apply offset and head_limit
        String[] lines = output.split("\n", -1);
        List<String> filtered = Arrays.stream(lines)
            .skip(offset)
            .limit(headLimit)
            .collect(Collectors.toList());

        return String.join("\n", filtered);
    }

    private String executeJavaSearch(Input args, String searchPath, String outputMode,
                                      int headLimit, int offset) throws Exception {
        int flags = Pattern.DOTALL;
        if (Boolean.TRUE.equals(args.getCaseInsensitive())) flags |= Pattern.CASE_INSENSITIVE;
        if (Boolean.TRUE.equals(args.getMultiline())) flags |= Pattern.MULTILINE;

        Pattern pattern = Pattern.compile(args.getPattern(), flags);
        Path base = Paths.get(searchPath);

        List<String> results = new ArrayList<>();

        Files.walkFileTree(base, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                try {
                    // Apply type filter
                    if (args.getType() != null && !matchesType(file, args.getType())) {
                        return FileVisitResult.CONTINUE;
                    }
                    // Apply glob filter
                    if (args.getGlob() != null) {
                        PathMatcher globMatcher = FileSystems.getDefault()
                            .getPathMatcher("glob:" + args.getGlob());
                        if (!globMatcher.matches(file.getFileName())) {
                            return FileVisitResult.CONTINUE;
                        }
                    }

                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    Matcher m = pattern.matcher(content);

                    switch (outputMode) {
                        case "files_with_matches" -> {
                            if (m.find()) results.add(file.toAbsolutePath().toString());
                        }
                        case "count" -> {
                            int count = 0;
                            while (m.find()) count++;
                            if (count > 0) results.add(file.toAbsolutePath() + ":" + count);
                        }
                        case "content" -> {
                            String[] lines = content.split("\n", -1);
                            for (int i = 0; i < lines.length; i++) {
                                if (pattern.matcher(lines[i]).find()) {
                                    results.add(file.toAbsolutePath() + ":" + (i + 1) + ":" + lines[i]);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // Skip unreadable files
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                if (Set.of("node_modules", "target", "build", ".git").contains(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return results.stream()
            .skip(offset)
            .limit(headLimit)
            .collect(Collectors.joining("\n"));
    }

    private boolean matchesType(Path file, String type) {
        String name = file.getFileName().toString();
        Map<String, List<String>> typeExtensions = Map.of(
            "java", List.of(".java"),
            "js", List.of(".js", ".mjs", ".cjs"),
            "ts", List.of(".ts", ".tsx"),
            "py", List.of(".py"),
            "go", List.of(".go"),
            "rust", List.of(".rs"),
            "json", List.of(".json"),
            "xml", List.of(".xml"),
            "yaml", List.of(".yaml", ".yml"),
            "md", List.of(".md", ".markdown")
        );
        List<String> exts = typeExtensions.get(type);
        if (exts == null) return true;
        return exts.stream().anyMatch(name::endsWith);
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Searching for: " + input.getPattern());
    }

    @Override
    public boolean isReadOnly(Input input) { return true; }

    @Override
    public boolean isConcurrencySafe(Input input) { return true; }

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
        return "Searching for " + (input != null ? input.getPattern() : "pattern");
    }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        return Map.of(
            "type", "tool_result",
            "tool_use_id", toolUseId,
            "content", content.getContent() != null ? content.getContent() : "No matches found."
        );
    }

    @Override
    public int getMaxResultSizeChars() { return 100_000; }

    // =========================================================================
    // Input/Output types
    // =========================================================================

    @Data
    @lombok.Builder
    
    public static class Input {
        private String pattern;
        private String path;
        private String glob;
        private String outputMode;
        private Integer beforeContext;
        private Integer afterContext;
        private Integer context;
        private Integer contextAlias; // -C alias
        private Boolean showLineNumbers;
        private Boolean caseInsensitive;
        private String type;
        private Integer headLimit;
        private Integer offset;
        private Boolean multiline;
    }

    @Data
    @lombok.Builder
    
    public static class Output {
        private String content;
        private long durationMs;
        private String outputMode;
    }
}
