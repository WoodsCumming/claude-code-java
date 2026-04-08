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
 * File write tool.
 * Translated from src/tools/FileWriteTool/FileWriteTool.ts
 *
 * Creates or overwrites files on the local filesystem.
 */
@Slf4j
@Component
public class FileWriteTool extends AbstractTool<FileWriteTool.Input, FileWriteTool.Output> {



    public static final String TOOL_NAME = "Write";

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getSearchHint() {
        return "create or overwrite files with content";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "file_path", Map.of(
                    "type", "string",
                    "description", "The absolute path to the file to write (must be absolute, not relative)"
                ),
                "content", Map.of(
                    "type", "string",
                    "description", "The content to write to the file"
                )
            ),
            "required", List.of("file_path", "content")
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

                boolean isNewFile = !file.exists();
                String originalContent = null;

                if (!isNewFile) {
                    originalContent = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                }

                // Create parent directories if needed
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }

                // Write the file
                Files.writeString(file.toPath(), args.getContent(), StandardCharsets.UTF_8);

                log.debug("Wrote file: {} ({} chars)", filePath, args.getContent().length());

                Output output = Output.builder()
                    .type(isNewFile ? "create" : "update")
                    .filePath(filePath)
                    .content(args.getContent())
                    .originalFile(originalContent)
                    .build();

                return result(output);

            } catch (Exception e) {
                log.error("File write failed: {}", e.getMessage());
                throw new RuntimeException("Failed to write file: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Writing " + input.getFilePath());
    }

    @Override
    public boolean isReadOnly(Input input) {
        return false;
    }

    @Override
    public boolean isDestructive(Input input) {
        return true; // Overwrites existing content
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
        return "Writing " + (input != null ? input.getFilePath() : "file");
    }

    @Override
    public Object toAutoClassifierInput(Input input) {
        if (input == null) return "";
        return input.getFilePath() + ": " + truncate(input.getContent(), 200);
    }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        String text = content.getType() + " " + content.getFilePath();
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

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }

    // =========================================================================
    // Input/Output types
    // =========================================================================

    @Data
    @lombok.Builder
    
    public static class Input {
        private String filePath;
        private String content;
    }

    @Data
    @lombok.Builder
    
    public static class Output {
        private String type; // "create" | "update"
        private String filePath;
        private String content;
        private String originalFile;
    }
}
