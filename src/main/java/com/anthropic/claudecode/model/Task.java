package com.anthropic.claudecode.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Builder;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Task types, statuses, and base state.
 * Translated from src/Task.ts
 */
public class Task {

    // =========================================================================
    // TaskType
    // Translated from TypeScript TaskType union
    // =========================================================================

    public enum TaskType {
        LOCAL_BASH("local_bash"),
        LOCAL_AGENT("local_agent"),
        REMOTE_AGENT("remote_agent"),
        IN_PROCESS_TEAMMATE("in_process_teammate"),
        LOCAL_WORKFLOW("local_workflow"),
        MONITOR_MCP("monitor_mcp"),
        DREAM("dream");

        private final String value;

        TaskType(String value) { this.value = value; }

        public String getValue() { return value; }

        /** Returns the single-character prefix used in task IDs. */
        public String getIdPrefix() {
            return switch (this) {
                case LOCAL_BASH -> "b";         // Keep 'b' for backward compatibility
                case LOCAL_AGENT -> "a";
                case REMOTE_AGENT -> "r";
                case IN_PROCESS_TEAMMATE -> "t";
                case LOCAL_WORKFLOW -> "w";
                case MONITOR_MCP -> "m";
                case DREAM -> "d";
            };
        }

        public static TaskType fromValue(String value) {
            for (TaskType t : values()) {
                if (t.value.equals(value)) return t;
            }
            return null;
        }
    }

    // =========================================================================
    // TaskStatus
    // Translated from TypeScript TaskStatus union
    // =========================================================================

    public enum TaskStatus {
        PENDING("pending"),
        RUNNING("running"),
        COMPLETED("completed"),
        FAILED("failed"),
        KILLED("killed");

        private final String value;

        TaskStatus(String value) { this.value = value; }

        public String getValue() { return value; }

        /**
         * True when a task is in a terminal state and will not transition further.
         * Translated from isTerminalTaskStatus() in Task.ts
         */
        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED || this == KILLED;
        }

