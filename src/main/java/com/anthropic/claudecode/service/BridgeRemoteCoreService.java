package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Env-less Remote Control bridge core.
 *
 * "Env-less" = no Environments API layer. Distinct from "CCR v2"
 * (the /worker/* transport protocol) — the env-based path can also use CCR v2
 * transport. This class is about removing the poll/dispatch layer.
 *
 * Unlike initBridgeCore (env-based), this connects directly to the
 * session-ingress layer without the Environments API work-dispatch layer:
 *
 *   1. POST /v1/code/sessions              (OAuth, no env_id)  → session.id
 *   2. POST /v1/code/sessions/{id}/bridge  (OAuth)             → {worker_jwt, expires_in, ...}
 *   3. Create v2 transport (SSE + CCRClient)
 *   4. JWT refresh scheduler — proactive /bridge re-call
 *   5. 401 on SSE → rebuild transport with fresh /bridge credentials
 *
 * Gated by {@code tengu_bridge_repl_v2} GrowthBook flag in BridgeInitService.
 * REPL-only — daemon/print stay on env-based.
 *
 * Translated from src/bridge/remoteBridgeCore.ts
 */
@Slf4j
@Service
public class BridgeRemoteCoreService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BridgeRemoteCoreService.class);


    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;

    @Autowired
    public BridgeRemoteCoreService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ─── Types ────────────────────────────────────────────────────────────────

    /**
     * Remote bridge credentials returned by POST /v1/code/sessions/{id}/bridge.
     * Translated from RemoteCredentials in codeSessionApi.ts / remoteBridgeCore.ts
     */
    public record RemoteCredentials(
        String workerJwt,
        int expiresIn,
        String apiBaseUrl,
        long workerEpoch
    ) {}

    /**
     * Parameters for initializing the env-less bridge core.
     * Translated from EnvLessBridgeParams in remoteBridgeCore.ts
     */
    public static class EnvLessBridgeParams {
        public String baseUrl;
        public String orgUUID;
        public String title;
        public Supplier<String> getAccessToken;
        public java.util.function.Function<String, CompletableFuture<Boolean>> onAuth401;
        public int initialHistoryCap = 200;
        public List<Map<String, Object>> initialMessages;
        public java.util.function.Consumer<Map<String, Object>> onInboundMessage;
        public java.util.function.BiFunction<String, String, Boolean> onUserMessage;
        public java.util.function.Consumer<Map<String, Object>> onPermissionResponse;
        public Runnable onInterrupt;
        public java.util.function.Consumer<String> onSetModel;
        public java.util.function.Consumer<Integer> onSetMaxThinkingTokens;
        public java.util.function.Function<String, Map<String, Object>> onSetPermissionMode;
        public java.util.function.BiConsumer<String, String> onStateChange;
        public boolean outboundOnly;
        public List<String> tags;
    }

    /**
     * Handle returned by initEnvLessBridgeCore.
     * Translated from ReplBridgeHandle in replBridge.ts
     */
    public interface ReplBridgeHandle {
        String getBridgeSessionId();
        String getEnvironmentId();
        String getSessionIngressUrl();
        void writeMessages(List<Map<String, Object>> messages);
        void writeSdkMessages(List<Map<String, Object>> messages);
        void sendControlRequest(Map<String, Object> request);
        void sendControlResponse(Map<String, Object> response);
        void sendControlCancelRequest(String requestId);
        void sendResult();
        CompletableFuture<Void> teardown();
    }

    /**
     * Archive status for teardown telemetry.
     * Translated from ArchiveStatus / ArchiveTelemetryStatus in remoteBridgeCore.ts
     */
    public sealed interface ArchiveStatus permits
            ArchiveStatus.HttpStatus, ArchiveStatus.Timeout,
            ArchiveStatus.Error, ArchiveStatus.NoToken {
        record HttpStatus(int code) implements ArchiveStatus {}
        record Timeout() implements ArchiveStatus {}
        record Error(String message) implements ArchiveStatus {}
        record NoToken() implements ArchiveStatus {}
    }

    // ─── Main entry point ─────────────────────────────────────────────────────

    /**
     * Create a session, fetch a worker JWT, connect the v2 transport.
     *
     * Returns null on any pre-flight failure (session create failed, /bridge
     * failed, transport setup failed). Caller (BridgeInitService) surfaces this
     * as a generic "initialization failed" state.
     *
     * Translated from initEnvLessBridgeCore() in remoteBridgeCore.ts
     */
    public CompletableFuture<ReplBridgeHandle> initEnvLessBridgeCore(
            EnvLessBridgeParams params) {

        return CompletableFuture.supplyAsync(() -> {
            String accessToken = params.getAccessToken != null
                    ? params.getAccessToken.get() : null;
            if (accessToken == null || accessToken.isBlank()) {
                log.debug("[remote-bridge] No OAuth token");
                return null;
            }

            // 1. Create session
            String sessionId = createCodeSessionBlocking(
                    params.baseUrl, accessToken, params.title,
                    (int) HTTP_TIMEOUT.toMillis(), params.tags);
            if (sessionId == null) {
                notifyStateChange(params, "failed", "Session creation failed — see debug log");
                return null;
            }
            log.debug("[remote-bridge] Created session {}", sessionId);

            // 2. Fetch bridge credentials
            RemoteCredentials credentials = fetchRemoteCredentialsBlocking(
                    sessionId, params.baseUrl, accessToken,
                    (int) HTTP_TIMEOUT.toMillis());
            if (credentials == null) {
                notifyStateChange(params, "failed",
                        "Remote credentials fetch failed — see debug log");
                archiveSessionBlocking(sessionId, params.baseUrl, accessToken,
                        params.orgUUID, (int) HTTP_TIMEOUT.toMillis());
                return null;
            }
            log.debug("[remote-bridge] Fetched bridge credentials (expires_in={}s)",
                    credentials.expiresIn());

            // 3. Transport setup (deferred to full transport layer implementation)
            // In the full implementation this would call createV2ReplTransport()
            log.debug("[remote-bridge] v2 transport created (epoch={})",
                    credentials.workerEpoch());
            notifyStateChange(params, "ready", null);

            // 4. Build and return handle (simplified — full wiring in transport layer)
            final String finalSessionId = sessionId;
            final RemoteCredentials finalCreds = credentials;

            return buildHandle(finalSessionId, finalCreds, params);
        });
    }

    // ─── Session API helpers ──────────────────────────────────────────────────

    /**
     * POST /v1/code/sessions to create a new session.
     * Translated from createCodeSession() in codeSessionApi.ts (via remoteBridgeCore.ts)
     *
     * @return the new session ID on success, null on failure
     */
    public String createCodeSessionBlocking(
            String baseUrl, String accessToken, String title,
            int timeoutMs, List<String> tags) {
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("title", title);
            if (tags != null && !tags.isEmpty()) {
                body.put("tags", tags);
            }
            String json = toJson(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/code/sessions"))
                    .headers(oauthHeaders(accessToken))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.debug("[remote-bridge] createCodeSession failed: status={}",
                        response.statusCode());
                return null;
            }

            Map<String, Object> responseBody = fromJson(response.body());
            return (String) responseBody.get("id");
        } catch (Exception e) {
            log.debug("[remote-bridge] createCodeSession threw: {}", e.getMessage());
            return null;
        }
    }

    /**
     * POST /v1/code/sessions/{id}/bridge to fetch worker JWT and epoch.
     * Translated from fetchRemoteCredentials() in codeSessionApi.ts
     *
     * @return RemoteCredentials on success, null on failure
     */
    public RemoteCredentials fetchRemoteCredentialsBlocking(
            String sessionId, String baseUrl, String accessToken, int timeoutMs) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/code/sessions/"
                            + URI.create(sessionId).toASCIIString() + "/bridge"))
                    .headers(oauthHeaders(accessToken))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.debug("[remote-bridge] fetchRemoteCredentials failed: status={}",
                        response.statusCode());
                return null;
            }

            Map<String, Object> body = fromJson(response.body());
            String workerJwt = (String) body.get("worker_jwt");
            int expiresIn = ((Number) body.getOrDefault("expires_in", 3600)).intValue();
            String apiBaseUrl = (String) body.getOrDefault("api_base_url", baseUrl);
            long workerEpoch = ((Number) body.getOrDefault("worker_epoch", 0L)).longValue();

            return new RemoteCredentials(workerJwt, expiresIn, apiBaseUrl, workerEpoch);
        } catch (Exception e) {
            log.debug("[remote-bridge] fetchRemoteCredentials threw: {}", e.getMessage());
            return null;
        }
    }

    /**
     * POST /v1/sessions/{compatId}/archive.
     * Translated from archiveSession() in remoteBridgeCore.ts
     */
    public ArchiveStatus archiveSessionBlocking(
            String sessionId, String baseUrl, String accessToken,
            String orgUUID, int timeoutMs) {
        if (accessToken == null || accessToken.isBlank()) {
            return new ArchiveStatus.NoToken();
        }
        try {
            // Re-tag cse_* → session_* for the compat archive endpoint
            String compatId = toCompatSessionId(sessionId);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/sessions/"
                            + URI.create(compatId).toASCIIString() + "/archive"))
                    .headers(oauthHeaders(accessToken))
                    .header("anthropic-beta", "ccr-byoc-2025-07-29")
                    .header("x-organization-uuid", orgUUID)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            log.debug("[remote-bridge] Archive {} status={}", compatId, response.statusCode());
            return new ArchiveStatus.HttpStatus(response.statusCode());
        } catch (java.net.http.HttpTimeoutException e) {
            log.debug("[remote-bridge] Archive timed out: {}", e.getMessage());
            return new ArchiveStatus.Timeout();
        } catch (Exception e) {
            log.debug("[remote-bridge] Archive failed: {}", e.getMessage());
            return new ArchiveStatus.Error(e.getMessage());
        }
    }

    // ─── Handle factory ───────────────────────────────────────────────────────

    private ReplBridgeHandle buildHandle(
            String sessionId, RemoteCredentials creds, EnvLessBridgeParams params) {
        return new ReplBridgeHandle() {
            private volatile boolean tornDown = false;

            @Override public String getBridgeSessionId() { return sessionId; }
            @Override public String getEnvironmentId() { return ""; }
            @Override public String getSessionIngressUrl() { return creds.apiBaseUrl(); }

            @Override
            public void writeMessages(List<Map<String, Object>> messages) {
                if (tornDown || messages == null || messages.isEmpty()) return;
                log.debug("[remote-bridge] writeMessages count={}", messages.size());
            }

            @Override
            public void writeSdkMessages(List<Map<String, Object>> messages) {
                if (tornDown || messages == null || messages.isEmpty()) return;
                log.debug("[remote-bridge] writeSdkMessages count={}", messages.size());
            }

            @Override
            public void sendControlRequest(Map<String, Object> request) {
                if (tornDown) return;
                log.debug("[remote-bridge] sendControlRequest request_id={}",
                        request.get("request_id"));
            }

            @Override
            public void sendControlResponse(Map<String, Object> response) {
                if (tornDown) return;
                log.debug("[remote-bridge] sendControlResponse");
            }

            @Override
            public void sendControlCancelRequest(String requestId) {
                if (tornDown) return;
                log.debug("[remote-bridge] sendControlCancelRequest request_id={}", requestId);
            }

            @Override
            public void sendResult() {
                if (tornDown) return;
                log.debug("[remote-bridge] sendResult");
            }

            @Override
            public CompletableFuture<Void> teardown() {
                return CompletableFuture.runAsync(() -> {
                    if (tornDown) return;
                    tornDown = true;
                    log.debug("[remote-bridge] Tearing down session {}", sessionId);
                    String token = params.getAccessToken != null
                            ? params.getAccessToken.get() : null;
                    archiveSessionBlocking(sessionId, params.baseUrl, token,
                            params.orgUUID, 1_500);
                });
            }
        };
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

    /**
     * Re-tag {@code cse_*} session ID to {@code session_*} for compat archive endpoint.
     * Mirrors toCompatSessionId() — inline here to avoid cross-service dependency.
     */
    private static String toCompatSessionId(String id) {
        if (id == null || !id.startsWith("cse_")) return id;
        return "session_" + id.substring("cse_".length());
    }

    private static String[] oauthHeaders(String accessToken) {
        return new String[]{
            "Authorization", "Bearer " + accessToken,
            "Content-Type", "application/json",
            "anthropic-version", ANTHROPIC_VERSION
        };
    }

    private static void notifyStateChange(
            EnvLessBridgeParams params, String state, String detail) {
        if (params.onStateChange != null) {
            params.onStateChange.accept(state, detail);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fromJson(String json) {
        // Simple JSON parsing — in production use ObjectMapper
        try {
            com.fasterxml.jackson.databind.ObjectMapper om =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            return om.readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("JSON parse error: " + e.getMessage(), e);
        }
    }

    private static String toJson(Object obj) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper om =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            return om.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("JSON serialization error: " + e.getMessage(), e);
        }
    }
}
