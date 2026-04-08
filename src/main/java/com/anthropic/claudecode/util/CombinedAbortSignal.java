package com.anthropic.claudecode.util;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Combined abort signal utilities.
 * Translated from src/utils/combinedAbortSignal.ts
 *
 * Creates a combined abort controller that aborts when any of the inputs abort
 * or a timeout elapses.
 */
public class CombinedAbortSignal {

    /**
     * Create a combined abort controller.
     * Translated from createCombinedAbortSignal() in combinedAbortSignal.ts
     */
    public static CombinedResult createCombinedAbortSignal(
            AbortControllerUtils.AbortController signal,
            AbortControllerUtils.AbortController signalB,
            Long timeoutMs) {

        AbortControllerUtils.AbortController combined = new AbortControllerUtils.AbortController();

        // Check if already aborted
        if ((signal != null && signal.isAborted()) || (signalB != null && signalB.isAborted())) {
            combined.abort();
            return new CombinedResult(combined, () -> {});
        }

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?>[] timerRef = new ScheduledFuture<?>[1];

        Runnable abortCombined = () -> {
            if (timerRef[0] != null) timerRef[0].cancel(false);
            combined.abort();
        };

        // Set up timeout
        if (timeoutMs != null && timeoutMs > 0) {
            timerRef[0] = scheduler.schedule(abortCombined, timeoutMs, TimeUnit.MILLISECONDS);
        }

        // Subscribe to parent signals
        Runnable unsubA = signal != null ? signal.subscribe(ignored -> abortCombined.run()) : () -> {};
        Runnable unsubB = signalB != null ? signalB.subscribe(ignored -> abortCombined.run()) : () -> {};

        Runnable cleanup = () -> {
            if (timerRef[0] != null) timerRef[0].cancel(false);
            scheduler.shutdown();
            unsubA.run();
            unsubB.run();
        };

        return new CombinedResult(combined, cleanup);
    }

    public record CombinedResult(
        AbortControllerUtils.AbortController controller,
        Runnable cleanup
    ) {}

    private CombinedAbortSignal() {}
}
