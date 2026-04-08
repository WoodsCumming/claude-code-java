package com.anthropic.claudecode.util;

/**
 * Context window utilities.
 * Translated from src/utils/context.ts
 *
 * Provides utilities for managing model context windows.
 */
public class ContextUtils {

    // Model context window size (200k tokens for all models right now)
    public static final int MODEL_CONTEXT_WINDOW_DEFAULT = 200_000;

    // Maximum output tokens for compact operations
    public static final int COMPACT_MAX_OUTPUT_TOKENS = 20_000;

    // Default max output tokens
    public static final int MAX_OUTPUT_TOKENS_DEFAULT = 32_000;
    public static final int MAX_OUTPUT_TOKENS_UPPER_LIMIT = 64_000;

    // Capped default for slot-reservation optimization
    public static final int CAPPED_DEFAULT_MAX_TOKENS = 8_000;
    public static final int ESCALATED_MAX_TOKENS = 64_000;

    /**
     * Check if 1M context is disabled.
     * Translated from is1mContextDisabled() in context.ts
     */
    public static boolean is1mContextDisabled() {
        return EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_DISABLE_1M_CONTEXT"));
    }

    /**
     * Check if a model has 1M context via the [1m] suffix.
     * Translated from has1mContext() in context.ts
     */
    public static boolean has1mContext(String model) {
        if (is1mContextDisabled()) return false;
        return model != null && model.toLowerCase().contains("[1m]");
    }

    /**
     * Check if a model supports 1M context.
     * Translated from modelSupports1M() in context.ts
     */
    public static boolean modelSupports1M(String model) {
        if (is1mContextDisabled()) return false;
        if (model == null) return false;
        String lower = model.toLowerCase();
        return lower.contains("claude-sonnet-4") || lower.contains("opus-4-6");
    }

    /**
     * Get the context window size for a model.
     * Translated from getContextWindowForModel() in context.ts
     */
    public static int getContextWindowForModel(String model) {
        if (model == null) return MODEL_CONTEXT_WINDOW_DEFAULT;

        // Check for 1M context
        if (has1mContext(model)) {
            return 1_048_576; // 1M tokens
        }

        // Default to 200k for all models
        return MODEL_CONTEXT_WINDOW_DEFAULT;
    }

    /**
     * Get the max output tokens for a model.
     * Translated from getModelMaxOutputTokens() in context.ts
     */
    public static int getModelMaxOutputTokens(String model) {
        if (model == null) return MAX_OUTPUT_TOKENS_DEFAULT;

        // Some models support higher output tokens
        String lower = model.toLowerCase();
        if (lower.contains("opus-4-6") || lower.contains("sonnet-4-6")) {
            return MAX_OUTPUT_TOKENS_UPPER_LIMIT;
        }
        return MAX_OUTPUT_TOKENS_DEFAULT;
    }

    private ContextUtils() {}
}
