package com.anthropic.claudecode.util;

import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.*;

/**
 * Simple reactive state store.
 * Translated from src/state/store.ts
 *
 * A minimal reactive state store that supports get, set, and subscribe.
 */
public class Store<T> {

    private volatile T state;
    private final Set<Runnable> listeners = new CopyOnWriteArraySet<>();
    private final BiConsumer<T, T> onChange;

    public Store(T initialState) {
        this(initialState, null);
    }

    public Store(T initialState, BiConsumer<T, T> onChange) {
        this.state = initialState;
        this.onChange = onChange;
    }

    /**
     * Get the current state.
     * Translated from Store.getState() in store.ts
     */
    public T getState() {
        return state;
    }

    /**
     * Update the state.
     * Translated from Store.setState() in store.ts
     */
    public void setState(UnaryOperator<T> updater) {
        T prev = state;
        T next = updater.apply(prev);
        if (next == prev) return; // No change

        state = next;
        if (onChange != null) {
            onChange.accept(next, prev);
        }
        listeners.forEach(Runnable::run);
    }

    /**
     * Subscribe to state changes.
     * Translated from Store.subscribe() in store.ts
     *
     * @return Unsubscribe function
     */
    public Runnable subscribe(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /**
     * Create a new store with initial state.
     * Translated from createStore() in store.ts
     */
    public static <T> Store<T> create(T initialState) {
        return new Store<>(initialState);
    }

    public static <T> Store<T> create(T initialState, BiConsumer<T, T> onChange) {
        return new Store<>(initialState, onChange);
    }
}
