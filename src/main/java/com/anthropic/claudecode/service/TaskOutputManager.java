package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Task output manager — registry and lifecycle wrapper for TaskOutputService instances.
 * Translated from src/utils/task/sdkProgress.ts (file-mode orchestration) and the
 * static registry patterns in src/utils/task/TaskOutput.ts.
 *
 * Complements TaskOutputService (which provides the core TaskOutputInstance logic)
 * by exposing a service-layer registry with create/get/remove and a shared
 * polling scheduler that mirrors TaskOutput.#registry / #activePolling.
 */
@Slf4j
@Service
public class TaskOutputManager {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TaskOutputManager.class);


    private static final long POLL_INTERVAL_MS = 1000;
    private static final int PROGRESS_TAIL_BYTES = 4096;

    private final TaskOutputDiskService taskOutputDiskService;
    private final TaskOutputService taskOutputService;

    /** All instances created through this manager (taskId → instance). */
    private final Map<String, TaskOutputService.TaskOutputInstance> registry = new ConcurrentHashMap<>();

    /** Subset currently receiving active polling (file-mode with onProgress). */
    private final Map<String, TaskOutputService.TaskOutputInstance> activePolling = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "task-output-manager-poller");
            t.setDaemon(true);
            return t;
        });
    private ScheduledFuture<?> pollTimer;

    @Autowired
    public TaskOutputManager(TaskOutputDiskService taskOutputDiskService,
                              TaskOutputService taskOutputService) {
        this.taskOutputDiskService = taskOutputDiskService;
        this.taskOutputService = taskOutputService;
    }

    // =========================================================================
    // Factory / registry
    // =========================================================================

    /**
     * Create a new task output instance and add it to the registry.
     * stdoutToFile=true → file mode (bash); false → pipe mode (hooks).
     */
    public TaskOutputService.TaskOutputInstance create(String taskId,
                                                        boolean stdoutToFile,
                                                        Consumer<ProgressUpdate> onProgress) {
        Consumer<TaskOutputService.ProgressUpdate> wrapped = onProgress != null
            ? pu -> onProgress.accept(new ProgressUpdate(
                pu.getLastLines(), pu.getAllLines(), pu.getTotalLines(), pu.getTotalBytes(), pu.isIsIncomplete()))
            : null;

        TaskOutputService.TaskOutputInstance instance =
            taskOutputService.create(taskId, wrapped, stdoutToFile);
        registry.put(taskId, instance);

        // Auto-register for polling when file-mode + progress needed
        if (stdoutToFile && onProgress != null) {
            activePolling.put(taskId, instance);
            ensurePollerRunning();
        }
        return instance;
    }

    /**
     * Get an existing instance.
     */
    public Optional<TaskOutputService.TaskOutputInstance> get(String taskId) {
        return Optional.ofNullable(registry.get(taskId));
    }

    /**
     * Remove an instance and stop its polling.
     */
    public void remove(String taskId) {
        registry.remove(taskId);
        stopPolling(taskId);
        taskOutputService.remove(taskId);
    }

    // =========================================================================
    // Polling control
    // =========================================================================

    /**
     * Begin polling the output file for a specific task.
     * Mirrors TaskOutput.startPolling() in TaskOutput.ts
     */
    public synchronized void startPolling(String taskId) {
        TaskOutputService.TaskOutputInstance instance = registry.get(taskId);
        if (instance == null || !instance.stdoutToFile || instance.onProgress == null) return;
        activePolling.put(taskId, instance);
        ensurePollerRunning();
    }

    /**
     * Stop polling the output file for a specific task.
     * Mirrors TaskOutput.stopPolling() in TaskOutput.ts
     */
    public synchronized void stopPolling(String taskId) {
        activePolling.remove(taskId);
        if (activePolling.isEmpty() && pollTimer != null) {
            pollTimer.cancel(false);
            pollTimer = null;
        }
    }

    private synchronized void ensurePollerRunning() {
        if (pollTimer == null || pollTimer.isCancelled()) {
            pollTimer = scheduler.scheduleAtFixedRate(
                this::tick, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Shared tick: reads the file tail for every actively-polled task.
     * Mirrors TaskOutput.#tick() in TaskOutput.ts
     */
    private void tick() {
        for (Map.Entry<String, TaskOutputService.TaskOutputInstance> entry
                : new ArrayList<>(activePolling.entrySet())) {
            TaskOutputService.TaskOutputInstance inst = entry.getValue();
            if (inst.onProgress == null) continue;
            try {
                File file = new File(inst.path);
                if (!file.exists()) continue;
                long fileSize = file.length();
                long startOffset = Math.max(0, fileSize - PROGRESS_TAIL_BYTES);
                try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                    raf.seek(startOffset);
                    int toRead = (int) (fileSize - startOffset);
                    byte[] bytes = new byte[toRead];
                    int read = raf.read(bytes);
                    if (read <= 0) {
                        inst.onProgress.accept(wrap(new TaskOutputService.ProgressUpdate("", "", inst.totalLines, fileSize, false)));
                        continue;
                    }
                    String content = new String(bytes, 0, read, StandardCharsets.UTF_8);
                    String[] lines = content.split("\n", -1);
                    int lineCount = lines.length;
                    long bytesRead = read;
                    int totalLines = fileSize <= bytesRead
                        ? lineCount
                        : Math.max(inst.totalLines, (int) Math.round((double) fileSize / bytesRead * lineCount));
                    inst.totalLines = totalLines;
                    inst.totalBytes = fileSize;
                    String last5 = joinLastN(lines, 5);
                    String last100 = joinLastN(lines, 100);
                    inst.onProgress.accept(wrap(new TaskOutputService.ProgressUpdate(
                        last5, last100, totalLines, fileSize, fileSize > bytesRead)));
                }
            } catch (Exception e) {
                // File may not exist yet
            }
        }
    }

    private static TaskOutputService.ProgressUpdate wrap(TaskOutputService.ProgressUpdate pu) {
        return pu;
    }

    private static String joinLastN(String[] lines, int n) {
        int start = Math.max(0, lines.length - n);
        return String.join("\n", Arrays.copyOfRange(lines, start, lines.length));
    }

    // =========================================================================
    // ProgressUpdate (manager-layer view matching the TS ProgressCallback shape)
    // =========================================================================

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ProgressUpdate {
        /** Last ~5 lines of output */
        private String lastLines;
        /** Last ~100 lines of output */
        private String allLines;
        private int totalLines;
        private long totalBytes;
        private boolean isIncomplete;

        public String getLastLines() { return lastLines; }
        public void setLastLines(String v) { lastLines = v; }
        public String getAllLines() { return allLines; }
        public void setAllLines(String v) { allLines = v; }
        public int getTotalLines() { return totalLines; }
        public void setTotalLines(int v) { totalLines = v; }
        public long getTotalBytes() { return totalBytes; }
        public void setTotalBytes(long v) { totalBytes = v; }
        public boolean isIsIncomplete() { return isIncomplete; }
        public void setIsIncomplete(boolean v) { isIncomplete = v; }
    }
}
