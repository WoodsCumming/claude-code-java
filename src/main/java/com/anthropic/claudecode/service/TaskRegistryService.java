package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Task registry service.
 * Translated from src/tasks.ts
 *
 * Registry of all task types.
 */
@Slf4j
@Service
public class TaskRegistryService {



    private final LocalShellTaskService localShellTaskService;
    private final DreamTaskService dreamTaskService;
    private final TaskFrameworkService taskFrameworkService;

    @Autowired
    public TaskRegistryService(LocalShellTaskService localShellTaskService,
                                DreamTaskService dreamTaskService,
                                TaskFrameworkService taskFrameworkService) {
        this.localShellTaskService = localShellTaskService;
        this.dreamTaskService = dreamTaskService;
        this.taskFrameworkService = taskFrameworkService;
    }

    /**
     * Get all active tasks.
     * Translated from getAllTasks() in tasks.ts
     */
    public List<Map<String, Object>> getAllTasks() {
        List<Map<String, Object>> tasks = new ArrayList<>();

        // Add local shell tasks
        for (LocalShellTaskService.LocalShellTask task : localShellTaskService.listTasks()) {
            tasks.add(Map.of(
                "id", task.getId(),
                "type", "local_bash",
                "status", task.getStatus(),
                "description", task.getDescription()
            ));
        }

        // Add framework tasks
        for (TaskFrameworkService.TaskState task : taskFrameworkService.listTasks()) {
            tasks.add(Map.of(
                "id", task.getId(),
                "type", task.getType(),
                "status", task.getStatus(),
                "description", task.getDescription()
            ));
        }

        return tasks;
    }

    /**
     * Get a task by ID.
     */
    public Optional<Map<String, Object>> getTaskById(String taskId) {
        return getAllTasks().stream()
            .filter(t -> taskId.equals(t.get("id")))
            .findFirst();
    }

    /**
     * Get the task type for a task ID.
     * Translated from getTaskByType() in tasks.ts
     */
    public String getTaskType(String taskId) {
        return getTaskById(taskId)
            .map(t -> (String) t.get("type"))
            .orElse(null);
    }
}
