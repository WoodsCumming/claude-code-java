package com.anthropic.claudecode.model;

/**
 * OAuth configuration.
 * Translated from src/constants/oauth.ts
 */
public class OAuthConfig {

    private final String baseApiUrl;
    private final String consoleAuthorizeUrl;
    private final String claudeAiAuthorizeUrl;
    private final String claudeAiOrigin;
    private final String tokenUrl;
    private final String apiKeyUrl;
    private final String rolesUrl;
    private final String consoleSuccessUrl;
    private final String claudeAiSuccessUrl;
    private final String manualRedirectUrl;
    private final String clientId;
    private final String oauthFileSuffix;
    private final String mcpProxyUrl;
    private final String mcpProxyPath;

    // OAuth scopes
    public static final String CLAUDE_AI_INFERENCE_SCOPE = "user:inference";
    public static final String CLAUDE_AI_PROFILE_SCOPE = "user:profile";
    public static final String CONSOLE_SCOPE = "org:create_api_key";
    public static final String OAUTH_BETA_HEADER = "oauth-2025-04-20";
    public static final String MCP_CLIENT_METADATA_URL =
        "https://claude.ai/oauth/claude-code-client-metadata";

    private OAuthConfig(Builder b) {
        this.baseApiUrl = b.baseApiUrl; this.consoleAuthorizeUrl = b.consoleAuthorizeUrl;
        this.claudeAiAuthorizeUrl = b.claudeAiAuthorizeUrl; this.claudeAiOrigin = b.claudeAiOrigin;
        this.tokenUrl = b.tokenUrl; this.apiKeyUrl = b.apiKeyUrl; this.rolesUrl = b.rolesUrl;
        this.consoleSuccessUrl = b.consoleSuccessUrl; this.claudeAiSuccessUrl = b.claudeAiSuccessUrl;
        this.manualRedirectUrl = b.manualRedirectUrl; this.clientId = b.clientId;
        this.oauthFileSuffix = b.oauthFileSuffix; this.mcpProxyUrl = b.mcpProxyUrl;
        this.mcpProxyPath = b.mcpProxyPath;
    }

    public String getBaseApiUrl() { return baseApiUrl; }
    public String getConsoleAuthorizeUrl() { return consoleAuthorizeUrl; }
    public String getClaudeAiAuthorizeUrl() { return claudeAiAuthorizeUrl; }
    public String getClaudeAiOrigin() { return claudeAiOrigin; }
    public String getTokenUrl() { return tokenUrl; }
    public String getApiKeyUrl() { return apiKeyUrl; }
    public String getRolesUrl() { return rolesUrl; }
    public String getConsoleSuccessUrl() { return consoleSuccessUrl; }
    public String getClaudeAiSuccessUrl() { return claudeAiSuccessUrl; }
    public String getManualRedirectUrl() { return manualRedirectUrl; }
    public String getClientId() { return clientId; }
    public String getOauthFileSuffix() { return oauthFileSuffix; }
    public String getMcpProxyUrl() { return mcpProxyUrl; }
    public String getMcpProxyPath() { return mcpProxyPath; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String baseApiUrl;
        private String consoleAuthorizeUrl;
        private String claudeAiAuthorizeUrl;
        private String claudeAiOrigin;
        private String tokenUrl;
        private String apiKeyUrl;
        private String rolesUrl;
        private String consoleSuccessUrl;
        private String claudeAiSuccessUrl;
        private String manualRedirectUrl;
        private String clientId;
        private String oauthFileSuffix;
        private String mcpProxyUrl;
        private String mcpProxyPath;

        public Builder baseApiUrl(String v) { this.baseApiUrl = v; return this; }
        public Builder consoleAuthorizeUrl(String v) { this.consoleAuthorizeUrl = v; return this; }
        public Builder claudeAiAuthorizeUrl(String v) { this.claudeAiAuthorizeUrl = v; return this; }
        public Builder claudeAiOrigin(String v) { this.claudeAiOrigin = v; return this; }
        public Builder tokenUrl(String v) { this.tokenUrl = v; return this; }
        public Builder apiKeyUrl(String v) { this.apiKeyUrl = v; return this; }
        public Builder rolesUrl(String v) { this.rolesUrl = v; return this; }
        public Builder consoleSuccessUrl(String v) { this.consoleSuccessUrl = v; return this; }
        public Builder claudeAiSuccessUrl(String v) { this.claudeAiSuccessUrl = v; return this; }
        public Builder manualRedirectUrl(String v) { this.manualRedirectUrl = v; return this; }
        public Builder clientId(String v) { this.clientId = v; return this; }
        public Builder oauthFileSuffix(String v) { this.oauthFileSuffix = v; return this; }
        public Builder mcpProxyUrl(String v) { this.mcpProxyUrl = v; return this; }
        public Builder mcpProxyPath(String v) { this.mcpProxyPath = v; return this; }
        public OAuthConfig build() { return new OAuthConfig(this); }
    }

    /**
     * Get the production OAuth configuration.
     */
    public static OAuthConfig prod() {
        return OAuthConfig.builder()
            .baseApiUrl("https://api.anthropic.com")
            .consoleAuthorizeUrl("https://platform.claude.com/oauth/authorize")
            .claudeAiAuthorizeUrl("https://claude.com/cai/oauth/authorize")
            .claudeAiOrigin("https://claude.ai")
            .tokenUrl("https://platform.claude.com/v1/oauth/token")
            .apiKeyUrl("https://api.anthropic.com/api/oauth/claude_cli/create_api_key")
            .rolesUrl("https://api.anthropic.com/api/oauth/claude_cli/roles")
            .consoleSuccessUrl("https://platform.claude.com/buy_credits?returnUrl=/oauth/code/success%3Fapp%3Dclaude-code")
            .claudeAiSuccessUrl("https://platform.claude.com/oauth/code/success?app=claude-code")
            .manualRedirectUrl("https://platform.claude.com/oauth/code/callback")
            .clientId("9d1c250a-e61b-44d9-88ed-5944d1962f5e")
            .oauthFileSuffix("")
            .mcpProxyUrl("https://mcp-proxy.anthropic.com")
            .mcpProxyPath("/v1/mcp/{server_id}")
            .build();
    }

    /**
     * Get the current OAuth config.
     */
    public static OAuthConfig current() {
        String customUrl = System.getenv("CLAUDE_CODE_CUSTOM_OAUTH_URL");
        if (customUrl != null) {
            return OAuthConfig.builder()
                .baseApiUrl(customUrl)
                .consoleAuthorizeUrl(customUrl + "/oauth/authorize")
                .claudeAiAuthorizeUrl(customUrl + "/oauth/authorize")
                .claudeAiOrigin(customUrl)
                .tokenUrl(customUrl + "/v1/oauth/token")
                .apiKeyUrl(customUrl + "/api/oauth/claude_cli/create_api_key")
                .rolesUrl(customUrl + "/api/oauth/claude_cli/roles")
                .consoleSuccessUrl(customUrl + "/oauth/code/success")
                .claudeAiSuccessUrl(customUrl + "/oauth/code/success")
                .manualRedirectUrl(customUrl + "/oauth/code/callback")
                .clientId("9d1c250a-e61b-44d9-88ed-5944d1962f5e")
                .oauthFileSuffix("-custom-oauth")
                .mcpProxyUrl(customUrl)
                .mcpProxyPath("/v1/mcp/{server_id}")
                .build();
        }
        return prod();
    }
}
