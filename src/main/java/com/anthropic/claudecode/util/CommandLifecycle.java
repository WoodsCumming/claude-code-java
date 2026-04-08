package com.anthropic.claudecode.util;

import java.util.function.BiConsumer;

/**
 * Command lifecycle tracking.
 * Translated from src/utils/commandLifecycle.ts
 */
public class CommandLifecycle {

    public enum State {
        STARTED,
        COMPLETED
    }

    private static volatile BiConsumer<String, State> listener;

    /**
     * Set the command lifecycle listener.
     * Translated from setCommandLifecycleListener() in commandLifecycle.ts
     */
    public static void setListener(BiConsumer<String, State> cb) {
        listener = cb;
    }

    /**
     * Notify about a command lifecycle event.
     * Translated from notifyCommandLifecycle() in commandLifecycle.ts
     */
    public static void notify(String uuid, State state) {
        BiConsumer<String, State> l = listener;
        if (l != null) {
            l.accept(uuid, state);
        }
    }

    private CommandLifecycle() {}
}
