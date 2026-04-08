package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.Task;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Task registry service.
 * Translated from src/tasks.ts
 *
 * Manages available task types.
 */
@Slf4j
@Service
public class TaskRegistry {



    /**
     * Get all available task types.
     * Translated from getAllTasks() in tasks.ts
     */
    public List<Task.TaskType> getAllTaskTypes() {
        return List.of(
            Task.TaskType.LOCAL_BASH,
            Task.TaskType.LOCAL_AGENT,
            Task.TaskType.REMOTE_AGENT,
            Task.TaskType.DREAM
        );
    }

    /**
     * Create a new task state.
     */
    public Task.TaskStateBase createTask(Task.TaskType type, String description, String toolUseId) {
        String id = Task.generateTaskId(type);
        return Task.TaskStateBase.builder()
            .id(id)
            .type(type)
            .status(Task.TaskStatus.PENDING)
            .description(description)
            .toolUseId(toolUseId)
            .startTime(System.currentTimeMillis())
            .outputFile("")
            .outputOffset(0)
            .notified(false)
            .build();
    }
}
