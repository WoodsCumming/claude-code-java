package com.anthropic.claudecode.util;

/**
 * Undercover mode utilities.
 * Translated from src/utils/undercover.ts
 *
 * Undercover mode adds safety instructions for contributing to public repos.
 * In external builds, this always returns false/empty.
 */
public class UndercoverMode {

    /**
     * Check if undercover mode is active.
     * Translated from isUndercover() in undercover.ts
     */
    public static boolean isUndercover() {
        // External builds: always false
        return false;
    }

    /**
     * Get undercover mode instructions.
     * Translated from getUndercoverInstructions() in undercover.ts
     */
    public static String getUndercoverInstructions() {
        // External builds: always empty
        return "";
    }

    /**
     * Check if undercover auto notice should be shown.
     * Translated from shouldShowUndercoverAutoNotice() in undercover.ts
     */
    public static boolean shouldShowUndercoverAutoNotice() {
        // External builds: always false
        return false;
    }

    private UndercoverMode() {}
}
