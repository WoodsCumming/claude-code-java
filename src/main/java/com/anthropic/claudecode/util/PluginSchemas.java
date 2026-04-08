package com.anthropic.claudecode.util;

import java.util.Set;

/**
 * Plugin schema constants.
 * Translated from src/utils/plugins/schemas.ts
 */
public class PluginSchemas {

    /**
     * Official marketplace names reserved for Anthropic/Claude official use.
     */
    public static final Set<String> ALLOWED_OFFICIAL_MARKETPLACE_NAMES = Set.of(
        "claude-code-marketplace",
        "claude-code-plugins",
        "claude-plugins-official",
        "anthropic-marketplace",
        "anthropic-plugins",
        "agent-skills",
        "life-sciences",
        "knowledge-work-plugins"
    );

    /**
     * Official marketplaces that should NOT auto-update by default.
     */
    public static final Set<String> NO_AUTO_UPDATE_OFFICIAL_MARKETPLACES = Set.of(
        "knowledge-work-plugins"
    );

    /**
     * Plugin scopes.
     */
    public enum PluginScope {
        USER("user"),
        PROJECT("project"),
        LOCAL("local"),
        FLAG("flag"),
        POLICY("policy"),
        DYNAMIC("dynamic");

        private final String value;
        PluginScope(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    /**
     * Check if a marketplace name is an official Anthropic marketplace.
     */
    public static boolean isOfficialMarketplace(String name) {
        return ALLOWED_OFFICIAL_MARKETPLACE_NAMES.contains(name);
    }

    /**
     * Check if auto-update is enabled for a marketplace.
     * Translated from isAutoUpdateEnabled() in schemas.ts
     */
    public static boolean isAutoUpdateEnabled(String marketplaceName, Boolean storedValue) {
        if (storedValue != null) return storedValue;
        return isOfficialMarketplace(marketplaceName)
            && !NO_AUTO_UPDATE_OFFICIAL_MARKETPLACES.contains(marketplaceName);
    }

    private PluginSchemas() {}
}
