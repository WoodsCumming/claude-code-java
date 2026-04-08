package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Task get tool.
 * Translated from src/tools/TaskGetTool/TaskGetTool.ts
 */
@Slf4j
@Component
public class TaskGetTool extends AbstractTool<TaskGetTool.Input, TaskGetTool.Output> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TaskGetTool.class);


    public static final String TOOL_NAME = "TaskGet";

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "taskId", Map.of("type", "string", "description", "The ID of the task to retrieve")
            ),
            "required", List.of("taskId")
        );
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
            Input args,
            ToolUseContext context,
            Tool.CanUseToolFn canUseTool,
            Message.AssistantMessage parentMessage,
            Consumer<Tool.ToolProgress> onProgress) {

        TaskCreateTool.TaskItem task = TaskCreateTool.getTaskStore().get(args.getTaskId());

        return futureResult(Output.builder()
            .task(task)
            .found(task != null)
            .build());
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Getting task " + input.getTaskId());
    }

    @Override
    public boolean isReadOnly(Input input) { return true; }

    @Override
    public boolean isConcurrencySafe(Input input) { return true; }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        String text = content.isFound() && content.getTask() != null
            ? "Task #" + content.getTask().getId() + ": " + content.getTask().getSubject()
                + "\nStatus: " + content.getTask().getStatus()
                + "\n" + content.getTask().getDescription()
            : "Task not found";
        return Map.of("type", "tool_result", "tool_use_id", toolUseId, "content", text);
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Input {
        private String taskId;
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Output {
        private TaskCreateTool.TaskItem task;
        private boolean found;
    }
}
