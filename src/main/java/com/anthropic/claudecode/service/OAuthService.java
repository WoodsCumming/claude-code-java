package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.OAuthTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * OAuth authentication service.
 * Translated from src/services/oauth/index.ts AND src/services/oauth/client.ts
 *
 * Handles the OAuth 2.0 authorization code flow with PKCE for Claude.ai.
 * Supports both automatic (browser redirect to localhost) and manual
 * (user pastes code) flows.
 */
@Slf4j
@Service
public class OAuthService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OAuthService.class);


    // -------------------------------------------------------------------------
    // OAuth constants (from constants/oauth.ts)
    // -------------------------------------------------------------------------

    private static final String CONSOLE_AUTHORIZE_URL = "https://claude.ai/oauth/authorize";
    private static final String CLAUDE_AI_AUTHORIZE_URL = "https://claude.ai/oauth/authorize";
    private static final String TOKEN_URL = "https://claude.ai/oauth/token";
    private static final String CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";
    private static final String MANUAL_REDIRECT_URL = "https://claude.ai/cli/callback";
    private static final String BASE_API_URL = "https://api.anthropic.com";
    private static final String ROLES_URL = BASE_API_URL + "/api/oauth/roles";
    private static final String API_KEY_URL = BASE_API_URL + "/api/oauth/api_key";

    private static final String CLAUDE_AI_INFERENCE_SCOPE = "user:inference";
    private static final List<String> CLAUDE_AI_OAUTH_SCOPES =
        List.of("org:create_api_key", "user:profile", "user:inference");
    private static final List<String> ALL_OAUTH_SCOPES = CLAUDE_AI_OAUTH_SCOPES;

    // -------------------------------------------------------------------------
    // Nested records / types
    // -------------------------------------------------------------------------

    /** OAuth tokens returned after successful authorization. */
    public static class OAuthTokens {
        private String accessToken;
        private String refreshToken;
        private long expiresAt;   // epoch millis
        private List<String> scopes;
        private String subscriptionType;
        private String rateLimitTier;
        private OAuthProfileResponse profile;
        private TokenAccount tokenAccount;

        public OAuthTokens() {}

        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String v) { this.accessToken = v; }
        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(String v) { this.refreshToken = v; }
        public long getExpiresAt() { return expiresAt; }
        public void setExpiresAt(long v) { this.expiresAt = v; }
        public List<String> getScopes() { return scopes; }
        public void setScopes(List<String> v) { this.scopes = v; }
        public String getSubscriptionType() { return subscriptionType; }
        public void setSubscriptionType(String v) { this.subscriptionType = v; }
        public String getRateLimitTier() { return rateLimitTier; }
        public void setRateLimitTier(String v) { this.rateLimitTier = v; }
        public OAuthProfileResponse getProfile() { return profile; }
        public void setProfile(OAuthProfileResponse v) { this.profile = v; }
        public TokenAccount getTokenAccount() { return tokenAccount; }
        public void setTokenAccount(TokenAccount v) { this.tokenAccount = v; }

        /** Check if the token has the claude.ai inference scope. */
        public boolean hasClaudeAiInferenceScope() {
            return scopes != null && scopes.contains("user:inference");
        }

        /** Check if the token has the profile scope. */
        public boolean hasProfileScope() {
            return scopes != null && (scopes.contains("user:profile")
                || scopes.contains("org:create_api_key"));
        }

        /** Check if the token is expired. */
        public boolean isExpired() {
            return expiresAt > 0 && System.currentTimeMillis() > expiresAt;
        }
    }

    public record TokenAccount(String uuid, String emailAddress, String organizationUuid) {}

    public record OAuthProfileResponse(
        AccountInfo account,
        OrganizationInfo organization
    ) {}

    public record AccountInfo(
        String uuid,
        String email,
        String display_name,
        String created_at
    ) {}

    public record OrganizationInfo(
        String uuid,
        String organization_type,
        String rate_limit_tier,
        Boolean has_extra_usage_enabled,
        String billing_type,
        String subscription_created_at
    ) {}

    /** Response from the token exchange endpoint. */
    public record OAuthTokenExchangeResponse(
        String access_token,
        String refresh_token,
        long expires_in,
        String scope,
        AccountInfo account,
        OrganizationInfo organization
    ) {}

    /** Response from the user roles endpoint. */
    public record UserRolesResponse(
        String organization_role,
        String workspace_role,
        String organization_name
    ) {}

    /** Profile info extracted from OAuthProfileResponse. */
    public record ProfileInfo(
        String subscriptionType,
        String rateLimitTier,
        Boolean hasExtraUsageEnabled,
        String billingType,
        String displayName,
        String accountCreatedAt,
        String subscriptionCreatedAt,
        OAuthProfileResponse rawProfile
    ) {}

    // Result of buildAuthUrl for PKCE
    public record AuthUrlResult(
        String url,
        String codeVerifier,
        String state,
        String redirectUri
    ) {}

    // -------------------------------------------------------------------------
    // OAuthService class state (from index.ts)
    // -------------------------------------------------------------------------

    private String codeVerifier;
    private volatile String manualAuthCodePending = null;
    private volatile CompletableFuture<String> manualCodeFuture = null;

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final ObjectMapper objectMapper;
    private final OAuthProfileService oauthProfileService;
    private final AnalyticsService analyticsService;

    // In-memory token storage
    private volatile OAuthTokens currentTokens;

    @Autowired
    public OAuthService(
            ObjectMapper objectMapper,
            OAuthProfileService oauthProfileService,
            AnalyticsService analyticsService) {
        this.objectMapper = objectMapper;
        this.oauthProfileService = oauthProfileService;
        this.analyticsService = analyticsService;
        // Generate code verifier at construction time (mirrors TS constructor)
        try {
            this.codeVerifier = generateCodeVerifier();
        } catch (Exception e) {
            this.codeVerifier = UUID.randomUUID().toString().replace("-", "");
        }
    }

    // =========================================================================
    // OAuthService class API (from index.ts)
    // =========================================================================

    /**
     * Start the full OAuth authorization code flow with PKCE.
     *
     * Supports two paths:
     *  1. Automatic — opens browser, redirects to localhost where we capture the code
     *  2. Manual — user copies and pastes the code
     *
     * Translated from OAuthService.startOAuthFlow() in index.ts
     */
    public CompletableFuture<OAuthTokens> startOAuthFlow(
            Function<String, CompletableFuture<Void>> authURLHandler,
            StartOAuthFlowOptions options) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                String codeChallenge = generateCodeChallenge(codeVerifier);
                String state = generateState();
                int port = 53682; // default local callback port

                // Build auth URLs for both automatic and manual flows
                String manualFlowUrl = buildAuthUrl(new BuildAuthUrlOptions(
                    codeChallenge, state, port, true,
                    options != null && Boolean.TRUE.equals(options.loginWithClaudeAi()),
                    options != null && Boolean.TRUE.equals(options.inferenceOnly()),
                    options != null ? options.orgUUID() : null,
                    options != null ? options.loginHint() : null,
                    options != null ? options.loginMethod() : null
                ));
                String automaticFlowUrl = buildAuthUrl(new BuildAuthUrlOptions(
                    codeChallenge, state, port, false,
                    options != null && Boolean.TRUE.equals(options.loginWithClaudeAi()),
                    options != null && Boolean.TRUE.equals(options.inferenceOnly()),
                    options != null ? options.orgUUID() : null,
                    options != null ? options.loginHint() : null,
                    options != null ? options.loginMethod() : null
                ));

                // Show manual URL to user, attempt automatic in background
                authURLHandler.apply(manualFlowUrl).get();

                // Wait for authorization code (manual or automatic)
                String authorizationCode = waitForAuthorizationCode(state, automaticFlowUrl).get();

                boolean isAutomaticFlow = (manualAuthCodePending == null);
                analyticsService.logEvent("tengu_oauth_auth_code_received",
                    Map.of("automatic", isAutomaticFlow));

                // Exchange authorization code for tokens
                OAuthTokenExchangeResponse tokenResponse = exchangeCodeForTokens(
                    authorizationCode, state, codeVerifier, port,
                    !isAutomaticFlow, options != null ? options.expiresIn() : null
                ).get();

                // Fetch profile info
                ProfileInfo profileInfo = fetchProfileInfo(tokenResponse.access_token()).get();

                return formatTokens(tokenResponse,
                    profileInfo != null ? profileInfo.subscriptionType() : null,
                    profileInfo != null ? profileInfo.rateLimitTier() : null,
                    profileInfo != null ? profileInfo.rawProfile() : null);

            } catch (Exception e) {
                throw new RuntimeException("OAuth flow failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Handle manual auth code input when the user pastes the code.
     * Translated from OAuthService.handleManualAuthCodeInput() in index.ts
     */
    public void handleManualAuthCodeInput(String authorizationCode, String state) {
        manualAuthCodePending = authorizationCode;
        if (manualCodeFuture != null) {
            manualCodeFuture.complete(authorizationCode);
            manualCodeFuture = null;
        }
    }

    /** Clean up resources. Translated from OAuthService.cleanup() in index.ts */
    public void cleanup() {
        manualAuthCodePending = null;
        if (manualCodeFuture != null && !manualCodeFuture.isDone()) {
            manualCodeFuture.cancel(true);
        }
        manualCodeFuture = null;
    }

    private CompletableFuture<String> waitForAuthorizationCode(String state, String automaticFlowUrl) {
        manualCodeFuture = new CompletableFuture<>();
        // In a real implementation this would also start a local HTTP listener for automatic flow
        return manualCodeFuture;
    }

    private OAuthTokens formatTokens(
            OAuthTokenExchangeResponse response,
            String subscriptionType,
            String rateLimitTier,
            OAuthProfileResponse rawProfile) {
        OAuthTokens tokens = new OAuthTokens();
        tokens.setAccessToken(response.access_token());
        tokens.setRefreshToken(response.refresh_token());
        tokens.setExpiresAt(System.currentTimeMillis() + response.expires_in() * 1000L);
        tokens.setScopes(parseScopes(response.scope()));
        tokens.setSubscriptionType(subscriptionType);
        tokens.setRateLimitTier(rateLimitTier);
        tokens.setProfile(rawProfile);
        if (response.account() != null) {
            tokens.setTokenAccount(new TokenAccount(
                response.account().uuid(),
                response.account().email(),
                response.organization() != null ? response.organization().uuid() : null
            ));
        }
        return tokens;
    }

    // =========================================================================
    // client.ts API
    // =========================================================================

    /**
     * Check if the user has Claude.ai authentication scope.
     * Translated from shouldUseClaudeAIAuth() in client.ts
     */
    public static boolean shouldUseClaudeAIAuth(List<String> scopes) {
        return scopes != null && scopes.contains(CLAUDE_AI_INFERENCE_SCOPE);
    }

    /**
     * Parse a space-separated scope string into a list.
     * Translated from parseScopes() in client.ts
     */
    public static List<String> parseScopes(String scopeString) {
        if (scopeString == null || scopeString.isBlank()) return List.of();
        return Arrays.stream(scopeString.split(" "))
            .filter(s -> !s.isBlank())
            .toList();
    }

    /**
     * Build the OAuth authorization URL.
     * Translated from buildAuthUrl() in client.ts
     */
    public String buildAuthUrl(BuildAuthUrlOptions opts) {
        String baseUrl = Boolean.TRUE.equals(opts.loginWithClaudeAi())
            ? CLAUDE_AI_AUTHORIZE_URL
            : CONSOLE_AUTHORIZE_URL;

        try {
            StringBuilder url = new StringBuilder(baseUrl).append("?");
            List<String[]> params = new ArrayList<>();
            params.add(new String[]{"code", "true"});
            params.add(new String[]{"client_id", CLIENT_ID});
            params.add(new String[]{"response_type", "code"});
            params.add(new String[]{"redirect_uri",
                Boolean.TRUE.equals(opts.isManual())
                    ? MANUAL_REDIRECT_URL
                    : "http://localhost:" + opts.port() + "/callback"});

            List<String> scopesToUse = Boolean.TRUE.equals(opts.inferenceOnly())
                ? List.of(CLAUDE_AI_INFERENCE_SCOPE)
                : ALL_OAUTH_SCOPES;
            params.add(new String[]{"scope", String.join(" ", scopesToUse)});
            params.add(new String[]{"code_challenge", opts.codeChallenge()});
            params.add(new String[]{"code_challenge_method", "S256"});
            params.add(new String[]{"state", opts.state()});

            if (opts.orgUUID() != null) params.add(new String[]{"orgUUID", opts.orgUUID()});
            if (opts.loginHint() != null) params.add(new String[]{"login_hint", opts.loginHint()});
            if (opts.loginMethod() != null) params.add(new String[]{"login_method", opts.loginMethod()});

            for (int i = 0; i < params.size(); i++) {
                if (i > 0) url.append("&");
                url.append(URLEncoder.encode(params.get(i)[0], StandardCharsets.UTF_8));
                url.append("=");
                url.append(URLEncoder.encode(params.get(i)[1], StandardCharsets.UTF_8));
            }
            return url.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build auth URL", e);
        }
    }

    /**
     * Options for buildAuthUrl.
     */
    public record BuildAuthUrlOptions(
        String codeChallenge,
        String state,
        int port,
        Boolean isManual,
        Boolean loginWithClaudeAi,
        Boolean inferenceOnly,
        String orgUUID,
        String loginHint,
        String loginMethod
    ) {}

    /**
     * Options for startOAuthFlow.
     */
    public record StartOAuthFlowOptions(
        Boolean loginWithClaudeAi,
        Boolean inferenceOnly,
        Long expiresIn,
        String orgUUID,
        String loginHint,
        String loginMethod,
        Boolean skipBrowserOpen
    ) {}

    /**
     * Exchange authorization code for tokens.
     * Translated from exchangeCodeForTokens() in client.ts
     */
    public CompletableFuture<OAuthTokenExchangeResponse> exchangeCodeForTokens(
            String authorizationCode,
            String state,
            String codeVerifier,
            int port,
            boolean useManualRedirect,
            Long expiresIn) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> requestBody = new LinkedHashMap<>();
                requestBody.put("grant_type", "authorization_code");
                requestBody.put("code", authorizationCode);
                requestBody.put("redirect_uri", useManualRedirect
                    ? MANUAL_REDIRECT_URL
                    : "http://localhost:" + port + "/callback");
                requestBody.put("client_id", CLIENT_ID);
                requestBody.put("code_verifier", codeVerifier);
                requestBody.put("state", state);
                if (expiresIn != null) requestBody.put("expires_in", expiresIn);

                String body = objectMapper.writeValueAsString(requestBody);
                HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(15))
                    .build();
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TOKEN_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .build();

                HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    String msg = response.statusCode() == 401
                        ? "Authentication failed: Invalid authorization code"
                        : "Token exchange failed (" + response.statusCode() + "): " + response.body();
                    throw new RuntimeException(msg);
                }

                analyticsService.logEvent("tengu_oauth_token_exchange_success", Map.of());
                return objectMapper.readValue(response.body(), OAuthTokenExchangeResponse.class);

            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Token exchange failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Refresh an OAuth access token using the refresh token.
     * Translated from refreshOAuthToken() in client.ts
     */
    public CompletableFuture<OAuthTokens> refreshOAuthToken(
            String refreshToken,
            List<String> requestedScopes) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                List<String> scopesToUse = (requestedScopes != null && !requestedScopes.isEmpty())
                    ? requestedScopes
                    : CLAUDE_AI_OAUTH_SCOPES;

                Map<String, Object> requestBody = new LinkedHashMap<>();
                requestBody.put("grant_type", "refresh_token");
                requestBody.put("refresh_token", refreshToken);
                requestBody.put("client_id", CLIENT_ID);
                requestBody.put("scope", String.join(" ", scopesToUse));

                String body = objectMapper.writeValueAsString(requestBody);
                HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(15))
                    .build();
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TOKEN_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .build();

                HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    throw new RuntimeException("Token refresh failed: " + response.statusCode());
                }

                OAuthTokenExchangeResponse data =
                    objectMapper.readValue(response.body(), OAuthTokenExchangeResponse.class);

                String newRefreshToken = data.refresh_token() != null
                    ? data.refresh_token()
                    : refreshToken;
                long expiresAt = System.currentTimeMillis() + data.expires_in() * 1000L;

                analyticsService.logEvent("tengu_oauth_token_refresh_success", Map.of());

                // Fetch profile info (skip if we already have subscription data)
                ProfileInfo profileInfo = null;
                if (currentTokens == null
                        || currentTokens.getSubscriptionType() == null
                        || currentTokens.getRateLimitTier() == null) {
                    profileInfo = fetchProfileInfo(data.access_token()).get();
                }

                OAuthTokens tokens = new OAuthTokens();
                tokens.setAccessToken(data.access_token());
                tokens.setRefreshToken(newRefreshToken);
                tokens.setExpiresAt(expiresAt);
                tokens.setScopes(parseScopes(data.scope()));
                tokens.setSubscriptionType(profileInfo != null
                    ? profileInfo.subscriptionType()
                    : (currentTokens != null ? currentTokens.getSubscriptionType() : null));
                tokens.setRateLimitTier(profileInfo != null
                    ? profileInfo.rateLimitTier()
                    : (currentTokens != null ? currentTokens.getRateLimitTier() : null));
                tokens.setProfile(profileInfo != null ? profileInfo.rawProfile() : null);
                if (data.account() != null) {
                    tokens.setTokenAccount(new TokenAccount(
                        data.account().uuid(),
                        data.account().email(),
                        data.organization() != null ? data.organization().uuid() : null
                    ));
                }
                this.currentTokens = tokens;
                return tokens;

            } catch (RuntimeException e) {
                analyticsService.logEvent("tengu_oauth_token_refresh_failure",
                    Map.of("error", e.getMessage() != null ? e.getMessage().hashCode() : 0));
                throw e;
            } catch (Exception e) {
                analyticsService.logEvent("tengu_oauth_token_refresh_failure",
                    Map.of("error", e.getMessage() != null ? e.getMessage().hashCode() : 0));
                throw new RuntimeException("Token refresh failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Fetch and store user roles from the OAuth API.
     * Translated from fetchAndStoreUserRoles() in client.ts
     */
    public CompletableFuture<UserRolesResponse> fetchAndStoreUserRoles(String accessToken) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ROLES_URL))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(15))
                    .build();

                HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    throw new RuntimeException("Failed to fetch user roles: " + response.statusCode());
                }

                return objectMapper.readValue(response.body(), UserRolesResponse.class);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Failed to fetch user roles: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Create and store an API key via the OAuth API.
     * Translated from createAndStoreApiKey() in client.ts
     */
    public CompletableFuture<String> createAndStoreApiKey(String accessToken) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_KEY_URL))
                    .header("Authorization", "Bearer " + accessToken)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(java.time.Duration.ofSeconds(15))
                    .build();

                HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

                @SuppressWarnings("unchecked")
                Map<String, Object> data = objectMapper.readValue(response.body(), Map.class);
                String apiKey = data.get("raw_key") instanceof String k ? k : null;
                if (apiKey != null) {
                    analyticsService.logEvent("tengu_oauth_api_key",
                        Map.of("statusCode", response.statusCode()));
                    return apiKey;
                }
                return null;
            } catch (Exception e) {
                analyticsService.logEvent("tengu_oauth_api_key",
                    Map.of("error", e.getMessage() != null ? e.getMessage().hashCode() : 0));
                throw new RuntimeException("Failed to create API key: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Check if an OAuth token is expired (with 5-minute buffer).
     * Translated from isOAuthTokenExpired() in client.ts
     */
    public static boolean isOAuthTokenExpired(Long expiresAt) {
        if (expiresAt == null) return false;
        long bufferMs = 5 * 60 * 1000L;
        return (System.currentTimeMillis() + bufferMs) >= expiresAt;
    }

    /**
     * Fetch profile info from an OAuth access token.
     * Translated from fetchProfileInfo() in client.ts
     */
    public CompletableFuture<ProfileInfo> fetchProfileInfo(String accessToken) {
        return oauthProfileService.getOauthProfileFromOauthToken(accessToken)
            .thenApply(profileOpt -> {
                OAuthTypes.OAuthProfileResponse rawProfile = profileOpt.orElse(null);
                if (rawProfile == null) {
                    analyticsService.logEvent("tengu_oauth_profile_fetch_success", Map.of());
                    return new ProfileInfo(null, null, null, null, null, null, null, null);
                }

                String subscriptionType = rawProfile.getSubscriptionType();
                String rateLimitTier = rawProfile.getRateLimitTier();
                Boolean hasExtraUsage = rawProfile.getHasExtraUsageEnabled();
                String billingType = rawProfile.getBillingType();
                String displayName = rawProfile.getDisplayName();
                String accountCreatedAt = null;
                String subscriptionCreatedAt = null;

                analyticsService.logEvent("tengu_oauth_profile_fetch_success", Map.of());
                return new ProfileInfo(
                    subscriptionType, rateLimitTier, hasExtraUsage,
                    billingType, displayName, accountCreatedAt,
                    subscriptionCreatedAt, null
                );
            });
    }

    /**
     * Get the organization UUID from current tokens or OAuth profile.
     * Translated from getOrganizationUUID() in client.ts
     */
    public CompletableFuture<String> getOrganizationUUID() {
        return CompletableFuture.supplyAsync(() -> {
            // Try from current tokens first
            if (currentTokens != null && currentTokens.getTokenAccount() != null
                    && currentTokens.getTokenAccount().organizationUuid() != null) {
                return currentTokens.getTokenAccount().organizationUuid();
            }
            // Fall back to profile fetch
            if (currentTokens == null) return null;
            try {
                Optional<OAuthTypes.OAuthProfileResponse> profileOpt =
                    oauthProfileService.getOauthProfileFromOauthToken(currentTokens.getAccessToken()).get();
                return profileOpt
                    .map(OAuthTypes.OAuthProfileResponse::getOrganizationUuid)
                    .orElse(null);
            } catch (Exception e) {
                log.debug("Could not fetch organization UUID: {}", e.getMessage());
                return null;
            }
        });
    }

    /**
     * Check and refresh the OAuth token if it is close to expiry.
     * Translated from checkAndRefreshOAuthTokenIfNeeded() in client.ts
     */
    public CompletableFuture<Void> checkAndRefreshOAuthTokenIfNeeded() {
        if (currentTokens == null || currentTokens.getRefreshToken() == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (isOAuthTokenExpired(currentTokens.getExpiresAt())) {
            return refreshOAuthToken(currentTokens.getRefreshToken(), null)
                .thenAccept(tokens -> {});
        }
        return CompletableFuture.completedFuture(null);
    }

    public OAuthTokens getCurrentTokens() {
        return currentTokens;
    }

    /** Alias for getCurrentTokens() - returns the current Claude AI OAuth tokens. */
    public OAuthTokens getClaudeAIOAuthTokens() {
        return currentTokens;
    }

    public boolean isAuthenticated() {
        return currentTokens != null && currentTokens.getAccessToken() != null;
    }

    public void setCurrentTokens(OAuthTokens tokens) {
        this.currentTokens = tokens;
    }

    // =========================================================================
    // PKCE helpers
    // =========================================================================

    private String generateCodeVerifier() throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateCodeChallenge(String verifier) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private String generateState() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Check if the user is a Claude.ai subscriber. */
    public boolean isClaudeAiSubscriber() {
        return isAuthenticated();
    }

    /** Alias for isClaudeAiSubscriber() with uppercase AI. */
    public boolean isClaudeAISubscriber() {
        return isClaudeAiSubscriber();
    }

    /** Get the subscription type from the current tokens. */
    public String getSubscriptionType() {
        return currentTokens != null ? currentTokens.getSubscriptionType() : null;
    }

    /** Check if the token has profile scope. */
    public boolean hasProfileScope() {
        if (currentTokens == null) return false;
        return currentTokens.hasProfileScope();
    }

    /** Check if the token is expired. */
    public boolean isTokenExpired(OAuthTokens tokens) {
        return tokens != null && tokens.isExpired();
    }

    /** Get the access token directly. */
    public String getAccessToken() {
        return currentTokens != null ? currentTokens.getAccessToken() : null;
    }

    /**
     * Get the base API URL for Claude API requests.
     */
    public String getBaseApiUrl() {
        String envUrl = System.getenv("ANTHROPIC_BASE_URL");
        return envUrl != null ? envUrl : "https://api.anthropic.com";
    }

    /**
     * Get the user agent string for API requests.
     */
    public String getUserAgent() {
        return "claude-code/1.0.0";
    }

    /**
     * Get OAuth authorization headers for API requests.
     * @param accessToken the OAuth access token
     * @return map of authorization headers
     */
    public Map<String, String> getOAuthHeaders(String accessToken) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        if (accessToken != null) {
            headers.put("Authorization", "Bearer " + accessToken);
        }
        return headers;
    }
}
