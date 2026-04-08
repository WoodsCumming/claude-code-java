package com.anthropic.claudecode.util;

import java.net.URI;

/**
 * API provider utilities.
 * Translated from src/utils/model/providers.ts
 */
public class ApiProviderUtils {

    public enum ApiProvider {
        FIRST_PARTY("firstParty"),
        BEDROCK("bedrock"),
        VERTEX("vertex"),
        FOUNDRY("foundry");

        private final String value;
        ApiProvider(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    /**
     * Get the current API provider.
     * Translated from getAPIProvider() in providers.ts
     */
    public static ApiProvider getAPIProvider() {
        if (EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_BEDROCK"))) return ApiProvider.BEDROCK;
        if (EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_VERTEX"))) return ApiProvider.VERTEX;
        if (EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_FOUNDRY"))) return ApiProvider.FOUNDRY;
        return ApiProvider.FIRST_PARTY;
    }

    /**
     * Check if the base URL is a first-party Anthropic URL.
     * Translated from isFirstPartyAnthropicBaseUrl() in providers.ts
     */
    public static boolean isFirstPartyAnthropicBaseUrl() {
        String baseUrl = System.getenv("ANTHROPIC_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) return true;

        try {
            String host = new URI(baseUrl).getHost();
            return "api.anthropic.com".equals(host)
                || "api-staging.anthropic.com".equals(host);
        } catch (Exception e) {
            return false;
        }
    }

    private ApiProviderUtils() {}
}
