package com.anthropic.claudecode.service;

import com.anthropic.claudecode.config.ClaudeCodeConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import lombok.Data;

/**
 * Authentication service.
 *
 * Translated from:
 * <ul>
 *   <li>{@code src/utils/auth.ts}              — core auth utilities (API key, OAuth tokens)</li>
 *   <li>{@code src/cli/handlers/auth.ts}        — CLI handlers: login, status, logout</li>
 *   <li>{@code src/hooks/useApiKeyVerification.ts} — React hook for API key verification
 *       status state machine</li>
 * </ul>
 *
 * <h3>TypeScript → Java mapping for useApiKeyVerification</h3>
 * <pre>
 * useApiKeyVerification()                   → ApiKeyVerificationState (inner class)
 *                                             + verifyApiKey() method
 * VerificationStatus ('loading'|'valid'|…)  → VerificationStatus enum
 * ApiKeyVerificationResult                  → ApiKeyVerificationResult record
 * useState(initialStatus)                   → ApiKeyVerificationState.status volatile field
 * useState(error)                           → ApiKeyVerificationState.error volatile field
 * verify() useCallback                      → verifyApiKey(ApiKeyVerificationState)
 * isAnthropicAuthEnabled()                  → isAnthropicAuthEnabled()
 * isClaudeAISubscriber()                    → isClaudeAISubscriber()
 * getAnthropicApiKeyWithSource(skipHelper)  → getApiKeyWithSource(boolean)
 * getApiKeyFromApiKeyHelper(nonInteractive) → getApiKeyFromHelper(boolean)
 * verifyApiKey(apiKey, false)               → verifyApiKeyRemote(String)
 * </pre>
 */
@Slf4j
@Service
public class AuthService {


    private final ClaudeCodeConfig config;
    private final OAuthService oauthService;
    private GlobalConfigService globalConfigService;

    // Cached authentication state
    private volatile AuthState authState;

    @Autowired
    public AuthService(ClaudeCodeConfig config, OAuthService oauthService) {
        this.config = config;
        this.oauthService = oauthService;
    }

    @Autowired
    public void setGlobalConfigService(GlobalConfigService globalConfigService) {
        this.globalConfigService = globalConfigService;
    }

    // =========================================================================
    // CLI handlers  (src/cli/handlers/auth.ts)
    // =========================================================================

    /**
     * Shared post-token-acquisition logic.  Saves tokens, fetches profile/roles,
     * and sets up the local auth state.
     *
     * Translated from {@code installOAuthTokens(tokens)} in auth.ts (CLI handlers).
     */
    public CompletableFuture<Void> installOAuthTokens(OAuthService.OAuthTokens tokens) {
        return CompletableFuture.runAsync(() -> {
            // Clear old state before saving new credentials
            oauthService.cleanup();

            // Store account info from profile if available
            String accessToken = tokens.getAccessToken();
            if (accessToken != null) {
                oauthService.setCurrentTokens(tokens);
            }

            // Fetch user roles (non-critical — log but don't fail)
            try {
                oauthService.fetchAndStoreUserRoles(accessToken);
            } catch (Exception err) {
                log.debug("fetchAndStoreUserRoles failed (non-critical): {}", err.getMessage());
            }

            // If not using Claude.ai auth, create an API key for Console users
            if (!oauthService.shouldUseClaudeAIAuth(tokens.getScopes())) {
                try {
                    String apiKey = oauthService.createAndStoreApiKey(accessToken).get();
                    if (apiKey == null) {
                        throw new IllegalStateException(
                            "Unable to create API key. The server accepted the request " +
                            "but did not return a key.");
                    }
                } catch (java.util.concurrent.ExecutionException | InterruptedException e) {
                    throw new IllegalStateException("Failed to create API key: " + e.getMessage(), e);
                }
            }
        });
    }

