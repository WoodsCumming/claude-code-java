package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.Task;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Local main session task service.
 * Translated from src/tasks/LocalMainSessionTask.ts
 *
 * Handles backgrounding the main session query when the user presses Ctrl+B.
 * The query continues running; a notification is sent when it completes.
 *
 * Reuses LocalAgentTask state structure (agentType='main-session').
 */
@Slf4j
@Service
public class LocalMainSessionTaskService {

    private static final String TASK_ID_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    /** Maximum recent tool activities kept for display. */
    private static final int MAX_RECENT_ACTIVITIES = 5;

    private final TaskFrameworkService taskFrameworkService;
    private final SdkEventQueueService sdkEventQueueService;

    @Autowired
    public LocalMainSessionTaskService(TaskFrameworkService taskFrameworkService,
                                        SdkEventQueueService sdkEventQueueService) {
        this.taskFrameworkService = taskFrameworkService;
        this.sdkEventQueueService = sdkEventQueueService;
    }

    // =========================================================================
    // LocalMainSessionTaskState
    // Translated from LocalMainSessionTaskState in LocalMainSessionTask.ts
    // =========================================================================

    public static class LocalMainSessionTaskState {
        private String id;
        private String type = "local_agent";
        private String agentType = "main-session";
        private Task.TaskStatus status;
        private String description;
        private String toolUseId;
        private long startTime;
        private Long endTime;
        private String outputFile;
        private long outputOffset;
        private boolean notified;
        private boolean backgrounded = true;
        private List<Object> messages = new ArrayList<>();
        private Runnable unregisterCleanup;
        private transient Runnable abortCallback;

        public LocalMainSessionTaskState() {}

        // Getters/setters
        public String getId() { return id; }
        public void setId(String v) { id = v; }
        public String getType() { return type; }
        public void setType(String v) { type = v; }
        public String getAgentType() { return agentType; }
        public void setAgentType(String v) { agentType = v; }
        public Task.TaskStatus getStatus() { return status; }
        public void setStatus(Task.TaskStatus v) { status = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
        public String getToolUseId() { return toolUseId; }
        public void setToolUseId(String v) { toolUseId = v; }
        public long getStartTime() { return startTime; }
        public void setStartTime(long v) { startTime = v; }
        public Long getEndTime() { return endTime; }
        public void setEndTime(Long v) { endTime = v; }
        public String getOutputFile() { return outputFile; }
        public void setOutputFile(String v) { outputFile = v; }
        public long getOutputOffset() { return outputOffset; }
        public void setOutputOffset(long v) { outputOffset = v; }
        public boolean isNotified() { return notified; }
        public void setNotified(boolean v) { notified = v; }
        public boolean isBackgrounded() { return backgrounded; }
        public void setBackgrounded(boolean v) { backgrounded = v; }
        public List<Object> getMessages() { return messages; }
        public void setMessages(List<Object> v) { messages = v; }
        public Runnable getUnregisterCleanup() { return unregisterCleanup; }
        public void setUnregisterCleanup(Runnable v) { unregisterCleanup = v; }
        public Runnable getAbortCallback() { return abortCallback; }
        public void setAbortCallback(Runnable v) { abortCallback = v; }

        // Builder pattern
        public static Builder builder() { return new Builder(); }
        public static class Builder {
            private final LocalMainSessionTaskState state = new LocalMainSessionTaskState();
            public Builder id(String v) { state.id = v; return this; }
            public Builder type(String v) { state.type = v; return this; }
            public Builder agentType(String v) { state.agentType = v; return this; }
            public Builder status(Task.TaskStatus v) { state.status = v; return this; }
            public Builder description(String v) { state.description = v; return this; }
            public Builder toolUseId(String v) { state.toolUseId = v; return this; }
            public Builder startTime(long v) { state.startTime = v; return this; }
            public Builder endTime(Long v) { state.endTime = v; return this; }
            public Builder outputFile(String v) { state.outputFile = v; return this; }
            public Builder outputOffset(long v) { state.outputOffset = v; return this; }
            public Builder notified(boolean v) { state.notified = v; return this; }
            public Builder backgrounded(boolean v) { state.backgrounded = v; return this; }
            public Builder messages(List<Object> v) { state.messages = v; return this; }
            public Builder unregisterCleanup(Runnable v) { state.unregisterCleanup = v; return this; }
            public Builder abortCallback(Runnable v) { state.abortCallback = v; return this; }
            public LocalMainSessionTaskState build() { return state; }
        }
    }

