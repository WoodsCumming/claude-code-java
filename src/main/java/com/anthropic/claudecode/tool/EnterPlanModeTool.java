package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Tool to enter plan mode.
 * Translated from src/tools/EnterPlanModeTool/EnterPlanModeTool.ts
 *
 * Transitions Claude Code into plan mode where it can explore the codebase
 * and design an implementation approach before writing code.
 */
@Slf4j
@Component
public class EnterPlanModeTool extends AbstractTool<EnterPlanModeTool.Input, EnterPlanModeTool.Output> {



    public static final String TOOL_NAME = "EnterPlanMode";

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(),
            "additionalProperties", false
        );
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
            Input args,
            ToolUseContext context,
            Tool.CanUseToolFn canUseTool,
            Message.AssistantMessage parentMessage,
            Consumer<Tool.ToolProgress> onProgress) {

        // Signal that we're entering plan mode
        log.info("Entering plan mode");

        return futureResult(Output.builder()
            .message("Entering plan mode. You can now explore the codebase and design your approach.")
            .build());
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Entering plan mode");
    }

    @Override
    public boolean isReadOnly(Input input) { return true; }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        return Map.of(
            "type", "tool_result",
            "tool_use_id", toolUseId,
            "content", content.getMessage()
        );
    }

    @lombok.Data @lombok.Builder
    public static class Input {}

    @lombok.Data @lombok.Builder
    public static class Output {
        private String message;
    }
}
