package com.anthropic.claudecode.util;

import java.util.Set;

/**
 * Model alias utilities.
 * Translated from src/utils/model/aliases.ts
 */
public class ModelAliases {

    public static final Set<String> MODEL_ALIASES = Set.of(
        "sonnet",
        "opus",
        "haiku",
        "best",
        "sonnet[1m]",
        "opus[1m]",
        "opusplan"
    );

    /**
     * Bare model family aliases that act as wildcards in the availableModels allowlist.
     * When "opus" is in the allowlist, ANY opus model is allowed.
     */
    public static final Set<String> MODEL_FAMILY_ALIASES = Set.of("sonnet", "opus", "haiku");

    /**
     * Check if a model input is a model alias.
     * Translated from isModelAlias() in aliases.ts
     */
    public static boolean isModelAlias(String modelInput) {
        return MODEL_ALIASES.contains(modelInput);
    }

    /**
     * Check if a model is a family alias (wildcard in allowlist).
     * Translated from isModelFamilyAlias() in aliases.ts
     */
    public static boolean isModelFamilyAlias(String model) {
        return MODEL_FAMILY_ALIASES.contains(model);
    }

    private ModelAliases() {}
}
