package com.anthropic.claudecode.util;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Sequential execution wrapper.
 * Translated from src/utils/sequential.ts
 *
 * Ensures that concurrent calls to an async function are executed
 * one at a time in the order they were received.
 */
public class SequentialExecutor<T> {

    private final Queue<QueueItem<T>> queue = new LinkedList<>();
    private volatile boolean processing = false;
    private final Object lock = new Object();

    /**
     * Submit a task for sequential execution.
     * Translated from the returned function in sequential() in sequential.ts
     */
    public CompletableFuture<T> submit(Supplier<CompletableFuture<T>> task) {
        CompletableFuture<T> future = new CompletableFuture<>();

        synchronized (lock) {
            queue.offer(new QueueItem<>(task, future));
        }

        processQueue();

        return future;
    }

    private void processQueue() {
        synchronized (lock) {
            if (processing) return;
            if (queue.isEmpty()) return;
            processing = true;
        }

        processNext();
    }

    private void processNext() {
        QueueItem<T> item;
        synchronized (lock) {
            item = queue.poll();
            if (item == null) {
                processing = false;
                return;
            }
        }

        try {
            item.task.get()
                .whenComplete((result, error) -> {
                    if (error != null) {
                        item.future.completeExceptionally(error);
                    } else {
                        item.future.complete(result);
                    }
                    processNext();
                });
        } catch (Exception e) {
            item.future.completeExceptionally(e);
            processNext();
        }
    }

    private record QueueItem<T>(Supplier<CompletableFuture<T>> task, CompletableFuture<T> future) {}

    /**
     * Create a sequential executor for a given function type.
     */
    public static <T> SequentialExecutor<T> create() {
        return new SequentialExecutor<>();
    }
}
