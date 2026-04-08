package com.anthropic.claudecode.util;

/**
 * Timeout constants and helpers for bash operations.
 * Translated from src/utils/timeouts.ts
 *
 * <p>Reads {@code BASH_DEFAULT_TIMEOUT_MS} and {@code BASH_MAX_TIMEOUT_MS}
 * environment variables; falls back to 2 minutes and 10 minutes respectively.
 */
public class TimeoutsUtils {

    /** Default bash timeout: 2 minutes (matches TypeScript DEFAULT_TIMEOUT_MS). */
    public static final long DEFAULT_TIMEOUT_MS = 120_000L;

    /** Maximum bash timeout: 10 minutes (matches TypeScript MAX_TIMEOUT_MS). */
    public static final long MAX_TIMEOUT_MS = 600_000L;

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Get the default timeout for bash operations in milliseconds.
     *
     * <p>Checks the {@code BASH_DEFAULT_TIMEOUT_MS} environment variable first.
     * If absent or invalid, returns {@link #DEFAULT_TIMEOUT_MS} (2 minutes).
     *
     * Translated from {@code getDefaultBashTimeoutMs()} in timeouts.ts.
     *
     * @return timeout in milliseconds
     */
    public static long getDefaultBashTimeoutMs() {
        return getDefaultBashTimeoutMs(null);
    }

    /**
     * Get the default timeout for bash operations in milliseconds.
     *
     * @param env environment variable map to check; pass {@code null} to use
     *            {@link System#getenv(String)}
     * @return timeout in milliseconds
     */
    public static long getDefaultBashTimeoutMs(java.util.Map<String, String> env) {
        String envValue = (env != null)
                ? env.get("BASH_DEFAULT_TIMEOUT_MS")
                : System.getenv("BASH_DEFAULT_TIMEOUT_MS");

        if (envValue != null && !envValue.isBlank()) {
            try {
                long parsed = Long.parseLong(envValue.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return DEFAULT_TIMEOUT_MS;
    }

    /**
     * Get the maximum timeout for bash operations in milliseconds.
     *
     * <p>Checks the {@code BASH_MAX_TIMEOUT_MS} environment variable first.
     * The result is always at least as large as {@link #getDefaultBashTimeoutMs()}.
     *
     * Translated from {@code getMaxBashTimeoutMs()} in timeouts.ts.
     *
     * @return timeout in milliseconds
     */
    public static long getMaxBashTimeoutMs() {
        return getMaxBashTimeoutMs(null);
    }

    /**
     * Get the maximum timeout for bash operations in milliseconds.
     *
     * @param env environment variable map to check; pass {@code null} to use
     *            {@link System#getenv(String)}
     * @return timeout in milliseconds, always &gt;= {@link #getDefaultBashTimeoutMs(Map)}
     */
    public static long getMaxBashTimeoutMs(java.util.Map<String, String> env) {
        long defaultTimeout = getDefaultBashTimeoutMs(env);

        String envValue = (env != null)
                ? env.get("BASH_MAX_TIMEOUT_MS")
                : System.getenv("BASH_MAX_TIMEOUT_MS");

        if (envValue != null && !envValue.isBlank()) {
            try {
                long parsed = Long.parseLong(envValue.trim());
                if (parsed > 0) {
                    // Ensure max is at least as large as the default
                    return Math.max(parsed, defaultTimeout);
                }
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }

        // Always ensure max is at least as large as the default
        return Math.max(MAX_TIMEOUT_MS, defaultTimeout);
    }

    private TimeoutsUtils() {}
}
