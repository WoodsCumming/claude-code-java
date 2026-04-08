package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Warning handler for suppressing/tracking JVM warnings.
 * Translated from src/utils/warningHandler.ts
 */
@Slf4j
public class WarningHandler {



    public static final int MAX_WARNING_KEYS = 1000;
    private static final Map<String, Integer> warningCounts = new ConcurrentHashMap<>();

    /**
     * Initialize the warning handler.
     * Translated from initializeWarningHandler() in warningHandler.ts
     */
    public static void initializeWarningHandler() {
        // In Java, we set up a global uncaught exception handler
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            String key = throwable.getClass().getSimpleName() + ": " +
                (throwable.getMessage() != null ? throwable.getMessage().substring(0, Math.min(50, throwable.getMessage().length())) : "");

            if (warningCounts.size() < MAX_WARNING_KEYS) {
                warningCounts.merge(key, 1, Integer::sum);
            }

            log.debug("Uncaught exception in thread {}: {}", thread.getName(), throwable.getMessage());
        });
    }

    /**
     * Reset the warning handler (for testing).
     * Translated from resetWarningHandler() in warningHandler.ts
     */
    public static void resetWarningHandler() {
        warningCounts.clear();
    }

    private WarningHandler() {}
}
