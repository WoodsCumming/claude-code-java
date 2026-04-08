package com.anthropic.claudecode.util;

import java.io.File;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Environment utility functions.
 * Translated from src/utils/envUtils.ts
 */
public class EnvUtils {

    /**
     * Get the Claude config home directory.
     * Translated from getClaudeConfigHomeDir() in envUtils.ts
     */
    public static String getClaudeConfigHomeDir() {
        String envDir = System.getenv("CLAUDE_CONFIG_DIR");
        if (envDir != null && !envDir.isBlank()) return envDir;
        return System.getProperty("user.home") + "/.claude";
    }

    /**
     * Check if an environment variable is truthy.
     * Translated from isEnvTruthy() in envUtils.ts
     */
    public static boolean isEnvTruthy(String envVar) {
        if (envVar == null) return false;
        String normalized = envVar.toLowerCase().trim();
        return Set.of("1", "true", "yes", "on").contains(normalized);
    }

    /**
     * Check if an environment variable is falsy (explicitly set to false).
     * Translated from isEnvDefinedFalsy() in envUtils.ts
     */
    public static boolean isEnvDefinedFalsy(String envVar) {
        if (envVar == null) return false;
        String normalized = envVar.toLowerCase().trim();
        return Set.of("0", "false", "no", "off").contains(normalized);
    }

    /**
     * Check if running in bare mode.
     * Translated from isBareMode() in envUtils.ts
     */
    public static boolean isBareMode() {
        return isEnvTruthy(System.getenv("CLAUDE_CODE_SIMPLE"));
    }

    /**
     * Get the AWS region.
     * Translated from getAWSRegion() in envUtils.ts
     */
    public static String getAWSRegion() {
        String region = System.getenv("AWS_REGION");
        if (region != null) return region;
        region = System.getenv("AWS_DEFAULT_REGION");
        if (region != null) return region;
        return "us-east-1";
    }

    /**
     * Get the teams directory.
     * Translated from getTeamsDir() in envUtils.ts
     */
    public static String getTeamsDir() {
        return getClaudeConfigHomeDir() + "/teams";
    }

    private EnvUtils() {}
}