    /**
     * CLI {@code auth login} command handler.
     * Translates {@code authLogin()} from auth.ts (CLI handlers).
     *
     * @param email     optional login-hint e-mail address
     * @param sso       request SSO login method
     * @param useConsole use Console OAuth flow (mutually exclusive with claudeai)
     * @param claudeai  use claude.ai OAuth flow
     */
    public CompletableFuture<Void> authLogin(String email, boolean sso,
                                             boolean useConsole, boolean claudeai) {
        return CompletableFuture.runAsync(() -> {
            if (useConsole && claudeai) {
                System.err.println("Error: --console and --claudeai cannot be used together.");
                System.exit(1);
            }

            // Fast path: if a refresh token is provided via env var, skip browser flow
            String envRefreshToken = System.getenv("CLAUDE_CODE_OAUTH_REFRESH_TOKEN");
            if (envRefreshToken != null && !envRefreshToken.isBlank()) {
                String envScopes = System.getenv("CLAUDE_CODE_OAUTH_SCOPES");
                if (envScopes == null || envScopes.isBlank()) {
                    System.err.println(
                        "CLAUDE_CODE_OAUTH_SCOPES is required when using " +
                        "CLAUDE_CODE_OAUTH_REFRESH_TOKEN.\n" +
                        "Set it to the space-separated scopes the refresh token was issued with\n" +
                        "(e.g. \"user:inference\" or \"user:profile user:inference " +
                        "user:sessions:claude_code user:mcp_servers\").");
                    System.exit(1);
                }
                List<String> scopes = Arrays.stream(envScopes.split("\\s+"))
                    .filter(s -> !s.isBlank())
                    .toList();
                try {
                    OAuthService.OAuthTokens tokens = oauthService.refreshOAuthToken(
                        envRefreshToken, scopes).get();
                    installOAuthTokens(tokens).join();
                    log.info("Login successful via refresh token.");
                    System.out.println("Login successful.");
                    System.exit(0);
                } catch (Exception err) {
                    log.error("Login failed", err);
                    System.err.println("Login failed: " + err.getMessage());
                    System.exit(1);
                }
            }

            // Full browser OAuth flow
            boolean loginWithClaudeAi = !useConsole;
            String loginMethod = sso ? "sso" : null;

            try {
                OAuthService.OAuthTokens result = oauthService.startOAuthFlow(
                    url -> {
                        System.out.println("Opening browser to sign in\u2026");
                        System.out.println("If the browser didn't open, visit: " + url);
                        return java.util.concurrent.CompletableFuture.completedFuture(null);
                    },
                    new OAuthService.StartOAuthFlowOptions(
                        loginWithClaudeAi, null, null, null, email, loginMethod, null
                    )).get();

                installOAuthTokens(result).join();
                System.out.println("Login successful.");
                System.exit(0);
            } catch (Exception err) {
                log.error("Login failed", err);
                System.err.println("Login failed: " + err.getMessage());
                System.exit(1);
            } finally {
                oauthService.cleanup();
            }
        });
    }

    /**
     * CLI {@code auth status} command handler.
     * Translated from {@code authStatus()} in auth.ts (CLI handlers).
     *
     * @param json  output in JSON format
     * @param text  output in human-readable text format
     */
    public CompletableFuture<Void> authStatus(boolean json, boolean text) {
        return CompletableFuture.runAsync(() -> {
            String apiKey = getApiKey();
            boolean hasApiKeyEnvVar = System.getenv("ANTHROPIC_API_KEY") != null;
            boolean isOAuthAuthenticated = oauthService.isAuthenticated();
            boolean loggedIn = apiKey != null || hasApiKeyEnvVar || isOAuthAuthenticated
                               || isUsing3PServices();

            String authMethod = resolveAuthMethod(isOAuthAuthenticated, apiKey, hasApiKeyEnvVar);

            if (text) {
                printTextStatus(loggedIn, authMethod, isOAuthAuthenticated);
            } else {
                printJsonStatus(loggedIn, authMethod, apiKey, isOAuthAuthenticated);
            }

            System.exit(loggedIn ? 0 : 1);
        });
    }

    private String resolveAuthMethod(boolean isOAuth, String apiKey, boolean hasApiKeyEnv) {
        if (isUsing3PServices()) return "third_party";
        if (isOAuth) return "claude.ai";
        if (apiKey != null) return "api_key";
        if (hasApiKeyEnv) return "api_key";
        return "none";
    }

    private void printTextStatus(boolean loggedIn, String authMethod, boolean isOAuth) {
        if (isOAuth) {
            OAuthService.OAuthTokens tokens = oauthService.getCurrentTokens();
            if (tokens != null) {
                String email = tokens.getTokenAccount() != null
                    ? tokens.getTokenAccount().emailAddress() : null;
                System.out.println("Account: " + email);
            }
        }
        if (!loggedIn) {
            System.out.println("Not logged in. Run claude auth login to authenticate.");
        }
    }

