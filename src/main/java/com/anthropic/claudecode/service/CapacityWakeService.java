package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared capacity-wake primitive for bridge poll loops.
 * Translated from src/bridge/capacityWake.ts
 *
 * Both replBridge and bridgeMain need to sleep while "at capacity" but wake
 * early when either:
 *   (a) the outer loop signal aborts (shutdown), or
 *   (b) capacity frees up (session done / transport lost).
 *
 * This service encapsulates the mutable wake-controller and two-signal
 * merger that both poll loops previously duplicated.
 */
@Slf4j
public class CapacityWakeService {



    /**
     * A merged abort signal with a cleanup function to remove listeners.
     */
    public static class CapacitySignal {
        private final AtomicBoolean aborted;
        private final Runnable cleanup;
        private final CompletableFuture<Void> future;

        public CapacitySignal(AtomicBoolean aborted, Runnable cleanup, CompletableFuture<Void> future) {
            this.aborted = aborted;
            this.cleanup = cleanup;
            this.future = future;
        }

        /** Returns true if the signal has been aborted (either outer or wake). */
        public boolean isAborted() {
            return aborted.get();
        }

        /** Returns a future that completes when this signal is aborted. */
        public CompletableFuture<Void> getFuture() {
            return future;
        }

        /** Remove event listeners (call when sleep resolves normally). */
        public void cleanup() {
            if (cleanup != null) {
                cleanup.run();
            }
        }
    }

    /** The outer loop's abort signal (set externally on shutdown). */
    private final AtomicBoolean outerAborted;

    /** Current wake controller — replaced on each wake() call. */
    private final AtomicReference<WakeController> wakeController;

    public CapacityWakeService(AtomicBoolean outerAborted) {
        this.outerAborted = outerAborted;
        this.wakeController = new AtomicReference<>(new WakeController());
    }

    /**
     * Create a signal that aborts when either the outer loop signal or the
     * capacity-wake controller fires. Returns the merged signal and a cleanup
     * function that removes listeners when the sleep resolves normally.
     */
    public CapacitySignal signal() {
        AtomicBoolean merged = new AtomicBoolean(false);
        CompletableFuture<Void> future = new CompletableFuture<>();

        WakeController currentWake = wakeController.get();

        // If already aborted, return immediately
        if (outerAborted.get() || currentWake.isAborted()) {
            merged.set(true);
            future.complete(null);
            return new CapacitySignal(merged, () -> {}, future);
        }

        Runnable abort = () -> {
            if (merged.compareAndSet(false, true)) {
                future.complete(null);
            }
        };

        // Register listeners on both signals
        outerAborted.set(false); // ensure not already set
        currentWake.addListener(abort);

        // Check outer signal via polling future (simplified — in production
        // would use a dedicated interrupt mechanism)
        Runnable outerCleanup = () -> currentWake.removeListener(abort);

        return new CapacitySignal(merged, outerCleanup, future);
    }

    /**
     * Abort the current at-capacity sleep and arm a fresh controller so the
     * poll loop immediately re-checks for new work.
     */
    public void wake() {
        WakeController old = wakeController.getAndSet(new WakeController());
        old.abort();
    }

    /**
     * Abort the outer signal (shutdown).
     */
    public void abortOuter() {
        outerAborted.set(true);
        // Also wake to unblock any pending sleeps
        wake();
    }

    /**
     * Returns true if the outer signal has been aborted.
     */
    public boolean isOuterAborted() {
        return outerAborted.get();
    }

    // --- Internal wake controller ---

    private static class WakeController {
        private final AtomicBoolean aborted = new AtomicBoolean(false);
        private final java.util.List<Runnable> listeners =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        public boolean isAborted() {
            return aborted.get();
        }

        public void addListener(Runnable listener) {
            if (aborted.get()) {
                listener.run();
            } else {
                listeners.add(listener);
            }
        }

        public void removeListener(Runnable listener) {
            listeners.remove(listener);
        }

        public void abort() {
            if (aborted.compareAndSet(false, true)) {
                for (Runnable listener : listeners) {
                    listener.run();
                }
                listeners.clear();
            }
        }
    }

    /**
     * Factory method. Creates a CapacityWakeService bound to a new outer signal.
     */
    public static CapacityWakeService create() {
        return new CapacityWakeService(new AtomicBoolean(false));
    }
}
