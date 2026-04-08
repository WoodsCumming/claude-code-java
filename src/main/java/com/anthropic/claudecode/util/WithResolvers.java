package com.anthropic.claudecode.util;

import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Promise with resolvers utility.
 * Translated from src/utils/withResolvers.ts
 *
 * Creates a CompletableFuture with exposed resolve and reject functions.
 */
public class WithResolvers {

    /**
     * Create a CompletableFuture with exposed resolve/reject.
     * Translated from withResolvers() in withResolvers.ts
     */
    public static <T> FutureWithResolvers<T> create() {
        CompletableFuture<T> future = new CompletableFuture<>();
        Consumer<T> resolve = future::complete;
        Consumer<Throwable> reject = future::completeExceptionally;
        return new FutureWithResolvers<>(future, resolve, reject);
    }

    public record FutureWithResolvers<T>(
        CompletableFuture<T> future,
        Consumer<T> resolve,
        Consumer<Throwable> reject
    ) {}

    private WithResolvers() {}
}
