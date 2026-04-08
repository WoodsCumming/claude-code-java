package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.CronUtils;
import com.anthropic.claudecode.util.EnvUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Cron tasks service for managing scheduled prompts.
 * Translated from src/utils/cronTasks.ts
 *
 * Tasks are stored in <project>/.claude/scheduled_tasks.json.
 */
@Slf4j
@Service
public class CronTasksService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CronTasksService.class);


    private static final String TASKS_FILENAME = "scheduled_tasks.json";
    private static final long DEFAULT_MAX_AGE_DAYS = 7;
    private static final int MAX_JOBS = 50;

    private final ObjectMapper objectMapper;

    // Session-scoped tasks (not persisted to disk)
    private final Map<String, CronTask> sessionTasks = new ConcurrentHashMap<>();

    @Autowired
    public CronTasksService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Add a cron task.
     * Translated from addCronTask() in cronTasks.ts
     */
    public CronTask addCronTask(String cron, String prompt, boolean recurring, boolean durable) {
        // Validate cron expression
        if (CronUtils.parseCronExpression(cron) == null) {
            throw new IllegalArgumentException("Invalid cron expression: " + cron);
        }

        // Check job limit
        List<CronTask> existing = listAllCronTasks();
        if (existing.size() >= MAX_JOBS) {
            throw new IllegalStateException("Maximum of " + MAX_JOBS + " scheduled tasks reached");
        }

        String id = UUID.randomUUID().toString().substring(0, 8);
        CronTask task = new CronTask(
            id, cron, prompt, System.currentTimeMillis(),
            null, recurring, false, !durable
        );

        if (durable) {
            // Persist to disk
            saveToDisk(task);
        } else {
            // Session-only
            sessionTasks.put(id, task);
        }

        return task;
    }

    /**
     * List all cron tasks (session + disk).
     * Translated from listAllCronTasks() in cronTasks.ts
     */
    public List<CronTask> listAllCronTasks() {
        List<CronTask> all = new ArrayList<>();
        all.addAll(sessionTasks.values());
        all.addAll(loadFromDisk());
        return all;
    }

    /**
     * Get a cron task by ID.
     */
    public Optional<CronTask> getCronTask(String id) {
        CronTask session = sessionTasks.get(id);
        if (session != null) return Optional.of(session);

        return loadFromDisk().stream()
            .filter(t -> t.getId().equals(id))
            .findFirst();
    }

    /**
     * Delete a cron task.
     * Translated from deleteCronTask() in cronTasks.ts
     */
    public boolean deleteCronTask(String id) {
        if (sessionTasks.remove(id) != null) return true;

        List<CronTask> tasks = loadFromDisk();
        boolean removed = tasks.removeIf(t -> t.getId().equals(id));
        if (removed) {
            saveDiskTasks(tasks);
        }
        return removed;
    }

    /**
     * Get the next run time in ms for a task.
     * Translated from nextCronRunMs() in cronTasks.ts
     */
    public long nextCronRunMs(String taskId) {
        return getCronTask(taskId)
            .map(t -> CronUtils.nextCronRunMs(t.getCron()))
            .orElse(-1L);
    }

    /**
     * Get the file path for cron tasks.
     */
    public String getCronFilePath() {
        return System.getProperty("user.dir") + "/.claude/" + TASKS_FILENAME;
    }

    private void saveToDisk(CronTask task) {
        List<CronTask> tasks = loadFromDisk();
        tasks.add(task);
        saveDiskTasks(tasks);
    }

    private List<CronTask> loadFromDisk() {
        String path = getCronFilePath();
        File file = new File(path);
        if (!file.exists()) return new ArrayList<>();

        try {
            Map<String, Object> data = objectMapper.readValue(file, Map.class);
            List<Map<String, Object>> taskMaps = (List<Map<String, Object>>) data.get("tasks");
            if (taskMaps == null) return new ArrayList<>();

            List<CronTask> tasks = new ArrayList<>();
            for (Map<String, Object> taskMap : taskMaps) {
                CronTask task = objectMapper.convertValue(taskMap, CronTask.class);
                if (task != null) tasks.add(task);
            }
            return tasks;
        } catch (Exception e) {
            log.debug("Could not load cron tasks: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private void saveDiskTasks(List<CronTask> tasks) {
        String path = getCronFilePath();
        try {
            new File(path).getParentFile().mkdirs();
            Map<String, Object> data = Map.of("tasks", tasks);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(path), data);
        } catch (Exception e) {
            log.error("Could not save cron tasks: {}", e.getMessage());
        }
    }

    public static class CronTask {
        private String id;
        private String cron;
        private String prompt;
        private long createdAt;
        private Long lastFiredAt;
        private boolean recurring;
        private boolean permanent;
        private boolean sessionOnly; // Runtime-only, not persisted

        public String getId() { return id; }
        public void setId(String v) { id = v; }
        public String getCron() { return cron; }
        public void setCron(String v) { cron = v; }
        public String getPrompt() { return prompt; }
        public void setPrompt(String v) { prompt = v; }
        public long getCreatedAt() { return createdAt; }
        public void setCreatedAt(long v) { createdAt = v; }
        public Long getLastFiredAt() { return lastFiredAt; }
        public void setLastFiredAt(Long v) { lastFiredAt = v; }
        public boolean isRecurring() { return recurring; }
        public void setRecurring(boolean v) { recurring = v; }
        public boolean isPermanent() { return permanent; }
        public void setPermanent(boolean v) { permanent = v; }
        public boolean isSessionOnly() { return sessionOnly; }
        public void setSessionOnly(boolean v) { sessionOnly = v; }

        public CronTask() {}
        public CronTask(String id, String cron, String prompt, long createdAt, Long lastFiredAt,
                         boolean recurring, boolean permanent, boolean sessionOnly) {
            this.id = id; this.cron = cron; this.prompt = prompt; this.createdAt = createdAt;
            this.lastFiredAt = lastFiredAt; this.recurring = recurring; this.permanent = permanent;
            this.sessionOnly = sessionOnly;
        }

        public static CronTaskBuilder builder() { return new CronTaskBuilder(); }
        public static class CronTaskBuilder {
            private final CronTask t = new CronTask();
            public CronTaskBuilder id(String v) { t.id = v; return this; }
            public CronTaskBuilder cron(String v) { t.cron = v; return this; }
            public CronTaskBuilder prompt(String v) { t.prompt = v; return this; }
            public CronTaskBuilder createdAt(long v) { t.createdAt = v; return this; }
            public CronTaskBuilder lastFiredAt(Long v) { t.lastFiredAt = v; return this; }
            public CronTaskBuilder recurring(boolean v) { t.recurring = v; return this; }
            public CronTaskBuilder permanent(boolean v) { t.permanent = v; return this; }
            public CronTaskBuilder sessionOnly(boolean v) { t.sessionOnly = v; return this; }
            public CronTask build() { return t; }
        }
    }
}
