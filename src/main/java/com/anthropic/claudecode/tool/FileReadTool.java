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
import lombok.Data;

/**
 * File reading tool.
 * Translated from src/tools/FileReadTool/FileReadTool.ts
 *
 * Reads files from the local filesystem with support for:
 * - Text files with line numbers
 * - PDF files
 * - Image files (returned as base64)
 * - Jupyter notebooks
 * - Line range reading (offset + limit)
 */
@Slf4j
@Component
public class FileReadTool extends AbstractTool<FileReadTool.Input, FileReadTool.Output> {



    public static final String TOOL_NAME = "Read";

    // Device files that would hang the process
    private static final Set<String> BLOCKED_DEVICE_PATHS = Set.of(
        "/dev/zero", "/dev/random", "/dev/urandom", "/dev/full",
        "/dev/stdin", "/dev/stdout", "/dev/stderr",
        "/dev/fd/0", "/dev/fd/1", "/dev/fd/2"
    );

    // Default file reading limits
    private static final int DEFAULT_MAX_LINES = 2000;
    private static final long DEFAULT_MAX_SIZE_BYTES = 10 * 1024 * 1024; // 10MB

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getSearchHint() {
        return "read file contents from disk";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "file_path", Map.of(
                    "type", "string",
                    "description", "The absolute path to the file to read"
                ),
                "offset", Map.of(
                    "type", "integer",
                    "description", "The line number to start reading from (1-indexed)",
                    "minimum", 1
                ),
                "limit", Map.of(
                    "type", "integer",
                    "description", "The number of lines to read",
                    "minimum", 1
                )
            ),
            "required", List.of("file_path")
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
            try {
                String filePath = PathUtils.expandPath(args.getFilePath());
                File file = new File(filePath);

                // Check blocked device paths
                if (BLOCKED_DEVICE_PATHS.contains(filePath)) {
                    throw new IOException("Cannot read device file: " + filePath);
                }

                if (!file.exists()) {
                    throw new FileNotFoundException("File not found: " + filePath
                        + "\nNote: your current working directory is " + System.getProperty("user.dir"));
                }

                if (file.isDirectory()) {
                    return result(readDirectory(file));
                }

                // Check file size
                long fileSize = file.length();
                long maxSizeBytes = context.getFileReadingLimits() != null
                    && context.getFileReadingLimits().getMaxSizeBytes() != null
                    ? context.getFileReadingLimits().getMaxSizeBytes()
                    : DEFAULT_MAX_SIZE_BYTES;

                if (fileSize > maxSizeBytes) {
                    throw new IOException("File too large: " + filePath
                        + " (" + formatFileSize(fileSize) + "). Max: " + formatFileSize(maxSizeBytes));
                }

                // Read based on file type
                String extension = getExtension(filePath);
                if (isImageExtension(extension)) {
                    return result(readImageFile(file, filePath));
                }

                return result(readTextFile(file, filePath, args.getOffset(), args.getLimit()));

            } catch (Exception e) {
                log.error("File read failed: {}", e.getMessage());
                throw new RuntimeException(e.getMessage(), e);
            }
        });
    }

    private Output readDirectory(File dir) throws IOException {
        File[] entries = dir.listFiles();
        if (entries == null) entries = new File[0];
        Arrays.sort(entries, Comparator.comparing(File::getName));

        StringBuilder sb = new StringBuilder();
        for (File entry : entries) {
            sb.append(entry.isDirectory() ? "d " : "f ");
            sb.append(entry.getName());
            sb.append("\n");
        }

        return Output.builder()
            .filePath(dir.getAbsolutePath())
            .content(sb.toString())
            .type("directory")
            .build();
    }

    private Output readTextFile(File file, String filePath, Integer offset, Integer limit) throws IOException {
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);

        int startLine = offset != null ? Math.max(0, offset - 1) : 0;
        int endLine = limit != null
            ? Math.min(lines.size(), startLine + limit)
            : Math.min(lines.size(), startLine + DEFAULT_MAX_LINES);

        StringBuilder sb = new StringBuilder();
        for (int i = startLine; i < endLine; i++) {
            // Add 1-based line numbers (matching TypeScript behavior)
            sb.append(String.format("%d\t%s%n", i + 1, lines.get(i)));
        }

        return Output.builder()
            .filePath(filePath)
            .content(sb.toString())
            .type("text")
            .totalLines(lines.size())
            .startLine(startLine + 1)
            .endLine(endLine)
            .build();
    }

    private Output readImageFile(File file, String filePath) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        String base64 = Base64.getEncoder().encodeToString(bytes);
        String mediaType = detectMediaType(filePath);

        return Output.builder()
            .filePath(filePath)
            .type("image")
            .imageData(base64)
            .mediaType(mediaType)
            .build();
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Reading " + input.getFilePath());
    }

    @Override
    public boolean isReadOnly(Input input) {
        return true;
    }

    @Override
    public SearchOrReadInfo isSearchOrReadCommand(Input input) {
        return SearchOrReadInfo.READ;
    }

    @Override
    public String userFacingName(Input input) {
        if (input == null || input.getFilePath() == null) return TOOL_NAME;
        String path = input.getFilePath();
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    @Override
    public String getActivityDescription(Input input) {
        return "Reading " + (input != null ? input.getFilePath() : "file");
    }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        if ("image".equals(content.getType())) {
            return Map.of(
                "type", "tool_result",
                "tool_use_id", toolUseId,
                "content", List.of(Map.of(
                    "type", "image",
                    "source", Map.of(
                        "type", "base64",
                        "media_type", content.getMediaType(),
                        "data", content.getImageData()
                    )
                ))
            );
        }

        return Map.of(
            "type", "tool_result",
            "tool_use_id", toolUseId,
            "content", content.getContent() != null ? content.getContent() : ""
        );
    }

    @Override
    public int getMaxResultSizeChars() {
        // Read tool output must never be persisted (creates circular Read→file→Read loop)
        return Integer.MAX_VALUE;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String getExtension(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1).toLowerCase() : "";
    }

    private static boolean isImageExtension(String ext) {
        return Set.of("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico").contains(ext);
    }

    private static String detectMediaType(String path) {
        String ext = getExtension(path);
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "svg" -> "image/svg+xml";
            case "bmp" -> "image/bmp";
            default -> "image/png";
        };
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return (bytes / (1024 * 1024)) + " MB";
    }

    // =========================================================================
    // Input/Output types
    // =========================================================================

    @Data
    @lombok.Builder
    
    public static class Input {
        private String filePath;
        private Integer offset;
        private Integer limit;
    }

    @Data
    @lombok.Builder
    
    public static class Output {
        private String filePath;
        private String content;
        private String type; // "text" | "image" | "directory"
        private Integer totalLines;
        private Integer startLine;
        private Integer endLine;
        private String imageData; // base64
        private String mediaType;
    }
}
