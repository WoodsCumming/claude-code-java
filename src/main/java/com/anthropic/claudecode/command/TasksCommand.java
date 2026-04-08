package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.BackgroundTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Tasks command for listing and managing background tasks.
 * Translated from src/commands/tasks/index.ts
 */
@Slf4j
@Component
@Command(
    name = "tasks",
    aliases = {"bashes"},
    description = "List and manage background tasks"
)
public class TasksCommand implements Callable<Integer> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TasksCommand.class);


    private final BackgroundTaskService backgroundTaskService;

    @Autowired
    public TasksCommand(BackgroundTaskService backgroundTaskService) {
        this.backgroundTaskService = backgroundTaskService;
    }

    @Override
    public Integer call() {
        List<BackgroundTaskService.TaskInfo> tasks = backgroundTaskService.listTasks();

        if (tasks.isEmpty()) {
            System.out.println("No background tasks running.");
            return 0;
        }

        System.out.println("Background tasks:");
        for (BackgroundTaskService.TaskInfo task : tasks) {
            System.out.printf("  [%s] %s - %s%n",
                task.getStatus(),
                task.getId(),
                task.getDescription());
        }
        return 0;
    }
}
