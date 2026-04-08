package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Task update tool.
 * Translated from src/tools/TaskUpdateTool/TaskUpdateTool.ts
 */
@Slf4j
@Component
public class TaskUpdateTool extends AbstractTool<TaskUpdateTool.Input, TaskUpdateTool.Output> {



    public static final String TOOL_NAME = "TaskUpdate";

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "taskId", Map.of("type", "string", "description", "The ID of the task to update"),
                "status", Map.of("type", "string", "description", "New status for the task"),
                "subject", Map.of("type", "string"),
                "description", Map.of("type", "string"),
                "owner", Map.of("type", "string"),
                "addBlocks", Map.of("type", "array", "items", Map.of("type", "string")),
                "addBlockedBy", Map.of("type", "array", "items", Map.of("type", "string")),
                "metadata", Map.of("type", "object")
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

        Map<String, TaskCreateTool.TaskItem> taskStore = TaskCreateTool.getTaskStore();
        TaskCreateTool.TaskItem task = taskStore.get(args.getTaskId());

        if (task == null) {
            return futureResult(Output.builder()
                .message("Task not found: " + args.getTaskId())
                .build());
        }

        // Apply updates
        if (args.getStatus() != null) task.setStatus(args.getStatus());
        if (args.getSubject() != null) task.setSubject(args.getSubject());
        if (args.getDescription() != null) task.setDescription(args.getDescription());
        if (args.getOwner() != null) task.setOwner(args.getOwner());

        return futureResult(Output.builder()
            .message("Updated task #" + args.getTaskId())
            .task(task)
            .build());
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Updating task " + input.getTaskId());
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
        private String taskId;
        private String status;
        private String subject;
        private String description;
        private String activeForm;
        private String owner;
        private List<String> addBlocks;
        private List<String> addBlockedBy;
        private Map<String, Object> metadata;
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Output {
        private String message;
        private TaskCreateTool.TaskItem task;
    }
}
