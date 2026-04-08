package com.anthropic.claudecode.util;

import java.util.*;

/**
 * Model configuration constants.
 * Translated from src/utils/model/configs.ts
 *
 * Defines model IDs for different providers (firstParty, bedrock, vertex, foundry).
 */
public class ModelConfigs {

    public record ModelConfig(String firstParty, String bedrock, String vertex, String foundry) {}

    // Model configurations
    public static final ModelConfig CLAUDE_3_5_HAIKU = new ModelConfig(
        "claude-3-5-haiku-20241022",
        "us.anthropic.claude-3-5-haiku-20241022-v1:0",
        "claude-3-5-haiku@20241022",
        "claude-3-5-haiku"
    );

    public static final ModelConfig CLAUDE_HAIKU_4_5 = new ModelConfig(
        "claude-haiku-4-5-20251001",
        "us.anthropic.claude-haiku-4-5-20251001-v1:0",
        "claude-haiku-4-5@20251001",
        "claude-haiku-4-5"
    );

    public static final ModelConfig CLAUDE_3_5_SONNET = new ModelConfig(
        "claude-3-5-sonnet-20241022",
        "anthropic.claude-3-5-sonnet-20241022-v2:0",
        "claude-3-5-sonnet-v2@20241022",
        "claude-3-5-sonnet"
    );

    public static final ModelConfig CLAUDE_3_7_SONNET = new ModelConfig(
        "claude-3-7-sonnet-20250219",
        "us.anthropic.claude-3-7-sonnet-20250219-v1:0",
        "claude-3-7-sonnet@20250219",
        "claude-3-7-sonnet"
    );

    public static final ModelConfig CLAUDE_SONNET_4 = new ModelConfig(
        "claude-sonnet-4-20250514",
        "us.anthropic.claude-sonnet-4-20250514-v1:0",
        "claude-sonnet-4@20250514",
        "claude-sonnet-4"
    );

    public static final ModelConfig CLAUDE_SONNET_4_5 = new ModelConfig(
        "claude-sonnet-4-5-20250929",
        "us.anthropic.claude-sonnet-4-5-20250929-v1:0",
        "claude-sonnet-4-5@20250929",
        "claude-sonnet-4-5"
    );

    public static final ModelConfig CLAUDE_SONNET_4_6 = new ModelConfig(
        "claude-sonnet-4-6",
        "us.anthropic.claude-sonnet-4-6",
        "claude-sonnet-4-6",
        "claude-sonnet-4-6"
    );

    public static final ModelConfig CLAUDE_OPUS_4 = new ModelConfig(
        "claude-opus-4-20250514",
        "us.anthropic.claude-opus-4-20250514-v1:0",
        "claude-opus-4@20250514",
        "claude-opus-4"
    );

    public static final ModelConfig CLAUDE_OPUS_4_1 = new ModelConfig(
        "claude-opus-4-1-20250805",
        "us.anthropic.claude-opus-4-1-20250805-v1:0",
        "claude-opus-4-1@20250805",
        "claude-opus-4-1"
    );

    public static final ModelConfig CLAUDE_OPUS_4_5 = new ModelConfig(
        "claude-opus-4-5-20251101",
        "us.anthropic.claude-opus-4-5-20251101-v1:0",
        "claude-opus-4-5@20251101",
        "claude-opus-4-5"
    );

    public static final ModelConfig CLAUDE_OPUS_4_6 = new ModelConfig(
        "claude-opus-4-6",
        "us.anthropic.claude-opus-4-6-v1",
        "claude-opus-4-6",
        "claude-opus-4-6"
    );

    /**
     * All model configurations keyed by short name.
     */
    public static final Map<String, ModelConfig> ALL_MODEL_CONFIGS = Map.ofEntries(
        Map.entry("haiku35", CLAUDE_3_5_HAIKU),
        Map.entry("haiku45", CLAUDE_HAIKU_4_5),
        Map.entry("sonnet35", CLAUDE_3_5_SONNET),
        Map.entry("sonnet37", CLAUDE_3_7_SONNET),
        Map.entry("sonnet40", CLAUDE_SONNET_4),
        Map.entry("sonnet45", CLAUDE_SONNET_4_5),
        Map.entry("sonnet46", CLAUDE_SONNET_4_6),
        Map.entry("opus40", CLAUDE_OPUS_4),
        Map.entry("opus41", CLAUDE_OPUS_4_1),
        Map.entry("opus45", CLAUDE_OPUS_4_5),
        Map.entry("opus46", CLAUDE_OPUS_4_6)
    );

    /**
     * Get all canonical first-party model IDs.
     */
    public static List<String> getCanonicalModelIds() {
        return ALL_MODEL_CONFIGS.values().stream()
            .map(ModelConfig::firstParty)
            .toList();
    }

    /**
     * Get model config for a given first-party model ID.
     */
    public static Optional<ModelConfig> getConfigByFirstPartyId(String firstPartyId) {
        return ALL_MODEL_CONFIGS.values().stream()
            .filter(c -> c.firstParty().equals(firstPartyId))
            .findFirst();
    }

    private ModelConfigs() {}
}