        public static TaskStatus fromValue(String value) {
            for (TaskStatus s : values()) {
                if (s.value.equals(value)) return s;
            }
            return null;
        }
    }

    // =========================================================================
    // Task ID generation
    // Translated from generateTaskId() in Task.ts
    // =========================================================================

    /**
     * Case-insensitive-safe alphabet (digits + lowercase) for task IDs.
     * 36^8 ≈ 2.8 trillion combinations, sufficient to resist brute-force symlink attacks.
     */
    private static final String TASK_ID_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * Generate a task ID with the type-specific prefix followed by 8 random characters.
     * Translated from generateTaskId() in Task.ts
     */
    public static String generateTaskId(TaskType type) {
        String prefix = type != null ? type.getIdPrefix() : "x";
        byte[] bytes = new byte[8];
        SECURE_RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(prefix);
        for (byte b : bytes) {
            sb.append(TASK_ID_ALPHABET.charAt(Byte.toUnsignedInt(b) % TASK_ID_ALPHABET.length()));
        }
        return sb.toString();
    }

    // =========================================================================
    // TaskStateBase — base fields shared by all task states
    // Translated from TaskStateBase in Task.ts
    // =========================================================================

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TaskStateBase {
        private String id;
        private TaskType type;
        private TaskStatus status;
        private String description;
        private String toolUseId;
        private long startTime;
        private Long endTime;
        private Long totalPausedMs;
        private String outputFile;
        private long outputOffset;
        private boolean notified;


        public static TaskStateBaseBuilder builder() { return new TaskStateBaseBuilder(); }
        public static class TaskStateBaseBuilder {
            private final TaskStateBase s = new TaskStateBase();
            public TaskStateBaseBuilder id(String v) { s.id = v; return this; }
            public TaskStateBaseBuilder type(TaskType v) { s.type = v; return this; }
            public TaskStateBaseBuilder status(TaskStatus v) { s.status = v; return this; }
            public TaskStateBaseBuilder description(String v) { s.description = v; return this; }
            public TaskStateBaseBuilder toolUseId(String v) { s.toolUseId = v; return this; }
            public TaskStateBaseBuilder startTime(long v) { s.startTime = v; return this; }
            public TaskStateBaseBuilder endTime(Long v) { s.endTime = v; return this; }
            public TaskStateBaseBuilder totalPausedMs(Long v) { s.totalPausedMs = v; return this; }
            public TaskStateBaseBuilder outputFile(String v) { s.outputFile = v; return this; }
            public TaskStateBaseBuilder outputOffset(long v) { s.outputOffset = v; return this; }
            public TaskStateBaseBuilder notified(boolean v) { s.notified = v; return this; }
            public TaskStateBase build() { return s; }
        }

        public String getId() { return id; }
        public void setId(String v) { id = v; }
        public TaskType getType() { return type; }
        public void setType(TaskType v) { type = v; }
        public TaskStatus getStatus() { return status; }
        public void setStatus(TaskStatus v) { status = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
        public String getToolUseId() { return toolUseId; }
        public void setToolUseId(String v) { toolUseId = v; }
        public long getStartTime() { return startTime; }
        public void setStartTime(long v) { startTime = v; }
        public Long getEndTime() { return endTime; }
        public void setEndTime(Long v) { endTime = v; }
        public Long getTotalPausedMs() { return totalPausedMs; }
        public void setTotalPausedMs(Long v) { totalPausedMs = v; }
        public String getOutputFile() { return outputFile; }
        public void setOutputFile(String v) { outputFile = v; }
        public long getOutputOffset() { return outputOffset; }
        public void setOutputOffset(long v) { outputOffset = v; }
        public boolean isNotified() { return notified; }
        public void setNotified(boolean v) { notified = v; }
    }

    // =========================================================================
    // createTaskStateBase
    // Translated from createTaskStateBase() in Task.ts
    // =========================================================================

    /**
     * Build an initial TaskStateBase for a new task.
     * Translated from createTaskStateBase() in Task.ts
     *
     * The outputFile path follows the same convention as getTaskOutputPath(id)
     * in diskOutput.ts — callers are expected to supply the resolved path.
     */
    public static TaskStateBase createTaskStateBase(
            String id,
            TaskType type,
            String description,
            String toolUseId,
            String outputFilePath) {
        return TaskStateBase.builder()
                .id(id)
                .type(type)
                .status(TaskStatus.PENDING)
                .description(description)
                .toolUseId(toolUseId)
                .startTime(System.currentTimeMillis())
                .outputFile(outputFilePath)
                .outputOffset(0L)
                .notified(false)
                .build();
    }

    // =========================================================================
    // TaskHandle
    // Translated from TaskHandle in Task.ts
    // =========================================================================

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    
    public static class TaskHandle {
        private String taskId;
        /** Optional cleanup callback invoked when the task should be torn down. */
        private Runnable cleanup;


        public String getTaskId() { return taskId; }
        public void setTaskId(String v) { taskId = v; }
        public Runnable getCleanup() { return cleanup; }
        public void setCleanup(Runnable v) { cleanup = v; }
    }

    // =========================================================================
    // TaskContext
    // Translated from TaskContext in Task.ts
    // =========================================================================

    /**
     * Runtime context passed to tasks.
     * In TypeScript, getAppState/setAppState reference the reactive store;
     * here they are plain function interfaces around the AppState bean.
     */
    public interface TaskContext {
        /** Abort controller for this task's lifecycle. */
        java.util.concurrent.CompletableFuture<Void> getAbortFuture();

        /** Read-only snapshot of current app state. */
        Object getAppState();

        /** Apply a transformation to app state. */
        void setAppState(Function<Object, Object> updater);
    }

    // =========================================================================
    // Task — kill contract
    // Translated from Task type in Task.ts
    // =========================================================================

    /**
     * Contract implemented by each concrete task type for lifecycle management.
     * What getTaskByType dispatches for: kill only (spawn/render removed in #22546).
     */
    public interface TaskDefinition {
        String getName();
        TaskType getType();

        /**
         * Kill the task identified by taskId.
         * All six TS kill() implementations use only setAppState.
         */
        CompletableFuture<Void> kill(String taskId, Function<Object, Object> setAppState);
    }

    // =========================================================================
    // LocalShellSpawnInput
    // Translated from LocalShellSpawnInput in Task.ts
    // =========================================================================

    @Data
    @lombok.Builder
    
    public static class LocalShellSpawnInput {
        private String command;
        private String description;
        private Integer timeout;
        private String toolUseId;
        private String agentId;
        /** UI display variant: description-as-label, dialog title, status bar pill. */
        private String kind; // bash | monitor

        /** SetAppState function type — a Consumer<Function<AppState, AppState>>. */
        public interface SetAppState {
            void apply(Function<Map<String, Object>, Map<String, Object>> updater);
        }
    }

    private Task() {}
}
