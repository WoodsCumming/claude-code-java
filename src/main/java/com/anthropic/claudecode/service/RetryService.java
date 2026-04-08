package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Retry service for API calls.
 * Translated from src/services/api/withRetry.ts
 *
 * Implements exponential backoff retry logic for API requests.
 */
@Slf4j
@Service
public class RetryService {



    private static final int DEFAULT_MAX_RETRIES = 10;
    private static final long BASE_DELAY_MS = 500;
    private static final int MAX_529_RETRIES = 3;

    /**
     * Execute a supplier with retry logic.
     * Translated from withRetry() in withRetry.ts
     */
    public <T> CompletableFuture<T> withRetry(
            Supplier<CompletableFuture<T>> supplier,
            int maxRetries,
            String querySource) {

        return withRetryInternal(supplier, maxRetries, querySource, 0);
    }

    public <T> CompletableFuture<T> withRetry(Supplier<CompletableFuture<T>> supplier) {
        return withRetry(supplier, DEFAULT_MAX_RETRIES, "unknown");
    }

    private <T> CompletableFuture<T> withRetryInternal(
            Supplier<CompletableFuture<T>> supplier,
            int maxRetries,
            String querySource,
            int attempt) {

        return supplier.get().exceptionally(error -> {
            throw new RuntimeException(error);
        }).thenApply(result -> result)
        .exceptionallyCompose(error -> {
            Throwable cause = error.getCause() != null ? error.getCause() : error;

            // Don't retry on user abort
            if (cause instanceof InterruptedException) {
                CompletableFuture<T> failed = new CompletableFuture<>();
                failed.completeExceptionally(cause);
                return failed;
            }

            // Check if we should retry
            if (attempt >= maxRetries) {
                log.error("Max retries ({}) exceeded for {}: {}",
                    maxRetries, querySource, cause.getMessage());
                CompletableFuture<T> failed = new CompletableFuture<>();
                failed.completeExceptionally(cause);
                return failed;
            }

            // Calculate delay with exponential backoff
            long delayMs = calculateDelay(attempt, cause);

            if (shouldRetry(cause)) {
                log.warn("Retrying {} (attempt {}/{}): {} - waiting {}ms",
                    querySource, attempt + 1, maxRetries, cause.getMessage(), delayMs);

                java.util.concurrent.Executor delayed =
                    CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS);
                return CompletableFuture.runAsync(() -> {}, delayed)
                    .thenCompose(v -> withRetryInternal(supplier, maxRetries, querySource, attempt + 1));
            }

            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(cause);
            return failed;
        });
    }

    private boolean shouldRetry(Throwable error) {
        String message = error.getMessage();
        if (message == null) return false;

        // Retry on rate limits (429) and server errors (500, 529)
        if (message.contains("429") || message.contains("rate limit")) return true;
        if (message.contains("529") || message.contains("overloaded")) return true;
        if (message.contains("500") || message.contains("502") || message.contains("503")) return true;
        if (message.contains("connection") || message.contains("timeout")) return true;

        return false;
    }

    private long calculateDelay(int attempt, Throwable error) {
        // Exponential backoff with jitter
        long delay = BASE_DELAY_MS * (long) Math.pow(2, attempt);
        // Add jitter (±25%)
        long jitter = (long) (delay * 0.25 * (Math.random() * 2 - 1));
        delay = Math.max(BASE_DELAY_MS, delay + jitter);

        // For 529 errors, use longer delays
        if (error.getMessage() != null && error.getMessage().contains("529")) {
            delay = Math.max(delay, 5_000);
        }

        // Cap at 30 seconds
        return Math.min(delay, 30_000);
    }
}
