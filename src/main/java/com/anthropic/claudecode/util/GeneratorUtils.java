package com.anthropic.claudecode.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Async generator / iterator utilities.
 * Translated from src/utils/generators.ts
 */
public class GeneratorUtils {

    // -------------------------------------------------------------------------
    // lastX — consume an iterable and return the last element
    // -------------------------------------------------------------------------

    /**
     * Consumes an iterable and returns the last element.
     * Throws if the iterable is empty.
     *
     * Translated from lastX() in generators.ts
     *
     * @throws NoSuchElementException if no items are produced
     */
    public static <A> A lastX(Iterable<A> items) {
        A last = null;
        boolean hasValue = false;
        for (A item : items) {
            last = item;
            hasValue = true;
        }
        if (!hasValue) {
            throw new NoSuchElementException("No items in generator");
        }
        return last;
    }

    /**
     * Drives a list of CompletableFutures to completion and returns the last value.
     *
     * Translated from lastX() in generators.ts (async variant)
     */
    public static <A> CompletableFuture<A> lastXAsync(List<CompletableFuture<A>> futures) {
        if (futures == null || futures.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new NoSuchElementException("No items in generator"));
        }

        CompletableFuture<A> result = new CompletableFuture<>();
        // Chain all futures sequentially, tracking the last resolved value
        CompletableFuture<A> chain = CompletableFuture.completedFuture(null);
        for (CompletableFuture<A> f : futures) {
            chain = chain.thenCompose(_prev -> f);
        }
        chain.whenComplete((value, err) -> {
            if (err != null) result.completeExceptionally(err);
            else result.complete(value);
        });
        return result;
    }

    // -------------------------------------------------------------------------
    // returnValue — drain a generator and return the generator's return value
    // -------------------------------------------------------------------------

    /**
     * Drives an iterator to completion and returns whatever the terminal call returns.
     *
     * Translated from returnValue() in generators.ts
     *
     * In TypeScript this operates on AsyncGenerator<unknown, A>. Here we
     * accept a Supplier that represents a single "final" computation, since
     * Java iterators do not carry a separate return type.
     *
     * @param finalValueSupplier supplies the return value after all items are consumed
     */
    public static <A> CompletableFuture<A> returnValue(Supplier<CompletableFuture<A>> finalValueSupplier) {
        return finalValueSupplier.get();
    }

    // -------------------------------------------------------------------------
    // all — run generators concurrently up to a concurrency cap
    // -------------------------------------------------------------------------

    /**
     * Runs multiple async suppliers concurrently up to a concurrency cap,
     * collecting all results as they complete.
     *
     * Translated from all() in generators.ts
     *
     * @param suppliers    list of async tasks to run
     * @param concurrencyCap maximum number of tasks running simultaneously
     *                       (use {@link Integer#MAX_VALUE} for unlimited)
     * @return CompletableFuture of all collected results (in completion order)
     */
    public static <A> CompletableFuture<List<A>> all(
            List<Supplier<CompletableFuture<A>>> suppliers,
            int concurrencyCap) {

        if (suppliers == null || suppliers.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        // Use a Semaphore to bound concurrency
        int cap = concurrencyCap <= 0 ? Integer.MAX_VALUE : concurrencyCap;
        Semaphore semaphore = new Semaphore(cap);
        List<CompletableFuture<A>> futures = new ArrayList<>(suppliers.size());

        for (Supplier<CompletableFuture<A>> supplier : suppliers) {
            CompletableFuture<A> future = CompletableFuture.supplyAsync(() -> {
                try {
                    semaphore.acquire();
                    try {
                        return supplier.get().get();
                    } finally {
                        semaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                } catch (ExecutionException e) {
                    throw new RuntimeException(e.getCause());
                }
            });
            futures.add(future);
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(_v -> futures.stream()
                        .map(f -> {
                            try {
                                return f.get();
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .collect(java.util.stream.Collectors.toList()));
    }

    // -------------------------------------------------------------------------
    // toArray — collect all items from an iterable
    // -------------------------------------------------------------------------

    /**
     * Collects all items from an iterable into a list.
     *
     * Translated from toArray() in generators.ts
     */
    public static <A> List<A> toArray(Iterable<A> items) {
        List<A> result = new ArrayList<>();
        for (A item : items) {
            result.add(item);
        }
        return result;
    }

    /**
     * Async variant: drives a CompletableFuture stream and returns all collected items.
     *
     * Translated from toArray() in generators.ts (async variant)
     */
    public static <A> CompletableFuture<List<A>> toArrayAsync(List<CompletableFuture<A>> futures) {
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(_v -> futures.stream()
                        .map(f -> {
                            try { return f.get(); }
                            catch (Exception e) { throw new RuntimeException(e); }
                        })
                        .collect(java.util.stream.Collectors.toList()));
    }

    // -------------------------------------------------------------------------
    // fromArray — wrap a list as an iterable (identity, provided for symmetry)
    // -------------------------------------------------------------------------

    /**
     * Returns the list as-is (Java lists are already Iterable).
     *
     * Translated from fromArray() in generators.ts
     */
    public static <T> Iterable<T> fromArray(List<T> values) {
        return values == null ? List.of() : values;
    }

    private GeneratorUtils() {}
}