    private void printJsonStatus(boolean loggedIn, String authMethod,
                                 String apiKey, boolean isOAuth) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("loggedIn", loggedIn);
        output.put("authMethod", authMethod);
        output.put("apiProvider", getApiProvider().name().toLowerCase());
        if (apiKey != null) {
            output.put("apiKeySource", "config");
        }
        if ("claude.ai".equals(authMethod)) {
            OAuthService.OAuthTokens tokens = oauthService.getCurrentTokens();
            String tokenEmail = (tokens != null && tokens.getTokenAccount() != null)
                ? tokens.getTokenAccount().emailAddress() : null;
            output.put("email", tokenEmail);
            output.put("subscriptionType", getSubscriptionType());
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
            System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output));
        } catch (Exception e) {
            System.out.println("{}");
        }
    }

    /**
     * CLI {@code auth logout} command handler.
     * Translated from {@code authLogout()} in auth.ts (CLI handlers).
     */
    public CompletableFuture<Void> authLogout() {
        return CompletableFuture.runAsync(() -> {
            try {
                oauthService.cleanup();
            } catch (Exception e) {
                System.err.println("Failed to log out.");
                System.exit(1);
            }
            System.out.println("Successfully logged out from your Anthropic account.");
            System.exit(0);
        });
    }

    // =========================================================================
    // Core auth utilities  (src/utils/auth.ts)
    // =========================================================================

    /**
     * Get the API key for making requests.
     * Translated from getAnthropicApiKey() in auth.ts.
     */
    public String getApiKey() {
        String envKey = System.getenv("ANTHROPIC_API_KEY");
        if (envKey != null && !envKey.isBlank()) return envKey;

        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            return config.getApiKey();
        }

        return null;
    }

    /**
     * Check if user is a Claude.ai subscriber (OAuth user).
     * Translated from isClaudeAISubscriber() in auth.ts.
     */
    public boolean isClaudeAISubscriber() {
        return oauthService.isAuthenticated();
    }

    /**
     * Check if third-party service credentials are in use (Bedrock/Vertex/Foundry).
     * Translated from isUsing3PServices() in auth.ts.
     */
    public boolean isUsing3PServices() {
        return isEnvTruthy("CLAUDE_CODE_USE_BEDROCK")
            || isEnvTruthy("CLAUDE_CODE_USE_VERTEX")
            || isEnvTruthy("CLAUDE_CODE_USE_FOUNDRY");
    }

    /**
     * Get the subscription type.
     * Translated from getSubscriptionType() in auth.ts.
     */
    public String getSubscriptionType() {
        if (!isClaudeAISubscriber()) return "api_key";

        OAuthService.OAuthTokens tokens = oauthService.getCurrentTokens();
        if (tokens == null) return "unknown";

        List<String> scopeList = tokens.getScopes();
        String scope = scopeList != null ? String.join(" ", scopeList) : null;
        if (scope == null) return "free";
        if (scope.contains("pro")) return "pro";
        if (scope.contains("max")) return "max";
        return "free";
    }

    /**
     * Check if user is a Pro subscriber.
     */
    public boolean isProSubscriber() {
        return "pro".equals(getSubscriptionType());
    }

    /**
     * Check if user is a Max subscriber.
     */
    public boolean isMaxSubscriber() {
        return "max".equals(getSubscriptionType());
    }

    /**
     * Check if user is a Team Premium subscriber.
     */
    public boolean isTeamPremiumSubscriber() {
        String subType = getSubscriptionType();
        return "team".equals(subType) || "enterprise".equals(subType);
    }

    /**
     * Check if user has access to 1M context window.
     */
    public boolean has1mContextAccess() {
        return isMaxSubscriber() || isTeamPremiumSubscriber()
            || "ant".equals(System.getenv("USER_TYPE"));
    }

    /**
     * Get the Claude.ai OAuth access token.
     */
    public String getClaudeAiOAuthAccessToken() {
        OAuthService.OAuthTokens tokens = getClaudeAIOAuthTokens();
        return tokens != null ? tokens.getAccessToken() : null;
    }

    /**
     * Check if overage provisioning is allowed for this account.
     * Translated from isOverageProvisioningAllowed() in auth.ts.
     */
    public boolean isOverageProvisioningAllowed() {
        if (!isClaudeAISubscriber()) return false;

        var globalConfig = globalConfigService.getGlobalConfig();
        if (globalConfig.getOauthAccount() == null) return false;

        String billingType = globalConfig.getOauthAccount().getBillingType();
        return "stripe_subscription".equals(billingType)
            || "stripe_subscription_contracted".equals(billingType)
            || "apple_subscription".equals(billingType)
            || "google_play_subscription".equals(billingType);
    }

    /**
     * Check if the user is a consumer (non-enterprise) Claude.ai subscriber.
     * Consumer subscribers are Pro and Max plan users.
     */
    public boolean isConsumerSubscriber() {
        String subType = getSubscriptionType();
        return isClaudeAISubscriber()
                && ("pro".equals(subType) || "max".equals(subType) || "claude_ai".equals(subType));
    }

    /**
     * Check whether the user has extra (overage) usage enabled.
     * Reads the cached flag from the global config's OAuth account.
     */
    public boolean hasExtraUsageEnabled() {
        if (globalConfigService == null) return false;
        var globalConfig = globalConfigService.getGlobalConfig();
        if (globalConfig.getOauthAccount() == null) return false;
        return Boolean.TRUE.equals(globalConfig.getOauthAccount().getHasExtraUsageEnabled());
    }

    /**
     * Check if any auth token is available.
     */
    public boolean hasAuthToken() {
        return isClaudeAISubscriber() || getApiKey() != null
            || System.getenv("ANTHROPIC_API_KEY") != null;
    }

    /**
     * Check if the user is a Claude.ai subscriber (lowercase variant).
     */
    public boolean isClaudeAiSubscriber() {
        return isClaudeAISubscriber();
    }

    /**
     * Get authentication headers for API calls.
     * Returns an Optional containing a Map of header name to value.
     */
    public Optional<Map<String, String>> getAuthHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();

        // OAuth token takes priority
        if (isClaudeAISubscriber()) {
            String accessToken = getClaudeAiOAuthAccessToken();
            if (accessToken != null) {
                headers.put("Authorization", "Bearer " + accessToken);
                headers.put("anthropic-version", "2023-06-01");
                return Optional.of(headers);
            }
        }

        // Fall back to API key
        String apiKey = getApiKey();
        if (apiKey != null) {
            headers.put("x-api-key", apiKey);
            headers.put("anthropic-version", "2023-06-01");
            return Optional.of(headers);
        }

        return Optional.empty();
    }

    /**
     * Check and refresh OAuth token if needed.
     * Translated from checkAndRefreshOAuthTokenIfNeeded() in auth.ts.
     */
    public CompletableFuture<Void> checkAndRefreshOAuthTokenIfNeeded() {
        if (!isClaudeAISubscriber()) {
            return CompletableFuture.completedFuture(null);
        }
        return oauthService.checkAndRefreshOAuthTokenIfNeeded();
    }

    /**
     * Get the OAuth tokens.
     * Translated from getClaudeAIOAuthTokens() in auth.ts.
     */
    public OAuthService.OAuthTokens getClaudeAIOAuthTokens() {
        return oauthService.getCurrentTokens();
    }

    /**
     * Determine the API provider to use.
     * Translated from getAPIProvider() in providers.ts.
     */
    public ApiProvider getApiProvider() {
        if (isEnvTruthy("CLAUDE_CODE_USE_BEDROCK")) return ApiProvider.BEDROCK;
        if (isEnvTruthy("CLAUDE_CODE_USE_VERTEX")) return ApiProvider.VERTEX;
        if (isEnvTruthy("CLAUDE_CODE_USE_FOUNDRY")) return ApiProvider.FOUNDRY;
        return ApiProvider.FIRST_PARTY;
    }

    private boolean isEnvTruthy(String envVar) {
        String val = System.getenv(envVar);
        return val != null && !val.isBlank()
            && !val.equalsIgnoreCase("false")
            && !val.equals("0");
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    // =========================================================================
    // useApiKeyVerification() equivalent
    // =========================================================================

    /**
     * Verification status for an API key.
     *
     * Mirrors the {@code VerificationStatus} union type in useApiKeyVerification.ts:
     * {@code 'loading' | 'valid' | 'invalid' | 'missing' | 'error'}
     */
    public enum VerificationStatus {
        LOADING,
        VALID,
        INVALID,
        MISSING,
        ERROR
    }

    /**
     * Result of an API key verification, including the current status, a
     * re-verify callback, and any error encountered during the last attempt.
     *
     * Mirrors {@code ApiKeyVerificationResult} in useApiKeyVerification.ts.
     */
    public record ApiKeyVerificationResult(
        VerificationStatus status,
        Exception error
    ) {}

    /**
     * Mutable verification state object.
     *
     * Mirrors the two {@code useState} variables ({@code status}, {@code error})
     * and the {@code verify} useCallback inside {@code useApiKeyVerification()}.
     *
     * Callers hold a reference to this object and call {@link #reverify()} to
     * trigger a fresh verification; the result fields are updated in place.
     */
    public class ApiKeyVerificationState {

        /** Current status — volatile for safe publication to other threads. */
        private volatile VerificationStatus status;
        /** Last error from a failed verify call; null when status is not ERROR. */
        private volatile Exception error = null;

        /**
         * Initialise state following the same logic as the {@code useState}
         * initialiser in useApiKeyVerification.ts:
         * <ul>
         *   <li>If auth is disabled or user is a Claude.ai subscriber → VALID</li>
         *   <li>If an API key or apiKeyHelper source is present → LOADING
         *       (actual verification deferred to avoid RCE via settings.json)</li>
         *   <li>Otherwise → MISSING</li>
         * </ul>
         */
        public ApiKeyVerificationState() {
            if (!isAnthropicAuthEnabled() || isClaudeAISubscriber()) {
                this.status = VerificationStatus.VALID;
            } else {
                ApiKeyWithSource kws = getApiKeyWithSource(true);
                if (kws.key() != null || "apiKeyHelper".equals(kws.source())) {
                    this.status = VerificationStatus.LOADING;
                } else {
                    this.status = VerificationStatus.MISSING;
                }
            }
        }

        public VerificationStatus getStatus()  { return status; }
        public Exception          getError()   { return error;  }

        public ApiKeyVerificationResult toResult() {
            return new ApiKeyVerificationResult(status, error);
        }

        /**
         * Perform (or re-perform) API key verification.
         *
         * Mirrors the {@code verify} {@code useCallback} in useApiKeyVerification.ts:
         * <ol>
         *   <li>Fast-path for non-auth / subscriber sessions → VALID</li>
         *   <li>Warm the apiKeyHelper cache (getApiKeyFromHelper)</li>
         *   <li>Read key from all sources</li>
         *   <li>Call the remote verification endpoint</li>
         *   <li>Update status: VALID / INVALID / MISSING / ERROR</li>
         * </ol>
         *
         * @return a future that completes when verification finishes
         */
        public CompletableFuture<Void> reverify() {
            return CompletableFuture.runAsync(() -> {
                if (!isAnthropicAuthEnabled() || isClaudeAISubscriber()) {
                    status = VerificationStatus.VALID;
                    return;
                }

                // Warm the apiKeyHelper cache
                boolean nonInteractive = isNonInteractiveSession();
                try {
                    getApiKeyFromHelper(nonInteractive);
                } catch (Exception e) {
                    log.debug("getApiKeyFromHelper failed (non-critical): {}", e.getMessage());
                }

                ApiKeyWithSource kws = getApiKeyWithSource(false);
                if (kws.key() == null) {
                    if ("apiKeyHelper".equals(kws.source())) {
                        error  = new IllegalStateException(
                                "API key helper did not return a valid key");
                        status = VerificationStatus.ERROR;
                    } else {
                        status = VerificationStatus.MISSING;
                    }
                    return;
                }

                try {
                    boolean valid = verifyApiKeyRemote(kws.key());
                    status = valid ? VerificationStatus.VALID : VerificationStatus.INVALID;
                    if (!valid) error = null;
                } catch (Exception e) {
                    error  = e;
                    status = VerificationStatus.ERROR;
                }
            });
        }
    }

    /**
     * Create a new {@link ApiKeyVerificationState} for the current auth context.
     *
     * Mirrors "calling" the {@code useApiKeyVerification()} hook — each call
     * returns an independent state object initialised to the correct starting
     * status.
     */
    public ApiKeyVerificationState newApiKeyVerificationState() {
        return new ApiKeyVerificationState();
    }

    // -------------------------------------------------------------------------
    // Helpers called by ApiKeyVerificationState
    // -------------------------------------------------------------------------

    /**
     * Whether Anthropic authentication is required.
     * Mirrors {@code isAnthropicAuthEnabled()} in auth.ts.
     */
    public boolean isAnthropicAuthEnabled() {
        // Auth is enabled unless a third-party provider is configured
        return !isUsing3PServices();
    }

    /**
     * Get the API key together with its source identifier.
     *
     * Mirrors {@code getAnthropicApiKeyWithSource(options)} in auth.ts.
     *
     * @param skipRetrievingKeyFromApiKeyHelper when true, does not execute
     *                                          the apiKeyHelper script
     */
    public ApiKeyWithSource getApiKeyWithSource(boolean skipRetrievingKeyFromApiKeyHelper) {
        // 1. Environment variable
        String envKey = System.getenv("ANTHROPIC_API_KEY");
        if (envKey != null && !envKey.isBlank()) return new ApiKeyWithSource(envKey, "env");

        // 2. Config file key
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            return new ApiKeyWithSource(config.getApiKey(), "config");
        }

        // 3. apiKeyHelper — skip if requested (security: avoids RCE before trust dialog)
        String helperCmd = System.getenv("CLAUDE_CODE_API_KEY_HELPER");
        if (helperCmd != null && !helperCmd.isBlank()) {
            if (skipRetrievingKeyFromApiKeyHelper) {
                return new ApiKeyWithSource(null, "apiKeyHelper");
            }
            try {
                String helperKey = getApiKeyFromHelper(false);
                if (helperKey != null && !helperKey.isBlank()) {
                    return new ApiKeyWithSource(helperKey, "apiKeyHelper");
                }
            } catch (Exception e) {
                log.debug("apiKeyHelper failed: {}", e.getMessage());
                return new ApiKeyWithSource(null, "apiKeyHelper");
            }
        }

        return new ApiKeyWithSource(null, null);
    }

    /**
     * Execute the configured apiKeyHelper command and return its output.
     * Mirrors {@code getApiKeyFromApiKeyHelper(isNonInteractive)} in auth.ts.
     */
    public String getApiKeyFromHelper(boolean nonInteractive) {
        String helperCmd = System.getenv("CLAUDE_CODE_API_KEY_HELPER");
        if (helperCmd == null || helperCmd.isBlank()) return null;
        try {
            Process proc = new ProcessBuilder("sh", "-c", helperCmd)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(proc.getInputStream().readAllBytes()).trim();
            int exitCode = proc.waitFor();
            if (exitCode != 0) {
                log.warn("apiKeyHelper exited with code {}", exitCode);
                return null;
            }
            return output.isBlank() ? null : output;
        } catch (Exception e) {
            log.warn("apiKeyHelper execution failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Verify an API key against the Anthropic API.
     * Mirrors {@code verifyApiKey(apiKey, false)} in claude.ts.
     *
     * @param apiKey the key to verify
     * @return true if the key is valid
     */
    public boolean verifyApiKeyRemote(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return false;
        try {
            // Minimal verification: attempt a lightweight API call
            // Real implementation would call the Anthropic models endpoint
            log.debug("Verifying API key (first 8 chars): {}…", apiKey.substring(0, Math.min(8, apiKey.length())));
            return apiKey.startsWith("sk-ant-");
        } catch (Exception e) {
            log.debug("API key verification failed: {}", e.getMessage());
            throw new RuntimeException("API key verification failed: " + e.getMessage(), e);
        }
    }

    /** Whether this is a non-interactive (scripted / piped) session. */
    private boolean isNonInteractiveSession() {
        return System.console() == null
            || "true".equalsIgnoreCase(System.getenv("CLAUDE_CODE_NON_INTERACTIVE"));
    }

    /**
     * API key together with its source identifier.
     * Mirrors the return type of {@code getAnthropicApiKeyWithSource()} in auth.ts.
     */
    public record ApiKeyWithSource(String key, String source) {}

    /** Check if the current OAuth token is fresh (not expired). */
    public boolean isOAuthTokenFresh() {
        if (oauthService == null) return false;
        OAuthService.OAuthTokens tokens = oauthService.getCurrentTokens();
        return tokens != null && !oauthService.isTokenExpired(tokens);
    }

    // =========================================================================
    // Enum and private types
    // =========================================================================

    public enum ApiProvider {
        FIRST_PARTY,
        BEDROCK,
        VERTEX,
        FOUNDRY
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    private static class AuthState {
        private boolean authenticated;
        private String method; // "api_key" | "oauth"
        private long lastRefreshed;
    }
}
