package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import com.anthropic.claudecode.util.TeammateContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileAttribute;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Task management service for agent swarm coordination.
 * Translated from src/utils/tasks.ts
 *
 * Manages persistent task lists stored as JSON files on disk, supporting
 * concurrent access from multiple swarm agents with file-system locking.
 */
@Slf4j
@Service
public class TaskManagerService {



    public static final String DEFAULT_TASKS_MODE_TASK_LIST_ID = "tasklist";

    public enum TaskStatus {
        pending, in_progress, completed
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class Task {
        private String id;
        private String subject;
        private String description;
        private String activeForm;     // present continuous form for spinner
        private String owner;          // agent ID
        private TaskStatus status;
        private List<String> blocks    = new ArrayList<>();  // task IDs this task blocks
        private List<String> blockedBy = new ArrayList<>();  // task IDs that block this task
        private Map<String, Object> metadata;

        public String getId() { return id; }
        public void setId(String v) { id = v; }
        public String getSubject() { return subject; }
        public void setSubject(String v) { subject = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
        public String getActiveForm() { return activeForm; }
        public void setActiveForm(String v) { activeForm = v; }
        public String getOwner() { return owner; }
        public void setOwner(String v) { owner = v; }
        public TaskStatus getStatus() { return status; }
        public void setStatus(TaskStatus v) { status = v; }
        public List<String> getBlocks() { return blocks; }
        public void setBlocks(List<String> v) { blocks = v; }
        public List<String> getBlockedBy() { return blockedBy; }
        public void setBlockedBy(List<String> v) { blockedBy = v; }
        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> v) { metadata = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TeamMember {
        private String agentId;
        private String name;
        private String agentType;

        public String getAgentId() { return agentId; }
        public void setAgentId(String v) { agentId = v; }
        public String getName() { return name; }
        public void setName(String v) { name = v; }
        public String getAgentType() { return agentType; }
        public void setAgentType(String v) { agentType = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AgentStatus {
        private String agentId;
        private String name;
        private String agentType;
        private String status; // "idle" | "busy"
        private List<String> currentTasks = new ArrayList<>();

        public List<String> getCurrentTasks() { return currentTasks; }
        public void setCurrentTasks(List<String> v) { currentTasks = v; }
    }

    public sealed interface ClaimTaskResult permits
            ClaimTaskResult.Success,
            ClaimTaskResult.Failure {

        record Success(Task task) implements ClaimTaskResult {}

        record Failure(
            String reason,   // task_not_found | already_claimed | already_resolved | blocked | agent_busy
            Task task,
            List<String> busyWithTasks,
            List<String> blockedByTasks
        ) implements ClaimTaskResult {}
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class UnassignTasksResult {
        private List<Map<String, String>> unassignedTasks;
        private String notificationMessage;

        public List<Map<String, String>> getUnassignedTasks() { return unassignedTasks; }
        public void setUnassignedTasks(List<Map<String, String>> v) { unassignedTasks = v; }
        public String getNotificationMessage() { return notificationMessage; }
        public void setNotificationMessage(String v) { notificationMessage = v; }
    }

    private static final String HIGH_WATER_MARK_FILE = ".highwatermark";

    /** In-process signal listeners for tasks-updated events */
    private final List<Runnable> tasksUpdatedListeners = new CopyOnWriteArrayList<>();

    /** Leader team name - set by team creation, used for task list ID resolution */
    private volatile String leaderTeamName;

    /** Per-task-list file-system locks (Java-level guard for same-JVM concurrency) */
    private final ConcurrentHashMap<String, ReentrantLock> taskListLocks = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;
    private final TeammateService teammateService;

    @Autowired
    public TaskManagerService(ObjectMapper objectMapper, TeammateService teammateService) {
        this.objectMapper = objectMapper;
        this.teammateService = teammateService;
    }

    // -------------------------------------------------------------------------
    // Leader team name
    // -------------------------------------------------------------------------

    public void setLeaderTeamName(String teamName) {
        if (Objects.equals(leaderTeamName, teamName)) return;
        leaderTeamName = teamName;
        notifyTasksUpdated();
    }

    public void clearLeaderTeamName() {
        if (leaderTeamName == null) return;
        leaderTeamName = null;
        notifyTasksUpdated();
    }

    // -------------------------------------------------------------------------
    // Listeners
    // -------------------------------------------------------------------------

    /** Register a listener called when tasks are updated. Returns unsubscribe runnable. */
    public Runnable onTasksUpdated(Runnable listener) {
        tasksUpdatedListeners.add(listener);
        return () -> tasksUpdatedListeners.remove(listener);
    }

    public void notifyTasksUpdated() {
        for (Runnable listener : tasksUpdatedListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                // Ignore listener errors — task mutations must not fail
            }
        }
    }

    // -------------------------------------------------------------------------
    // Task list ID / path helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the task list ID for the current context.
     * Priority: env var > in-process teammate > team name > leader team name > session ID.
     */
    public String getTaskListId() {
        String envId = System.getenv("CLAUDE_CODE_TASK_LIST_ID");
        if (envId != null && !envId.isBlank()) return envId;

        // In-process teammates share the leader's task list
        Optional<TeammateContext.TeammateContextData> ctx = TeammateContext.getTeammateContext();
        if (ctx.isPresent()) return ctx.get().getTeamName();

        String teamName = teammateService.getTeamName();
        if (teamName != null && !teamName.isBlank()) return teamName;
        if (leaderTeamName != null && !leaderTeamName.isBlank()) return leaderTeamName;

        return "session-" + ProcessHandle.current().pid();
    }

    /** Sanitize a string for safe use in file path components. */
    public static String sanitizePathComponent(String input) {
        if (input == null) return "-";
        return input.replaceAll("[^a-zA-Z0-9_\\-]", "-");
    }

    public Path getTasksDir(String taskListId) {
        return Path.of(EnvUtils.getClaudeConfigHomeDir(), "tasks", sanitizePathComponent(taskListId));
    }

    public Path getTaskPath(String taskListId, String taskId) {
        return getTasksDir(taskListId).resolve(sanitizePathComponent(taskId) + ".json");
    }

    public boolean isTodoV2Enabled() {
        if (EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_ENABLE_TASKS"))) return true;
        // Non-interactive sessions have tasks disabled by default
        return !"true".equalsIgnoreCase(System.getenv("CLAUDE_CODE_NON_INTERACTIVE"));
    }

    // -------------------------------------------------------------------------
    // Directory / lock helpers
    // -------------------------------------------------------------------------

    private void ensureTasksDir(String taskListId) {
        try {
            Files.createDirectories(getTasksDir(taskListId));
        } catch (IOException e) {
            log.debug("[Tasks] Could not create tasks dir for {}: {}", taskListId, e.getMessage());
        }
    }

    private ReentrantLock getOrCreateLock(String key) {
        return taskListLocks.computeIfAbsent(key, k -> new ReentrantLock());
    }

    /** Execute supplier while holding the JVM-level lock for taskListId. */
    private <T> T withTaskListLock(String taskListId, Supplier<T> fn) {
        ReentrantLock lock = getOrCreateLock(taskListId);
        lock.lock();
        try {
            return fn.get();
        } finally {
            lock.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // High water mark
    // -------------------------------------------------------------------------

    private Path getHighWaterMarkPath(String taskListId) {
        return getTasksDir(taskListId).resolve(HIGH_WATER_MARK_FILE);
    }

    private int readHighWaterMark(String taskListId) {
        try {
            String content = Files.readString(getHighWaterMarkPath(taskListId)).trim();
            int val = Integer.parseInt(content);
            return val < 0 ? 0 : val;
        } catch (Exception e) {
            return 0;
        }
    }

    private void writeHighWaterMark(String taskListId, int value) {
        try {
            Files.writeString(getHighWaterMarkPath(taskListId), String.valueOf(value));
        } catch (IOException e) {
            log.debug("[Tasks] Failed to write high water mark: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // ID helpers
    // -------------------------------------------------------------------------

    private int findHighestTaskIdFromFiles(String taskListId) {
        Path dir = getTasksDir(taskListId);
        try (var stream = Files.list(dir)) {
            return stream
                .map(p -> p.getFileName().toString())
                .filter(f -> f.endsWith(".json") && !f.startsWith("."))
                .mapToInt(f -> {
                    try { return Integer.parseInt(f.replace(".json", "")); }
                    catch (NumberFormatException e) { return 0; }
                })
                .max()
                .orElse(0);
        } catch (IOException e) {
            return 0;
        }
    }

    private int findHighestTaskId(String taskListId) {
        int fromFiles = findHighestTaskIdFromFiles(taskListId);
        int fromMark  = readHighWaterMark(taskListId);
        return Math.max(fromFiles, fromMark);
    }

    // -------------------------------------------------------------------------
    // CRUD
    // -------------------------------------------------------------------------

    /**
     * Reset the task list for a new swarm run. Preserves the high water mark
     * so IDs are not reused after reset.
     */
    public CompletableFuture<Void> resetTaskList(String taskListId) {
        return CompletableFuture.runAsync(() -> withTaskListLock(taskListId, () -> {
            ensureTasksDir(taskListId);
            int currentHighest = findHighestTaskIdFromFiles(taskListId);
            if (currentHighest > 0) {
                int existing = readHighWaterMark(taskListId);
                if (currentHighest > existing) writeHighWaterMark(taskListId, currentHighest);
            }

            Path dir = getTasksDir(taskListId);
            try (var stream = Files.list(dir)) {
                stream
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".json") && !name.startsWith(".");
                    })
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); }
                        catch (IOException e) { /* ignore */ }
                    });
            } catch (IOException e) {
                log.debug("[Tasks] Could not list dir during reset: {}", e.getMessage());
            }
            notifyTasksUpdated();
            return null;
        }));
    }

    /**
     * Create a new task with an auto-incremented ID.
     * Thread-safe via JVM lock + file serialization.
     */
    public CompletableFuture<String> createTask(String taskListId, Task taskData) {
        return CompletableFuture.supplyAsync(() -> {
            ensureTasksDir(taskListId);
            return withTaskListLock(taskListId, () -> {
                int highestId = findHighestTaskId(taskListId);
                String id = String.valueOf(highestId + 1);
                taskData.setId(id);
                Path path = getTaskPath(taskListId, id);
                try {
                    Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(taskData));
                } catch (IOException e) {
                    throw new RuntimeException("Failed to write task " + id, e);
                }
                notifyTasksUpdated();
                return id;
            });
        });
    }

    /** Read a task from disk. Returns null if not found or invalid. */
    public CompletableFuture<Task> getTask(String taskListId, String taskId) {
        return CompletableFuture.supplyAsync(() -> {
            Path path = getTaskPath(taskListId, taskId);
            try {
                String content = Files.readString(path);
                Task task = objectMapper.readValue(content, Task.class);
                // Legacy status migration (ant-only)
                if ("ant".equals(System.getenv("USER_TYPE"))) {
                    migrateTaskStatus(task);
                }
                return task;
            } catch (NoSuchFileException e) {
                return null;
            } catch (IOException e) {
                log.debug("[Tasks] Failed to read task {}: {}", taskId, e.getMessage());
                return null;
            }
        });
    }

    private void migrateTaskStatus(Task task) {
        if (task.getStatus() == null) return;
        // Status is already an enum – no string migration needed at the Java level.
        // The ObjectMapper handles "open"→pending etc. via a custom deserializer if configured.
    }

    /** Update a task. Returns the updated task or null if not found. */
    public CompletableFuture<Task> updateTask(String taskListId, String taskId,
                                               Task updates) {
        return CompletableFuture.supplyAsync(() -> {
            // Check existence before locking
            Path path = getTaskPath(taskListId, taskId);
            if (!Files.exists(path)) return null;

            ReentrantLock lock = getOrCreateLock(taskListId + ":" + taskId);
            lock.lock();
            try {
                return updateTaskUnsafe(taskListId, taskId, updates);
            } finally {
                lock.unlock();
            }
        });
    }

    /** Internal update without acquiring the task-level lock. */
    private Task updateTaskUnsafe(String taskListId, String taskId, Task updates) {
        Path path = getTaskPath(taskListId, taskId);
        Task existing;
        try {
            existing = objectMapper.readValue(Files.readString(path), Task.class);
        } catch (IOException e) {
            return null;
        }
        // Merge non-null fields from updates
        if (updates.getSubject()     != null) existing.setSubject(updates.getSubject());
        if (updates.getDescription() != null) existing.setDescription(updates.getDescription());
        if (updates.getActiveForm()  != null) existing.setActiveForm(updates.getActiveForm());
        if (updates.getOwner()       != null) existing.setOwner(updates.getOwner());
        if (updates.getStatus()      != null) existing.setStatus(updates.getStatus());
        if (updates.getBlocks()      != null) existing.setBlocks(updates.getBlocks());
        if (updates.getBlockedBy()   != null) existing.setBlockedBy(updates.getBlockedBy());
        if (updates.getMetadata()    != null) existing.setMetadata(updates.getMetadata());
        existing.setId(taskId);

        try {
            Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(existing));
        } catch (IOException e) {
            throw new RuntimeException("Failed to write updated task " + taskId, e);
        }
        notifyTasksUpdated();
        return existing;
    }

    /** Delete a task and remove all references to it from other tasks. */
    public CompletableFuture<Boolean> deleteTask(String taskListId, String taskId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path path = getTaskPath(taskListId, taskId);

                // Update high water mark before deleting
                try {
                    int numericId = Integer.parseInt(taskId);
                    int current = readHighWaterMark(taskListId);
                    if (numericId > current) writeHighWaterMark(taskListId, numericId);
                } catch (NumberFormatException ignored) {}

                try {
                    Files.delete(path);
                } catch (NoSuchFileException e) {
                    return false;
                }

                // Remove references to this task from other tasks
                List<Task> allTasks = listTasksSync(taskListId);
                for (Task task : allTasks) {
                    List<String> newBlocks    = task.getBlocks().stream()
                        .filter(id -> !id.equals(taskId)).collect(Collectors.toList());
                    List<String> newBlockedBy = task.getBlockedBy().stream()
                        .filter(id -> !id.equals(taskId)).collect(Collectors.toList());
                    if (newBlocks.size() != task.getBlocks().size()
                            || newBlockedBy.size() != task.getBlockedBy().size()) {
                        Task patch = new Task();
                        patch.setBlocks(newBlocks);
                        patch.setBlockedBy(newBlockedBy);
                        updateTask(taskListId, task.getId(), patch).join();
                    }
                }

                notifyTasksUpdated();
                return true;
            } catch (Exception e) {
                return false;
            }
        });
    }

    /** List all tasks in a task list. */
    public CompletableFuture<List<Task>> listTasks(String taskListId) {
        return CompletableFuture.supplyAsync(() -> listTasksSync(taskListId));
    }

    private List<Task> listTasksSync(String taskListId) {
        Path dir = getTasksDir(taskListId);
        List<String> taskIds;
        try (var stream = Files.list(dir)) {
            taskIds = stream
                .map(p -> p.getFileName().toString())
                .filter(f -> f.endsWith(".json") && !f.startsWith("."))
                .map(f -> f.replace(".json", ""))
                .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }

        List<Task> result = new ArrayList<>();
        for (String id : taskIds) {
            Task t = getTask(taskListId, id).join();
            if (t != null) result.add(t);
        }
        return result;
    }

    /** Add a blocking relationship: fromTaskId blocks toTaskId. */
    public CompletableFuture<Boolean> blockTask(String taskListId,
                                                 String fromTaskId, String toTaskId) {
        return CompletableFuture.supplyAsync(() -> {
            Task from = getTask(taskListId, fromTaskId).join();
            Task to   = getTask(taskListId, toTaskId).join();
            if (from == null || to == null) return false;

            if (!from.getBlocks().contains(toTaskId)) {
                List<String> newBlocks = new ArrayList<>(from.getBlocks());
                newBlocks.add(toTaskId);
                Task patch = new Task();
                patch.setBlocks(newBlocks);
                updateTask(taskListId, fromTaskId, patch).join();
            }
            if (!to.getBlockedBy().contains(fromTaskId)) {
                List<String> newBlockedBy = new ArrayList<>(to.getBlockedBy());
                newBlockedBy.add(fromTaskId);
                Task patch = new Task();
                patch.setBlockedBy(newBlockedBy);
                updateTask(taskListId, toTaskId, patch).join();
            }
            return true;
        });
    }

    /**
     * Attempt to claim a task for an agent.
     * Returns a ClaimTaskResult indicating success or the reason for failure.
     */
    public CompletableFuture<ClaimTaskResult> claimTask(
            String taskListId, String taskId, String claimantAgentId,
            boolean checkAgentBusy) {

        return CompletableFuture.supplyAsync(() -> {
            // Check existence before locking
            Task taskBeforeLock = getTask(taskListId, taskId).join();
            if (taskBeforeLock == null) {
                return new ClaimTaskResult.Failure("task_not_found", null, null, null);
            }

            if (checkAgentBusy) {
                return claimTaskWithBusyCheck(taskListId, taskId, claimantAgentId);
            }

            ReentrantLock lock = getOrCreateLock(taskListId + ":" + taskId);
            lock.lock();
            try {
                Task task = getTask(taskListId, taskId).join();
                if (task == null) return new ClaimTaskResult.Failure("task_not_found", null, null, null);

                if (task.getOwner() != null && !task.getOwner().equals(claimantAgentId)) {
                    return new ClaimTaskResult.Failure("already_claimed", task, null, null);
                }
                if (task.getStatus() == TaskStatus.completed) {
                    return new ClaimTaskResult.Failure("already_resolved", task, null, null);
                }

                List<Task> allTasks = listTasksSync(taskListId);
                Set<String> unresolvedIds = allTasks.stream()
                    .filter(t -> t.getStatus() != TaskStatus.completed)
                    .map(Task::getId)
                    .collect(Collectors.toSet());
                List<String> blockedBy = task.getBlockedBy().stream()
                    .filter(unresolvedIds::contains).collect(Collectors.toList());
                if (!blockedBy.isEmpty()) {
                    return new ClaimTaskResult.Failure("blocked", task, null, blockedBy);
                }

                Task patch = new Task();
                patch.setOwner(claimantAgentId);
                Task updated = updateTaskUnsafe(taskListId, taskId, patch);
                return new ClaimTaskResult.Success(updated);
            } catch (Exception e) {
                log.debug("[Tasks] Failed to claim task {}: {}", taskId, e.getMessage());
                return new ClaimTaskResult.Failure("task_not_found", null, null, null);
            } finally {
                lock.unlock();
            }
        });
    }

    private ClaimTaskResult claimTaskWithBusyCheck(
            String taskListId, String taskId, String claimantAgentId) {
        return withTaskListLock(taskListId, () -> {
            List<Task> allTasks = listTasksSync(taskListId);
            Task task = allTasks.stream().filter(t -> t.getId().equals(taskId))
                .findFirst().orElse(null);
            if (task == null) return new ClaimTaskResult.Failure("task_not_found", null, null, null);

            if (task.getOwner() != null && !task.getOwner().equals(claimantAgentId)) {
                return new ClaimTaskResult.Failure("already_claimed", task, null, null);
            }
            if (task.getStatus() == TaskStatus.completed) {
                return new ClaimTaskResult.Failure("already_resolved", task, null, null);
            }

            Set<String> unresolvedIds = allTasks.stream()
                .filter(t -> t.getStatus() != TaskStatus.completed)
                .map(Task::getId).collect(Collectors.toSet());
            List<String> blockedBy = task.getBlockedBy().stream()
                .filter(unresolvedIds::contains).collect(Collectors.toList());
            if (!blockedBy.isEmpty()) {
                return new ClaimTaskResult.Failure("blocked", task, null, blockedBy);
            }

            List<String> agentOpenTasks = allTasks.stream()
                .filter(t -> t.getStatus() != TaskStatus.completed
                    && claimantAgentId.equals(t.getOwner())
                    && !t.getId().equals(taskId))
                .map(Task::getId).collect(Collectors.toList());
            if (!agentOpenTasks.isEmpty()) {
                return new ClaimTaskResult.Failure("agent_busy", task, agentOpenTasks, null);
            }

            Task patch = new Task();
            patch.setOwner(claimantAgentId);
            Task updated = updateTaskUnsafe(taskListId, taskId, patch);
            return new ClaimTaskResult.Success(updated);
        });
    }

    // -------------------------------------------------------------------------
    // Agent status
    // -------------------------------------------------------------------------

    public CompletableFuture<List<AgentStatus>> getAgentStatuses(String teamName) {
        return CompletableFuture.supplyAsync(() -> {
            List<Task> allTasks = listTasksSync(sanitizePathComponent(teamName));

            // Group unresolved tasks by owner
            Map<String, List<String>> byOwner = new HashMap<>();
            for (Task task : allTasks) {
                if (task.getStatus() != TaskStatus.completed && task.getOwner() != null) {
                    byOwner.computeIfAbsent(task.getOwner(), k -> new ArrayList<>())
                        .add(task.getId());
                }
            }

            // Build statuses (members come from the team file – here we derive from tasks)
            Set<String> owners = new HashSet<>(byOwner.keySet());
            List<AgentStatus> statuses = new ArrayList<>();
            for (String owner : owners) {
                AgentStatus s = new AgentStatus();
                s.setAgentId(owner);
                s.setName(owner);
                s.setCurrentTasks(byOwner.get(owner));
                s.setStatus("busy");
                statuses.add(s);
            }
            return statuses;
        });
    }

    // -------------------------------------------------------------------------
    // Unassign teammate tasks
    // -------------------------------------------------------------------------

    /**
     * Unassign all open tasks from a teammate and return a notification message.
     * Used when a teammate is killed or gracefully shuts down.
     */
    public CompletableFuture<UnassignTasksResult> unassignTeammateTasks(
            String teamName, String teammateId, String teammateName,
            String reason) { // "terminated" | "shutdown"
        return CompletableFuture.supplyAsync(() -> {
            List<Task> tasks = listTasksSync(teamName);
            List<Task> unresolvedAssigned = tasks.stream()
                .filter(t -> t.getStatus() != TaskStatus.completed
                    && (teammateId.equals(t.getOwner()) || teammateName.equals(t.getOwner())))
                .collect(Collectors.toList());

            for (Task task : unresolvedAssigned) {
                Task patch = new Task();
                patch.setOwner("");          // clear owner
                patch.setStatus(TaskStatus.pending);
                updateTask(teamName, task.getId(), patch).join();
            }

            if (!unresolvedAssigned.isEmpty()) {
                log.debug("[Tasks] Unassigned {} task(s) from {}",
                    unresolvedAssigned.size(), teammateName);
            }

            String actionVerb = "terminated".equals(reason) ? "was terminated" : "has shut down";
            StringBuilder msg = new StringBuilder(teammateName).append(" ").append(actionVerb).append(".");
            if (!unresolvedAssigned.isEmpty()) {
                String taskList = unresolvedAssigned.stream()
                    .map(t -> "#" + t.getId() + " \"" + t.getSubject() + "\"")
                    .collect(Collectors.joining(", "));
                msg.append(" ").append(unresolvedAssigned.size())
                    .append(" task(s) were unassigned: ").append(taskList)
                    .append(". Use TaskList to check availability and TaskUpdate with owner to reassign them to idle teammates.");
            }

            UnassignTasksResult result = new UnassignTasksResult();
            result.setUnassignedTasks(unresolvedAssigned.stream()
                .map(t -> Map.of("id", t.getId(), "subject", t.getSubject()))
                .collect(Collectors.toList()));
            result.setNotificationMessage(msg.toString());
            return result;
        });
    }
}
