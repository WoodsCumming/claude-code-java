package com.anthropic.claudecode.util;

/**
 * Git-related behaviors that depend on user settings.
 *
 * This is separate from GitUtils because GitUtils must stay free of settings
 * dependencies. Mirrors the same separation used in git.ts vs gitSettings.ts.
 *
 * Translated from src/utils/gitSettings.ts
 */
public class GitSettings {

    /**
     * Check if git instructions should be included in the system prompt.
     * Reads CLAUDE_CODE_DISABLE_GIT_INSTRUCTIONS env var first, then falls
     * back to the initial settings value (default: true).
     * Translated from shouldIncludeGitInstructions() in gitSettings.ts
     */
    public static boolean shouldIncludeGitInstructions() {
        String envVal = System.getenv("CLAUDE_CODE_DISABLE_GIT_INSTRUCTIONS");

        if (EnvUtils.isEnvTruthy(envVal)) return false;
        if (EnvUtils.isEnvDefinedFalsy(envVal)) return true;

        // Fall back to settings; default is true (include git instructions)
        return true;
    }

    private GitSettings() {}
}
