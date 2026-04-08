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
 * File edit tool for performing exact string replacements.
 * Translated from src/tools/FileEditTool/FileEditTool.ts
 *
 * Performs exact string replacements in files. The edit will FAIL if
 * old_string is not unique in the file.
 */
@Slf4j
@Component
public class FileEditTool extends AbstractTool<FileEditTool.Input, FileEditTool.Output> {



    public static final String TOOL_NAME = "Edit";

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getSearchHint() {
        return "edit files with exact string replacement";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "file_path", Map.of(
                    "type", "string",
                    "description", "The absolute path to the file to modify"
                ),
                "old_string", Map.of(
                    "type", "string",
                    "description", "The text to replace (must be unique in the file)"
                ),
                "new_string", Map.of(
                    "type", "string",
                    "description", "The text to replace it with"
                ),
                "replace_all", Map.of(
                    "type", "boolean",
                    "description", "Replace all occurrences (default false)",
                    "default", false
                )
            ),
            "required", List.of("file_path", "old_string", "new_string")
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

                if (!file.exists()) {
                    throw new FileNotFoundException("File not found: " + filePath);
                }

                String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                String oldString = args.getOldString();
                String newString = args.getNewString();
                boolean replaceAll = Boolean.TRUE.equals(args.getReplaceAll());

                // Count occurrences
                int occurrences = countOccurrences(content, oldString);

                if (occurrences == 0) {
                    throw new IllegalArgumentException(
                        "old_string not found in file: " + filePath
                        + "\nThe string to replace was not found. Check for exact whitespace and indentation."
                    );
                }

                if (!replaceAll && occurrences > 1) {
                    throw new IllegalArgumentException(
                        "old_string is not unique in file: " + filePath
                        + " (found " + occurrences + " occurrences). "
                        + "Provide more surrounding context to make it unique, or use replace_all=true."
                    );
                }

                String newContent;
                if (replaceAll) {
                    newContent = content.replace(oldString, newString);
                } else {
                    newContent = content.replaceFirst(
                        java.util.regex.Pattern.quote(oldString),
                        java.util.regex.Matcher.quoteReplacement(newString)
                    );
                }

                Files.writeString(file.toPath(), newContent, StandardCharsets.UTF_8);

                log.debug("Edited file: {} ({} replacement(s))", filePath, replaceAll ? occurrences : 1);

                Output output = Output.builder()
                    .filePath(filePath)
                    .replacements(replaceAll ? occurrences : 1)
                    .build();

                return result(output);

            } catch (Exception e) {
                log.error("File edit failed: {}", e.getMessage());
                throw new RuntimeException(e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Editing " + input.getFilePath());
    }

    @Override
    public boolean isReadOnly(Input input) {
        return false;
    }

    @Override
    public boolean isDestructive(Input input) {
        return false; // Edits are reversible with undo
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
        return "Editing " + (input != null ? input.getFilePath() : "file");
    }

    @Override
    public Object toAutoClassifierInput(Input input) {
        if (input == null) return "";
        return input.getFilePath() + ": " + truncate(input.getOldString(), 100)
            + " → " + truncate(input.getNewString(), 100);
    }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        return Map.of(
            "type", "tool_result",
            "tool_use_id", toolUseId,
            "content", "The file " + content.getFilePath() + " has been edited successfully."
        );
    }

    private static int countOccurrences(String text, String pattern) {
        if (pattern == null || pattern.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(pattern, idx)) != -1) {
            count++;
            idx += pattern.length();
        }
        return count;
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
        private String oldString;
        private String newString;
        private Boolean replaceAll;
    }

    @Data
    @lombok.Builder
    
    public static class Output {
        private String filePath;
        private int replacements;
    }
}
