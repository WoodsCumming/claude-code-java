package com.anthropic.claudecode.util.ink;

import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java equivalent of Ink's instances.ts (src/ink/instances.ts).
 *
 * <p>Stores all active Ink rendering instances, keyed by their output stream (analogous to
 * Node's {@code NodeJS.WriteStream}). This ensures that consecutive {@code render()} calls reuse
 * the same instance rather than creating a new one.
 *
 * <p>The registry is a singleton module-level map. An Ink instance removes itself from this map
 * when it unmounts.
 *
 * <p>The type parameter {@code <I>} is intentionally left as {@code Object} to avoid a circular
 * dependency on a hypothetical {@code Ink} class. Callers should cast to the appropriate type.
 *
 * <h3>Usage</h3>
 *
 * <pre>{@code
 * // Register an instance
 * InkInstances.put(outputStream, inkInstance);
 *
 * // Look up an existing instance for a stream
 * Optional<Object> existing = InkInstances.get(outputStream);
 *
 * // Remove on unmount
 * InkInstances.remove(outputStream);
 * }</pre>
 */
public final class InkInstances {

    /**
     * The backing map: output stream → Ink instance.
     * Mirrors {@code const instances = new Map<NodeJS.WriteStream, Ink>()}.
     */
    private static final Map<OutputStream, Object> INSTANCES = new ConcurrentHashMap<>();

    private InkInstances() {}

    // -------------------------------------------------------------------------
    // Map operations
    // -------------------------------------------------------------------------

    /**
     * Register an Ink instance for the given output stream.
     *
     * @param stream   the output stream the instance writes to
     * @param instance the Ink instance
     */
    public static void put(OutputStream stream, Object instance) {
        INSTANCES.put(stream, instance);
    }

    /**
     * Retrieve the Ink instance registered for the given stream, or {@code null} if none.
     *
     * @param stream the output stream
     * @return the registered instance, or {@code null}
     */
    public static Object get(OutputStream stream) {
        return INSTANCES.get(stream);
    }

    /**
     * Returns {@code true} if an instance is registered for the given stream.
     */
    public static boolean contains(OutputStream stream) {
        return INSTANCES.containsKey(stream);
    }

    /**
     * Remove the instance registered for the given stream. Called by an Ink instance on unmount.
     *
     * @param stream the output stream to deregister
     */
    public static void remove(OutputStream stream) {
        INSTANCES.remove(stream);
    }

    /**
     * Returns the number of currently registered instances.
     */
    public static int size() {
        return INSTANCES.size();
    }

    /**
     * Clear all registered instances. Primarily useful for testing.
     */
    public static void clear() {
        INSTANCES.clear();
    }
}
