package com.anthropic.claudecode.util;

/**
 * FileRead tool output limits.
 * Translated from src/tools/FileReadTool/limits.ts
 */
public class FileReadLimits {

    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 25_000;
    public static final long MAX_SIZE_BYTES = 256 * 1024; // 256 KB

    /**
     * Get the maximum output tokens for file reads.
     * Translated from getMaxOutputTokens() in limits.ts
     */
    public static int getMaxOutputTokens() {
        String override = System.getenv("CLAUDE_CODE_FILE_READ_MAX_OUTPUT_TOKENS");
        if (override != null) {
            try {
                int parsed = Integer.parseInt(override);
                if (parsed > 0) return parsed;
            } catch (NumberFormatException ignored) {}
        }
        return DEFAULT_MAX_OUTPUT_TOKENS;
    }

    /**
     * Get the maximum file size in bytes.
     * Translated from getMaxSizeBytes() in limits.ts
     */
    public static long getMaxSizeBytes() {
        return MAX_SIZE_BYTES;
    }

    private FileReadLimits() {}
}
