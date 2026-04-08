package com.anthropic.claudecode.util;

import java.util.*;

/**
 * Model allowlist utilities.
 * Translated from src/utils/model/modelAllowlist.ts
 *
 * Checks if a model is allowed based on an allowlist configuration.
 */
public class ModelAllowlist {

    /**
     * Check if a model is allowed by an allowlist.
     * Translated from isModelAllowed() in modelAllowlist.ts
     */
    public static boolean isModelAllowed(String model, List<String> allowlist) {
        if (model == null || allowlist == null || allowlist.isEmpty()) {
            return true; // No allowlist = all allowed
        }

        String lowerModel = model.toLowerCase();

        for (String entry : allowlist) {
            String lowerEntry = entry.toLowerCase();

            // Exact match
            if (lowerModel.equals(lowerEntry)) return true;

            // Family alias match (e.g., "opus" matches any opus model)
            if (ModelAliases.isModelFamilyAlias(lowerEntry)) {
                if (lowerModel.contains(lowerEntry)) return true;
            }

            // Version prefix match
            if (modelMatchesVersionPrefix(lowerModel, lowerEntry)) return true;
        }

        return false;
    }

    /**
     * Check if a model matches a version prefix entry.
     */
    private static boolean modelMatchesVersionPrefix(String model, String entry) {
        // Try the entry as-is (e.g. "claude-opus-4-5")
        if (prefixMatchesModel(model, entry)) return true;

        // Try with "claude-" prefix (e.g. "opus-4-5" → "claude-opus-4-5")
        if (!entry.startsWith("claude-") && prefixMatchesModel(model, "claude-" + entry)) {
            return true;
        }

        return false;
    }

    private static boolean prefixMatchesModel(String modelName, String prefix) {
        if (!modelName.startsWith(prefix)) return false;
        return modelName.length() == prefix.length() || modelName.charAt(prefix.length()) == '-';
    }

    private ModelAllowlist() {}
}
