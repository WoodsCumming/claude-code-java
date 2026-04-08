package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * XAA IdP Login service.
 * Translated from src/services/mcp/xaaIdpLogin.ts
 *
 * Acquires an OIDC id_token from an enterprise IdP via the standard
 * authorization_code + PKCE flow, then caches it by IdP issuer.
 *
 * This is the "one browser pop" in the XAA value prop:
 * one IdP login → N silent MCP server auths via the XaaService flow.
 * The id_token is cached and reused until expiry.
 */
@Slf4j
@Service
public class XaaIdpLoginService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(XaaIdpLoginService.class);


    private static final long IDP_LOGIN_TIMEOUT_MS = 5 * 60 * 1000L;
    private static final long IDP_REQUEST_TIMEOUT_MS = 30_000L;
    private static final long ID_TOKEN_EXPIRY_BUFFER_S = 60L;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(IDP_REQUEST_TIMEOUT_MS))
        .build();

    // In-memory id_token cache: issuer key → cached entry
    private final Map<String, IdTokenCacheEntry> idTokenCache = new java.util.concurrent.ConcurrentHashMap<>();
    // In-memory IdP client secret store: issuer key → client secret
    private final Map<String, String> idpClientSecrets = new java.util.concurrent.ConcurrentHashMap<>();

    // ── Types ────────────────────────────────────────────────────────────────

    /**
     * XAA IdP settings read from application config.
     * Translated from XaaIdpSettings in xaaIdpLogin.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class XaaIdpSettings {
        private String issuer;
        private String clientId;
        private Integer callbackPort;

        public String getIssuer() { return issuer; }
        public void setIssuer(String v) { issuer = v; }
        public String getClientId() { return clientId; }
        public void setClientId(String v) { clientId = v; }
        public Integer getCallbackPort() { return callbackPort; }
        public void setCallbackPort(Integer v) { callbackPort = v; }
    }

    /**
     * Options for acquiring an IdP id_token.
     * Translated from IdpLoginOptions in xaaIdpLogin.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class IdpLoginOptions {
        private String idpIssuer;
        private String idpClientId;
        /** Optional IdP client secret for confidential clients. */
        private String idpClientSecret;
        /** Fixed callback port. If null, a random available port is chosen. */
        private Integer callbackPort;
        /** Called with the authorization URL before (or instead of) opening the browser. */
        private java.util.function.Consumer<String> onAuthorizationUrl;
        /** If true, don't auto-open the browser — just call onAuthorizationUrl. */
        private boolean skipBrowserOpen;
        /** Cancellation signal. */
        private volatile boolean aborted;

        /** Convenience constructor for the most common case. */

        public String getIdpIssuer() { return idpIssuer; }
        public void setIdpIssuer(String v) { idpIssuer = v; }
        public String getIdpClientId() { return idpClientId; }
        public void setIdpClientId(String v) { idpClientId = v; }
        public String getIdpClientSecret() { return idpClientSecret; }
        public void setIdpClientSecret(String v) { idpClientSecret = v; }
        public boolean isSkipBrowserOpen() { return skipBrowserOpen; }
        public void setSkipBrowserOpen(boolean v) { skipBrowserOpen = v; }
        public boolean getAborted() { return aborted; }
        public void setAborted(boolean v) { aborted = v; }
    
        public Integer getCallbackPort() { return callbackPort; }
    }

    @Data
    private static class IdTokenCacheEntry {
        private String idToken;
        private long expiresAt;  // epoch ms
    
        public long getExpiresAt() { return expiresAt; }
    
        public String getIdToken() { return idToken; }
    

        public IdTokenCacheEntry() {}
        public IdTokenCacheEntry(String idToken, long expiresAt) {
            this.idToken = idToken;
            this.expiresAt = expiresAt;
        }
    }

    // ── Feature gate ─────────────────────────────────────────────────────────

    /**
     * Check if XAA is enabled.
     * Translated from isXaaEnabled() in xaaIdpLogin.ts
     */
    public boolean isXaaEnabled() {
        return EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_ENABLE_XAA"));
    }

    /**
     * Get XAA IdP settings from application config.
     * Translated from getXaaIdpSettings() in xaaIdpLogin.ts
     */
    public Optional<XaaIdpSettings> getXaaIdpSettings() {
        // Full implementation would read from settings.xaaIdp
        // The field is env-gated and not surfaced in SDK types
        return Optional.empty();
    }

    // ── Issuer key ───────────────────────────────────────────────────────────

    /**
     * Normalize an IdP issuer URL for use as a cache key.
     * Translated from issuerKey() in xaaIdpLogin.ts
     *
     * Strips trailing slashes and lowercases host so that issuers from config
     * and from OIDC discovery hit the same cache slot even if they differ cosmetically.
     */
    public String issuerKey(String issuer) {
        try {
            URI u = URI.create(issuer);
            String path = u.getPath().replaceAll("/+$", "");
            String host = u.getHost().toLowerCase();
            int port = u.getPort();
            String portStr = (port == -1) ? "" : (":" + port);
            String scheme = u.getScheme().toLowerCase();
            return scheme + "://" + host + portStr + path;
        } catch (Exception e) {
            return issuer.replaceAll("/+$", "");
        }
    }

    // ── id_token cache ───────────────────────────────────────────────────────

    /**
     * Read a cached id_token for the given IdP issuer.
     * Returns null if missing or within ID_TOKEN_EXPIRY_BUFFER_S of expiring.
     * Translated from getCachedIdpIdToken() in xaaIdpLogin.ts
     */
    public String getCachedIdpIdToken(String idpIssuer) {
        IdTokenCacheEntry entry = idTokenCache.get(issuerKey(idpIssuer));
        if (entry == null) return null;
        long remainingMs = entry.getExpiresAt() - System.currentTimeMillis();
        if (remainingMs <= ID_TOKEN_EXPIRY_BUFFER_S * 1000) return null;
        return entry.getIdToken();
    }

    private void saveIdpIdToken(String idpIssuer, String idToken, long expiresAt) {
        idTokenCache.put(issuerKey(idpIssuer), new IdTokenCacheEntry(idToken, expiresAt));
        log.debug("[xaa] Cached id_token for {} (expires {})", idpIssuer,
            new java.util.Date(expiresAt));
    }

    /**
     * Save an externally-obtained id_token into the XAA cache.
     * Parses the JWT's exp claim for cache TTL.
     * Translated from saveIdpIdTokenFromJwt() in xaaIdpLogin.ts
     *
     * @return the computed expiresAt epoch-ms so the caller can report it
     */
    public long saveIdpIdTokenFromJwt(String idpIssuer, String idToken) {
        Long expFromJwt = jwtExp(idToken);
        long expiresAt = (expFromJwt != null)
            ? expFromJwt * 1000L
            : System.currentTimeMillis() + 3600_000L;
        saveIdpIdToken(idpIssuer, idToken, expiresAt);
        return expiresAt;
    }

    /**
     * Clear the cached id_token for the given IdP issuer.
     * Translated from clearIdpIdToken() in xaaIdpLogin.ts
     */
    public void clearIdpIdToken(String idpIssuer) {
        idTokenCache.remove(issuerKey(idpIssuer));
        log.debug("[xaa] Cleared id_token for {}", idpIssuer);
    }

    // ── IdP client secret storage ────────────────────────────────────────────

    /**
     * Save an IdP client secret keyed by IdP issuer.
     * Translated from saveIdpClientSecret() in xaaIdpLogin.ts
     */
    public boolean saveIdpClientSecret(String idpIssuer, String clientSecret) {
        idpClientSecrets.put(issuerKey(idpIssuer), clientSecret);
        return true;
    }

    /**
     * Get the IdP client secret for the given issuer.
     * Translated from getIdpClientSecret() in xaaIdpLogin.ts
     */
    public String getIdpClientSecret(String idpIssuer) {
        return idpClientSecrets.get(issuerKey(idpIssuer));
    }

    /**
     * Remove the IdP client secret for the given issuer.
     * Translated from clearIdpClientSecret() in xaaIdpLogin.ts
     */
    public void clearIdpClientSecret(String idpIssuer) {
        idpClientSecrets.remove(issuerKey(idpIssuer));
    }

    // ── OIDC discovery ───────────────────────────────────────────────────────

    /**
     * Discover OIDC metadata for the given IdP issuer.
     * Follows OIDC Discovery §4.1: {issuer}/.well-known/openid-configuration
     * using path-append (not path-replace) to handle multi-tenant issuers.
     * Translated from discoverOidc() in xaaIdpLogin.ts
     */
    public CompletableFuture<Map<String, Object>> discoverOidc(String idpIssuer) {
        return CompletableFuture.supplyAsync(() -> {
            // Path APPEND, not replace — critical for Azure AD, Okta, Keycloak
            String base = idpIssuer.endsWith("/") ? idpIssuer : idpIssuer + "/";
            String discoveryUrl = base + ".well-known/openid-configuration";
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(discoveryUrl))
                    .timeout(Duration.ofMillis(IDP_REQUEST_TIMEOUT_MS))
                    .header("Accept", "application/json")
                    .GET().build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    throw new RuntimeException(
                        "XAA IdP: OIDC discovery failed: HTTP " + resp.statusCode() + " at " + discoveryUrl);
                }

                // Basic validation: require token_endpoint and HTTPS
                String tokenEndpoint = extractJsonString(resp.body(), "token_endpoint");
                String authEndpoint = extractJsonString(resp.body(), "authorization_endpoint");
                if (tokenEndpoint == null || authEndpoint == null) {
                    throw new RuntimeException("XAA IdP: invalid OIDC metadata from " + discoveryUrl);
                }
                if (!tokenEndpoint.startsWith("https://")) {
                    throw new RuntimeException(
                        "XAA IdP: refusing non-HTTPS token endpoint: " + tokenEndpoint);
                }

                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("token_endpoint", tokenEndpoint);
                meta.put("authorization_endpoint", authEndpoint);
                // Optional fields
                String issuer = extractJsonString(resp.body(), "issuer");
                if (issuer != null) meta.put("issuer", issuer);
                return meta;

            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("XAA IdP: OIDC discovery failed: " + e.getMessage(), e);
            }
        });
    }

    // ── id_token acquisition ─────────────────────────────────────────────────

    /**
     * Acquire an id_token from the IdP: return cached if valid, otherwise run
     * the full OIDC authorization_code + PKCE flow (one browser pop).
     * Translated from acquireIdpIdToken() in xaaIdpLogin.ts
     *
     * @return the id_token string
     */
    public CompletableFuture<String> acquireIdpIdToken(IdpLoginOptions opts) {
        String cached = getCachedIdpIdToken(opts.getIdpIssuer());
        if (cached != null) {
            log.debug("[xaa] Using cached id_token for {}", opts.getIdpIssuer());
            return CompletableFuture.completedFuture(cached);
        }

        log.debug("[xaa] No cached id_token for {}; starting OIDC login", opts.getIdpIssuer());

        return discoverOidc(opts.getIdpIssuer()).thenCompose(meta -> {
            int port;
            try {
                port = opts.getCallbackPort() != null ? opts.getCallbackPort() : findAvailablePort();
            } catch (Exception e) {
                return CompletableFuture.failedFuture(
                    new RuntimeException("XAA IdP: could not find available callback port", e));
            }

            String redirectUri = "http://127.0.0.1:" + port + "/callback";
            String state = generateState();
            String codeVerifier = generateCodeVerifier();
            String codeChallenge = computeCodeChallenge(codeVerifier);

            String authEndpoint = (String) meta.get("authorization_endpoint");
            String authUrl = authEndpoint
                + "?response_type=code"
                + "&client_id=" + urlEncode(opts.getIdpClientId())
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&scope=openid"
                + "&state=" + urlEncode(state)
                + "&code_challenge=" + urlEncode(codeChallenge)
                + "&code_challenge_method=S256";

            // Wait for callback then exchange code for tokens
            return waitForAuthorizationCode(port, state, opts)
                .thenCompose(code -> {
                    String tokenEndpoint = (String) meta.get("token_endpoint");
                    return exchangeAuthorizationCode(
                        tokenEndpoint,
                        code,
                        redirectUri,
                        opts.getIdpClientId(),
                        opts.getIdpClientSecret(),
                        codeVerifier
                    );
                })
                .thenApply(tokens -> {
                    String idToken = (String) tokens.get("id_token");
                    if (idToken == null) {
                        throw new RuntimeException(
                            "XAA IdP: token response missing id_token (check scope=openid)");
                    }
                    Long expFromJwt = jwtExp(idToken);
                    Long expiresIn = extractTokenLong(tokens, "expires_in");
                    long expiresAt = (expFromJwt != null)
                        ? expFromJwt * 1000L
                        : System.currentTimeMillis() + (expiresIn != null ? expiresIn : 3600L) * 1000L;
                    saveIdpIdToken(opts.getIdpIssuer(), idToken, expiresAt);
                    return idToken;
                })
                .whenComplete((result, err) -> {
                    if (opts.getOnAuthorizationUrl() != null) {
                        opts.getOnAuthorizationUrl().accept(authUrl);
                    }
                });
        });
    }

    // ── PKCE / OAuth helpers ─────────────────────────────────────────────────

    /**
     * Wait for the OAuth authorization code on a local callback server.
     * Translated from waitForCallback() in xaaIdpLogin.ts
     *
     * Uses a plain ServerSocket to accept one HTTP connection and parse the
     * /callback query string — lightweight alternative to HttpServer to avoid
     * reliance on the internal sun.net.httpserver API.
     */
    private CompletableFuture<String> waitForAuthorizationCode(
            int port,
            String expectedState,
            IdpLoginOptions opts) {

        CompletableFuture<String> future = new CompletableFuture<>();

        Thread.ofVirtual().name("xaa-callback-server").start(() -> {
            try (java.net.ServerSocket serverSocket =
                    new java.net.ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1"))) {

                serverSocket.setSoTimeout((int) IDP_LOGIN_TIMEOUT_MS);
                log.debug("[xaa] Callback server listening on port {}", port);

                try (java.net.Socket client = serverSocket.accept()) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                    java.io.OutputStream out = client.getOutputStream();

                    // Read the first line: "GET /callback?code=...&state=... HTTP/1.1"
                    String requestLine = reader.readLine();
                    if (requestLine == null) {
                        sendHttpResponse(out, 400, "Bad Request");
                        future.completeExceptionally(new RuntimeException("XAA IdP: empty request"));
                        return;
                    }

                    // Parse path + query
                    String[] parts = requestLine.split(" ");
                    String pathAndQuery = parts.length >= 2 ? parts[1] : "";
                    int qIdx = pathAndQuery.indexOf('?');
                    String query = qIdx >= 0 ? pathAndQuery.substring(qIdx + 1) : "";
                    Map<String, String> params = parseQueryString(query);

                    if (params.containsKey("error")) {
                        String errMsg = params.getOrDefault("error", "unknown") +
                            (params.containsKey("error_description")
                                ? " - " + params.get("error_description") : "");
                        sendHttpResponse(out, 400, "<html><body><h3>IdP login failed</h3><p>" + errMsg + "</p></body></html>");
                        future.completeExceptionally(new RuntimeException("XAA IdP: " + errMsg));
                        return;
                    }

                    String stateParam = params.get("state");
                    if (!expectedState.equals(stateParam)) {
                        sendHttpResponse(out, 400, "<html><body><h3>State mismatch</h3></body></html>");
                        future.completeExceptionally(new RuntimeException("XAA IdP: state mismatch (possible CSRF)"));
                        return;
                    }

                    String code = params.get("code");
                    if (code == null) {
                        sendHttpResponse(out, 400, "<html><body><h3>Missing code</h3></body></html>");
                        future.completeExceptionally(new RuntimeException("XAA IdP: callback missing code"));
                        return;
                    }

                    sendHttpResponse(out, 200, "<html><body><h3>IdP login complete — you can close this window.</h3></body></html>");
                    future.complete(code);
                }
            } catch (java.net.SocketTimeoutException e) {
                future.completeExceptionally(new RuntimeException("XAA IdP: login timed out"));
            } catch (Exception e) {
                if (!future.isDone()) {
                    future.completeExceptionally(
                        new RuntimeException("XAA IdP: callback server failed: " + e.getMessage(), e));
                }
            }
        });

        return future;
    }

    private static void sendHttpResponse(java.io.OutputStream out, int status, String body) throws Exception {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String statusText = status == 200 ? "OK" : "Bad Request";
        String headers = "HTTP/1.1 " + status + " " + statusText + "\r\n"
            + "Content-Type: text/html; charset=utf-8\r\n"
            + "Content-Length: " + bodyBytes.length + "\r\n"
            + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(bodyBytes);
        out.flush();
    }

    /**
     * Exchange the authorization code for tokens at the IdP token endpoint.
     */
    private CompletableFuture<Map<String, Object>> exchangeAuthorizationCode(
            String tokenEndpoint,
            String code,
            String redirectUri,
            String clientId,
            String clientSecret,
            String codeVerifier) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("grant_type", "authorization_code");
                params.put("code", code);
                params.put("redirect_uri", redirectUri);
                params.put("client_id", clientId);
                params.put("code_verifier", codeVerifier);
                if (clientSecret != null) params.put("client_secret", clientSecret);

                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(tokenEndpoint))
                    .timeout(Duration.ofMillis(IDP_REQUEST_TIMEOUT_MS))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(urlEncodeForm(params)))
                    .build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                    throw new RuntimeException(
                        "XAA IdP: token exchange failed: HTTP " + resp.statusCode());
                }

                Map<String, Object> result = new LinkedHashMap<>();
                String idToken = extractJsonString(resp.body(), "id_token");
                String accessToken = extractJsonString(resp.body(), "access_token");
                Long expiresIn = extractJsonLong(resp.body(), "expires_in");
                if (idToken != null) result.put("id_token", idToken);
                if (accessToken != null) result.put("access_token", accessToken);
                if (expiresIn != null) result.put("expires_in", expiresIn);
                return result;

            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("XAA IdP: token exchange failed: " + e.getMessage(), e);
            }
        });
    }

    // ── JWT exp extraction ────────────────────────────────────────────────────

    /**
     * Decode the exp claim from a JWT without verifying its signature.
     * Used only to derive a cache TTL.
     * Translated from jwtExp() in xaaIdpLogin.ts
     */
    Long jwtExp(String jwt) {
        if (jwt == null) return null;
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) return null;
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            String json = new String(payload, StandardCharsets.UTF_8);
            Long exp = extractJsonLong(json, "exp");
            return exp;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Misc helpers ──────────────────────────────────────────────────────────

    private static int findAvailablePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String generateState() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String computeCodeChallenge(String verifier) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Could not compute code challenge", e);
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String urlEncodeForm(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (!sb.isEmpty()) sb.append("&");
            sb.append(urlEncode(k)).append("=").append(urlEncode(v));
        });
        return sb.toString();
    }

    private static Map<String, String> parseQueryString(String query) {
        Map<String, String> result = new LinkedHashMap<>();
        if (query == null || query.isBlank()) return result;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                result.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                           URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return result;
    }

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

    private static Long extractTokenLong(Map<String, Object> tokens, String key) {
        Object val = tokens.get(key);
        if (val instanceof Long l) return l;
        if (val instanceof Number n) return n.longValue();
        return null;
    }

    /** Get cached id token as Optional. */
    public java.util.Optional<String> getCachedIdpIdTokenOptional(String idpIssuer) {
        return java.util.Optional.ofNullable(getCachedIdpIdToken(idpIssuer));
    }

    /** Overload acquireIdpIdToken accepting XaaIdpSettings. */
    public java.util.concurrent.CompletableFuture<java.util.Optional<String>> acquireIdpIdToken(
            XaaIdpSettings settings) {
        if (settings == null) return java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.empty());
        IdpLoginOptions opts = new IdpLoginOptions();
        opts.setIdpIssuer(settings.getIssuer());
        opts.setIdpClientId(settings.getClientId());
        opts.setCallbackPort(settings.getCallbackPort());
        return acquireIdpIdToken(opts).thenApply(java.util.Optional::ofNullable);
    }

    /** Clear XAA IdP settings. */
    public void clearXaaIdpSettings() {
        log.debug("clearXaaIdpSettings called");
    }
}
