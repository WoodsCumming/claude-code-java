package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Task list tool.
 * Translated from src/tools/TaskListTool/
 */
@Slf4j
@Component
public class TaskListTool extends AbstractTool<TaskListTool.Input, TaskListTool.Output> {



    public static final String TOOL_NAME = "TaskList";

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

        List<TaskCreateTool.TaskItem> tasks = new ArrayList<>(TaskCreateTool.getTaskStore().values());
        tasks.sort(Comparator.comparing(t -> Integer.parseInt(t.getId())));

        return futureResult(Output.builder()
            .tasks(tasks)
            .build());
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Listing all tasks");
    }

    @Override
    public boolean isReadOnly(Input input) { return true; }

    @Override
    public boolean isConcurrencySafe(Input input) { return true; }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        String text;
        if (content.getTasks().isEmpty()) {
            text = "No tasks.";
        } else {
            text = content.getTasks().stream()
                .map(t -> "#" + t.getId() + " [" + t.getStatus() + "] " + t.getSubject())
                .collect(Collectors.joining("\n"));
        }
        return Map.of("type", "tool_result", "tool_use_id", toolUseId, "content", text);
    }

    @lombok.Data @lombok.Builder
    public static class Input {}

    @lombok.Data @lombok.Builder
    public static class Output {
        private List<TaskCreateTool.TaskItem> tasks;
    }
}
