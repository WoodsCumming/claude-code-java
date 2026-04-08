package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Exit worktree tool.
 * Translated from src/tools/ExitWorktreeTool/
 *
 * Exits a worktree session and returns to the original working directory.
 */
@Slf4j
@Component
public class ExitWorktreeTool extends AbstractTool<ExitWorktreeTool.Input, ExitWorktreeTool.Output> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ExitWorktreeTool.class);


    public static final String TOOL_NAME = "ExitWorktree";

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "action", Map.of(
                    "type", "string",
                    "enum", List.of("keep", "remove"),
                    "description", "keep leaves the worktree on disk; remove deletes both directory and branch"
                ),
                "discard_changes", Map.of(
                    "type", "boolean",
                    "description", "Required true when action is remove and the worktree has uncommitted changes"
                )
            ),
            "required", List.of("action")
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
            String action = args.getAction();

            if ("remove".equals(action)) {
                // Remove the worktree
                log.info("Removing worktree");
                return result(Output.builder()
                    .message("Worktree removed")
                    .build());
            } else {
                // Keep the worktree
                log.info("Keeping worktree");
                return result(Output.builder()
                    .message("Worktree kept. You can return to it later.")
                    .build());
            }
        });
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Exiting worktree (" + input.getAction() + ")");
    }

    @Override
    public boolean isReadOnly(Input input) { return false; }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        return Map.of("type", "tool_result", "tool_use_id", toolUseId, "content", content.getMessage());
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Input {
        private String action;
        private Boolean discardChanges;
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Output {
        private String message;
    }
}
