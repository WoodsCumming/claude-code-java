package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Kill shell tasks service.
 * Translated from src/tasks/LocalShellTask/killShellTasks.ts
 *
 * Pure (non-React) kill helpers for LocalShellTask.
 */
@Slf4j
@Service
public class KillShellTasksService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KillShellTasksService.class);


    private final LocalShellTaskService localShellTaskService;
    private final TaskFrameworkService taskFrameworkService;

    @Autowired
    public KillShellTasksService(LocalShellTaskService localShellTaskService,
                                   TaskFrameworkService taskFrameworkService) {
        this.localShellTaskService = localShellTaskService;
        this.taskFrameworkService = taskFrameworkService;
    }

    /**
     * Kill a specific shell task.
     * Translated from killTask() in killShellTasks.ts
     */
    public void killTask(String taskId) {
        log.debug("Killing shell task: {}", taskId);
        localShellTaskService.killTask(taskId);
    }

    /**
     * Kill all shell tasks for an agent.
     * Translated from killShellTasksForAgent() in killShellTasks.ts
     */
    public void killShellTasksForAgent(String agentId) {
        log.debug("Killing all shell tasks for agent: {}", agentId);

        List<LocalShellTaskService.LocalShellTask> tasks = localShellTaskService.listTasks()
            .stream()
            .filter(t -> agentId.equals(((LocalShellTaskService.LocalShellTask) t).getId()))
            .map(t -> (LocalShellTaskService.LocalShellTask) t)
            .toList();

        for (LocalShellTaskService.LocalShellTask task : tasks) {
            if ("running".equals(task.getStatus())) {
                localShellTaskService.killTask(task.getId());
            }
        }
    }

    /**
     * Kill all running shell tasks.
     * Translated from killAllShellTasks() in killShellTasks.ts
     */
    public void killAllShellTasks() {
        log.debug("Killing all shell tasks");
        localShellTaskService.listTasks()
            .stream()
            .filter(t -> "running".equals(t.getStatus()))
            .forEach(t -> localShellTaskService.killTask(t.getId()));
    }
}
