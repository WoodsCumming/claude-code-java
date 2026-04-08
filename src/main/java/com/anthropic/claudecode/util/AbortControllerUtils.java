package com.anthropic.claudecode.util;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * Abort controller utilities.
 * Translated from src/utils/abortController.ts
 *
 * Provides abort controller functionality similar to the Web API AbortController.
 */
public class AbortControllerUtils {

    /**
     * Simple abort controller implementation.
     * Translated from createAbortController() in abortController.ts
     */
    public static class AbortController {
        private final AtomicBoolean aborted = new AtomicBoolean(false);
        private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
        private String reason;

        /**
         * Abort the controller.
         */
        public void abort() {
            abort("aborted");
        }

        public void abort(String reason) {
            if (aborted.compareAndSet(false, true)) {
                this.reason = reason;
                for (Runnable listener : listeners) {
                    try {
                        listener.run();
                    } catch (Exception e) {
                        // Ignore listener errors
                    }
                }
                listeners.clear();
            }
        }

        /**
         * Check if aborted.
         */
        public boolean isAborted() {
            return aborted.get();
        }

        /**
         * Get abort reason.
         */
        public String getReason() {
            return reason;
        }

        /**
         * Add an abort listener.
         */
        public void addAbortListener(Runnable listener) {
            if (isAborted()) {
                listener.run();
            } else {
                listeners.add(listener);
            }
        }

        /**
         * Remove an abort listener.
         */
        public void removeAbortListener(Runnable listener) {
            listeners.remove(listener);
        }

        /**
         * Subscribe to abort events. Returns a Runnable that unsubscribes.
         * The consumer receives {@code null} as the abort event payload.
         */
        public Runnable subscribe(java.util.function.Consumer<Object> listener) {
            Runnable r = () -> listener.accept(null);
            addAbortListener(r);
            return () -> removeAbortListener(r);
        }
    }

    /**
     * Create an abort controller.
     * Translated from createAbortController() in abortController.ts
     */
    public static AbortController create() {
        return new AbortController();
    }

    /**
     * Create a child abort controller that aborts when parent aborts.
     * Translated from createChildAbortController() in abortController.ts
     */
    public static AbortController createChild(AbortController parent) {
        AbortController child = create();

        if (parent.isAborted()) {
            child.abort(parent.getReason());
            return child;
        }

        parent.addAbortListener(() -> child.abort(parent.getReason()));

        return child;
    }

    private AbortControllerUtils() {}
}
