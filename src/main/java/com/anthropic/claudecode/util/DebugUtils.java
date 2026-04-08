package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Debug logging utilities.
 * Translated from src/utils/debug.ts
 */
@Slf4j
public class DebugUtils {



    public enum DebugLogLevel {
        VERBOSE(0), DEBUG(1), INFO(2), WARN(3), ERROR(4);

        private final int order;
        DebugLogLevel(int order) { this.order = order; }
        public int getOrder() { return order; }
    }

    private static volatile boolean debugEnabled = false;
    private static volatile boolean debugToStderr = false;

    /**
     * Check if debug mode is enabled.
     * Translated from isDebugMode() in debug.ts
     */
    public static boolean isDebugMode() {
        return debugEnabled
            || EnvUtils.isEnvTruthy(System.getenv("DEBUG"))
            || EnvUtils.isEnvTruthy(System.getenv("DEBUG_SDK"));
    }

    /**
     * Check if debug output goes to stderr.
     * Translated from isDebugToStdErr() in debug.ts
     */
    public static boolean isDebugToStdErr() {
        return debugToStderr
            || EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_DEBUG_STDERR"));
    }

    /**
     * Enable debug mode.
     */
    public static void enableDebug() {
        debugEnabled = true;
    }

    /**
     * Log a debug message.
     * Translated from logForDebugging() in debug.ts
     */
    public static void logForDebugging(String message) {
        if (!isDebugMode()) return;

        if (isDebugToStdErr()) {
            System.err.println("[DEBUG] " + message);
        } else {
            log.debug("{}", message);
        }
    }

    /**
     * Log an error message.
     * Translated from logAntError() in debug.ts
     */
    public static void logAntError(String message, Throwable error) {
        if (isDebugToStdErr()) {
            System.err.println("[ERROR] " + message + ": " + (error != null ? error.getMessage() : ""));
        } else {
            log.error("{}: {}", message, error != null ? error.getMessage() : "");
        }
    }

    private DebugUtils() {}
}
