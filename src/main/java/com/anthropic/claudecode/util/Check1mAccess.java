package com.anthropic.claudecode.util;

/**
 * 1M context access check utilities.
 * Translated from src/utils/model/check1mAccess.ts
 *
 * Checks if users have access to 1M context window models.
 */
public class Check1mAccess {

    /**
     * Check if Opus 1M context access is available.
     * Translated from checkOpus1mAccess() in check1mAccess.ts
     */
    public static boolean checkOpus1mAccess() {
        if (ContextUtils.is1mContextDisabled()) return false;

        // API key users always have access
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) return true;

        // Check for subscriber with extra usage
        return isExtraUsageEnabled();
    }

    /**
     * Check if Sonnet 1M context access is available.
     * Translated from checkSonnet1mAccess() in check1mAccess.ts
     */
    public static boolean checkSonnet1mAccess() {
        if (ContextUtils.is1mContextDisabled()) return false;

        // API key users always have access
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) return true;

        // Check for subscriber with extra usage
        return isExtraUsageEnabled();
    }

    /**
     * Check if extra usage (overages) is enabled.
     * Simplified version - full implementation checks cached API response.
     */
    private static boolean isExtraUsageEnabled() {
        // Check if extra usage is explicitly enabled via env var
        String extraUsage = System.getenv("CLAUDE_CODE_EXTRA_USAGE_ENABLED");
        if (extraUsage != null) {
            return EnvUtils.isEnvTruthy(extraUsage);
        }
        // Default: not enabled for subscribers
        return false;
    }

    private Check1mAccess() {}
}
