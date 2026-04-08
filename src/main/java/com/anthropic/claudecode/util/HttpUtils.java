package com.anthropic.claudecode.util;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * HTTP utility constants and helpers.
 * Translated from src/utils/http.ts
 *
 * Provides user-agent strings and authentication header helpers for API requests.
 */
@Slf4j
public class HttpUtils {



    // WARNING: We rely on `claude-cli` in the user agent for log filtering.
    // Do NOT change this without also updating log filtering configuration.

    /**
     * Get the primary user agent string for Claude Code API requests.
     * Translated from getUserAgent() in http.ts
     *
     * Format: claude-cli/{version} ({userType}, {entrypoint}[, agent-sdk/{v}][, client-app/{v}][, workload/{w}])
     */
    public static String getUserAgent() {
        String version = getVersion();

        String agentSdkVersion = System.getenv("CLAUDE_AGENT_SDK_VERSION");
        String agentSdkSuffix = agentSdkVersion != null
                ? ", agent-sdk/" + agentSdkVersion
                : "";

        String clientApp = System.getenv("CLAUDE_AGENT_SDK_CLIENT_APP");
        String clientAppSuffix = clientApp != null
                ? ", client-app/" + clientApp
                : "";

        String userType = System.getenv("USER_TYPE");
        if (userType == null) userType = "external";

        String entrypoint = System.getenv("CLAUDE_CODE_ENTRYPOINT");
        if (entrypoint == null) entrypoint = "cli";

        // Workload tag for cron-initiated requests (1P-only observability)
        String workload = WorkloadContext.getWorkload();
        String workloadSuffix = (workload != null && !workload.isEmpty())
                ? ", workload/" + workload
                : "";

        return "claude-cli/" + version + " (" + userType + ", " + entrypoint
                + agentSdkSuffix + clientAppSuffix + workloadSuffix + ")";
    }

    /**
     * Get the MCP user agent string.
     * Translated from getMCPUserAgent() in http.ts
     *
     * Format: claude-code/{version}[ ({entrypoint}[, agent-sdk/{v}][, client-app/{v}])]
     */
    public static String getMCPUserAgent() {
        String version = getVersion();
        StringBuilder parts = new StringBuilder();

        String entrypoint = System.getenv("CLAUDE_CODE_ENTRYPOINT");
        if (entrypoint != null) {
            parts.append(entrypoint);
        }

        String agentSdkVersion = System.getenv("CLAUDE_AGENT_SDK_VERSION");
        if (agentSdkVersion != null) {
            if (parts.length() > 0) parts.append(", ");
            parts.append("agent-sdk/").append(agentSdkVersion);
        }

        String clientApp = System.getenv("CLAUDE_AGENT_SDK_CLIENT_APP");
        if (clientApp != null) {
            if (parts.length() > 0) parts.append(", ");
            parts.append("client-app/").append(clientApp);
        }

        String suffix = parts.length() > 0 ? " (" + parts + ")" : "";
        return "claude-code/" + version + suffix;
    }

    /**
     * Get the WebFetch user agent for arbitrary site requests.
     * Translated from getWebFetchUserAgent() in http.ts
     *
     * Uses `Claude-User` — Anthropic's publicly documented agent for user-initiated fetches.
     */
    public static String getWebFetchUserAgent() {
        return "Claude-User (" + getUserAgent() + "; +https://support.anthropic.com/)";
    }

    /**
     * Authentication headers result.
     * Translated from AuthHeaders in http.ts
     */
    public static class AuthHeaders {
        private final Map<String, String> headers;
        private final String error;

        public AuthHeaders(Map<String, String> headers, String error) { this.headers = headers; this.error = error; }
        public AuthHeaders(Map<String, String> headers) { this.headers = headers; this.error = null; }
        public Map<String, String> getHeaders() { return headers; }
        public String getError() { return error; }
        public boolean isError() { return error != null; }
    }

