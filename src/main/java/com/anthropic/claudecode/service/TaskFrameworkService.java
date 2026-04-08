package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.UnaryOperator;

/**
 * Task framework service for managing task lifecycle.
 * Translated from src/utils/task/framework.ts
 *
 * Provides the core task management infrastructure including task registration,
 * state updates, eviction, output delta polling, and notification dispatch.
 */
@Slf4j
@Service
public class TaskFrameworkService {



    // Standard polling interval for all tasks
    public static final long POLL_INTERVAL_MS = 1000;

    // Duration to display killed tasks before eviction
    public static final long STOPPED_DISPLAY_MS = 3_000;

    // Grace period for terminal local_agent tasks in the coordinator panel
    public static final long PANEL_GRACE_MS = 30_000;

    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final SdkEventQueueService sdkEventQueueService;
    private final TaskOutputDiskService taskOutputDiskService;

    @Autowired
    public TaskFrameworkService(SdkEventQueueService sdkEventQueueService,
                                TaskOutputDiskService taskOutputDiskService) {
        this.sdkEventQueueService = sdkEventQueueService;
        this.taskOutputDiskService = taskOutputDiskService;
    }

    // =========================================================================
    // TaskAttachment — corresponds to TypeScript TaskAttachment type
    // =========================================================================

    public static class TaskAttachment {
        private String type;          // always "task_status"
        private String taskId;
        private String toolUseId;     // nullable
        private String taskType;
        private String status;
        private String description;
        private String deltaSummary;  // nullable — new output since last attachment

        public TaskAttachment() {}
        public String getType() { return type; }
        public void setType(String v) { type = v; }
        public String getTaskId() { return taskId; }
        public void setTaskId(String v) { taskId = v; }
        public String getToolUseId() { return toolUseId; }
        public void setToolUseId(String v) { toolUseId = v; }
        public String getTaskType() { return taskType; }
        public void setTaskType(String v) { taskType = v; }
        public String getStatus() { return status; }
        public void setStatus(String v) { status = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
        public String getDeltaSummary() { return deltaSummary; }
        public void setDeltaSummary(String v) { deltaSummary = v; }

        public static TaskAttachmentBuilder builder() { return new TaskAttachmentBuilder(); }
        public static class TaskAttachmentBuilder {
            private final TaskAttachment a = new TaskAttachment();
            public TaskAttachmentBuilder type(String v) { a.type = v; return this; }
            public TaskAttachmentBuilder taskId(String v) { a.taskId = v; return this; }
            public TaskAttachmentBuilder toolUseId(String v) { a.toolUseId = v; return this; }
            public TaskAttachmentBuilder taskType(String v) { a.taskType = v; return this; }
            public TaskAttachmentBuilder status(String v) { a.status = v; return this; }
            public TaskAttachmentBuilder description(String v) { a.description = v; return this; }
            public TaskAttachmentBuilder deltaSummary(String v) { a.deltaSummary = v; return this; }
            public TaskAttachment build() { return a; }
        }
    }

    // =========================================================================
    // Task state — corresponds to TypeScript TaskState
    // =========================================================================

    public static class TaskState {
        private String id;
        private String type;
        private String status;       // "pending" | "running" | "completed" | "failed" | "killed"
        private String description;
        private String toolUseId;    // nullable
        private boolean notified;
        private long outputOffset;
        private long startTime = System.currentTimeMillis();
        private Long evictAfter;     // nullable — panel grace deadline
        private boolean retain;      // local_agent: retain across /clear
        private Map<String, Object> metadata = new LinkedHashMap<>();

        public TaskState() {}
        public TaskState(String id, String type, String status, String description, String toolUseId,
                         boolean notified, long outputOffset, long startTime, Long evictAfter, boolean retain,
                         Map<String, Object> metadata) {
            this.id = id; this.type = type; this.status = status; this.description = description;
            this.toolUseId = toolUseId; this.notified = notified; this.outputOffset = outputOffset;
            this.startTime = startTime; this.evictAfter = evictAfter; this.retain = retain;
            this.metadata = metadata != null ? metadata : new LinkedHashMap<>();
        }

        public String getId() { return id; }
        public void setId(String v) { this.id = v; }
        public String getType() { return type; }
        public void setType(String v) { type = v; }
        public String getStatus() { return status; }
        public void setStatus(String v) { status = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
        public String getToolUseId() { return toolUseId; }
        public void setToolUseId(String v) { toolUseId = v; }
        public boolean isNotified() { return notified; }
        public void setNotified(boolean v) { this.notified = v; }
        public long getOutputOffset() { return outputOffset; }
        public void setOutputOffset(long v) { this.outputOffset = v; }
        public long getStartTime() { return startTime; }
        public void setStartTime(long v) { this.startTime = v; }
        public Long getEvictAfter() { return evictAfter; }
        public void setEvictAfter(Long v) { this.evictAfter = v; }
        public boolean isRetain() { return retain; }
        public void setRetain(boolean v) { this.retain = v; }
        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> v) { this.metadata = v != null ? v : new LinkedHashMap<>(); }
    
