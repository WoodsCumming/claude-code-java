package com.anthropic.claudecode.util;

import java.util.*;

/**
 * Model string mappings for different providers.
 * Translated from src/utils/model/modelStrings.ts
 */
public class ModelStrings {

    // Model IDs for different providers
    public static final Map<String, ProviderModelIds> MODEL_CONFIGS = Map.of(
        "opus46", new ProviderModelIds(
            "claude-opus-4-6",
            "anthropic.claude-opus-4-6-v1:0",
            "claude-opus-4-6@v1",
            "claude-opus-4-6"
        ),
        "sonnet46", new ProviderModelIds(
            "claude-sonnet-4-6",
            "anthropic.claude-sonnet-4-6-v1:0",
            "claude-sonnet-4-6@v1",
            "claude-sonnet-4-6"
        ),
        "haiku45", new ProviderModelIds(
            "claude-haiku-4-5-20251001",
            "anthropic.claude-haiku-4-5-v1:0",
            "claude-haiku-4-5@v1",
            "claude-haiku-4-5-20251001"
        )
    );

    /**
     * Get the model ID for a specific provider.
     */
    public static String getModelId(String modelKey, ApiProviderUtils.ApiProvider provider) {
        ProviderModelIds ids = MODEL_CONFIGS.get(modelKey);
        if (ids == null) return modelKey;

        return switch (provider) {
            case FIRST_PARTY -> ids.firstParty();
            case BEDROCK -> ids.bedrock();
            case VERTEX -> ids.vertex();
            case FOUNDRY -> ids.foundry();
        };
    }

    /**
     * Normalize a model string for the API.
     * Translated from normalizeModelStringForAPI() in model.ts
     */
    public static String normalizeModelStringForAPI(String model) {
        if (model == null) return ModelUtils.DEFAULT_OPUS_MODEL;

        ApiProviderUtils.ApiProvider provider = ApiProviderUtils.getAPIProvider();

        // For first-party, use the model as-is
        if (provider == ApiProviderUtils.ApiProvider.FIRST_PARTY) {
            return model;
        }

        // For other providers, find the appropriate model ID
        for (Map.Entry<String, ProviderModelIds> entry : MODEL_CONFIGS.entrySet()) {
            if (entry.getValue().firstParty().equals(model)) {
                return getModelId(entry.getKey(), provider);
            }
        }

        return model;
    }

    public record ProviderModelIds(
        String firstParty,
        String bedrock,
        String vertex,
        String foundry
    ) {}

    private ModelStrings() {}
}
