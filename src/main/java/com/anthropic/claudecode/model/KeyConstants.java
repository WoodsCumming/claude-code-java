package com.anthropic.claudecode.model;

/**
 * GrowthBook client key constants for feature flag configuration.
 * Translated from src/constants/keys.ts
 *
 * Note: The TypeScript source is a single function getGrowthBookClientKey()
 * that returns a GrowthBook SDK key based on user type and environment.
 * In Java we expose these as named constants and a utility method.
 */
public final class KeyConstants {

    /**
     * GrowthBook SDK key for internal Anthropic (ant) users in dev mode.
     */
    public static final String GROWTHBOOK_KEY_ANT_DEV = "sdk-yZQvlplybuXjYh6L";

    /**
     * GrowthBook SDK key for internal Anthropic (ant) users in production.
     */
    public static final String GROWTHBOOK_KEY_ANT_PROD = "sdk-xRVcrliHIlrg4og4";

    /**
     * GrowthBook SDK key for external / public users.
     */
    public static final String GROWTHBOOK_KEY_EXTERNAL = "sdk-zAZezfDKGoZuXXKe";

    /**
     * Resolve the GrowthBook client key for the given user type and
     * dev-mode flag, mirroring the TypeScript getGrowthBookClientKey() logic.
     *
     * @param isAntUser         true when USER_TYPE == "ant"
     * @param enableGrowthbookDev true when ENABLE_GROWTHBOOK_DEV is truthy
     * @return the appropriate GrowthBook SDK key string
     */
    public static String getGrowthBookClientKey(boolean isAntUser, boolean enableGrowthbookDev) {
        if (isAntUser) {
            return enableGrowthbookDev ? GROWTHBOOK_KEY_ANT_DEV : GROWTHBOOK_KEY_ANT_PROD;
        }
        return GROWTHBOOK_KEY_EXTERNAL;
    }

    private KeyConstants() {}
}
