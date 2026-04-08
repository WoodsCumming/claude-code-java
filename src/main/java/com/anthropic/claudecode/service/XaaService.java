package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Cross-App Access (XAA) / Enterprise Managed Authorization (SEP-990) service.
 * Translated from src/services/mcp/xaa.ts
 *
 * Obtains an MCP access token WITHOUT a browser consent screen by chaining:
 *   1. RFC 8693 Token Exchange at the IdP: id_token → ID-JAG
 *   2. RFC 7523 JWT Bearer Grant at the AS: ID-JAG → access_token
 *
 * Spec refs:
 *   - ID-JAG (IETF draft): https://datatracker.ietf.org/doc/draft-ietf-oauth-identity-assertion-authz-grant/
 *   - MCP ext-auth (SEP-990): https://github.com/modelcontextprotocol/ext-auth
 *   - RFC 8693 (Token Exchange), RFC 7523 (JWT Bearer), RFC 9728 (PRM)
 */
@Slf4j
@Service
public class XaaService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(XaaService.class);


    private static final long XAA_REQUEST_TIMEOUT_MS = 30_000L;

    // OAuth grant type / token type URNs
    private static final String TOKEN_EXCHANGE_GRANT  = "urn:ietf:params:oauth:grant-type:token-exchange";
    private static final String JWT_BEARER_GRANT      = "urn:ietf:params:oauth:grant-type:jwt-bearer";
    private static final String ID_JAG_TOKEN_TYPE     = "urn:ietf:params:oauth:token-type:id-jag";
    private static final String ID_TOKEN_TYPE         = "urn:ietf:params:oauth:token-type:id_token";

    // Matches quoted values for known token-bearing keys — used to redact debug logs.
    private static final Pattern SENSITIVE_TOKEN_RE = Pattern.compile(
        "\"(access_token|refresh_token|id_token|assertion|subject_token|client_secret)\"\\s*:\\s*\"[^\"]*\"");

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(XAA_REQUEST_TIMEOUT_MS))
        .build();

    // ── Error types ──────────────────────────────────────────────────────────

    /**
     * Thrown when the IdP token-exchange leg fails.
     * Carries shouldClearIdToken so callers can decide whether to drop the cached id_token.
     * Translated from XaaTokenExchangeError in xaa.ts
     */
    public static class XaaTokenExchangeError extends RuntimeException {
        private final boolean shouldClearIdToken;

        public XaaTokenExchangeError(String message, boolean shouldClearIdToken) {
            super(message);
            this.shouldClearIdToken = shouldClearIdToken;
        }

        public boolean isShouldClearIdToken() { return shouldClearIdToken; }
    }

    // ── Result types ─────────────────────────────────────────────────────────

    /**
     * RFC 9728 Protected Resource Metadata.
     * Translated from ProtectedResourceMetadata in xaa.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ProtectedResourceMetadata {
        private String resource;
        private List<String> authorizationServers;

        public String getResource() { return resource; }
        public void setResource(String v) { resource = v; }
        public List<String> getAuthorizationServers() { return authorizationServers; }
        public void setAuthorizationServers(List<String> v) { authorizationServers = v; }
    }

    /**
     * Authorization Server Metadata (subset used for XAA).
     * Translated from AuthorizationServerMetadata in xaa.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AuthorizationServerMetadata {
        private String issuer;
        private String tokenEndpoint;
        private List<String> grantTypesSupported;
        private List<String> tokenEndpointAuthMethodsSupported;

        public String getIssuer() { return issuer; }
        public void setIssuer(String v) { issuer = v; }
        public String getTokenEndpoint() { return tokenEndpoint; }
        public void setTokenEndpoint(String v) { tokenEndpoint = v; }
        public List<String> getGrantTypesSupported() { return grantTypesSupported; }
        public void setGrantTypesSupported(List<String> v) { grantTypesSupported = v; }
        public List<String> getTokenEndpointAuthMethodsSupported() { return tokenEndpointAuthMethodsSupported; }
        public void setTokenEndpointAuthMethodsSupported(List<String> v) { tokenEndpointAuthMethodsSupported = v; }
    }

    /**
     * Result of the RFC 8693 token exchange (id_token → ID-JAG).
     * Translated from JwtAuthGrantResult in xaa.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class JwtAuthGrantResult {
        /** The ID-JAG (Identity Assertion Authorization Grant). */
        private String jwtAuthGrant;
        private Long expiresIn;
        private String scope;

        public String getJwtAuthGrant() { return jwtAuthGrant; }
        public void setJwtAuthGrant(String v) { jwtAuthGrant = v; }
        public Long getExpiresIn() { return expiresIn; }
        public void setExpiresIn(Long v) { expiresIn = v; }
        public String getScope() { return scope; }
        public void setScope(String v) { scope = v; }
    }

    /**
     * Token result from the AS (ID-JAG → access_token).
     * Translated from XaaTokenResult in xaa.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class XaaTokenResult {
        private String accessToken;
        private String tokenType;
        private Long expiresIn;
        private String scope;
        private String refreshToken;

        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String v) { accessToken = v; }
        public String getTokenType() { return tokenType; }
        public void setTokenType(String v) { tokenType = v; }
        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(String v) { refreshToken = v; }
    }

    /**
     * Full XAA result, including the discovered AS issuer URL.
     * Translated from XaaResult in xaa.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class XaaResult {
        private String accessToken;
        private String tokenType;
        private Long expiresIn;
        private String scope;
        private String refreshToken;
        /**
         * The AS issuer URL discovered via PRM. Callers must persist this as
         * discoveryState.authorizationServerUrl so that refresh and revocation
         * can locate the token/revocation endpoints.
         */
        private String authorizationServerUrl;

        public String getAuthorizationServerUrl() { return authorizationServerUrl; }
        public void setAuthorizationServerUrl(String v) { authorizationServerUrl = v; }
    }

    /**
     * Configuration for the full XAA flow.
     * Translated from XaaConfig in xaa.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class XaaConfig {
        /** Client ID registered at the MCP server's authorization server. */
        private String clientId;
        /** Client secret for the MCP server's authorization server. */
        private String clientSecret;
        /** Client ID registered at the IdP (for the token-exchange request). */
        private String idpClientId;
        /** Optional IdP client secret (client_secret_post) — some IdPs require it. */
        private String idpClientSecret;
        /** The user's OIDC id_token from the IdP login. */
        private String idpIdToken;
        /** IdP token endpoint (where to send the RFC 8693 token-exchange). */
        private String idpTokenEndpoint;

        public String getClientId() { return clientId; }
        public void setClientId(String v) { clientId = v; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String v) { clientSecret = v; }
        public String getIdpClientId() { return idpClientId; }
        public void setIdpClientId(String v) { idpClientId = v; }
        public String getIdpClientSecret() { return idpClientSecret; }
        public void setIdpClientSecret(String v) { idpClientSecret = v; }
        public String getIdpIdToken() { return idpIdToken; }
        public void setIdpIdToken(String v) { idpIdToken = v; }
        public String getIdpTokenEndpoint() { return idpTokenEndpoint; }
        public void setIdpTokenEndpoint(String v) { idpTokenEndpoint = v; }
    }

    // ── Layer 2: Discovery ───────────────────────────────────────────────────

    /**
     * RFC 9728 PRM discovery plus resource-mismatch validation (mix-up protection).
     * Translated from discoverProtectedResource() in xaa.ts
     *
     * @param serverUrl the MCP server URL
     * @return the discovered PRM metadata
     * @throws RuntimeException on network/parse failure or resource mismatch
     */
    public CompletableFuture<ProtectedResourceMetadata> discoverProtectedResource(String serverUrl) {
        return CompletableFuture.supplyAsync(() -> {
            // Well-known URL per RFC 9728: GET /.well-known/oauth-protected-resource
            String prmUrl;
            try {
                URI base = new URI(serverUrl);
                prmUrl = new URI(base.getScheme(), base.getAuthority(),
                    "/.well-known/oauth-protected-resource",
                    base.getPath().isEmpty() ? null : "resource=" + base.getPath(), null).toString();
            } catch (Exception e) {
                throw new RuntimeException("XAA: PRM discovery failed: invalid server URL: " + serverUrl, e);
            }

            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(prmUrl))
                    .timeout(Duration.ofMillis(XAA_REQUEST_TIMEOUT_MS))
                    .header("Accept", "application/json")
                    .GET().build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    throw new RuntimeException(
                        "XAA: PRM discovery failed: HTTP " + resp.statusCode() + " at " + prmUrl);
                }

                String resource = extractJsonString(resp.body(), "resource");
                List<String> authServers = extractJsonStringArray(resp.body(), "authorization_servers");

                if (resource == null || authServers == null || authServers.isEmpty()) {
                    throw new RuntimeException(
                        "XAA: PRM discovery failed: PRM missing resource or authorization_servers");
                }
                // RFC 9728 §3.3 resource-mismatch validation
                if (!normalizeUrl(resource).equals(normalizeUrl(serverUrl))) {
                    throw new RuntimeException(
                        "XAA: PRM discovery failed: PRM resource mismatch: expected " +
                        serverUrl + ", got " + resource);
                }
                return new ProtectedResourceMetadata(resource, authServers);

            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("XAA: PRM discovery failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * AS metadata discovery plus issuer-mismatch validation and HTTPS check.
     * Translated from discoverAuthorizationServer() in xaa.ts
     */
    public CompletableFuture<AuthorizationServerMetadata> discoverAuthorizationServer(String asUrl) {
        return CompletableFuture.supplyAsync(() -> {
            // RFC 8414: /.well-known/oauth-authorization-server
            String metaUrl = asUrl.endsWith("/")
                ? asUrl + ".well-known/oauth-authorization-server"
                : asUrl + "/.well-known/oauth-authorization-server";
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(metaUrl))
                    .timeout(Duration.ofMillis(XAA_REQUEST_TIMEOUT_MS))
                    .header("Accept", "application/json")
                    .GET().build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    throw new RuntimeException(
                        "XAA: AS metadata discovery failed: HTTP " + resp.statusCode() + " at " + metaUrl);
                }

                String issuer = extractJsonString(resp.body(), "issuer");
                String tokenEndpoint = extractJsonString(resp.body(), "token_endpoint");

                if (issuer == null || tokenEndpoint == null) {
                    throw new RuntimeException(
                        "XAA: AS metadata discovery failed: no valid metadata at " + asUrl);
                }
                // RFC 8414 §3.3 issuer-mismatch validation
                if (!normalizeUrl(issuer).equals(normalizeUrl(asUrl))) {
                    throw new RuntimeException(
                        "XAA: AS metadata discovery failed: issuer mismatch: expected " +
                        asUrl + ", got " + issuer);
                }
                // Require HTTPS for token endpoint
                if (!tokenEndpoint.startsWith("https://")) {
                    throw new RuntimeException(
                        "XAA: refusing non-HTTPS token endpoint: " + tokenEndpoint);
                }

                List<String> grantTypes = extractJsonStringArray(resp.body(), "grant_types_supported");
                List<String> authMethods = extractJsonStringArray(resp.body(), "token_endpoint_auth_methods_supported");

                return new AuthorizationServerMetadata(issuer, tokenEndpoint, grantTypes, authMethods);

            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("XAA: AS metadata discovery failed: " + e.getMessage(), e);
            }
        });
    }

    // ── Layer 2: Exchange ────────────────────────────────────────────────────

    /**
     * RFC 8693 Token Exchange at the IdP: id_token → ID-JAG.
     * Validates issued_token_type is urn:ietf:params:oauth:token-type:id-jag.
     * Translated from requestJwtAuthorizationGrant() in xaa.ts
     */
    public CompletableFuture<JwtAuthGrantResult> requestJwtAuthorizationGrant(
            String tokenEndpoint,
            String audience,
            String resource,
            String idToken,
            String clientId,
            String clientSecret,
            String scope) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("grant_type", TOKEN_EXCHANGE_GRANT);
                params.put("requested_token_type", ID_JAG_TOKEN_TYPE);
                params.put("audience", audience);
                params.put("resource", resource);
                params.put("subject_token", idToken);
                params.put("subject_token_type", ID_TOKEN_TYPE);
                params.put("client_id", clientId);
                if (clientSecret != null) params.put("client_secret", clientSecret);
                if (scope != null) params.put("scope", scope);

                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(tokenEndpoint))
                    .timeout(Duration.ofMillis(XAA_REQUEST_TIMEOUT_MS))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(urlEncodeParams(params)))
                    .build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 400) {
                    String body = redactTokens(resp.body()).substring(0, Math.min(200, resp.body().length()));
                    // 4xx → id_token rejected, clear cache; 5xx → IdP down, preserve it
                    boolean shouldClear = resp.statusCode() < 500;
                    throw new XaaTokenExchangeError(
                        "XAA: token exchange failed: HTTP " + resp.statusCode() + ": " + body,
                        shouldClear);
                }

                String accessToken = extractJsonString(resp.body(), "access_token");
                String issuedTokenType = extractJsonString(resp.body(), "issued_token_type");
                Long expiresIn = extractJsonLong(resp.body(), "expires_in");
                String responseScope = extractJsonString(resp.body(), "scope");

                if (accessToken == null) {
                    throw new XaaTokenExchangeError(
                        "XAA: token exchange response missing access_token: " + redactTokens(resp.body()),
                        true);
                }
                if (!ID_JAG_TOKEN_TYPE.equals(issuedTokenType)) {
                    throw new XaaTokenExchangeError(
                        "XAA: token exchange returned unexpected issued_token_type: " + issuedTokenType,
                        true);
                }
                return new JwtAuthGrantResult(accessToken, expiresIn, responseScope);

            } catch (XaaTokenExchangeError e) {
                throw e;
            } catch (Exception e) {
                throw new XaaTokenExchangeError(
                    "XAA: token exchange at " + tokenEndpoint + " failed: " + e.getMessage(), false);
            }
        });
    }

    /**
     * RFC 7523 JWT Bearer Grant at the AS: ID-JAG → access_token.
     * Translated from exchangeJwtAuthGrant() in xaa.ts
     *
     * @param authMethod "client_secret_basic" (default) or "client_secret_post"
     */
    public CompletableFuture<XaaTokenResult> exchangeJwtAuthGrant(
            String tokenEndpoint,
            String assertion,
            String clientId,
            String clientSecret,
            String authMethod,
            String scope) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                String method = authMethod != null ? authMethod : "client_secret_basic";

                Map<String, String> params = new LinkedHashMap<>();
                params.put("grant_type", JWT_BEARER_GRANT);
                params.put("assertion", assertion);
                if (scope != null) params.put("scope", scope);

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(tokenEndpoint))
                    .timeout(Duration.ofMillis(XAA_REQUEST_TIMEOUT_MS))
                    .header("Content-Type", "application/x-www-form-urlencoded");

                if ("client_secret_basic".equals(method)) {
                    String credentials = java.util.Base64.getEncoder().encodeToString(
                        (encode(clientId) + ":" + encode(clientSecret))
                            .getBytes(StandardCharsets.UTF_8));
                    reqBuilder.header("Authorization", "Basic " + credentials);
                } else {
                    params.put("client_id", clientId);
                    params.put("client_secret", clientSecret);
                }

                HttpRequest req = reqBuilder
                    .POST(HttpRequest.BodyPublishers.ofString(urlEncodeParams(params)))
                    .build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (!isOk(resp.statusCode())) {
                    String body = redactTokens(resp.body()).substring(0, Math.min(200, resp.body().length()));
                    throw new RuntimeException(
                        "XAA: jwt-bearer grant failed: HTTP " + resp.statusCode() + ": " + body);
                }

                String accessToken = extractJsonString(resp.body(), "access_token");
                if (accessToken == null || accessToken.isBlank()) {
                    throw new RuntimeException(
                        "XAA: jwt-bearer response missing access_token: " + redactTokens(resp.body()));
                }

                String tokenType = extractJsonString(resp.body(), "token_type");
                Long expiresIn = extractJsonLong(resp.body(), "expires_in");
                String responseScope = extractJsonString(resp.body(), "scope");
                String refreshToken = extractJsonString(resp.body(), "refresh_token");

                return new XaaTokenResult(
                    accessToken,
                    tokenType != null ? tokenType : "Bearer",
                    expiresIn,
                    responseScope,
                    refreshToken
                );

            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("XAA: jwt-bearer grant failed: " + e.getMessage(), e);
            }
        });
    }

    // ── Layer 3: Orchestrator ────────────────────────────────────────────────

    /**
     * Full XAA flow: PRM → AS metadata → token-exchange → jwt-bearer → access_token.
     * Translated from performCrossAppAccess() in xaa.ts
     *
     * @param serverUrl  the MCP server URL (e.g. https://mcp.example.com/mcp)
     * @param config     IdP + AS credentials
     * @param serverName server name for debug logging
     * @return XaaResult containing the access token and the AS issuer URL
     */
    public CompletableFuture<XaaResult> performCrossAppAccess(
            String serverUrl,
            XaaConfig config,
            String serverName) {

        log.debug("[{}] XAA: discovering PRM for {}", serverName, serverUrl);

        return discoverProtectedResource(serverUrl).thenCompose(prm -> {
            log.debug("[{}] XAA: discovered resource={} ASes=[{}]",
                serverName, prm.getResource(), String.join(", ", prm.getAuthorizationServers()));

            // Try each advertised AS in order, find first that supports jwt-bearer
            return findSupportedAuthorizationServer(prm.getAuthorizationServers(), serverName)
                .thenCompose(asMeta -> {
                    // Pick auth method
                    List<String> supportedMethods = asMeta.getTokenEndpointAuthMethodsSupported();
                    String authMethod =
                        (supportedMethods != null
                            && !supportedMethods.contains("client_secret_basic")
                            && supportedMethods.contains("client_secret_post"))
                        ? "client_secret_post"
                        : "client_secret_basic";

                    log.debug("[{}] XAA: AS issuer={} token_endpoint={} auth_method={}",
                        serverName, asMeta.getIssuer(), asMeta.getTokenEndpoint(), authMethod);
                    log.debug("[{}] XAA: exchanging id_token for ID-JAG at IdP", serverName);

                    return requestJwtAuthorizationGrant(
                        config.getIdpTokenEndpoint(),
                        asMeta.getIssuer(),
                        prm.getResource(),
                        config.getIdpIdToken(),
                        config.getIdpClientId(),
                        config.getIdpClientSecret(),
                        null
                    ).thenCompose(jag -> {
                        log.debug("[{}] XAA: ID-JAG obtained", serverName);
                        log.debug("[{}] XAA: exchanging ID-JAG for access_token at AS", serverName);

                        return exchangeJwtAuthGrant(
                            asMeta.getTokenEndpoint(),
                            jag.getJwtAuthGrant(),
                            config.getClientId(),
                            config.getClientSecret(),
                            authMethod,
                            null
                        ).thenApply(tokens -> {
                            log.debug("[{}] XAA: access_token obtained", serverName);
                            return new XaaResult(
                                tokens.getAccessToken(),
                                tokens.getTokenType(),
                                tokens.getExpiresIn(),
                                tokens.getScope(),
                                tokens.getRefreshToken(),
                                asMeta.getIssuer()
                            );
                        });
                    });
                });
        });
    }

    /**
     * Iterate the advertised ASes and return the first one that supports jwt-bearer.
     */
    private CompletableFuture<AuthorizationServerMetadata> findSupportedAuthorizationServer(
            List<String> asUrls,
            String serverName) {

        CompletableFuture<AuthorizationServerMetadata> result = CompletableFuture.failedFuture(
            new RuntimeException("No authorization servers to try"));

        for (String asUrl : asUrls) {
            final CompletableFuture<AuthorizationServerMetadata> prev = result;
            result = prev.exceptionallyCompose(prevErr ->
                discoverAuthorizationServer(asUrl).thenApply(asMeta -> {
                    List<String> grantTypes = asMeta.getGrantTypesSupported();
                    if (grantTypes != null && !grantTypes.contains(JWT_BEARER_GRANT)) {
                        log.debug("[{}] XAA: AS {} does not advertise jwt-bearer, skipping", serverName, asUrl);
                        throw new RuntimeException(asUrl + ": does not advertise jwt-bearer grant");
                    }
                    return asMeta;
                }).exceptionallyCompose(e -> CompletableFuture.failedFuture(e))
            );
        }

        return result.exceptionally(e -> {
            throw new RuntimeException(
                "XAA: no authorization server supports jwt-bearer. " + e.getMessage());
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * RFC 3986 §6.2.2 syntax normalization + strip trailing slash.
     * Translated from normalizeUrl() in xaa.ts
     */
    private static String normalizeUrl(String url) {
        try {
            return URI.create(url).normalize().toString().replaceAll("/$", "");
        } catch (Exception e) {
            return url.replaceAll("/$", "");
        }
    }

    private static String redactTokens(String raw) {
        return SENSITIVE_TOKEN_RE.matcher(raw).replaceAll(m -> "\"" + m.group(1) + "\":\"[REDACTED]\"");
    }

    private static String urlEncodeParams(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (!sb.isEmpty()) sb.append("&");
            sb.append(encode(k)).append("=").append(encode(v));
        });
        return sb.toString();
    }

    private static String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    private static boolean isOk(int status) { return status >= 200 && status < 300; }

    private static String extractJsonString(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static Long extractJsonLong(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"" + key + "\"\\s*:\\s*([0-9]+)").matcher(json);
        try { return m.find() ? Long.parseLong(m.group(1)) : null; }
        catch (NumberFormatException e) { return null; }
    }

    private static List<String> extractJsonStringArray(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"" + key + "\"\\s*:\\s*\\[([^\\]]*)]").matcher(json);
        if (!m.find()) return null;
        String contents = m.group(1);
        if (contents.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        java.util.regex.Matcher strM = java.util.regex.Pattern.compile("\"([^\"]+)\"").matcher(contents);
        while (strM.find()) result.add(strM.group(1));
        return result;
    }
}
