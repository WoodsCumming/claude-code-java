package com.anthropic.claudecode.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Internal writes tracker for settings files.
 * Translated from src/utils/settings/internalWrites.ts
 *
 * Tracks timestamps of in-process settings-file writes so the file-watcher can
 * ignore its own echoes.  Callers pass resolved paths.
 */
public final class InternalWritesTracker {

    private static final Map<String, Long> timestamps = new ConcurrentHashMap<>();

    private InternalWritesTracker() {}

    /**
     * Record that we are about to write {@code path} internally.
     * Translated from markInternalWrite() in internalWrites.ts
     *
     * @param path the resolved absolute path that is being written
     */
    public static void markInternalWrite(String path) {
        timestamps.put(path, System.currentTimeMillis());
    }

    /**
     * Returns {@code true} if {@code path} was marked within {@code windowMs} and
     * <em>consumes</em> the mark on match — so the next (real, external) change to the
     * same file is not suppressed.
     * Translated from consumeInternalWrite() in internalWrites.ts
     *
     * @param path     the resolved absolute path to check
     * @param windowMs the maximum age of the mark (in milliseconds) to consider a match
     * @return true if the write was ours and happened within the window
     */
    public static boolean consumeInternalWrite(String path, long windowMs) {
        Long ts = timestamps.get(path);
        if (ts != null && System.currentTimeMillis() - ts < windowMs) {
            timestamps.remove(path);
            return true;
        }
        return false;
    }

    /**
     * Clear all pending internal-write timestamps.
     * Translated from clearInternalWrites() in internalWrites.ts
     */
    public static void clearInternalWrites() {
        timestamps.clear();
    }
}
