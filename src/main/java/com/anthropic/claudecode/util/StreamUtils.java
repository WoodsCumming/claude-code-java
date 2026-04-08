package com.anthropic.claudecode.util;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Async stream utilities.
 * Translated from src/utils/stream.ts
 *
 * Provides an async stream implementation for streaming data.
 */
public class StreamUtils {

    /**
     * An async stream that supports enqueueing and consuming values.
     * Translated from Stream class in stream.ts
     */
    public static class Stream<T> {
        private final BlockingQueue<Optional<T>> queue = new LinkedBlockingQueue<>();
        private final AtomicBoolean done = new AtomicBoolean(false);
        private volatile Throwable error;

        /**
         * Enqueue a value.
         * Translated from Stream.enqueue() in stream.ts
         */
        public void enqueue(T value) {
            if (!done.get()) {
                queue.offer(Optional.of(value));
            }
        }

        /**
         * Close the stream.
         * Translated from Stream.return() in stream.ts
         */
        public void close() {
            done.set(true);
            queue.offer(Optional.empty()); // Sentinel
        }

        /**
         * Close the stream with an error.
         * Translated from Stream.throw() in stream.ts
         */
        public void closeWithError(Throwable error) {
            this.error = error;
            done.set(true);
            queue.offer(Optional.empty()); // Sentinel
        }

        /**
         * Get the next value from the stream.
         */
        public CompletableFuture<Optional<T>> next() {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Optional<T> value = queue.take();
                    if (error != null) {
                        throw new RuntimeException(error);
                    }
                    return value;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
        }

        /**
         * Consume all values from the stream.
         */
        public CompletableFuture<List<T>> toList() {
            return CompletableFuture.supplyAsync(() -> {
                List<T> result = new ArrayList<>();
                try {
                    while (true) {
                        Optional<T> value = queue.take();
                        if (value.isEmpty()) break;
                        result.add(value.get());
                    }
                    if (error != null) throw new RuntimeException(error);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                return result;
            });
        }

        public boolean isDone() { return done.get(); }
    }

    private StreamUtils() {}
}
