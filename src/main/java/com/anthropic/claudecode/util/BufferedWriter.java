package com.anthropic.claudecode.util;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Buffered writer for efficient output.
 * Translated from src/utils/bufferedWriter.ts
 */
public class BufferedWriter {

    private final Consumer<String> writeFn;
    private final long flushIntervalMs;
    private final int maxBufferSize;
    private final boolean immediateMode;

    private final List<String> buffer = new ArrayList<>();
    private ScheduledFuture<?> flushTimer;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public BufferedWriter(
            Consumer<String> writeFn,
            long flushIntervalMs,
            int maxBufferSize,
            boolean immediateMode) {
        this.writeFn = writeFn;
        this.flushIntervalMs = flushIntervalMs;
        this.maxBufferSize = maxBufferSize;
        this.immediateMode = immediateMode;
    }

    public static BufferedWriter create(Consumer<String> writeFn) {
        return new BufferedWriter(writeFn, 1000, 100, false);
    }

    /**
     * Write content to the buffer.
     * Translated from BufferedWriter.write() in bufferedWriter.ts
     */
    public synchronized void write(String content) {
        if (immediateMode) {
            writeFn.accept(content);
            return;
        }

        buffer.add(content);

        if (buffer.size() >= maxBufferSize) {
            flush();
        } else {
            scheduleFlush();
        }
    }

    /**
     * Flush the buffer.
     * Translated from BufferedWriter.flush() in bufferedWriter.ts
     */
    public synchronized void flush() {
        cancelTimer();
        if (!buffer.isEmpty()) {
            writeFn.accept(String.join("", buffer));
            buffer.clear();
        }
    }

    /**
     * Dispose the writer.
     * Translated from BufferedWriter.dispose() in bufferedWriter.ts
     */
    public synchronized void dispose() {
        flush();
        scheduler.shutdown();
    }

    private void scheduleFlush() {
        if (flushTimer == null || flushTimer.isDone()) {
            flushTimer = scheduler.schedule(this::flush, flushIntervalMs, TimeUnit.MILLISECONDS);
        }
    }

    private void cancelTimer() {
        if (flushTimer != null && !flushTimer.isDone()) {
            flushTimer.cancel(false);
            flushTimer = null;
        }
    }
}
