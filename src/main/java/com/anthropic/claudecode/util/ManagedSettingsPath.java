package com.anthropic.claudecode.util;

import java.io.File;

/**
 * Managed settings path utilities.
 * Translated from src/utils/settings/managedPath.ts
 *
 * Gets the path to the managed settings directory.
 */
public class ManagedSettingsPath {

    private static volatile String cachedPath;

    /**
     * Get the managed settings file path.
     * Translated from getManagedFilePath() in managedPath.ts
     */
    public static String getManagedFilePath() {
        if (cachedPath != null) return cachedPath;

        // Allow override for testing
        String userType = System.getenv("USER_TYPE");
        String override = System.getenv("CLAUDE_CODE_MANAGED_SETTINGS_PATH");
        if ("ant".equals(userType) && override != null && !override.isBlank()) {
            cachedPath = override;
            return cachedPath;
        }

        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            cachedPath = "/Library/Application Support/ClaudeCode";
        } else if (os.contains("win")) {
            String programData = System.getenv("ProgramData");
            cachedPath = (programData != null ? programData : "C:/ProgramData") + "/ClaudeCode";
        } else {
            cachedPath = "/etc/claude-code";
        }

        return cachedPath;
    }

    /**
     * Get the managed settings drop-in directory.
     * Translated from getManagedSettingsDropInDir() in managedPath.ts
     */
    public static String getManagedSettingsDropInDir() {
        return getManagedFilePath() + "/settings.d";
    }

    /**
     * Get the managed CLAUDE.md rules directory.
     */
    public static String getManagedClaudeRulesDir() {
        return getManagedFilePath() + "/claude-rules.d";
    }

    private ManagedSettingsPath() {}
}
