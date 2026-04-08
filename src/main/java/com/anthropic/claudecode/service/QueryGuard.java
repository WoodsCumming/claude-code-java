package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/**
 * Query guard for managing query lifecycle.
 * Translated from src/utils/QueryGuard.ts
 *
 * State machine with three states: idle, dispatching, running.
 */
@Slf4j
@Component
public class QueryGuard {



    public enum Status {
        IDLE, DISPATCHING, RUNNING
    }

    private volatile Status status = Status.IDLE;
    private final AtomicInteger generation = new AtomicInteger(0);
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /**
     * Reserve the guard for queue processing.
     * Translated from reserve() in QueryGuard.ts
     */
    public synchronized boolean reserve() {
        if (status != Status.IDLE) return false;
        status = Status.DISPATCHING;
        notifyListeners();
        return true;
    }

    /**
     * Cancel a reservation.
     * Translated from cancelReservation() in QueryGuard.ts
     */
    public synchronized void cancelReservation() {
        if (status != Status.DISPATCHING) return;
        status = Status.IDLE;
        notifyListeners();
    }

    /**
     * Start a query.
     * Translated from tryStart() in QueryGuard.ts
     */
    public synchronized Integer tryStart() {
        if (status == Status.RUNNING) return null;
        status = Status.RUNNING;
        int gen = generation.incrementAndGet();
        notifyListeners();
        return gen;
    }

    /**
     * End a query.
     * Translated from end() in QueryGuard.ts
     */
    public synchronized void end() {
        status = Status.IDLE;
        notifyListeners();
    }

    /**
     * Force end a query.
     * Translated from forceEnd() in QueryGuard.ts
     */
    public synchronized void forceEnd() {
        status = Status.IDLE;
        notifyListeners();
    }

    /**
     * Check if active (dispatching or running).
     * Translated from isActive getter in QueryGuard.ts
     */
    public boolean isActive() {
        return status != Status.IDLE;
    }

    /**
     * Get the current status.
     */
    public Status getStatus() {
        return status;
    }

    /**
     * Get the current generation.
     */
    public int getGeneration() {
        return generation.get();
    }

    /**
     * Subscribe to state changes.
     */
    public Runnable subscribe(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (Exception e) {
                log.warn("Query guard listener failed: {}", e.getMessage());
            }
        }
    }
}