        public static TaskStateBuilder builder() { return new TaskStateBuilder(); }
        public static class TaskStateBuilder {
            private String id;
            private String type;
            private String status;
            private String description;
            private String toolUseId;
            private boolean notified;
            private long outputOffset;
            private long startTime;
            private Long evictAfter;
            private boolean retain;
            private Map<String, Object> metadata;
            public TaskStateBuilder id(String v) { this.id = v; return this; }
            public TaskStateBuilder type(String v) { this.type = v; return this; }
            public TaskStateBuilder status(String v) { this.status = v; return this; }
            public TaskStateBuilder description(String v) { this.description = v; return this; }
            public TaskStateBuilder toolUseId(String v) { this.toolUseId = v; return this; }
            public TaskStateBuilder notified(boolean v) { this.notified = v; return this; }
            public TaskStateBuilder outputOffset(long v) { this.outputOffset = v; return this; }
            public TaskStateBuilder startTime(long v) { this.startTime = v; return this; }
            public TaskStateBuilder evictAfter(Long v) { this.evictAfter = v; return this; }
            public TaskStateBuilder retain(boolean v) { this.retain = v; return this; }
            public TaskStateBuilder metadata(Map<String, Object> v) { this.metadata = v; return this; }
            public TaskState build() {
                TaskState o = new TaskState();
                o.id = id;
                o.type = type;
                o.status = status;
                o.description = description;
                o.toolUseId = toolUseId;
                o.notified = notified;
                o.outputOffset = outputOffset;
                o.startTime = startTime;
                o.evictAfter = evictAfter;
                o.retain = retain;
                o.metadata = metadata;
                return o;
            }
        }
    }

    // =========================================================================
    // Core framework operations
    // =========================================================================

    /**
     * Register a new task.
     * Translated from registerTask() in framework.ts
     *
     * On re-registration (resume), merges UI-held state (retain, startTime,
     * messages, pendingMessages) from the existing entry rather than clobbering it.
     */
    public void registerTask(TaskState task) {
        boolean[] isReplacement = {false};
        tasks.compute(task.getId(), (id, existing) -> {
            if (existing != null) {
                isReplacement[0] = true;
                // Carry forward UI-held state on re-register
                TaskState merged = new TaskState();
                merged.setId(task.getId());
                merged.setType(task.getType());
                merged.setStatus(task.getStatus());
                merged.setDescription(task.getDescription());
                merged.setToolUseId(task.getToolUseId());
                merged.setNotified(task.isNotified());
                merged.setOutputOffset(task.getOutputOffset());
                // Preserved from existing
                merged.setRetain(existing.isRetain());
                merged.setStartTime(existing.getStartTime());
                merged.setMetadata(task.getMetadata() != null ? task.getMetadata() : new LinkedHashMap<>());
                return merged;
            }
            return task;
        });

        // Replacement (resume) — not a new start. Skip to avoid double-emit.
        if (isReplacement[0]) return;

        String workflowName = task.getMetadata() != null
                ? (String) task.getMetadata().get("workflowName") : null;
        String prompt = task.getMetadata() != null
                ? (String) task.getMetadata().get("prompt") : null;
        sdkEventQueueService.enqueueSdkEvent(new SdkEventQueueService.TaskStartedEvent(
                task.getId(), task.getToolUseId(), task.getDescription(),
                task.getType(), workflowName, prompt));

        log.debug("[framework] Registered task: {}", task.getId());
    }

    /**
     * Update a task's state.
     * Translated from updateTaskState() in framework.ts
     *
     * Uses compute() to apply the updater atomically. If the updater returns
     * the same reference (no-op), the map entry is left unchanged.
     */
    public void updateTaskState(String taskId, UnaryOperator<TaskState> updater) {
        tasks.computeIfPresent(taskId, (id, current) -> {
            TaskState updated = updater.apply(current);
            return updated == current ? current : updated;
        });
    }

