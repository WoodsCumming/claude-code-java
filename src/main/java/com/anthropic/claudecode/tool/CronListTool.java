package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Cron job listing tool.
 * Translated from src/tools/ScheduleCronTool/CronListTool.ts
 */
@Slf4j
@Component
public class CronListTool extends AbstractTool<CronListTool.Input, CronListTool.Output> {



    public static final String TOOL_NAME = "CronList";

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of("type", "object", "properties", Map.of(), "additionalProperties", false);
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
            Input args,
            ToolUseContext context,
            Tool.CanUseToolFn canUseTool,
            Message.AssistantMessage parentMessage,
            Consumer<Tool.ToolProgress> onProgress) {

        List<CronCreateTool.CronJob> jobs = new ArrayList<>(CronCreateTool.getCronJobs().values());
        return futureResult(Output.builder().jobs(jobs).build());
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Listing cron jobs");
    }

    @Override
    public boolean isReadOnly(Input input) { return true; }

    @Override
    public boolean isConcurrencySafe(Input input) { return true; }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        String text = content.getJobs().isEmpty()
            ? "No scheduled jobs."
            : content.getJobs().stream()
                .map(j -> j.getId() + ": " + j.getCron() + " - " + j.getPrompt())
                .collect(Collectors.joining("\n"));
        return Map.of("type", "tool_result", "tool_use_id", toolUseId, "content", text);
    }

    @lombok.Data @lombok.Builder
    public static class Input {}

    @lombok.Data @lombok.Builder
    public static class Output {
        private List<CronCreateTool.CronJob> jobs;
    }
}