    /**
     * Get authentication headers for API requests.
     * Translated from getAuthHeaders() in http.ts
     *
     * Returns either OAuth headers for Max/Pro users or API key headers for regular users.
     */
    public static AuthHeaders getAuthHeaders() {
        // Check if this is a Claude.ai subscriber using OAuth
        if (isClaudeAISubscriber()) {
            String accessToken = getClaudeAIOAuthAccessToken();
            if (accessToken == null || accessToken.isEmpty()) {
                return new AuthHeaders(Map.of(), "No OAuth token available");
            }
            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + accessToken);
            headers.put("anthropic-beta", getOAuthBetaHeader());
            return new AuthHeaders(headers);
        }

        // Regular API key authentication
        String apiKey = getAnthropicApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            return new AuthHeaders(Map.of(), "No API key available");
        }
        return new AuthHeaders(Map.of("x-api-key", apiKey));
    }

    /**
     * Wrapper that handles OAuth 401 errors by force-refreshing the token and retrying once.
     * Addresses clock drift scenarios where the local expiration check disagrees with the server.
     * Translated from withOAuth401Retry() in http.ts
     *
     * The request supplier is called again on retry, so it must re-read auth (e.g., via
     * getAuthHeaders()) to pick up the refreshed token.
     *
     * @param request        The HTTP request supplier to execute and potentially retry
     * @param also403Revoked Also retry on 403 with "OAuth token has been revoked" body
     * @param <T>            The response type
     * @return CompletableFuture resolving to the response
     */
    public static <T> CompletableFuture<T> withOAuth401Retry(
            Supplier<CompletableFuture<T>> request,
            boolean also403Revoked) {

        return request.get().exceptionallyCompose(ex -> {
            HttpStatusException httpEx = extractHttpException(ex);
            if (httpEx == null) {
                return CompletableFuture.failedFuture(ex);
            }

            int status = httpEx.getStatusCode();
            boolean is403Revoked = also403Revoked
                    && status == 403
                    && httpEx.getBody() != null
                    && httpEx.getBody().contains("OAuth token has been revoked");
            boolean isAuthError = status == 401 || is403Revoked;

            if (!isAuthError) {
                return CompletableFuture.failedFuture(ex);
            }

            // Force refresh the token
            String failedAccessToken = getClaudeAIOAuthAccessToken();
            if (failedAccessToken == null || failedAccessToken.isEmpty()) {
                return CompletableFuture.failedFuture(ex);
            }

            return handleOAuth401Error(failedAccessToken)
                    .thenCompose(ignored -> request.get());
        });
    }

    /**
     * withOAuth401Retry without 403 revoked check.
     */
    public static <T> CompletableFuture<T> withOAuth401Retry(Supplier<CompletableFuture<T>> request) {
        return withOAuth401Retry(request, false);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private static String getVersion() {
        String version = System.getenv("CLAUDE_CODE_VERSION");
        return version != null ? version : "2.1.88";
    }

    private static boolean isClaudeAISubscriber() {
        String subscriber = System.getenv("CLAUDE_AI_SUBSCRIBER");
        return "true".equalsIgnoreCase(subscriber) || "1".equals(subscriber);
    }

    private static String getClaudeAIOAuthAccessToken() {
        return System.getenv("CLAUDE_AI_OAUTH_ACCESS_TOKEN");
    }

    private static String getAnthropicApiKey() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        if (key != null) return key;
        return System.getenv("CLAUDE_API_KEY");
    }

    private static String getOAuthBetaHeader() {
        // oauth-2023-05-31
        return "oauth-2023-05-31";
    }

    private static CompletableFuture<Void> handleOAuth401Error(String failedAccessToken) {
        log.warn("OAuth 401 received — forcing token refresh (failed token prefix: {}...)",
                failedAccessToken.length() > 8 ? failedAccessToken.substring(0, 8) : failedAccessToken);
        // Trigger OAuth token refresh (implementation depends on AuthService)
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Simple wrapper for HTTP error status codes.
     */
    @lombok.EqualsAndHashCode(callSuper = false)
    @Data
    @lombok.NoArgsConstructor(force = true)
    @lombok.AllArgsConstructor
    public static class HttpStatusException extends RuntimeException {
        private final int statusCode;
        private final String body;


        public int getStatusCode() { return statusCode; }
        public String getBody() { return body; }
    }

    private static HttpStatusException extractHttpException(Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof HttpStatusException hse) {
                return hse;
            }
            cause = cause.getCause();
        }
        return null;
    }

    private HttpUtils() {}
}
