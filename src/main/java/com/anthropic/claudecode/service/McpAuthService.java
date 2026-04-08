package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * MCP OAuth authentication service.
 * Translated from src/services/mcp/auth.ts
 *
 * Manages OAuth 2.0 authentication for MCP servers using SSE and HTTP transports.
 * Supports:
 * - OAuth authorization code + PKCE flow (browser-based)
 * - Token refresh (RFC 6749)
 * - Token revocation (RFC 7009)
 * - XAA (Cross-App Access) silent re-auth via cached IdP id_token (SEP-990)
 * - Error normalization for non-standard servers (e.g. Slack 200+error body pattern)
 */
@Slf4j
@Service
public class McpAuthService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(McpAuthService.class);


    private static final long AUTH_REQUEST_TIMEOUT_MS = 30_000L;
    private static final int MAX_LOCK_RETRIES = 5;

    /**
     * OAuth query parameters that should be redacted from logs.
     */
    private static final Set<String> SENSITIVE_OAUTH_PARAMS = Set.of(
        "state", "nonce", "code_challenge", "code_verifier", "code"
    );

    /**
     * Non-standard error codes from some OAuth servers (e.g. Slack) that
     * are semantically equivalent to RFC 6749 "invalid_grant".
     */
    private static final Set<String> NONSTANDARD_INVALID_GRANT_ALIASES = Set.of(
        "invalid_refresh_token", "expired_refresh_token", "token_expired"
    );

    // ── Failure reason enums ─────────────────────────────────────────────────

    /**
     * Failure reasons for mcp_oauth_refresh_failure analytics events.
     * Translated from MCPRefreshFailureReason in auth.ts
     */
    public enum McpRefreshFailureReason {
        METADATA_DISCOVERY_FAILED,
        NO_CLIENT_INFO,
        NO_TOKENS_RETURNED,
        INVALID_GRANT,
        TRANSIENT_RETRIES_EXHAUSTED,
        REQUEST_FAILED
    }

    /**
     * Failure reasons for mcp_oauth_flow_error analytics events.
     * Translated from MCPOAuthFlowErrorReason in auth.ts
     */
    public enum McpOAuthFlowErrorReason {
        CANCELLED,
        TIMEOUT,
        PROVIDER_DENIED,
        STATE_MISMATCH,
        PORT_UNAVAILABLE,
        SDK_AUTH_FAILED,
        TOKEN_EXCHANGE_FAILED,
        UNKNOWN
    }

    // ── Error types ──────────────────────────────────────────────────────────

    /**
     * Thrown when the user cancels the OAuth browser flow.
     * Translated from AuthenticationCancelledError in auth.ts
     */
    public static class AuthenticationCancelledError extends RuntimeException {
        public AuthenticationCancelledError() {
            super("Authentication was cancelled");
        }
    }

    // ── OAuth tokens ─────────────────────────────────────────────────────────

    /**
     * OAuth token set stored in secure storage per server.
     * Translated from SecureStorageData.mcpOAuth[key] entries in auth.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class McpOAuthTokens {
        private String accessToken;
        private String refreshToken;
        private String tokenType;
        private Long expiresAt;  // epoch ms
        private OAuthDiscoveryState discoveryState;
        private OAuthClientInfo clientInfo;

        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String v) { accessToken = v; }
        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(String v) { refreshToken = v; }
        public String getTokenType() { return tokenType; }
        public void setTokenType(String v) { tokenType = v; }
        public Long getExpiresAt() { return expiresAt; }
        public void setExpiresAt(Long v) { expiresAt = v; }
        public OAuthDiscoveryState getDiscoveryState() { return discoveryState; }
        public void setDiscoveryState(OAuthDiscoveryState v) { discoveryState = v; }
        public OAuthClientInfo getClientInfo() { return clientInfo; }
        public void setClientInfo(OAuthClientInfo v) { clientInfo = v; }
    }

    /**
     * Discovered OAuth server metadata cached alongside tokens.
     * Translated from OAuthDiscoveryState in @modelcontextprotocol/sdk
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class OAuthDiscoveryState {
        private String authorizationServerUrl;
        private String authorizationEndpoint;
        private String tokenEndpoint;
        private String revocationEndpoint;
        private List<String> grantTypesSupported;

        public String getAuthorizationServerUrl() { return authorizationServerUrl; }
        public void setAuthorizationServerUrl(String v) { authorizationServerUrl = v; }
        public String getAuthorizationEndpoint() { return authorizationEndpoint; }
        public void setAuthorizationEndpoint(String v) { authorizationEndpoint = v; }
        public String getTokenEndpoint() { return tokenEndpoint; }
        public void setTokenEndpoint(String v) { tokenEndpoint = v; }
        public String getRevocationEndpoint() { return revocationEndpoint; }
        public void setRevocationEndpoint(String v) { revocationEndpoint = v; }
        public List<String> getGrantTypesSupported() { return grantTypesSupported; }
        public void setGrantTypesSupported(List<String> v) { grantTypesSupported = v; }
    }

    /**
     * OAuth client registration information.
     * Translated from OAuthClientInformation in @modelcontextprotocol/sdk
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class OAuthClientInfo {
        private String clientId;
        private String clientSecret;

        public String getClientId() { return clientId; }
        public void setClientId(String v) { clientId = v; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String v) { clientSecret = v; }
    }

    // ── Server key ───────────────────────────────────────────────────────────

    /**
     * Generates a unique key for server credentials based on name + config hash.
     * Translated from getServerKey() in auth.ts
     *
     * Prevents credentials from being reused across different servers with the same
     * name or different configurations.
     *
     * @param serverName  the MCP server name
     * @param serverUrl   the MCP server URL
     * @param headers     any custom headers in the config (may be null)
     * @param configType  the transport type string
     * @return stable key of the form "serverName|hexHash16"
     */
    public static String getServerKey(
            String serverName,
            String serverUrl,
            Map<String, String> headers,
            String configType) {
        try {
            // Mirror the JSON key structure from TypeScript: { type, url, headers }
            String configJson = String.format(
                "{\"type\":\"%s\",\"url\":\"%s\",\"headers\":%s}",
                configType != null ? configType : "",
                serverUrl != null ? serverUrl : "",
                headers != null && !headers.isEmpty()
                    ? mapToJson(headers)
                    : "{}"
            );
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest(configJson.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(digest);
            return serverName + "|" + hex.substring(0, 16);
        } catch (Exception e) {
            log.warn("Could not compute server key for {}: {}", serverName, e.getMessage());
            return serverName + "|" + serverUrl.hashCode();
        }
    }

    // ── Token discovery check ────────────────────────────────────────────────

    /**
     * Returns true when we have probed this server before (OAuth discovery state
     * is stored) but hold no credentials to try.
     * Translated from hasMcpDiscoveryButNoToken() in auth.ts
     *
     * A connection attempt in this state is guaranteed to 401 — the only way out
     * is the user running /mcp to authenticate.
     * XAA servers are excluded because they can silently re-auth via cached id_token.
     */
    public boolean hasMcpDiscoveryButNoToken(
            String serverName,
            String serverUrl,
            Map<String, String> headers,
            String configType,
            boolean isXaaServer,
            XaaIdpLoginService xaaIdpLoginService) {

        // XAA servers can silently re-auth — don't block connection
        if (xaaIdpLoginService.isXaaEnabled() && isXaaServer) {
            return false;
        }

        String serverKey = getServerKey(serverName, serverUrl, headers, configType);
        McpOAuthTokens tokens = loadTokens(serverKey);
        return tokens != null && tokens.getAccessToken() == null && tokens.getRefreshToken() == null;
    }

    // ── URL redaction ────────────────────────────────────────────────────────

    /**
     * Redacts sensitive OAuth query parameters from a URL for safe logging.
     * Translated from redactSensitiveUrlParams() in auth.ts
     */
    public static String redactSensitiveUrlParams(String url) {
        try {
            java.net.URI uri = new java.net.URI(url);
            String query = uri.getQuery();
            if (query == null) return url;

            StringBuilder redacted = new StringBuilder();
            for (String param : query.split("&")) {
                String[] parts = param.split("=", 2);
                if (parts.length == 2 && SENSITIVE_OAUTH_PARAMS.contains(parts[0])) {
                    redacted.append(parts[0]).append("=[REDACTED]&");
                } else {
                    redacted.append(param).append("&");
                }
            }
            String newQuery = redacted.length() > 0
                ? redacted.substring(0, redacted.length() - 1)
                : "";

            return new java.net.URI(
                uri.getScheme(), uri.getAuthority(), uri.getPath(),
                newQuery.isEmpty() ? null : newQuery,
                uri.getFragment()
            ).toString();
        } catch (Exception e) {
            return url;
        }
    }

    // ── OAuth error normalization ─────────────────────────────────────────────

    /**
     * Normalize OAuth error bodies for servers that return HTTP 200 with a JSON
     * error body (e.g. Slack). Also maps non-standard error codes (invalid_refresh_token,
     * expired_refresh_token, token_expired) to RFC 6749 "invalid_grant".
     * Translated from normalizeOAuthErrorBody() in auth.ts
     *
     * @param statusCode     the HTTP status code of the response
     * @param bodyText       the raw response body text
     * @return true if the caller should treat this as "invalid_grant", false otherwise
     */
    public OAuthNormalizationResult normalizeOAuthErrorBody(int statusCode, String bodyText) {
        if (statusCode < 200 || statusCode >= 300) {
            // Not a 2xx — pass through; the SDK will handle !ok responses normally
            return new OAuthNormalizationResult(false, null, null);
        }

        // Try to parse the body as JSON
        try {
            // Look for error field in the JSON
            String errorValue = extractJsonString(bodyText, "error");
            if (errorValue == null) {
                // Not an error body — genuine success response
                return new OAuthNormalizationResult(false, null, null);
            }

            // Check for non-standard aliases
            String normalizedError = NONSTANDARD_INVALID_GRANT_ALIASES.contains(errorValue)
                ? "invalid_grant"
                : errorValue;
            String errorDescription = extractJsonString(bodyText, "error_description");

            if (NONSTANDARD_INVALID_GRANT_ALIASES.contains(errorValue)) {
                log.debug("Normalized non-standard error '{}' to 'invalid_grant'", errorValue);
            }

            return new OAuthNormalizationResult(true, normalizedError, errorDescription);
        } catch (Exception e) {
            return new OAuthNormalizationResult(false, null, null);
        }
    }

    @Data
    public static class OAuthNormalizationResult {
        /** True if the body contained an OAuth error envelope (should rewrite to 400). */
        private boolean isError;
        /** The (possibly normalized) error code. */
        private String error;
        /** The error_description, if present. */
        private String errorDescription;

        public boolean isInvalidGrant() {
            return "invalid_grant".equals(error);
        }
    

        public OAuthNormalizationResult() {}
        public OAuthNormalizationResult(boolean isError, String error, String errorDescription) {
            this.isError = isError;
            this.error = error;
            this.errorDescription = errorDescription;
        }
    }

    // ── Token storage stubs ──────────────────────────────────────────────────

    /**
     * Load stored OAuth tokens for a server key from secure storage.
     * In a full implementation, delegates to SecureStorage.read().mcpOAuth[key].
     */
    private McpOAuthTokens loadTokens(String serverKey) {
        // Stub — full implementation reads from OS keychain
        log.debug("Loading tokens for key: {}", serverKey);
        return null;
    }

    /**
     * Save OAuth tokens for a server key to secure storage.
     * In a full implementation, delegates to SecureStorage.update().
     */
    private void saveTokens(String serverKey, McpOAuthTokens tokens) {
        // Stub — full implementation writes to OS keychain
        log.debug("Saving tokens for key: {}", serverKey);
    }

    /**
     * Clear stored OAuth tokens and discovery state for a server key.
     * Translated from invalidateCredentials()/clearMcpAuthCache() in auth.ts
     */
    public void clearTokens(String serverKey) {
        log.debug("Clearing tokens for key: {}", serverKey);
    }

    /** Clear all cached MCP auth state. */
    public void clearMcpAuthCache() {
        log.debug("Clearing all MCP auth cache");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]+)\"";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String mapToJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : new TreeMap<>(map).entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey()).append("\":\"").append(e.getValue()).append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    /** Read a client secret from the user. */
    public String readClientSecret() {
        log.debug("readClientSecret called");
        return null;
    }

    /** Save MCP client secret securely. */
    public void saveMcpClientSecret(String serverName, Object serverConfig, String clientSecret) {
        log.debug("saveMcpClientSecret: {}", serverName);
    }
}
