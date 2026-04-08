package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;

/**
 * Background task management service.
 * Translated from src/commands/tasks/
 *
 * Manages background shell tasks and their lifecycle.
 */
@Slf4j
@Service
public class BackgroundTaskService {



    private final Map<String, TaskInfo> tasks = new ConcurrentHashMap<>();

    /**
     * Register a background task.
     */
    public TaskInfo registerTask(String description) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        TaskInfo task = new TaskInfo(id, description, "running");
        tasks.put(id, task);
        log.debug("Registered background task: {} - {}", id, description);
        return task;
    }

    /**
     * Update task status.
     */
    public void updateTaskStatus(String taskId, String status) {
        TaskInfo task = tasks.get(taskId);
        if (task != null) {
            task.setStatus(status);
        }
    }

    /**
     * Register an async agent task.
     */
    public String registerAsyncAgent(String agentId, String agentType, String description,
                                      com.anthropic.claudecode.model.AgentDefinition agentDefinition,
                                      com.anthropic.claudecode.model.ToolUseContext toolUseContext) {
        return registerTask(description).getId();
    }

    /**
     * Complete an async agent task (success).
     */
    public void completeAsyncAgent(String agentId, Throwable error) {
        if (error != null) {
            failTask(agentId, error.getMessage());
        } else {
            completeTask(agentId);
        }
    }

    /**
     * Complete an async agent task (exception).
     */
    public void completeAsyncAgent(String agentId, Exception error) {
        if (error != null) {
            failTask(agentId, error.getMessage());
        } else {
            completeTask(agentId);
        }
    }

    /**
     * Complete a task.
     */
    public void completeTask(String taskId) {
        updateTaskStatus(taskId, "completed");
    }

    /**
     * Fail a task.
     */
    public void failTask(String taskId, String error) {
        TaskInfo task = tasks.get(taskId);
        if (task != null) {
            task.setStatus("failed");
            task.setError(error);
        }
    }

    /**
     * Remove a completed task.
     */
    public void removeTask(String taskId) {
        tasks.remove(taskId);
    }

    /**
     * List all tasks.
     */
    public List<TaskInfo> listTasks() {
        return new ArrayList<>(tasks.values());
    }

    /**
     * Get a task by ID.
     */
    public Optional<TaskInfo> getTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    public static class TaskInfo {
        private final String id;
        private String description;
        private String status;
        private String error;

        public TaskInfo() { this.id = null; }
        public TaskInfo(String id, String description, String status) {
            this.id = id;
            this.description = description;
            this.status = status;
        }
        public TaskInfo(String id, String description, String status, String error) {
            this.id = id;
            this.description = description;
            this.status = status;
            this.error = error;
        }

        public String getId() { return id; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
        public String getStatus() { return status; }
        public void setStatus(String v) { status = v; }
        public String getError() { return error; }
        public void setError(String v) { error = v; }
    }
}
