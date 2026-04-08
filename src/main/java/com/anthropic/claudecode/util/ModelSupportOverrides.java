package com.anthropic.claudecode.util;

import java.util.*;

/**
 * Model capability override utilities for 3P providers.
 * Translated from src/utils/model/modelSupportOverrides.ts
 *
 * Allows overriding model capabilities for 3P provider models via environment variables.
 */
public class ModelSupportOverrides {

    private static final String[][] TIERS = {
        {"ANTHROPIC_DEFAULT_OPUS_MODEL", "ANTHROPIC_DEFAULT_OPUS_MODEL_SUPPORTED_CAPABILITIES"},
        {"ANTHROPIC_DEFAULT_SONNET_MODEL", "ANTHROPIC_DEFAULT_SONNET_MODEL_SUPPORTED_CAPABILITIES"},
        {"ANTHROPIC_DEFAULT_HAIKU_MODEL", "ANTHROPIC_DEFAULT_HAIKU_MODEL_SUPPORTED_CAPABILITIES"},
    };

    /**
     * Check whether a 3P model capability override is set.
     * Translated from get3PModelCapabilityOverride() in modelSupportOverrides.ts
     *
     * @return true/false if override exists, null if no override
     */
    public static Boolean get3PModelCapabilityOverride(String model, String capability) {
        if (ApiProviderUtils.getAPIProvider() == ApiProviderUtils.ApiProvider.FIRST_PARTY) {
            return null;
        }

        String lowerModel = model.toLowerCase();

        for (String[] tier : TIERS) {
            String pinnedModel = System.getenv(tier[0]);
            String capabilities = System.getenv(tier[1]);

            if (pinnedModel == null || capabilities == null) continue;
            if (!lowerModel.equals(pinnedModel.toLowerCase())) continue;

            return Arrays.asList(capabilities.toLowerCase().split(",")).stream()
                .map(String::trim)
                .anyMatch(c -> c.equals(capability));
        }

        return null;
    }

    private ModelSupportOverrides() {}
}
