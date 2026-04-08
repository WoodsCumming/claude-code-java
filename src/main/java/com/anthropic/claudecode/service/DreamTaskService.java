package com.anthropic.claudecode.service;
import lombok.Builder;

import com.anthropic.claudecode.model.Task;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Dream task service for memory consolidation background agents.
 * Translated from src/tasks/DreamTask/DreamTask.ts
 *
 * Makes the auto-dream (memory consolidation) subagent visible in the footer
 * pill and Shift+Down dialog. Pure UI surfacing via the task registry.
 */
@Slf4j
@Service
public class DreamTaskService {



    /** Keep only the N most recent assistant turns for live display. */
    private static final int MAX_TURNS = 30;

    // =========================================================================
    // DreamPhase
    // No phase detection — the 4-stage prompt structure is not parsed.
    // Just flip from 'starting' to 'updating' when the first Edit/Write lands.
    // Translated from DreamPhase type in DreamTask.ts
    // =========================================================================

    public enum DreamPhase {
        STARTING("starting"),
        UPDATING("updating");

        private final String value;
        DreamPhase(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    // =========================================================================
    // DreamTurn
    // A single assistant turn from the dream agent, tool uses collapsed to count.
    // Translated from DreamTurn type in DreamTask.ts
    // =========================================================================

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DreamTurn {
        private String text;
        private int toolUseCount;

        public String getText() { return text; }
        public void setText(String v) { text = v; }
        public int getToolUseCount() { return toolUseCount; }
        public void setToolUseCount(int v) { toolUseCount = v; }
    }

    // =========================================================================
    // DreamTaskState
    // Translated from DreamTaskState type in DreamTask.ts
    // =========================================================================

    @Data
    @lombok.Builder
    
    public static class DreamTaskState {
        private String id;
        /** Always "dream". */
        @Builder.Default
        private String type = "dream";
        @Builder.Default
        private Task.TaskStatus status = Task.TaskStatus.RUNNING;
        @Builder.Default
        private DreamPhase phase = DreamPhase.STARTING;
        private int sessionsReviewing;
        /**
         * Paths observed in Edit/Write tool_use blocks.
         * Incomplete reflection — misses bash-mediated writes. Treat as
         * "at least these were touched", not "only these were touched".
         */
        @Builder.Default
        private List<String> filesTouched = new ArrayList<>();
        /** Assistant text responses, tool uses collapsed. Prompt NOT included. */
        @Builder.Default
        private List<DreamTurn> turns = new ArrayList<>();
        /** Stashed so kill can rewind the lock mtime (same path as fork-failure). */
        private long priorMtime;
        private long startTime;
        private Long endTime;
        @Builder.Default
        private boolean notified = false;
        /** Abort signal — null when task is complete. */
        private transient CompletableFuture<Void> abortFuture;
        private transient Runnable abortCallback;
    }

    /** In-process task registry keyed by task ID. */
    private final Map<String, DreamTaskState> activeTasks = new ConcurrentHashMap<>();

    // =========================================================================
    // registerDreamTask
    // Translated from registerDreamTask() in DreamTask.ts
    // =========================================================================

    /**
     * Register a new dream task and return its ID.
     * Translated from registerDreamTask() in DreamTask.ts
     */
    public String registerDreamTask(int sessionsReviewing, long priorMtime, Runnable abortCallback) {
        String id = Task.generateTaskId(com.anthropic.claudecode.model.Task.TaskType.DREAM);
        DreamTaskState state = DreamTaskState.builder()
                .id(id)
                .type("dream")
                .status(Task.TaskStatus.RUNNING)
                .phase(DreamPhase.STARTING)
                .sessionsReviewing(sessionsReviewing)
                .filesTouched(new ArrayList<>())
                .turns(new ArrayList<>())
                .priorMtime(priorMtime)
                .startTime(System.currentTimeMillis())
                .notified(false)
                .abortCallback(abortCallback)
                .build();

        activeTasks.put(id, state);
        log.info("Registered dream task: {} (reviewing {} sessions)", id, sessionsReviewing);
        return id;
    }

    // =========================================================================
    // addDreamTurn
    // Translated from addDreamTurn() in DreamTask.ts
    // =========================================================================

    /**
     * Append a new assistant turn to a dream task, deduplicating touched paths.
     * Translated from addDreamTurn() in DreamTask.ts
     *
     * Skips the update entirely if the turn is empty AND nothing new was touched,
     * to avoid re-rendering on pure no-ops.
     */
    public void addDreamTurn(String taskId, DreamTurn turn, List<String> touchedPaths) {
        DreamTaskState state = activeTasks.get(taskId);
        if (state == null) return;

        // Deduplicate touched paths
        Set<String> seen = new HashSet<>(state.getFilesTouched());
        List<String> newTouched = touchedPaths.stream()
                .filter(p -> !seen.contains(p))
                .toList();

        // Skip no-ops
        if (turn.getText().isEmpty() && turn.getToolUseCount() == 0 && newTouched.isEmpty()) {
            return;
        }

        synchronized (state) {
            // Flip phase when the first file is touched
            if (!newTouched.isEmpty()) {
                state.setPhase(DreamPhase.UPDATING);
                List<String> combined = new ArrayList<>(state.getFilesTouched());
                combined.addAll(newTouched);
                state.setFilesTouched(combined);
            }

            // Keep only the most recent MAX_TURNS turns
            List<DreamTurn> turns = new ArrayList<>(state.getTurns());
            if (turns.size() >= MAX_TURNS) {
                turns = new ArrayList<>(turns.subList(turns.size() - (MAX_TURNS - 1), turns.size()));
            }
            turns.add(turn);
            state.setTurns(turns);
        }
    }

    // =========================================================================
    // completeDreamTask
    // Translated from completeDreamTask() in DreamTask.ts
    // =========================================================================

    /**
     * Mark a dream task as completed.
     * notified is set to true immediately — dream has no model-facing notification path.
     * Translated from completeDreamTask() in DreamTask.ts
     */
    public void completeDreamTask(String taskId) {
        DreamTaskState state = activeTasks.get(taskId);
        if (state != null) {
            state.setStatus(Task.TaskStatus.COMPLETED);
            state.setEndTime(System.currentTimeMillis());
            state.setNotified(true);
            state.setAbortCallback(null);
            log.info("Dream task {} completed", taskId);
        }
    }

    // =========================================================================
    // failDreamTask
    // Translated from failDreamTask() in DreamTask.ts
    // =========================================================================

    /**
     * Mark a dream task as failed.
     * Translated from failDreamTask() in DreamTask.ts
     */
    public void failDreamTask(String taskId) {
        DreamTaskState state = activeTasks.get(taskId);
        if (state != null) {
            state.setStatus(Task.TaskStatus.FAILED);
            state.setEndTime(System.currentTimeMillis());
            state.setNotified(true);
            state.setAbortCallback(null);
            log.info("Dream task {} failed", taskId);
        }
    }

    // =========================================================================
    // kill  (DreamTask.kill in DreamTask.ts)
    // =========================================================================

    /**
     * Kill a running dream task and rewind the consolidation lock mtime.
     * Translated from DreamTask.kill() in DreamTask.ts
     *
     * @param taskId   ID of the task to kill
     * @param onRewindLock  Callback that receives priorMtime to rewind the lock,
     *                      analogous to rollbackConsolidationLock(priorMtime) in TS.
     */
    public CompletableFuture<Void> kill(String taskId, Function<Long, CompletableFuture<Void>> onRewindLock) {
        DreamTaskState state = activeTasks.get(taskId);
        if (state == null || state.getStatus() != Task.TaskStatus.RUNNING) {
            return CompletableFuture.completedFuture(null);
        }

        long priorMtime = state.getPriorMtime();

        // Abort the underlying agent
        Runnable abortCallback = state.getAbortCallback();
        if (abortCallback != null) {
            abortCallback.run();
        }

        state.setStatus(Task.TaskStatus.KILLED);
        state.setEndTime(System.currentTimeMillis());
        state.setNotified(true);
        state.setAbortCallback(null);

        log.info("Dream task {} killed; rewinding lock mtime={}", taskId, priorMtime);

        // Rewind the lock mtime so the next session can retry
        return onRewindLock.apply(priorMtime);
    }

    // =========================================================================
    // isDreamTask
    // Translated from isDreamTask() in DreamTask.ts
    // =========================================================================

    /**
     * Type guard — returns true when the given object is a DreamTaskState.
     * Translated from isDreamTask() in DreamTask.ts
     */
    public static boolean isDreamTask(Object task) {
        if (task instanceof DreamTaskState dreamTask) {
            return "dream".equals(dreamTask.getType());
        }
        if (task instanceof Map<?, ?> map) {
            return "dream".equals(map.get("type"));
        }
        return false;
    }

    // =========================================================================
    // Query helpers
    // =========================================================================

    public Optional<DreamTaskState> getTask(String taskId) {
        return Optional.ofNullable(activeTasks.get(taskId));
    }

    public Map<String, DreamTaskState> getAllActiveTasks() {
        return Collections.unmodifiableMap(activeTasks);
    }
}
