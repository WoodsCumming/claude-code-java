package com.anthropic.claudecode.util.ink.events;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Java equivalent of Ink's EventEmitter (src/ink/events/emitter.ts).
 *
 * <p>Similar to Node's built-in EventEmitter, but also aware of our {@link InkEvent} class, so
 * {@link #emit} respects {@code stopImmediatePropagation()}. No max-listener limit is enforced
 * because many components (e.g. useInput hooks) can legitimately subscribe to the same event.
 *
 * <p>Thread-safe: uses {@link CopyOnWriteArrayList} per event type so listeners can be added or
 * removed during iteration without {@link java.util.ConcurrentModificationException}.
 */
public class EventEmitter {

    private final Map<String, CopyOnWriteArrayList<Consumer<Object>>> listeners =
            new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Subscription
    // -------------------------------------------------------------------------

    /**
     * Register {@code listener} for {@code eventType}. Multiple registrations of the same listener
     * are allowed (mirrors Node behaviour where duplicates produce duplicate invocations).
     */
    public void on(String eventType, Consumer<Object> listener) {
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /**
     * Remove the first occurrence of {@code listener} for {@code eventType}.
     */
    public void removeListener(String eventType, Consumer<Object> listener) {
        CopyOnWriteArrayList<Consumer<Object>> list = listeners.get(eventType);
        if (list != null) {
            list.remove(listener);
        }
    }

    /**
     * Remove all listeners for {@code eventType}.
     */
    public void removeAllListeners(String eventType) {
        listeners.remove(eventType);
    }

    // -------------------------------------------------------------------------
    // Emission
    // -------------------------------------------------------------------------

    /**
     * Emit an event, calling each registered listener in registration order. If the argument is an
     * {@link InkEvent} and {@link InkEvent#didStopImmediatePropagation()} returns {@code true} after
     * a listener, iteration stops immediately.
     *
     * <p>The special {@code "error"} event bypasses the propagation check (mirrors Node's behaviour
     * of rethrowing unhandled error events).
     *
     * @return {@code true} if at least one listener was called, {@code false} otherwise
     */
    public boolean emit(String eventType, Object... args) {
        Object arg = args.length > 0 ? args[0] : null;

        if ("error".equals(eventType)) {
            // Rethrow unhandled error events, just as Node does.
            CopyOnWriteArrayList<Consumer<Object>> errorListeners = listeners.get("error");
            if (errorListeners == null || errorListeners.isEmpty()) {
                if (arg instanceof RuntimeException re) throw re;
                if (arg instanceof Throwable t) throw new RuntimeException(t);
                throw new RuntimeException("Unhandled 'error' event: " + arg);
            }
            for (Consumer<Object> l : errorListeners) {
                l.accept(arg);
            }
            return true;
        }

        CopyOnWriteArrayList<Consumer<Object>> list = listeners.get(eventType);
        if (list == null || list.isEmpty()) {
            return false;
        }

        InkEvent inkEvent = (arg instanceof InkEvent ie) ? ie : null;

        for (Consumer<Object> listener : list) {
            listener.accept(arg);
            if (inkEvent != null && inkEvent.didStopImmediatePropagation()) {
                break;
            }
        }

        return true;
    }

    // -------------------------------------------------------------------------
    // Introspection helpers
    // -------------------------------------------------------------------------

    /**
     * Returns a snapshot of all listeners currently registered for {@code eventType}. Mutating the
     * returned list has no effect on the emitter's internal state.
     */
    public List<Consumer<Object>> rawListeners(String eventType) {
        CopyOnWriteArrayList<Consumer<Object>> list = listeners.get(eventType);
        return list == null ? List.of() : new ArrayList<>(list);
    }

    /** Returns the number of listeners registered for {@code eventType}. */
    public int listenerCount(String eventType) {
        CopyOnWriteArrayList<Consumer<Object>> list = listeners.get(eventType);
        return list == null ? 0 : list.size();
    }
}
