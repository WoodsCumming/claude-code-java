package com.anthropic.claudecode.util;

/**
 * Utilities for inference-config command immediacy.
 * Translated from src/utils/immediateCommand.ts
 */
public class ImmediateCommandUtils {

    private ImmediateCommandUtils() {}

    /**
     * Whether inference-config commands (/model, /fast, /effort) should execute
     * immediately (during a running query) rather than waiting for the current
     * turn to finish.
     *
     * Always enabled for Anthropic employees; gated by feature flag for external users.
     * Translated from shouldInferenceConfigCommandBeImmediate() in immediateCommand.ts
     *
     * @return {@code true} if inference-config commands should execute immediately
     */
    public static boolean shouldInferenceConfigCommandBeImmediate() {
        // Always on for internal Anthropic users
        String userType = System.getenv("USER_TYPE");
        if ("ant".equals(userType)) {
            return true;
        }

        // For external users, check the feature flag
        // In a full implementation this would call the GrowthBook feature flag service:
        // return FeatureFlagService.getFeatureValue("tengu_immediate_model_command", false)
        return false;
    }
}
