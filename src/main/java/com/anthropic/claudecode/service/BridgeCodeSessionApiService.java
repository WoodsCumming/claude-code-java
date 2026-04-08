package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.BridgeDebugUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
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
import java.util.concurrent.CompletableFuture;

/**
 * Thin HTTP wrappers for the CCR v2 code-session API.
 * Translated from src/bridge/codeSessionApi.ts
 *
 * Separate from main bridge core so the SDK /bridge subpath can export
 * createCodeSession + fetchRemoteCredentials without bundling the heavy CLI
 * tree (analytics, transport, etc.). Callers supply explicit accessToken +
 * baseUrl — no implicit auth or config reads.
 */
@Slf4j
@Service
public class BridgeCodeSessionApiService {



    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public BridgeCodeSessionApiService(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Credentials from POST /bridge. JWT is opaque — do not decode.
     * Each /bridge call bumps worker_epoch server-side (it IS the register).
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class RemoteCredentials {
        private String worker_jwt;
        private String api_base_url;
        private long expires_in;
        private long worker_epoch;

        public String getWorker_jwt() { return worker_jwt; }
        public void setWorker_jwt(String v) { worker_jwt = v; }
        public String getApi_base_url() { return api_base_url; }
        public void setApi_base_url(String v) { api_base_url = v; }
        public long getExpires_in() { return expires_in; }
        public void setExpires_in(long v) { expires_in = v; }
        public long getWorker_epoch() { return worker_epoch; }
        public void setWorker_epoch(long v) { worker_epoch = v; }
    

    }

    /**
     * Create a code session via POST /v1/code/sessions.
     *
     * @return the session ID (cse_* prefix) on success, null on failure
     */
    public CompletableFuture<String> createCodeSession(
            String baseUrl,
            String accessToken,
            String title,
            long timeoutMs,
            List<String> tags) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/v1/code/sessions";
            try {
                Map<String, Object> bodyMap = new java.util.LinkedHashMap<>();
                bodyMap.put("title", title);
                bodyMap.put("bridge", Map.of());
                if (tags != null && !tags.isEmpty()) {
                    bodyMap.put("tags", tags);
                }
                String jsonBody = objectMapper.writeValueAsString(bodyMap);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Content-Type", "application/json")
                        .header("anthropic-version", ANTHROPIC_VERSION)
                        .timeout(Duration.ofMillis(timeoutMs))
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build();

                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200 && response.statusCode() != 201) {
                    Object detail = parseErrorDetail(response.body());
                    log.debug("[code-session] Session create failed {}{}",
                            response.statusCode(),
                            detail != null ? ": " + detail : "");
                    return null;
                }

                Map<?, ?> data = objectMapper.readValue(response.body(), Map.class);
                Object session = data.get("session");
                if (!(session instanceof Map<?, ?> sessionMap)) {
                    log.debug("[code-session] No session in response: {}",
                            response.body().substring(0, Math.min(200, response.body().length())));
                    return null;
                }
                Object id = sessionMap.get("id");
                if (!(id instanceof String sessionId) || !sessionId.startsWith("cse_")) {
                    log.debug("[code-session] No session.id (cse_*) in response: {}",
                            response.body().substring(0, Math.min(200, response.body().length())));
                    return null;
                }
                return sessionId;

            } catch (Exception err) {
                log.debug("[code-session] Session create request failed: {}", err.getMessage());
                return null;
            }
        });
    }

    /**
     * Fetch remote credentials via POST /v1/code/sessions/{sessionId}/bridge.
     *
     * @return RemoteCredentials on success, null on failure
     */
    public CompletableFuture<RemoteCredentials> fetchRemoteCredentials(
            String sessionId,
            String baseUrl,
            String accessToken,
            long timeoutMs,
            String trustedDeviceToken) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/v1/code/sessions/" + sessionId + "/bridge";
            try {
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Content-Type", "application/json")
                        .header("anthropic-version", ANTHROPIC_VERSION)
                        .timeout(Duration.ofMillis(timeoutMs))
                        .POST(HttpRequest.BodyPublishers.ofString("{}"));

                if (trustedDeviceToken != null && !trustedDeviceToken.isEmpty()) {
                    requestBuilder.header("X-Trusted-Device-Token", trustedDeviceToken);
                }

                HttpResponse<String> response = httpClient.send(
                        requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    Object detail = parseErrorDetail(response.body());
                    log.debug("[code-session] /bridge failed {}{}",
                            response.statusCode(),
                            detail != null ? ": " + detail : "");
                    return null;
                }

                Map<?, ?> data = objectMapper.readValue(response.body(), Map.class);
                Object workerJwt = data.get("worker_jwt");
                Object apiBaseUrl = data.get("api_base_url");
                Object expiresIn = data.get("expires_in");
                Object workerEpoch = data.get("worker_epoch");

                if (!(workerJwt instanceof String)
                        || !(apiBaseUrl instanceof String)
                        || expiresIn == null
                        || workerEpoch == null) {
                    log.debug("[code-session] /bridge response malformed "
                            + "(need worker_jwt, expires_in, api_base_url, worker_epoch): {}",
                            response.body().substring(0, Math.min(200, response.body().length())));
                    return null;
                }

                // protojson serializes int64 as string to avoid JS precision loss
                long epoch;
                if (workerEpoch instanceof String s) {
                    epoch = Long.parseLong(s);
                } else if (workerEpoch instanceof Number n) {
                    epoch = n.longValue();
                } else {
                    log.debug("[code-session] /bridge worker_epoch invalid: {}", workerEpoch);
                    return null;
                }

                RemoteCredentials creds = new RemoteCredentials();
                creds.setWorker_jwt((String) workerJwt);
                creds.setApi_base_url((String) apiBaseUrl);
                creds.setExpires_in(((Number) expiresIn).longValue());
                creds.setWorker_epoch(epoch);
                return creds;

            } catch (Exception err) {
                log.debug("[code-session] /bridge request failed: {}", err.getMessage());
                return null;
            }
        });
    }

    // --- Private helpers ---

    private String parseErrorDetail(String body) {
        try {
            Map<?, ?> data = objectMapper.readValue(body, Map.class);
            return BridgeDebugUtils.extractErrorDetail(data);
        } catch (Exception e) {
            return null;
        }
    }
}
