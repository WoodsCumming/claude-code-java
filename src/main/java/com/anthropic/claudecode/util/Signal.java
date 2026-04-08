package com.anthropic.claudecode.util;

import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * Event signal primitive.
 * Translated from src/utils/signal.ts
 *
 * A simple listener-set for pure event signals (no stored state).
 */
public class Signal<T> {

    private final Set<Consumer<T>> listeners = new CopyOnWriteArraySet<>();

    /**
     * Subscribe a listener. Returns an unsubscribe function.
     * Translated from Signal.subscribe() in signal.ts
     */
    public Runnable subscribe(Consumer<T> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /**
     * Emit an event to all listeners.
     * Translated from Signal.emit() in signal.ts
     */
    public void emit(T value) {
        for (Consumer<T> listener : listeners) {
            try {
                listener.accept(value);
            } catch (Exception e) {
                // Don't let one listener failure affect others
            }
        }
    }

    /**
     * Remove all listeners.
     * Translated from Signal.clear() in signal.ts
     */
    public void clear() {
        listeners.clear();
    }

    /**
     * Create a no-arg signal.
     */
    public static Signal<Void> create() {
        return new Signal<>();
    }

    /**
     * Emit with no value.
     */
    public void emit() {
        emit(null);
    }
}
