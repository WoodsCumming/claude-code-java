package com.anthropic.claudecode.util;

import java.util.concurrent.*;

/**
 * Abort-responsive sleep and timeout utilities.
 * Translated from src/utils/sleep.ts
 */
public class SleepUtils {

    // -------------------------------------------------------------------------
    // sleep
    // Translated from sleep() in sleep.ts
    // -------------------------------------------------------------------------

    /**
     * Options for {@link #sleep}.
     * Translated from the opts parameter of sleep() in sleep.ts
     */
    public record SleepOptions(boolean throwOnAbort, boolean unref) {
        public static final SleepOptions DEFAULT = new SleepOptions(false, false);
    }

    /**
     * Abort-responsive sleep.
     *
     * Resolves after {@code ms} milliseconds, or completes immediately if the
     * provided {@link CompletableFuture} cancel-token is already done.  Pass
     * a non-null {@code abortSignal} future to get abort behaviour:
     * <ul>
     *   <li>If {@code throwOnAbort} is {@code false} (default) the returned
     *       future completes normally when aborted.</li>
     *   <li>If {@code throwOnAbort} is {@code true} the returned future
     *       completes exceptionally with {@code new CancellationException()}
     *       when aborted.</li>
     * </ul>
     *
     * Translated from sleep() in sleep.ts
     *
     * @param ms          milliseconds to sleep
     * @param abortSignal a future whose completion signals abort; may be null
     * @param throwOnAbort if true, abort causes the returned future to fail
     * @return a CompletableFuture that completes after the sleep or on abort
     */
    public static CompletableFuture<Void> sleep(long ms, CompletableFuture<?> abortSignal,
                                                  boolean throwOnAbort) {
        CompletableFuture<Void> result = new CompletableFuture<>();

        // Check already-aborted state before scheduling
        if (abortSignal != null && abortSignal.isDone()) {
            if (throwOnAbort) {
                result.completeExceptionally(new CancellationException("aborted"));
            } else {
                result.complete(null);
            }
            return result;
        }

        ScheduledFuture<?> timer = SCHEDULER.schedule(() -> result.complete(null), ms,
                TimeUnit.MILLISECONDS);

        if (abortSignal != null) {
            abortSignal.whenComplete((v, ex) -> {
                timer.cancel(false);
                if (throwOnAbort) {
                    result.completeExceptionally(new CancellationException("aborted"));
                } else {
                    result.complete(null);
                }
            });
        }

        return result;
    }

    /**
     * Simple sleep with no abort signal.
     * Translated from sleep() in sleep.ts (no-signal overload)
     */
    public static CompletableFuture<Void> sleep(long ms) {
        return sleep(ms, null, false);
    }

    // -------------------------------------------------------------------------
    // withTimeout
    // Translated from withTimeout() in sleep.ts
    // -------------------------------------------------------------------------

    /**
     * Race a {@link CompletableFuture} against a timeout.
     *
     * Rejects with {@code TimeoutException(message)} if the promise does not
     * settle within {@code ms} milliseconds.  The timeout timer is cancelled
     * when the promise settles (no dangling timer).
     *
     * Note: this does not cancel the underlying work — it only returns control
     * to the caller after the deadline.
     *
     * Translated from withTimeout() in sleep.ts
     *
     * @param <T>     result type
     * @param future  the future to race
     * @param ms      timeout in milliseconds
     * @param message message used in the thrown {@link TimeoutException}
     * @return a future that resolves with the original value or fails with TimeoutException
     */
    public static <T> CompletableFuture<T> withTimeout(CompletableFuture<T> future,
                                                         long ms, String message) {
        CompletableFuture<T> timeout = new CompletableFuture<>();
        ScheduledFuture<?> timer = SCHEDULER.schedule(
                () -> timeout.completeExceptionally(new TimeoutException(message)),
                ms, TimeUnit.MILLISECONDS);

        return future.whenComplete((v, ex) -> timer.cancel(false))
                .applyToEither(timeout, java.util.function.Function.identity());
    }

    // -------------------------------------------------------------------------
    // Shared scheduler
    // -------------------------------------------------------------------------

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sleep-utils-scheduler");
                t.setDaemon(true);
                return t;
            });

    private SleepUtils() {}
}
