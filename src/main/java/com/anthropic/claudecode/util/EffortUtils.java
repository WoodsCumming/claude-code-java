package com.anthropic.claudecode.util;

import com.anthropic.claudecode.model.EffortLevel;

/**
 * Effort level utilities.
 * Translated from src/utils/effort.ts
 *
 * Manages effort level configuration for Claude's thinking.
 */
public class EffortUtils {

    /**
     * Check if a model supports the effort parameter.
     * Translated from modelSupportsEffort() in effort.ts
     */
    public static boolean modelSupportsEffort(String model) {
        if (model == null) return false;

        if (EnvUtils.isEnvTruthy("CLAUDE_CODE_ALWAYS_ENABLE_EFFORT")) {
            return true;
        }

        String m = model.toLowerCase();
        // Supported by Claude 4.6 models
        if (m.contains("opus-4-6") || m.contains("sonnet-4-6")) {
            return true;
        }
        // Exclude legacy models
        if (m.contains("haiku") || m.contains("sonnet") || m.contains("opus")) {
            return false;
        }
        // Default to true for unknown models on first-party
        return ApiProviderUtils.getAPIProvider() == ApiProviderUtils.ApiProvider.FIRST_PARTY;
    }

    /**
     * Check if a model supports 'max' effort.
     * Translated from modelSupportsMaxEffort() in effort.ts
     */
    public static boolean modelSupportsMaxEffort(String model) {
        if (model == null) return false;
        return model.toLowerCase().contains("opus-4-6");
    }

    /**
     * Get the effort env override.
     * Translated from getEffortEnvOverride() in effort.ts
     */
    public static EffortLevel getEffortEnvOverride() {
        String envVal = System.getenv("CLAUDE_CODE_EFFORT");
        if (envVal == null || envVal.isBlank()) return null;
        try {
            return EffortLevel.fromValue(envVal.toLowerCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Get the default effort level for a model.
     */
    public static EffortLevel getDefaultEffort(String model) {
        EffortLevel envOverride = getEffortEnvOverride();
        if (envOverride != null) return envOverride;

        // Default to medium for supported models
        if (modelSupportsEffort(model)) {
            return EffortLevel.MEDIUM;
        }
        return null;
    }

    private EffortUtils() {}
}
