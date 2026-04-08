package com.anthropic.claudecode.util;

/**
 * Authentication utilities.
 * Translated from src/utils/auth.ts
 */
public class AuthUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthUtils.class);


    /**
     * Check if running in a managed OAuth context.
     * Translated from isManagedOAuthContext() in auth.ts
     */
    public static boolean isManagedOAuthContext() {
        return EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_REMOTE"))
            || "claude-desktop".equals(System.getenv("CLAUDE_CODE_ENTRYPOINT"));
    }

    /**
     * Check if Anthropic auth is enabled.
     * Translated from isAnthropicAuthEnabled() in auth.ts
     */
    public static boolean isAnthropicAuthEnabled() {
        if (EnvUtils.isBareMode()) return false;

        // Unix socket proxy
        if (System.getenv("ANTHROPIC_UNIX_SOCKET") != null) {
            return System.getenv("CLAUDE_CODE_OAUTH_TOKEN") != null;
        }

        // 3P providers
        if (EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_BEDROCK"))
            || EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_VERTEX"))
            || EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_FOUNDRY"))) {
            return false;
        }

        // External API key takes precedence
        if (System.getenv("ANTHROPIC_API_KEY") != null
            || System.getenv("ANTHROPIC_AUTH_TOKEN") != null) {
            return false;
        }

        return true;
    }

    /**
     * Get the subscription type.
     * Translated from getSubscriptionType() in auth.ts
     */
    public static String getSubscriptionType() {
        String oauthToken = System.getenv("CLAUDE_CODE_OAUTH_TOKEN");
        if (oauthToken != null) return "claude_ai";
        if (System.getenv("ANTHROPIC_API_KEY") != null) return "api_key";
        return "unknown";
    }

    /**
     * Returns true when the current credential is a first-party API key
     * (i.e. an Anthropic Console API key, not OAuth).
     */
    public static boolean is1PApiCustomer() {
        return System.getenv("ANTHROPIC_API_KEY") != null
                && System.getenv("CLAUDE_CODE_OAUTH_TOKEN") == null;
    }

    /**
     * Returns true when the user is authenticated via Claude.ai OAuth.
     */
    public static boolean isClaudeAISubscriber() {
        return System.getenv("CLAUDE_CODE_OAUTH_TOKEN") != null;
    }

    private AuthUtils() {}
}
