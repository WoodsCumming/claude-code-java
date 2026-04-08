package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Task stop tool for stopping background tasks.
 * Translated from src/tools/TaskStopTool/TaskStopTool.ts
 */
@Slf4j
@Component
public class TaskStopTool extends AbstractTool<TaskStopTool.Input, TaskStopTool.Output> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TaskStopTool.class);


    public static final String TOOL_NAME = "TaskStop";

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "task_id", Map.of("type", "string", "description", "The ID of the background task to stop"),
                "shell_id", Map.of("type", "string", "description", "Deprecated: use task_id instead")
            )
        );
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
            Input args,
            ToolUseContext context,
            Tool.CanUseToolFn canUseTool,
            Message.AssistantMessage parentMessage,
            Consumer<Tool.ToolProgress> onProgress) {

        String taskId = args.getTaskId() != null ? args.getTaskId() : args.getShellId();

        if (taskId == null) {
            return futureResult(Output.builder()
                .success(false)
                .message("No task ID provided")
                .build());
        }

        log.info("Stopping task: {}", taskId);

        // Mark task as stopped in the task store
        TaskCreateTool.TaskItem task = TaskCreateTool.getTaskStore().get(taskId);
        if (task != null) {
            task.setStatus("completed");
        }

        return futureResult(Output.builder()
            .success(true)
            .message("Task " + taskId + " stopped")
            .build());
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Stopping task " + input.getTaskId());
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
        private String shellId; // deprecated
    
        public String getShellId() { return shellId; }
    
        public String getTaskId() { return taskId; }
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Output {
        private boolean success;
        private String message;
    
        public String getMessage() { return message; }
    
        public static OutputBuilder builder() { return new OutputBuilder(); }
        public static class OutputBuilder {
            private boolean success;
            private String message;
            public OutputBuilder success(boolean v) { this.success = v; return this; }
            public OutputBuilder message(String v) { this.message = v; return this; }
            public Output build() {
                Output o = new Output();
                o.success = success;
                o.message = message;
                return o;
            }
        }
    }
}
