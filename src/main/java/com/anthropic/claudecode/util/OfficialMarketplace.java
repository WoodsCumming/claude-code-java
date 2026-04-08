package com.anthropic.claudecode.util;

/**
 * Official marketplace constants.
 * Translated from src/utils/plugins/officialMarketplace.ts
 */
public class OfficialMarketplace {

    public static final String OFFICIAL_MARKETPLACE_NAME = "anthropic-official";
    public static final String OFFICIAL_MARKETPLACE_REPO = "anthropics/claude-plugins-official";
    public static final String OFFICIAL_MARKETPLACE_SOURCE_TYPE = "github";

    /**
     * Check if a marketplace is the official Anthropic marketplace.
     */
    public static boolean isOfficialMarketplace(String marketplaceName) {
        return OFFICIAL_MARKETPLACE_NAME.equals(marketplaceName);
    }

    private OfficialMarketplace() {}
}
