package com.anthropic.claudecode.util;

import java.util.ArrayList;
import java.util.List;

/**
 * State machine for gating message writes during an initial flush.
 * Translated from src/bridge/flushGate.ts
 *
 * When a bridge session starts, historical messages are flushed to the
 * server via a single HTTP POST. During that flush, new messages must
 * be queued to prevent them from arriving at the server interleaved
 * with the historical messages.
 *
 * Lifecycle:
 *   start()      → enqueue() returns true, items are queued
 *   end()        → returns queued items for draining, enqueue() returns false
 *   drop()       → discards queued items (permanent transport close)
 *   deactivate() → clears active flag without dropping items
 *                  (transport replacement — new transport will drain)
 *
 * @param <T> the type of items being gated
 */
public class BridgeFlushGate<T> {

    private volatile boolean active = false;
    private final List<T> pending = new ArrayList<>();

    /**
     * Returns true if a flush is currently in progress.
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Returns the number of items currently queued.
     */
    public synchronized int getPendingCount() {
        return pending.size();
    }

    /**
     * Mark flush as in-progress. enqueue() will start queuing items.
     */
    public synchronized void start() {
        active = true;
    }

    /**
     * End the flush and return any queued items for draining.
     * Caller is responsible for sending the returned items.
     */
    public synchronized List<T> end() {
        active = false;
        List<T> items = new ArrayList<>(pending);
        pending.clear();
        return items;
    }

    /**
     * If flush is active, queue the items and return true.
     * If flush is not active, return false (caller should send directly).
     */
    @SafeVarargs
    public final synchronized boolean enqueue(T... items) {
        if (!active) return false;
        for (T item : items) {
            pending.add(item);
        }
        return true;
    }

    /**
     * If flush is active, queue a single item and return true.
     * If flush is not active, return false (caller should send directly).
     */
    public synchronized boolean enqueue(T item) {
        if (!active) return false;
        pending.add(item);
        return true;
    }

    /**
     * Enqueue a list of items if flush is active.
     * Returns true if queued, false if caller should send directly.
     */
    public synchronized boolean enqueueAll(List<T> items) {
        if (!active) return false;
        pending.addAll(items);
        return true;
    }

    /**
     * Discard all queued items (permanent transport close).
     * Returns the number of items dropped.
     */
    public synchronized int drop() {
        active = false;
        int count = pending.size();
        pending.clear();
        return count;
    }

    /**
     * Clear the active flag without dropping queued items.
     * Used when the transport is replaced (onWorkReceived) — the new
     * transport's flush will drain the pending items.
     */
    public synchronized void deactivate() {
        active = false;
    }
}
