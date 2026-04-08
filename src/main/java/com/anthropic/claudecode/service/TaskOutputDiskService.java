package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Task output disk management service.
 * Translated from src/utils/task/diskOutput.ts
 *
 * Manages async disk writes for task output files. Each task gets its own
 * output file under the session-scoped tasks directory. Supports append,
 * flush, delta-read, and full tail-read operations.
 */
@Slf4j
@Service
public class TaskOutputDiskService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TaskOutputDiskService.class);


    /**
     * Disk cap for task output files.
     * Translated from MAX_TASK_OUTPUT_BYTES in diskOutput.ts
     */
    public static final long MAX_TASK_OUTPUT_BYTES = 5L * 1024 * 1024 * 1024; // 5GB
    public static final String MAX_TASK_OUTPUT_BYTES_DISPLAY = "5GB";

    private static final long DEFAULT_MAX_READ_BYTES = 8L * 1024 * 1024; // 8MB

    /**
     * Session-scoped task output directory.
     * Memoised on first call — mirrors the TypeScript _taskOutputDir singleton.
     */
    private volatile String taskOutputDir;
    private final String sessionId;

    // Per-task async write queues
    private final Map<String, DiskTaskOutput> outputs = new ConcurrentHashMap<>();

    public TaskOutputDiskService() {
        this.sessionId = UUID.randomUUID().toString();
    }

    // =========================================================================
    // Directory / path helpers
    // =========================================================================

    /**
     * Get the task output directory for this session.
     * Translated from getTaskOutputDir() in diskOutput.ts
     */
    public String getTaskOutputDir() {
        if (taskOutputDir == null) {
            synchronized (this) {
                if (taskOutputDir == null) {
                    taskOutputDir = EnvUtils.getClaudeConfigHomeDir()
                        + "/tmp/" + sessionId + "/tasks";
                }
            }
        }
        return taskOutputDir;
    }

    /**
     * Get the output file path for a task.
     * Translated from getTaskOutputPath() in diskOutput.ts
     */
    public String getTaskOutputPath(String taskId) {
        return getTaskOutputDir() + "/" + taskId + ".output";
    }

    /**
     * Ensure the task output directory exists.
     * Translated from ensureOutputDir() in diskOutput.ts
     */
    public void ensureOutputDir() throws IOException {
        Files.createDirectories(Paths.get(getTaskOutputDir()));
    }

    // =========================================================================
    // Async write queue (DiskTaskOutput inner class)
    // =========================================================================

    /**
     * Encapsulates async disk writes for a single task's output.
     * Translated from DiskTaskOutput class in diskOutput.ts
     *
     * Uses a ConcurrentLinkedDeque as the write queue and a single drain
     * CompletableFuture so each chunk can be GC'd after its write completes.
     */
    public class DiskTaskOutput {
        private final String path;
        private final Queue<String> queue = new ConcurrentLinkedQueue<>();
        private long bytesWritten = 0;
        private volatile boolean capped = false;
        private volatile CompletableFuture<Void> flushFuture = null;
        private final Object lock = new Object();

        DiskTaskOutput(String taskId) {
            this.path = getTaskOutputPath(taskId);
        }

        public void append(String content) {
            if (capped) return;
            bytesWritten += content.length();
            if (bytesWritten > MAX_TASK_OUTPUT_BYTES) {
                capped = true;
                queue.add("\n[output truncated: exceeded " + MAX_TASK_OUTPUT_BYTES_DISPLAY + " disk cap]\n");
            } else {
                queue.add(content);
            }
            synchronized (lock) {
                if (flushFuture == null) {
                    flushFuture = CompletableFuture.runAsync(this::drain);
                }
            }
        }

        public CompletableFuture<Void> flush() {
            synchronized (lock) {
                return flushFuture != null ? flushFuture : CompletableFuture.completedFuture(null);
            }
        }

        public void cancel() {
            queue.clear();
        }

        private void drain() {
            try {
                drainAllChunks();
            } catch (Exception e) {
                log.warn("[diskOutput] drain error (retrying once): {}", e.getMessage());
                if (!queue.isEmpty()) {
                    try {
                        drainAllChunks();
                    } catch (Exception e2) {
                        log.warn("[diskOutput] drain retry failed: {}", e2.getMessage());
                    }
                }
            } finally {
                synchronized (lock) {
                    flushFuture = null;
                }
            }
        }

        private void drainAllChunks() throws IOException {
            while (!queue.isEmpty()) {
                ensureOutputDir();
                Path p = Paths.get(path);
                try (FileChannel ch = FileChannel.open(p,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND,
                        StandardOpenOption.CREATE)) {
                    while (!queue.isEmpty()) {
                        List<String> batch = drainQueue();
                        byte[] bytes = String.join("", batch).getBytes(StandardCharsets.UTF_8);
                        ByteBuffer buf = ByteBuffer.wrap(bytes);
                        while (buf.hasRemaining()) {
                            ch.write(buf);
                        }
                    }
                }
            }
        }

        private List<String> drainQueue() {
            List<String> batch = new ArrayList<>();
            String chunk;
            while ((chunk = queue.poll()) != null) {
                batch.add(chunk);
            }
            return batch;
        }
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Append output to a task's disk file asynchronously.
     * Translated from appendTaskOutput() in diskOutput.ts
     */
    public void appendTaskOutput(String taskId, String content) {
        getOrCreateOutput(taskId).append(content);
    }

    /**
     * Wait for all pending writes for a task to complete.
     * Translated from flushTaskOutput() in diskOutput.ts
     */
    public CompletableFuture<Void> flushTaskOutput(String taskId) {
        DiskTaskOutput output = outputs.get(taskId);
        return output != null ? output.flush() : CompletableFuture.completedFuture(null);
    }

    /**
     * Evict a task's DiskTaskOutput from the in-memory map after flushing.
     * Translated from evictTaskOutput() in diskOutput.ts
     */
    public CompletableFuture<Void> evictTaskOutput(String taskId) {
        DiskTaskOutput output = outputs.remove(taskId);
        if (output == null) return CompletableFuture.completedFuture(null);
        return output.flush();
    }

    /**
     * Initialize an empty output file for a new task.
     * Translated from initTaskOutput() in diskOutput.ts
     */
    public CompletableFuture<String> initTaskOutput(String taskId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ensureOutputDir();
                String outputPath = getTaskOutputPath(taskId);
                Path p = Paths.get(outputPath);
                Files.createFile(p); // fails with FileAlreadyExistsException if present
                return outputPath;
            } catch (FileAlreadyExistsException e) {
                return getTaskOutputPath(taskId);
            } catch (IOException e) {
                log.warn("[diskOutput] initTaskOutput failed: {}", e.getMessage());
                return getTaskOutputPath(taskId);
            }
        });
    }

    /**
     * Initialize output file as a symlink to another file.
     * Translated from initTaskOutputAsSymlink() in diskOutput.ts
     */
    public CompletableFuture<String> initTaskOutputAsSymlink(String taskId, String targetPath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ensureOutputDir();
                String outputPath = getTaskOutputPath(taskId);
                Path link = Paths.get(outputPath);
                Path target = Paths.get(targetPath);
                try {
                    Files.createSymbolicLink(link, target);
                } catch (FileAlreadyExistsException e) {
                    Files.deleteIfExists(link);
                    Files.createSymbolicLink(link, target);
                }
                return outputPath;
            } catch (Exception e) {
                log.warn("[diskOutput] initTaskOutputAsSymlink failed, falling back to initTaskOutput: {}", e.getMessage());
                return initTaskOutput(taskId).join();
            }
        });
    }

    /**
     * Get delta (new content) since the last read.
     * Translated from getTaskOutputDelta() in diskOutput.ts
     */
    public DeltaResult getTaskOutputDelta(String taskId, long fromOffset) {
        return getTaskOutputDelta(taskId, fromOffset, DEFAULT_MAX_READ_BYTES);
    }

    public DeltaResult getTaskOutputDelta(String taskId, long fromOffset, long maxBytes) {
        String path = getTaskOutputPath(taskId);
        File file = new File(path);
        if (!file.exists()) {
            return new DeltaResult("", fromOffset);
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long fileSize = raf.length();
            if (fromOffset >= fileSize) {
                return new DeltaResult("", fromOffset);
            }
            raf.seek(fromOffset);
            long bytesToRead = Math.min(maxBytes, fileSize - fromOffset);
            byte[] bytes = new byte[(int) bytesToRead];
            int bytesRead = raf.read(bytes);
            if (bytesRead <= 0) return new DeltaResult("", fromOffset);
            String content = new String(bytes, 0, bytesRead, StandardCharsets.UTF_8);
            return new DeltaResult(content, fromOffset + bytesRead);
        } catch (IOException e) {
            log.debug("[diskOutput] getTaskOutputDelta error for {}: {}", taskId, e.getMessage());
            return new DeltaResult("", fromOffset);
        }
    }

    /**
     * Get output for a task, reading the tail of the file.
     * Translated from getTaskOutput() in diskOutput.ts
     */
    public String getTaskOutput(String taskId) {
        return getTaskOutput(taskId, DEFAULT_MAX_READ_BYTES);
    }

    public String getTaskOutput(String taskId, long maxBytes) {
        String path = getTaskOutputPath(taskId);
        File file = new File(path);
        if (!file.exists()) return "";
        try {
            long fileSize = file.length();
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                long startOffset = Math.max(0, fileSize - maxBytes);
                raf.seek(startOffset);
                long bytesToRead = fileSize - startOffset;
                byte[] bytes = new byte[(int) bytesToRead];
                int bytesRead = raf.read(bytes);
                if (bytesRead <= 0) return "";
                String content = new String(bytes, 0, bytesRead, StandardCharsets.UTF_8);
                if (startOffset > 0) {
                    return "[" + Math.round(startOffset / 1024.0) + "KB of earlier output omitted]\n" + content;
                }
                return content;
            }
        } catch (IOException e) {
            log.debug("[diskOutput] getTaskOutput error for {}: {}", taskId, e.getMessage());
            return "";
        }
    }

    /**
     * Get the current size (offset) of a task's output file.
     * Translated from getTaskOutputSize() in diskOutput.ts
     */
    public long getTaskOutputSize(String taskId) {
        File file = new File(getTaskOutputPath(taskId));
        return file.exists() ? file.length() : 0L;
    }

    /**
     * Clean up a task's output file and write queue.
     * Translated from cleanupTaskOutput() in diskOutput.ts
     */
    public void cleanupTaskOutput(String taskId) {
        DiskTaskOutput output = outputs.remove(taskId);
        if (output != null) output.cancel();
        try {
            Files.deleteIfExists(Paths.get(getTaskOutputPath(taskId)));
        } catch (IOException e) {
            log.debug("[diskOutput] cleanupTaskOutput could not delete file for {}: {}", taskId, e.getMessage());
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private DiskTaskOutput getOrCreateOutput(String taskId) {
        return outputs.computeIfAbsent(taskId, DiskTaskOutput::new);
    }

    // =========================================================================
    // Result types
    // =========================================================================

    public static class DeltaResult {
        private String content;
        private long newOffset;
        public DeltaResult() {}
        public DeltaResult(String content, long newOffset) { this.content = content; this.newOffset = newOffset; }
        public String getContent() { return content; }
        public void setContent(String v) { content = v; }
        public long getNewOffset() { return newOffset; }
        public void setNewOffset(long v) { newOffset = v; }
    }
}