    // =========================================================================
    // ToolActivity
    // =========================================================================

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    
    public static class ToolActivity {
        private String toolName;
        private Map<String, Object> input;

        public String getToolName() { return toolName; }
        public void setToolName(String v) { toolName = v; }
        public Map<String, Object> getInput() { return input; }
        public void setInput(Map<String, Object> v) { input = v; }
    }

    // =========================================================================
    // RegisterMainSessionResult
    // =========================================================================

    public record RegisterMainSessionResult(String taskId, Runnable abort) {}

    // =========================================================================
    // generateMainSessionTaskId
    // Uses 's' prefix — distinct from agent tasks ('a' prefix).
    // Translated from generateMainSessionTaskId() in LocalMainSessionTask.ts
    // =========================================================================

    private static String generateMainSessionTaskId() {
        byte[] bytes = new byte[8];
        SECURE_RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder("s");
        for (byte b : bytes) {
            sb.append(TASK_ID_ALPHABET.charAt(Byte.toUnsignedInt(b) % TASK_ID_ALPHABET.length()));
        }
        return sb.toString();
    }

    // =========================================================================
    // registerMainSessionTask
    // Translated from registerMainSessionTask() in LocalMainSessionTask.ts
    // =========================================================================

    /**
     * Register a backgrounded main session task.
     * Called when the user backgrounds the current session query (Ctrl+B).
     *
     * @param description        Description of the task.
     * @param existingAbortCallback Optional abort callback to reuse (important for
     *                              backgrounding an active query so aborting the
     *                              task aborts the actual query).
     * @return Result containing taskId and abort callback.
     */
    public RegisterMainSessionResult registerMainSessionTask(
            String description,
            Runnable existingAbortCallback) {

        String taskId = generateMainSessionTaskId();
        Runnable[] abortHolder = { existingAbortCallback };

        // Clean up on process exit / context close
        Runnable unregisterCleanup = () -> {
            taskFrameworkService.removeTask(taskId);
            log.debug("[LocalMainSessionTask] Cleanup: removed task {}", taskId);
        };

        LocalMainSessionTaskState taskState = LocalMainSessionTaskState.builder()
                .id(taskId)
                .type("local_agent")
                .agentType("main-session")
                .status(Task.TaskStatus.RUNNING)
                .description(description)
                .startTime(System.currentTimeMillis())
                .backgrounded(true)
                .messages(new ArrayList<>())
                .notified(false)
                .unregisterCleanup(unregisterCleanup)
                .abortCallback(abortHolder[0])
                .build();

        TaskFrameworkService.TaskState frameworkTask = new TaskFrameworkService.TaskState();
        frameworkTask.setId(taskId);
        frameworkTask.setType("local_agent");
        frameworkTask.setStatus(Task.TaskStatus.RUNNING.name().toLowerCase());
        frameworkTask.setDescription(description);
        frameworkTask.setStartTime(System.currentTimeMillis());
        taskFrameworkService.registerTask(frameworkTask);
        log.info("[LocalMainSessionTask] Registered task {} with description: {}", taskId, description);

        return new RegisterMainSessionResult(taskId, () -> {
            if (abortHolder[0] != null) abortHolder[0].run();
        });
    }

    // =========================================================================
    // completeMainSessionTask
    // Translated from completeMainSessionTask() in LocalMainSessionTask.ts
    // =========================================================================

    /**
     * Complete the main session task and send a notification if backgrounded.
     * Translated from completeMainSessionTask() in LocalMainSessionTask.ts
     */
    public void completeMainSessionTask(String taskId, boolean success) {
        Optional<TaskFrameworkService.TaskState> taskOpt = taskFrameworkService.getTask(taskId);
        if (taskOpt.isEmpty()) return;

        TaskFrameworkService.TaskState task = taskOpt.get();
        if (!"running".equals(task.getStatus())) return;

        boolean wasBackgrounded = Boolean.TRUE.equals(task.getMetadata().get("backgrounded"));
        String toolUseId = (String) task.getMetadata().get("toolUseId");

        // Update task to terminal state
        task.setStatus(success ? "completed" : "failed");
        task.getMetadata().put("endTime", System.currentTimeMillis());

        if (wasBackgrounded) {
            enqueueMainSessionNotification(taskId, task.getDescription(),
                    success ? "completed" : "failed", toolUseId);
        } else {
            // Foregrounded: no XML notification, but emit SDK event
            task.getMetadata().put("notified", true);
            sdkEventQueueService.enqueueTaskNotification(taskId,
                    "stopped", task.getDescription());
            log.info("[LocalMainSessionTask] Task {} {} (foregrounded)", taskId,
                    success ? "completed" : "failed");
        }
    }

