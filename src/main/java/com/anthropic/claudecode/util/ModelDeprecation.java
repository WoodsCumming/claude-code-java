package com.anthropic.claudecode.util;

import java.util.*;

/**
 * Model deprecation utilities.
 * Translated from src/utils/model/deprecation.ts
 *
 * Contains information about deprecated models and their retirement dates.
 */
public class ModelDeprecation {

    public record DeprecationEntry(String modelName, Map<String, String> retirementDates) {}

    /**
     * Deprecated models and their retirement dates by provider.
     * Keys are substrings to match in model IDs (case-insensitive).
     */
    public static final Map<String, DeprecationEntry> DEPRECATED_MODELS = Map.of(
        "claude-3-opus", new DeprecationEntry(
            "Claude 3 Opus",
            Map.of(
                "firstParty", "January 5, 2026",
                "bedrock", "January 15, 2026",
                "vertex", "January 5, 2026",
                "foundry", "January 5, 2026"
            )
        ),
        "claude-3-7-sonnet", new DeprecationEntry(
            "Claude 3.7 Sonnet",
            Map.of(
                "firstParty", "February 19, 2026",
                "bedrock", "April 28, 2026",
                "vertex", "May 11, 2026",
                "foundry", "February 19, 2026"
            )
        ),
        "claude-3-5-haiku", new DeprecationEntry(
            "Claude 3.5 Haiku",
            Map.of(
                "firstParty", "February 19, 2026"
            )
        )
    );

    /**
     * Get deprecation info for a model.
     * Translated from getDeprecationInfo() in deprecation.ts
     */
    public static Optional<DeprecationEntry> getDeprecationInfo(String modelId) {
        if (modelId == null) return Optional.empty();
        String lowerModelId = modelId.toLowerCase();

        return DEPRECATED_MODELS.entrySet().stream()
            .filter(e -> lowerModelId.contains(e.getKey()))
            .map(Map.Entry::getValue)
            .findFirst();
    }

    /**
     * Check if a model is deprecated.
     */
    public static boolean isDeprecated(String modelId) {
        return getDeprecationInfo(modelId).isPresent();
    }

    /**
     * Get the retirement date for a model on a given provider.
     */
    public static Optional<String> getRetirementDate(String modelId, String provider) {
        return getDeprecationInfo(modelId)
            .map(entry -> entry.retirementDates().get(provider));
    }

    private ModelDeprecation() {}
}
