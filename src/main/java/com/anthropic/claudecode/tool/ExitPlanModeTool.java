package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Tool to exit plan mode.
 * Translated from src/tools/ExitPlanModeTool/ExitPlanModeV2Tool.ts
 */
@Slf4j
@Component
public class ExitPlanModeTool extends AbstractTool<ExitPlanModeTool.Input, ExitPlanModeTool.Output> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ExitPlanModeTool.class);


    public static final String TOOL_NAME = "ExitPlanMode";

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "allowedPrompts", Map.of(
                    "type", "array",
                    "description", "Prompt-based permissions needed to implement the plan",
                    "items", Map.of(
                        "type", "object",
                        "properties", Map.of(
                            "tool", Map.of("type", "string"),
                            "prompt", Map.of("type", "string")
                        )
                    )
                )
            ),
            "additionalProperties", true
        );
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
            Input args,
            ToolUseContext context,
            Tool.CanUseToolFn canUseTool,
            Message.AssistantMessage parentMessage,
            Consumer<Tool.ToolProgress> onProgress) {

        log.info("Exiting plan mode");

        return futureResult(Output.builder()
            .message("Exiting plan mode. Ready to implement.")
            .build());
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Exiting plan mode");
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

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Input {
        private List<Map<String, String>> allowedPrompts;
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Output {
        private String message;
    
        public String getMessage() { return message; }
    
        public static OutputBuilder builder() { return new OutputBuilder(); }
        public static class OutputBuilder {
            private String message;
            public OutputBuilder message(String v) { this.message = v; return this; }
            public Output build() {
                Output o = new Output();
                o.message = message;
                return o;
            }
        }
    }
}