    // =========================================================================
    // enqueueMainSessionNotification (private)
    // Translated from enqueueMainSessionNotification() in LocalMainSessionTask.ts
    // =========================================================================

    private void enqueueMainSessionNotification(
            String taskId, String description, String status, String toolUseId) {

        // Atomically check-and-set notified to prevent duplicates
        Optional<TaskFrameworkService.TaskState> taskOpt = taskFrameworkService.getTask(taskId);
        if (taskOpt.isEmpty()) return;

        TaskFrameworkService.TaskState task = taskOpt.get();
        synchronized (task) {
            if (Boolean.TRUE.equals(task.getMetadata().get("notified"))) return;
            task.getMetadata().put("notified", true);
        }

        String summary = "completed".equals(status)
                ? String.format("Background session \"%s\" completed", description)
                : String.format("Background session \"%s\" failed", description);

        sdkEventQueueService.enqueueTaskNotification(taskId, status, summary);
        log.info("[LocalMainSessionTask] Enqueued notification for task {}: {}", taskId, summary);
    }

    // =========================================================================
    // foregroundMainSessionTask
    // Translated from foregroundMainSessionTask() in LocalMainSessionTask.ts
    // =========================================================================

    /**
     * Foreground a main session task — mark it as foregrounded so its output
     * appears in the main view. The background query keeps running.
     *
     * @param taskId  Task to foreground.
     * @param appState The current AppState to update.
     * @return Messages accumulated so far, or empty list if task not found.
     */
    @SuppressWarnings("unchecked")
    public List<Object> foregroundMainSessionTask(String taskId, AppState appState) {
        Object taskObj = appState.getTasks().get(taskId);
        if (taskObj == null) return Collections.emptyList();

        List<Object> messages = Collections.emptyList();

        if (taskObj instanceof Map<?, ?> rawTask) {
            Object msgs = rawTask.get("messages");
            if (msgs instanceof List<?> list) {
                messages = (List<Object>) list;
            }

            // Restore previous foregrounded task to background
            String prevId = appState.getForegroundedTaskId();
            if (prevId != null && !prevId.equals(taskId)) {
                Object prevTaskObj = appState.getTasks().get(prevId);
                if (prevTaskObj instanceof Map<?, ?> prevRaw) {
                    Map<String, Object> mutablePrev = new LinkedHashMap<>((Map<String, Object>) prevRaw);
                    mutablePrev.put("backgrounded", true);
                    appState.getTasks().put(prevId, mutablePrev);
                }
            }

            // Mark this task as foregrounded
            Map<String, Object> mutableTask = new LinkedHashMap<>((Map<String, Object>) rawTask);
            mutableTask.put("backgrounded", false);
            appState.getTasks().put(taskId, mutableTask);
            appState.setForegroundedTaskId(taskId);
        }

        return messages;
    }

    // =========================================================================
    // isMainSessionTask
    // Translated from isMainSessionTask() in LocalMainSessionTask.ts
    // =========================================================================

    /**
     * Check if a task map represents a main session task (vs a regular agent task).
     * Translated from isMainSessionTask() in LocalMainSessionTask.ts
     */
    public static boolean isMainSessionTask(Map<String, Object> task) {
        if (task == null) return false;
        return "local_agent".equals(task.get("type"))
                && "main-session".equals(task.get("agentType"));
    }

    // =========================================================================
    // Helper — convert LocalMainSessionTaskState to a raw map for the registry
    // =========================================================================

    private static Map<String, Object> toRawMap(LocalMainSessionTaskState state) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", state.getId());
        map.put("type", state.getType());
        map.put("agentType", state.getAgentType());
        map.put("status", state.getStatus().getValue());
        map.put("description", state.getDescription());
        map.put("toolUseId", state.getToolUseId());
        map.put("startTime", state.getStartTime());
        map.put("backgrounded", state.isBackgrounded());
        map.put("messages", state.getMessages());
        map.put("notified", state.isNotified());
        return map;
    }
}
