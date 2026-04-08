package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Task output retrieval tool.
 * Translated from src/tools/TaskOutputTool/TaskOutputTool.tsx
 *
 * Retrieves output from a running or completed background task.
 */
@Slf4j
@Component
public class TaskOutputTool extends AbstractTool<TaskOutputTool.Input, TaskOutputTool.Output> {



    public static final String TOOL_NAME = "TaskOutput";

    // Background task results store (would be populated by background tasks)
    private static final Map<String, TaskResult> taskResults = new ConcurrentHashMap<>();

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "task_id", Map.of("type", "string", "description", "The task ID to get output from"),
                "block", Map.of("type", "boolean", "description", "Whether to wait for completion", "default", true),
                "timeout", Map.of("type", "number", "description", "Max wait time in ms", "default", 30000)
            ),
            "required", List.of("task_id")
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
            String taskId = args.getTaskId();
            boolean block = args.getBlock() != null ? args.getBlock() : true;
            long timeout = args.getTimeout() != null ? args.getTimeout() : 30_000;

            // Check if task exists
            TaskResult result = taskResults.get(taskId);

            if (result == null) {
                // Check TaskCreate store for background tasks
                TaskCreateTool.TaskItem task = TaskCreateTool.getTaskStore().get(taskId);
                if (task == null) {
                    return this.result(Output.builder()
                        .retrievalStatus("not_found")
                        .build());
                }

                return this.result(Output.builder()
                    .retrievalStatus("not_ready")
                    .taskId(taskId)
                    .status(task.getStatus())
                    .description(task.getSubject())
                    .build());
            }

            if (!result.isCompleted() && block) {
                // Wait for completion
                long deadline = System.currentTimeMillis() + timeout;
                while (!result.isCompleted() && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                if (!result.isCompleted()) {
                    return this.result(Output.builder()
                        .retrievalStatus("timeout")
                        .taskId(taskId)
                        .build());
                }
            }

            return this.result(Output.builder()
                .retrievalStatus("success")
                .taskId(taskId)
                .status(result.getStatus())
                .output(result.getOutput())
                .exitCode(result.getExitCode())
                .build());
        });
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Getting output for task: " + input.getTaskId());
    }

    @Override
    public boolean isReadOnly(Input input) { return true; }

    @Override
    public boolean isConcurrencySafe(Input input) { return true; }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        String text = switch (content.getRetrievalStatus()) {
            case "success" -> content.getOutput() != null ? content.getOutput() : "Task completed.";
            case "timeout" -> "Timeout waiting for task " + content.getTaskId();
            case "not_ready" -> "Task " + content.getTaskId() + " is not yet complete (status: " + content.getStatus() + ")";
            case "not_found" -> "Task not found: " + content.getTaskId();
            default -> "Unknown status: " + content.getRetrievalStatus();
        };
        return Map.of("type", "tool_result", "tool_use_id", toolUseId, "content", text);
    }

    public static void registerTaskResult(String taskId, TaskResult result) {
        taskResults.put(taskId, result);
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Input {
        private String taskId;
        private Boolean block;
        private Long timeout;
    
        public Boolean getBlock() { return block; }
    
        public String getTaskId() { return taskId; }
    
        public Long getTimeout() { return timeout; }
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Output {
        private String retrievalStatus; // "success" | "timeout" | "not_ready" | "not_found"
        private String taskId;
        private String status;
        private String description;
        private String output;
        private Integer exitCode;
        private String error;
    
        public String getOutput() { return output; }
    
        public String getRetrievalStatus() { return retrievalStatus; }
    
        public String getStatus() { return status; }
    
        public String getTaskId() { return taskId; }
    
        public static OutputBuilder builder() { return new OutputBuilder(); }
        public static class OutputBuilder {
            private String retrievalStatus;
            private String taskId;
            private String status;
            private String description;
            private String output;
            private Integer exitCode;
            private String error;
            public OutputBuilder retrievalStatus(String v) { this.retrievalStatus = v; return this; }
            public OutputBuilder taskId(String v) { this.taskId = v; return this; }
            public OutputBuilder status(String v) { this.status = v; return this; }
            public OutputBuilder description(String v) { this.description = v; return this; }
            public OutputBuilder output(String v) { this.output = v; return this; }
            public OutputBuilder exitCode(Integer v) { this.exitCode = v; return this; }
            public OutputBuilder error(String v) { this.error = v; return this; }
            public Output build() {
                Output o = new Output();
                o.retrievalStatus = retrievalStatus;
                o.taskId = taskId;
                o.status = status;
                o.description = description;
                o.output = output;
                o.exitCode = exitCode;
                o.error = error;
                return o;
            }
        }
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class TaskResult {
        private String taskId;
        private boolean completed;
        private String status;
        private String output;
        private Integer exitCode;
        private String error;
    
        public Integer getExitCode() { return exitCode; }
    
        public String getOutput() { return output; }
    
        public String getStatus() { return status; }
    
        public boolean isCompleted() { return completed; }
    }
}