    /**
     * Eagerly evict a terminal task from the registry.
     * Translated from evictTerminalTask() in framework.ts
     *
     * The task must be terminal (completed/failed/killed) with notified=true.
     * Panel grace period (evictAfter) is respected.
     */
    public void evictTerminalTask(String taskId) {
        tasks.computeIfPresent(taskId, (id, task) -> {
            if (!isTerminalStatus(task.getStatus())) return task;
            if (!task.isNotified()) return task;
            long evictAfter = task.getEvictAfter() != null ? task.getEvictAfter() : Long.MAX_VALUE;
            if (task.isRetain() && evictAfter > System.currentTimeMillis()) return task;
            return null; // returning null removes the entry
        });
    }

    /**
     * Get all running tasks.
     * Translated from getRunningTasks() in framework.ts
     */
    public List<TaskState> getRunningTasks() {
        List<TaskState> running = new ArrayList<>();
        for (TaskState t : tasks.values()) {
            if ("running".equals(t.getStatus())) running.add(t);
        }
        return running;
    }

    /**
     * Get a task by ID.
     */
    public Optional<TaskState> getTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    /**
     * List all tasks.
     */
    public List<TaskState> listTasks() {
        return new ArrayList<>(tasks.values());
    }

    /**
     * Remove a task from the registry unconditionally.
     */
    public void removeTask(String taskId) {
        tasks.remove(taskId);
        log.debug("[framework] Removed task: {}", taskId);
    }

    // =========================================================================
    // Attachment generation + offset application
    // =========================================================================

    /**
     * Generate attachments for tasks with new output or status changes.
     * Translated from generateTaskAttachments() in framework.ts
     *
     * Returns delta-offset patches only (not the full task state) to avoid
     * clobbering concurrent status transitions.
     */
    public CompletableFuture<GenerateAttachmentsResult> generateTaskAttachments() {
        return CompletableFuture.supplyAsync(() -> {
            List<TaskAttachment> attachments = new ArrayList<>();
            Map<String, Long> updatedTaskOffsets = new LinkedHashMap<>();
            List<String> evictedTaskIds = new ArrayList<>();

            for (TaskState taskState : new ArrayList<>(tasks.values())) {
                if (taskState.isNotified()) {
                    switch (taskState.getStatus()) {
                        case "completed":
                        case "failed":
                        case "killed":
                            evictedTaskIds.add(taskState.getId());
                            continue;
                        case "pending":
                            continue;
                        case "running":
                            // fall through to running logic below
                            break;
                        default:
                            continue;
                    }
                }

                if ("running".equals(taskState.getStatus())) {
                    try {
                        TaskOutputDiskService.DeltaResult delta =
                            taskOutputDiskService.getTaskOutputDelta(
                                taskState.getId(), taskState.getOutputOffset());
                        if (delta.getContent() != null && !delta.getContent().isEmpty()) {
                            updatedTaskOffsets.put(taskState.getId(), delta.getNewOffset());
                        }
                    } catch (Exception e) {
                        log.debug("[framework] Error reading output delta for task {}: {}", taskState.getId(), e.getMessage());
                    }
                }

                // Completed tasks are NOT notified here — each task type handles its own
                // completion notification via enqueuePendingNotification(). Generating
                // attachments here would race with those per-type callbacks.
            }

            return new GenerateAttachmentsResult(attachments, updatedTaskOffsets, evictedTaskIds);
        });
    }

    /**
     * Apply offset patches and evictions from generateTaskAttachments.
     * Translated from applyTaskOffsetsAndEvictions() in framework.ts
     *
     * Merges patches against FRESH task state (not the stale pre-await snapshot)
     * so concurrent status transitions are not clobbered.
     */
    public void applyTaskOffsetsAndEvictions(Map<String, Long> updatedTaskOffsets,
                                              List<String> evictedTaskIds) {
        if (updatedTaskOffsets.isEmpty() && evictedTaskIds.isEmpty()) return;

        // Apply offset updates
        for (Map.Entry<String, Long> entry : updatedTaskOffsets.entrySet()) {
            String id = entry.getKey();
            long newOffset = entry.getValue();
            tasks.computeIfPresent(id, (tid, fresh) -> {
                // Re-check status on fresh state — task may have completed during the await
                if (!"running".equals(fresh.getStatus())) return fresh;
                TaskState updated = cloneTaskState(fresh);
                updated.setOutputOffset(newOffset);
                return updated;
            });
        }

        // Apply evictions
        for (String id : evictedTaskIds) {
            tasks.computeIfPresent(id, (tid, fresh) -> {
                if (!isTerminalStatus(fresh.getStatus()) || !fresh.isNotified()) return fresh;
                long evictAfter = fresh.getEvictAfter() != null ? fresh.getEvictAfter() : Long.MAX_VALUE;
                if (fresh.isRetain() && evictAfter > System.currentTimeMillis()) return fresh;
                return null; // remove
            });
        }
    }

