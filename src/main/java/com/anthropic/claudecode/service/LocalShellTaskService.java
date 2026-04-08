package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Local shell task service for managing background bash commands.
 * Translated from src/tasks/LocalShellTask/LocalShellTask.tsx
 *
 * Manages background shell tasks that run asynchronously.
 */
@Slf4j
@Service
public class LocalShellTaskService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LocalShellTaskService.class);


    public static final String BACKGROUND_BASH_SUMMARY_PREFIX = "Background command ";
    private static final long STALL_CHECK_INTERVAL_MS = 5_000;
    private static final long STALL_THRESHOLD_MS = 45_000;

    private static final List<Pattern> PROMPT_PATTERNS = List.of(
        Pattern.compile("\\(y/n\\)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\[y/n\\]", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\(yes/no\\)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(?:Do you|Would you|Shall I|Are you sure|Ready to)\\b.*\\?\\s*$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("Press (any key|Enter)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("Continue\\?", Pattern.CASE_INSENSITIVE),
        Pattern.compile("Overwrite\\?", Pattern.CASE_INSENSITIVE)
    );

    private final Map<String, LocalShellTask> activeTasks = new ConcurrentHashMap<>();
    private final BackgroundTaskService backgroundTaskService;

    @Autowired
    public LocalShellTaskService(BackgroundTaskService backgroundTaskService) {
        this.backgroundTaskService = backgroundTaskService;
    }

    /**
     * Check if a string looks like a shell prompt waiting for input.
     * Translated from looksLikePrompt() in LocalShellTask.tsx
     */
    public boolean looksLikePrompt(String tail) {
        if (tail == null) return false;
        String lastLine = tail.stripTrailing();
        int lastNewline = lastLine.lastIndexOf('\n');
        if (lastNewline >= 0) lastLine = lastLine.substring(lastNewline + 1);

        for (Pattern pattern : PROMPT_PATTERNS) {
            if (pattern.matcher(lastLine).find()) return true;
        }
        return false;
    }

    /**
     * Start a local shell task.
     */
    public CompletableFuture<LocalShellTask> startTask(String command, String description) {
        return CompletableFuture.supplyAsync(() -> {
            String taskId = UUID.randomUUID().toString().substring(0, 8);
            LocalShellTask task = new LocalShellTask(taskId, command, description);

            activeTasks.put(taskId, task);
            backgroundTaskService.registerTask(description);

            // Execute the command asynchronously
            CompletableFuture.runAsync(() -> {
                try {
                    ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
                    pb.redirectErrorStream(true);
                    Process process = pb.start();

                    task.setProcess(process);
                    task.setStatus("running");

                    String output = new String(process.getInputStream().readAllBytes());
                    int exitCode = process.waitFor();

                    task.setOutput(output);
                    task.setExitCode(exitCode);
                    task.setStatus(exitCode == 0 ? "completed" : "failed");

                    log.debug("Shell task {} completed with exit code {}", taskId, exitCode);
                } catch (Exception e) {
                    task.setStatus("failed");
                    task.setError(e.getMessage());
                    log.error("Shell task {} failed: {}", taskId, e.getMessage());
                }
            });

            return task;
        });
    }

    /**
     * List all active tasks.
     */
    public java.util.List<LocalShellTask> listTasks() {
        return new java.util.ArrayList<>(activeTasks.values());
    }

    /**
     * Kill a running task.
     */
    public boolean killTask(String taskId) {
        LocalShellTask task = activeTasks.get(taskId);
        if (task == null) return false;

        Process process = task.getProcess();
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            task.setStatus("killed");
            return true;
        }
        return false;
    }

    public static class LocalShellTask {
        private String id;
        private String command;
        private String description;
        private String status = "pending"; // pending | running | completed | failed | killed
        private Process process;
        private String output;
        private Integer exitCode;
        private String error;

        public LocalShellTask() {}
        public LocalShellTask(String id, String command, String description) {
            this.id = id; this.command = command; this.description = description; this.status = "pending";
        }
        public String getId() { return id; }
        public void setId(String v) { id = v; }
        public String getCommand() { return command; }
        public void setCommand(String v) { command = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
        public String getStatus() { return status; }
        public void setStatus(String v) { status = v; }
        public Process getProcess() { return process; }
        public void setProcess(Process v) { process = v; }
        public String getOutput() { return output; }
        public void setOutput(String v) { output = v; }
        public Integer getExitCode() { return exitCode; }
        public void setExitCode(Integer v) { exitCode = v; }
        public String getError() { return error; }
        public void setError(String v) { error = v; }
    }
}
