package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Cron job deletion tool.
 * Translated from src/tools/ScheduleCronTool/CronDeleteTool.ts
 */
@Slf4j
@Component
public class CronDeleteTool extends AbstractTool<CronDeleteTool.Input, CronDeleteTool.Output> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CronDeleteTool.class);


    public static final String TOOL_NAME = "CronDelete";

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "id", Map.of("type", "string", "description", "Job ID returned by CronCreate")
            ),
            "required", List.of("id")
        );
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
            Input args,
            ToolUseContext context,
            Tool.CanUseToolFn canUseTool,
            Message.AssistantMessage parentMessage,
            Consumer<Tool.ToolProgress> onProgress) {

        boolean removed = CronCreateTool.getCronJobs().remove(args.getId()) != null;
        return futureResult(Output.builder()
            .success(removed)
            .message(removed ? "Deleted cron job: " + args.getId() : "Job not found: " + args.getId())
            .build());
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Deleting cron job: " + input.getId());
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
        private String id;
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Output {
        private boolean success;
        private String message;
    }
}