    /**
     * Poll all running tasks and check for updates.
     * Translated from pollTasks() in framework.ts
     */
    public CompletableFuture<Void> pollTasks() {
        return generateTaskAttachments().thenAccept(result -> {
            applyTaskOffsetsAndEvictions(result.getUpdatedTaskOffsets(), result.getEvictedTaskIds());
            for (TaskAttachment attachment : result.getAttachments()) {
                enqueueTaskNotification(attachment);
            }
        });
    }

    // =========================================================================
    // Notification dispatch
    // =========================================================================

    /**
     * Enqueue a task notification to the message queue.
     * Translated from enqueueTaskNotification() in framework.ts
     */
    public void enqueueTaskNotification(TaskAttachment attachment) {
        String statusText = getStatusText(attachment.getStatus());
        String outputPath = taskOutputDiskService.getTaskOutputPath(attachment.getTaskId());

        String toolUseIdLine = attachment.getToolUseId() != null
            ? "\n<tool_use_id>" + attachment.getToolUseId() + "</tool_use_id>"
            : "";

        String message = "<task_notification>\n"
            + "<task_id>" + attachment.getTaskId() + "</task_id>" + toolUseIdLine + "\n"
            + "<task_type>" + attachment.getTaskType() + "</task_type>\n"
            + "<output_file>" + outputPath + "</output_file>\n"
            + "<status>" + attachment.getStatus() + "</status>\n"
            + "<summary>Task \"" + attachment.getDescription() + "\" " + statusText + "</summary>\n"
            + "</task_notification>";

        // Enqueue as a task notification event
        sdkEventQueueService.enqueueSdkEvent(new SdkEventQueueService.TaskNotificationEvent(
                attachment.getTaskId(), attachment.getToolUseId(), attachment.getStatus(),
                outputPath, message, null));
    }

    /**
     * Get human-readable status text.
     * Translated from getStatusText() in framework.ts
     */
    public static String getStatusText(String status) {
        return switch (status) {
            case "completed" -> "completed successfully";
            case "failed"    -> "failed";
            case "killed"    -> "was stopped";
            case "running"   -> "is running";
            case "pending"   -> "is pending";
            default          -> status;
        };
    }

    /**
     * Check if a status is terminal.
     * Translated from isTerminalTaskStatus() in Task.ts
     */
    public static boolean isTerminalStatus(String status) {
        return "completed".equals(status) || "failed".equals(status) || "killed".equals(status);
    }

    // =========================================================================
    // Result type for generateTaskAttachments
    // =========================================================================

    public static class GenerateAttachmentsResult {
        private List<TaskAttachment> attachments;
        private Map<String, Long> updatedTaskOffsets;
        private List<String> evictedTaskIds;
        public GenerateAttachmentsResult() {}
        public GenerateAttachmentsResult(List<TaskAttachment> attachments, Map<String, Long> updatedTaskOffsets, List<String> evictedTaskIds) {
            this.attachments = attachments; this.updatedTaskOffsets = updatedTaskOffsets; this.evictedTaskIds = evictedTaskIds;
        }
        public List<TaskAttachment> getAttachments() { return attachments; }
        public Map<String, Long> getUpdatedTaskOffsets() { return updatedTaskOffsets; }
        public List<String> getEvictedTaskIds() { return evictedTaskIds; }
    }

    /**
     * Update the metadata map for a task identified by ID.
     * The updater function receives the current metadata map and should return the updated map.
     */
    public synchronized void updateTaskMetadata(String taskId, java.util.function.UnaryOperator<Map<String, Object>> updater) {
        for (Map.Entry<String, TaskState> entry : tasks.entrySet()) {
            TaskState state = entry.getValue();
            if (taskId.equals(state.getId())) {
                Map<String, Object> meta = state.getMetadata() != null
                        ? new LinkedHashMap<>(state.getMetadata()) : new LinkedHashMap<>();
                state.setMetadata(updater.apply(meta));
                return;
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static TaskState cloneTaskState(TaskState src) {
        TaskState copy = new TaskState();
        copy.setId(src.getId());
        copy.setType(src.getType());
        copy.setStatus(src.getStatus());
        copy.setDescription(src.getDescription());
        copy.setToolUseId(src.getToolUseId());
        copy.setNotified(src.isNotified());
        copy.setOutputOffset(src.getOutputOffset());
        copy.setStartTime(src.getStartTime());
        copy.setEvictAfter(src.getEvictAfter());
        copy.setRetain(src.isRetain());
        copy.setMetadata(src.getMetadata() != null ? new LinkedHashMap<>(src.getMetadata()) : new LinkedHashMap<>());
        return copy;
    }
}
