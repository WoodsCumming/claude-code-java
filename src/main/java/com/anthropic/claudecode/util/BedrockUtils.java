package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.util.*;

/**
 * AWS Bedrock utilities.
 * Translated from src/utils/model/bedrock.ts
 */
@Slf4j
public class BedrockUtils {



    /**
     * Find the first matching inference profile.
     * Translated from findFirstMatch() in bedrock.ts
     */
    public static Optional<String> findFirstMatch(List<String> profiles, String substring) {
        if (profiles == null || substring == null) return Optional.empty();
        return profiles.stream()
            .filter(p -> p.contains(substring))
            .findFirst();
    }

    /**
     * Get the inference profile backing model.
     * Translated from getInferenceProfileBackingModel() in bedrock.ts
     */
    public static String getInferenceProfileBackingModel(String model) {
        // For inference profiles, extract the base model
        if (model != null && model.contains(".")) {
            // e.g., "eu.anthropic.claude-opus-4-6-v1" -> "anthropic.claude-opus-4-6-v1:0"
            int lastDot = model.lastIndexOf(".");
            String prefix = model.substring(0, lastDot + 1);
            String suffix = model.substring(lastDot + 1);
            return suffix; // Return the canonical part
        }
        return model;
    }

    /**
     * Check if a model is a foundation model (not an inference profile).
     * Translated from isFoundationModel() in bedrock.ts
     */
    public static boolean isFoundationModel(String model) {
        if (model == null) return false;
        // Foundation models don't have cross-region prefixes like "eu." or "us."
        return !model.matches("^[a-z]{2}\\..*");
    }

    private BedrockUtils() {}
}
