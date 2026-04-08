package com.anthropic.claudecode.model;

/**
 * Product URL constants.
 * Translated from src/constants/product.ts
 */
public class ProductConstants {

    public static final String PRODUCT_URL = "https://claude.com/claude-code";
    public static final String CLAUDE_AI_BASE_URL = "https://claude.ai";
    public static final String CLAUDE_AI_STAGING_BASE_URL = "https://claude-ai.staging.ant.dev";
    public static final String CLAUDE_AI_LOCAL_BASE_URL = "http://localhost:4000";

    /**
     * Check if this is a staging remote session.
     * Translated from isRemoteSessionStaging() in product.ts
     */
    public static boolean isRemoteSessionStaging(String sessionId, String ingressUrl) {
        return (sessionId != null && sessionId.contains("_staging_"))
            || (ingressUrl != null && ingressUrl.contains("staging"));
    }

    /**
     * Check if this is a local remote session.
     * Translated from isRemoteSessionLocal() in product.ts
     */
    public static boolean isRemoteSessionLocal(String sessionId, String ingressUrl) {
        return (sessionId != null && sessionId.contains("_local_"))
            || (ingressUrl != null && ingressUrl.contains("localhost"));
    }

    /**
     * Get the Claude AI base URL.
     * Translated from getClaudeAiBaseUrl() in product.ts
     */
    public static String getClaudeAiBaseUrl(String sessionId, String ingressUrl) {
        if (isRemoteSessionLocal(sessionId, ingressUrl)) return CLAUDE_AI_LOCAL_BASE_URL;
        if (isRemoteSessionStaging(sessionId, ingressUrl)) return CLAUDE_AI_STAGING_BASE_URL;
        return CLAUDE_AI_BASE_URL;
    }

    /**
     * Get the remote session URL.
     * Translated from getRemoteSessionUrl() in product.ts
     */
    public static String getRemoteSessionUrl(String sessionId, String ingressUrl) {
        return getClaudeAiBaseUrl(sessionId, ingressUrl) + "/code";
    }

    private ProductConstants() {}
}
