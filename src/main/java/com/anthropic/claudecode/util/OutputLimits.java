package com.anthropic.claudecode.util;

/**
 * Output limit utilities.
 * Translated from src/utils/shell/outputLimits.ts
 */
public class OutputLimits {

    public static final int BASH_MAX_OUTPUT_UPPER_LIMIT = 150_000;
    public static final int BASH_MAX_OUTPUT_DEFAULT = 30_000;

    /**
     * Get the maximum output length.
     * Translated from getMaxOutputLength() in outputLimits.ts
     */
    public static int getMaxOutputLength() {
        String envVal = System.getenv("BASH_MAX_OUTPUT_LENGTH");
        if (envVal != null) {
            try {
                int value = Integer.parseInt(envVal);
                if (value > 0 && value <= BASH_MAX_OUTPUT_UPPER_LIMIT) {
                    return value;
                }
            } catch (NumberFormatException e) {
                // Use default
            }
        }
        return BASH_MAX_OUTPUT_DEFAULT;
    }

    private OutputLimits() {}
}
