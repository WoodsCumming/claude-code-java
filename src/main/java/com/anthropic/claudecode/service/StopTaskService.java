package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.Task;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Stop task service for stopping running tasks.
 * Translated from src/tasks/stopTask.ts
 *
 * Shared logic used by TaskStopTool (LLM-invoked) and SDK stop_task control request.
 */
@Slf4j
@Service
public class StopTaskService {



    private final TaskFrameworkService taskFrameworkService;
    private final SdkEventQueueService sdkEventQueueService;
    private final KillShellTasksService killShellTasksService;

    @Autowired
    public StopTaskService(TaskFrameworkService taskFrameworkService,
                            SdkEventQueueService sdkEventQueueService,
                            KillShellTasksService killShellTasksService) {
        this.taskFrameworkService = taskFrameworkService;
        this.sdkEventQueueService = sdkEventQueueService;
        this.killShellTasksService = killShellTasksService;
    }

    // =========================================================================
    // StopTaskResult
    // Translated from StopTaskResult in stopTask.ts
    // =========================================================================

    public static class StopTaskResult {
        private String taskId;
        private String taskType;
        private String command;

        public StopTaskResult() {}
        public StopTaskResult(String taskId, String taskType, String command) {
            this.taskId = taskId; this.taskType = taskType; this.command = command;
        }
        public String getTaskId() { return taskId; }
        public void setTaskId(String v) { taskId = v; }
        public String getTaskType() { return taskType; }
        public void setTaskType(String v) { taskType = v; }
        public String getCommand() { return command; }
        public void setCommand(String v) { command = v; }
    }

    // =========================================================================
    // StopTaskError
    // Translated from StopTaskError class in stopTask.ts
    // Mirrors the three error codes: not_found | not_running | unsupported_type
    // =========================================================================

    public enum StopErrorCode { NOT_FOUND, NOT_RUNNING, UNSUPPORTED_TYPE }

    public static class StopTaskError extends RuntimeException {
        private final StopErrorCode code;

        public StopTaskError(String message, StopErrorCode code) {
            super(message);
            this.code = code;
        }

        public StopErrorCode getCode() { return code; }
    }

    // =========================================================================
    // stopTask
    // Translated from stopTask() in stopTask.ts
    // =========================================================================

    /**
     * Look up a task by ID, validate it is running, kill it, and mark it as notified.
     *
     * Throws {@link StopTaskError} when the task cannot be stopped:
     *   NOT_FOUND       — no task with the given ID
     *   NOT_RUNNING     — task exists but is not in "running" status
     *   UNSUPPORTED_TYPE — task type has no registered kill implementation
     *
     * Translated from stopTask() in stopTask.ts
     */
    public CompletableFuture<StopTaskResult> stopTask(String taskId) {
        return CompletableFuture.supplyAsync(() -> {
            // --- look up task ---
            Optional<TaskFrameworkService.TaskState> taskOpt = taskFrameworkService.getTask(taskId);
            if (taskOpt.isEmpty()) {
                throw new StopTaskError("No task found with ID: " + taskId, StopErrorCode.NOT_FOUND);
            }

            TaskFrameworkService.TaskState task = taskOpt.get();

            // --- validate running ---
            if (!"running".equals(task.getStatus())) {
                throw new StopTaskError(
                        "Task " + taskId + " is not running (status: " + task.getStatus() + ")",
                        StopErrorCode.NOT_RUNNING);
            }

            String taskType = task.getType();

            // --- dispatch kill ---
            // KillShellTasksService handles shell tasks; for unsupported types it is a no-op.
            // Fail open for now — unsupported-type detection would require a type registry.
            killShellTasksService.killTask(taskId);

            // --- suppress the "exit code 137" notification for shell tasks ---
            // Bash: suppress noise. Agent tasks: don't suppress — their AbortError
            // catch sends a notification carrying extractPartialResult(agentMessages).
            boolean isShellTask = isLocalShellTask(task);
            if (isShellTask) {
                boolean suppressed = suppressNotificationIfNeeded(taskId, task);
                if (suppressed) {
                    // Suppressing the XML notification also suppresses the parsed SDK event;
                    // emit directly so SDK consumers see the task close.
                    sdkEventQueueService.enqueueTaskNotification(
                            taskId, "stopped", task.getDescription());
                }
            }

            // --- build result ---
            // Shell tasks show the command; agent tasks show the description.
            String command = isShellTask
                    ? (String) task.getMetadata().getOrDefault("command", task.getDescription())
                    : task.getDescription();

            log.info("Stopped task: {} (type={}, command={})", taskId, taskType, command);
            return new StopTaskResult(taskId, taskType, command);
        });
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Returns true when the task is a local shell (bash / monitor) task.
     * Translated from isLocalShellTask() guard in stopTask.ts
     */
    private static boolean isLocalShellTask(TaskFrameworkService.TaskState task) {
        return "local_bash".equals(task.getType());
    }

    /**
     * Atomically set notified=true if not already notified.
     * Returns true if we actually changed the flag (suppression occurred).
     */
    private boolean suppressNotificationIfNeeded(String taskId,
                                                  TaskFrameworkService.TaskState task) {
        synchronized (task) {
            if (Boolean.TRUE.equals(task.getMetadata().get("notified"))) {
                return false;
            }
            task.getMetadata().put("notified", true);
            return true;
        }
    }
}
