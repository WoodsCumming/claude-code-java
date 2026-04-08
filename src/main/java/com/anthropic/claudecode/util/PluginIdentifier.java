package com.anthropic.claudecode.util;

import java.util.Optional;

/**
 * Plugin identifier utilities.
 * Translated from src/utils/plugins/pluginIdentifier.ts
 */
public class PluginIdentifier {

    public record ParsedPluginIdentifier(String name, String marketplace) {
        public boolean hasMarketplace() { return marketplace != null; }
    }

    /**
     * Parse a plugin identifier string.
     * Translated from parsePluginIdentifier() in pluginIdentifier.ts
     *
     * Format: "name" or "name@marketplace"
     */
    public static ParsedPluginIdentifier parsePluginIdentifier(String plugin) {
        if (plugin == null) return new ParsedPluginIdentifier("", null);

        if (plugin.contains("@")) {
            String[] parts = plugin.split("@", 2);
            return new ParsedPluginIdentifier(
                parts[0] != null ? parts[0] : "",
                parts.length > 1 ? parts[1] : null
            );
        }

        return new ParsedPluginIdentifier(plugin, null);
    }

    /**
     * Build a plugin ID from name and marketplace.
     */
    public static String buildPluginId(String name, String marketplace) {
        if (marketplace == null || marketplace.isBlank()) return name;
        return name + "@" + marketplace;
    }

    /**
     * Check if a marketplace is official.
     * Translated from isOfficialMarketplaceName() in pluginIdentifier.ts
     */
    public static boolean isOfficialMarketplaceName(String marketplace) {
        if (marketplace == null) return false;
        return "anthropic".equals(marketplace) || "builtin".equals(marketplace);
    }

    private PluginIdentifier() {}
}
