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
 * Task output management service.
 * Translated from src/utils/task/TaskOutput.ts
 *
 * Single source of truth for a shell command's output. Supports both:
 *   - File mode (bash): stdout/stderr go directly to a file via OS fds.
 *     Progress is extracted by polling the file tail.
 *   - Pipe mode (hooks): data flows through writeStdout()/writeStderr()
 *     and is buffered in memory, spilling to disk if it exceeds the limit.
 */
@Slf4j
@Service
public class TaskOutputService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TaskOutputService.class);


    private static final long DEFAULT_MAX_MEMORY = 8L * 1024 * 1024; // 8MB
    private static final long POLL_INTERVAL_MS = 1000;
    private static final int PROGRESS_TAIL_BYTES = 4096;

    private final TaskOutputDiskService diskService;

    /** Registry of all TaskOutputInstance objects (keyed by taskId). */
    private final Map<String, TaskOutputInstance> registry = new ConcurrentHashMap<>();

    /** Subset currently being polled (file-mode instances with onProgress). */
    private final Map<String, TaskOutputInstance> activePolling = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "task-output-poller");
            t.setDaemon(true);
            return t;
        });
    private ScheduledFuture<?> pollTimer;

    @Autowired
    public TaskOutputService(TaskOutputDiskService diskService) {
        this.diskService = diskService;
    }

    // =========================================================================
    // Factory / registry
    // =========================================================================

    /**
     * Create a new TaskOutputInstance and register it.
     * Translated from TaskOutput constructor in TaskOutput.ts
     */
    public TaskOutputInstance create(String taskId,
                                      Consumer<ProgressUpdate> onProgress,
                                      boolean stdoutToFile) {
        TaskOutputInstance instance = new TaskOutputInstance(
            taskId,
            diskService.getTaskOutputPath(taskId),
            stdoutToFile,
            onProgress,
            diskService
        );
        registry.put(taskId, instance);
        return instance;
    }

    public Optional<TaskOutputInstance> get(String taskId) {
        return Optional.ofNullable(registry.get(taskId));
    }

    public void remove(String taskId) {
        registry.remove(taskId);
        stopPolling(taskId);
    }

    // =========================================================================
    // Polling control (mirrors static startPolling / stopPolling in TS)
    // =========================================================================

    /**
     * Begin polling the output file for progress.
     * Translated from TaskOutput.startPolling() in TaskOutput.ts
     */
    public synchronized void startPolling(String taskId) {
        TaskOutputInstance instance = registry.get(taskId);
        if (instance == null || !instance.stdoutToFile || instance.onProgress == null) return;
        activePolling.put(taskId, instance);
        if (pollTimer == null || pollTimer.isCancelled()) {
            pollTimer = scheduler.scheduleAtFixedRate(this::tick, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Stop polling the output file.
     * Translated from TaskOutput.stopPolling() in TaskOutput.ts
     */
    public synchronized void stopPolling(String taskId) {
        activePolling.remove(taskId);
        if (activePolling.isEmpty() && pollTimer != null) {
            pollTimer.cancel(false);
            pollTimer = null;
        }
    }

    /**
     * Shared tick: reads the file tail for every actively-polled task.
     * Translated from TaskOutput.#tick() in TaskOutput.ts
     */
    private void tick() {
        for (Map.Entry<String, TaskOutputInstance> entry : new ArrayList<>(activePolling.entrySet())) {
            TaskOutputInstance inst = entry.getValue();
            if (inst.onProgress == null) continue;
            try {
                File file = new File(inst.path);
                if (!file.exists()) continue;
                long fileSize = file.length();
                long startOffset = Math.max(0, fileSize - PROGRESS_TAIL_BYTES);
                try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                    raf.seek(startOffset);
                    byte[] bytes = new byte[(int) (fileSize - startOffset)];
                    int read = raf.read(bytes);
                    if (read <= 0) {
                        inst.onProgress.accept(new ProgressUpdate("", "", inst.totalLines, fileSize, false));
                        continue;
                    }
                    String content = new String(bytes, 0, read, StandardCharsets.UTF_8);
                    // Count lines and extract last 5 / last 100 lines from tail
                    String[] lines = content.split("\n", -1);
                    int lineCount = lines.length;
                    int totalLines = fileSize <= read
                        ? lineCount
                        : Math.max(inst.totalLines, (int) Math.round((double) fileSize / read * lineCount));
                    inst.totalLines = totalLines;
                    inst.totalBytes = fileSize;
                    String last5 = joinLastN(lines, 5);
                    String last100 = joinLastN(lines, 100);
                    inst.onProgress.accept(new ProgressUpdate(last5, last100, totalLines, fileSize, fileSize > read));
                }
            } catch (Exception e) {
                // File may not exist yet — not an error
            }
        }
    }

    private static String joinLastN(String[] lines, int n) {
        int start = Math.max(0, lines.length - n);
        return String.join("\n", Arrays.copyOfRange(lines, start, lines.length));
    }

    // =========================================================================
    // Progress update type
    // =========================================================================

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ProgressUpdate {
        /** Last 5 lines of output */
        private String lastLines;
        /** Last 100 lines of output */
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

    // =========================================================================
    // TaskOutputInstance — corresponds to the TaskOutput class in TS
    // =========================================================================

    public static class TaskOutputInstance {
        public final String taskId;
        public final String path;
        /** True when stdout goes to a file fd (bypassing JVM). False for pipe mode. */
        public final boolean stdoutToFile;
        public final Consumer<ProgressUpdate> onProgress;
        private final TaskOutputDiskService diskService;

        private final StringBuilder stdoutBuffer = new StringBuilder();
        private final StringBuilder stderrBuffer = new StringBuilder();
        private TaskOutputDiskService.DiskTaskOutput disk = null;
        /** Recent lines circular buffer (last 1000 lines) */
        private final LinkedList<String> recentLines = new LinkedList<>();
        private static final int RECENT_LINES_CAP = 1000;

        volatile int totalLines = 0;
        volatile long totalBytes = 0;
        private final long maxMemory;

        /** Set after getStdout() — true when the file was fully read. */
        private boolean outputFileRedundant = false;
        /** Set after getStdout() — total file size in bytes. */
        private long outputFileSize = 0;

        public TaskOutputInstance(String taskId, String path, boolean stdoutToFile,
                                   Consumer<ProgressUpdate> onProgress,
                                   TaskOutputDiskService diskService) {
            this(taskId, path, stdoutToFile, onProgress, diskService, (long) (8 * 1024 * 1024));
        }

        public TaskOutputInstance(String taskId, String path, boolean stdoutToFile,
                                   Consumer<ProgressUpdate> onProgress,
                                   TaskOutputDiskService diskService, long maxMemory) {
            this.taskId = taskId;
            this.path = path;
            this.stdoutToFile = stdoutToFile;
            this.onProgress = onProgress;
            this.diskService = diskService;
            this.maxMemory = maxMemory;
        }

        /**
         * Write stdout data (pipe mode only — used by hooks).
         * Translated from writeStdout() in TaskOutput.ts
         */
        public void writeStdout(String data) {
            writeBuffered(data, false);
        }

        /**
         * Write stderr data (always piped).
         * Translated from writeStderr() in TaskOutput.ts
         */
        public void writeStderr(String data) {
            writeBuffered(data, true);
        }

        private void writeBuffered(String data, boolean isStderr) {
            totalBytes += data.length();
            updateProgress(data);

            if (disk != null) {
                disk.append(isStderr ? "[stderr] " + data : data);
                return;
            }

            long totalMem = stdoutBuffer.length() + stderrBuffer.length() + data.length();
            if (totalMem > maxMemory) {
                spillToDisk(isStderr ? data : null, isStderr ? null : data);
                return;
            }

            if (isStderr) {
                stderrBuffer.append(data);
            } else {
                stdoutBuffer.append(data);
            }
        }

        /**
         * Single backward pass to update progress state from pipe-mode data.
         * Translated from #updateProgress() in TaskOutput.ts
         */
        private void updateProgress(String data) {
            final int MAX_PROGRESS_BYTES = 4096;
            final int MAX_PROGRESS_LINES = 100;

            String[] parts = data.split("\n", -1);
            int lineCount = Math.max(0, parts.length - 1);
            totalLines += lineCount;

            // Collect last MAX_PROGRESS_LINES non-empty lines from this chunk
            int added = 0;
            int extractedBytes = 0;
            for (int i = parts.length - 1; i >= 0 && added < MAX_PROGRESS_LINES && extractedBytes < MAX_PROGRESS_BYTES; i--) {
                String line = parts[i].trim();
                if (!line.isEmpty()) {
                    recentLines.addLast(line);
                    if (recentLines.size() > RECENT_LINES_CAP) recentLines.removeFirst();
                    extractedBytes += parts[i].length();
                    added++;
                }
            }

            if (onProgress != null && added > 0) {
                String last5 = joinRecent(5);
                String last100 = joinRecent(100);
                onProgress.accept(new ProgressUpdate(last5, last100, totalLines, totalBytes, disk != null));
            }
        }

        private String joinRecent(int n) {
            List<String> list = new ArrayList<>(recentLines);
            int start = Math.max(0, list.size() - n);
            return String.join("\n", list.subList(start, list.size()));
        }

        private void spillToDisk(String stderrChunk, String stdoutChunk) {
            disk = diskService.new DiskTaskOutput(taskId);
            if (stdoutBuffer.length() > 0) {
                disk.append(stdoutBuffer.toString());
                stdoutBuffer.setLength(0);
            }
            if (stderrBuffer.length() > 0) {
                disk.append("[stderr] " + stderrBuffer);
                stderrBuffer.setLength(0);
            }
            if (stdoutChunk != null) disk.append(stdoutChunk);
            if (stderrChunk != null) disk.append("[stderr] " + stderrChunk);
        }

        /**
         * Get stdout content.
         * Translated from getStdout() in TaskOutput.ts
         */
        public CompletableFuture<String> getStdout() {
            if (stdoutToFile) {
                return readStdoutFromFile();
            }
            if (disk != null) {
                String recent = joinRecent(5);
                long sizeKB = Math.round(totalBytes / 1024.0);
                String notice = "\nOutput truncated (" + sizeKB + "KB total). Full output saved to: " + path;
                return CompletableFuture.completedFuture(recent.isEmpty() ? notice.stripLeading() : recent + notice);
            }
            return CompletableFuture.completedFuture(stdoutBuffer.toString());
        }

        private CompletableFuture<String> readStdoutFromFile() {
            return CompletableFuture.supplyAsync(() -> {
                File file = new File(path);
                if (!file.exists()) {
                    outputFileRedundant = true;
                    return "";
                }
                try {
                    long fileSize = file.length();
                    long maxBytes = 8L * 1024 * 1024; // getMaxOutputLength() equivalent
                    try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
                        long bytesToRead = Math.min(maxBytes, fileSize);
                        byte[] bytes = new byte[(int) bytesToRead];
                        int bytesRead = raf.read(bytes);
                        if (bytesRead <= 0) {
                            outputFileRedundant = true;
                            return "";
                        }
                        outputFileSize = fileSize;
                        outputFileRedundant = fileSize <= bytesRead;
                        return new String(bytes, 0, bytesRead, StandardCharsets.UTF_8);
                    }
                } catch (Exception e) {
                    String code = e.getMessage() != null ? e.getMessage() : "unknown";
                    log.debug("[TaskOutput] readStdoutFromFile failed for {} ({}): {}", path, code, e.getMessage());
                    return "<bash output unavailable: output file " + path
                        + " could not be read (" + code + "). This usually means another Claude Code process in the same project deleted it during startup cleanup.>";
                }
            });
        }

        /**
         * Get stderr. Returns empty string when spilled to disk (interleaved).
         * Translated from getStderr() in TaskOutput.ts
         */
        public String getStderr() {
            if (disk != null) return "";
            return stderrBuffer.toString();
        }

        public boolean isOverflowed() { return disk != null; }
        public boolean isOutputFileRedundant() { return outputFileRedundant; }
        public long getOutputFileSize() { return outputFileSize; }

        /**
         * Force all buffered content to disk.
         * Translated from spillToDisk() public call in TaskOutput.ts
         */
        public void spillToDisk() {
            if (disk == null) spillToDisk(null, null);
        }

        public CompletableFuture<Void> flush() {
            return disk != null ? disk.flush() : CompletableFuture.completedFuture(null);
        }

        /** Delete the output file.
         * Translated from deleteOutputFile() in TaskOutput.ts
         */
        public CompletableFuture<Void> deleteOutputFile() {
            return CompletableFuture.runAsync(() -> {
                try {
                    Files.deleteIfExists(Paths.get(path));
                } catch (IOException e) {
                    // non-fatal
                }
            });
        }

        /**
         * Clear all buffers and stop progress callbacks.
         * Translated from clear() in TaskOutput.ts
         */
        public void clear() {
            stdoutBuffer.setLength(0);
            stderrBuffer.setLength(0);
            recentLines.clear();
            if (disk != null) {
                disk.cancel();
                disk = null;
            }
        }
    }
}
