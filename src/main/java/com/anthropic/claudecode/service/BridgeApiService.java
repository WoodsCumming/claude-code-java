package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.BridgeTypes.BridgeConfig;
import com.anthropic.claudecode.model.BridgeTypes.PermissionResponseEvent;
import com.anthropic.claudecode.model.BridgeTypes.WorkResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Bridge API client service.
 * Translated from src/bridge/bridgeApi.ts
 *
 * Handles all HTTP interactions with the Anthropic bridge API (environments,
 * work polling, session events, etc.)
 */
@Slf4j
@Service
public class BridgeApiService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BridgeApiService.class);


    private static final String BETA_HEADER = "environments-2025-11-01";

    /** Allowlist pattern for server-provided IDs used in URL path segments. */
    private static final Pattern SAFE_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    /** Counter for consecutive empty poll responses — suppresses log spam. */
    private volatile int consecutiveEmptyPolls = 0;
    private static final int EMPTY_POLL_LOG_INTERVAL = 100;

    @Autowired
    public BridgeApiService(OkHttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    // ─── ID validation ────────────────────────────────────────────────────────

    /**
     * Validate that a server-provided ID is safe to interpolate into a URL path.
     * Prevents path traversal and injection via IDs containing slashes, dots, etc.
     *
     * Translated from validateBridgeId() in bridgeApi.ts
     */
    public static String validateBridgeId(String id, String label) {
        if (id == null || id.isEmpty() || !SAFE_ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid " + label + ": contains unsafe characters");
        }
        return id;
    }

    /**
     * Check whether an error type string indicates a session/environment expiry.
     *
     * Translated from isExpiredErrorType() in bridgeApi.ts
     */
    public static boolean isExpiredErrorType(String errorType) {
        if (errorType == null) return false;
        return errorType.contains("expired") || errorType.contains("lifetime");
    }

    /**
     * Check whether a BridgeFatalError is a suppressible 403 permission error.
     *
     * Translated from isSuppressible403() in bridgeApi.ts
     */
    public static boolean isSuppressible403(BridgeFatalException err) {
        if (err.getStatus() != 403) return false;
        return err.getMessage().contains("external_poll_sessions")
                || err.getMessage().contains("environments:manage");
    }

    // ─── Fatal error type ─────────────────────────────────────────────────────

    /**
     * Fatal bridge errors that should not be retried (e.g. auth failures).
     * Translated from BridgeFatalError class in bridgeApi.ts
     */
    public static class BridgeFatalException extends RuntimeException {
        private final int status;
        /** Server-provided error type, e.g. "environment_expired". */
        private final String errorType;

        public BridgeFatalException(String message, int status, String errorType) {
            super(message);
            this.status = status;
            this.errorType = errorType;
        }

        public int getStatus() {
            return status;
        }

        public String getErrorType() {
            return errorType;
        }
    }

    // ─── Deps interface ───────────────────────────────────────────────────────

    /**
     * Dependency bundle for bridge API operations.
     * Translated from BridgeApiDeps in bridgeApi.ts
     */
    public interface BridgeApiDeps {
        String getBaseUrl();
        String getAccessToken();
        String getRunnerVersion();
        default void onDebug(String msg) {}
        /**
         * Called on 401 to attempt OAuth token refresh. Returns true if refreshed.
         * May be null — daemon callers using env-var tokens omit this.
         */
        default CompletableFuture<Boolean> onAuth401(String staleAccessToken) {
            return CompletableFuture.completedFuture(false);
        }
        /**
         * Returns the trusted device token for X-Trusted-Device-Token header.
         * May return null — header is omitted when absent.
         */
        default String getTrustedDeviceToken() {
            return null;
        }
    }

    // ─── API client factory ───────────────────────────────────────────────────

    /**
     * Client interface mirroring BridgeApiClient from types.ts.
     * Translated from BridgeApiClient in types.ts
     */
    public interface BridgeApiClient {
        CompletableFuture<RegisterResult> registerBridgeEnvironment(BridgeConfig config);
        CompletableFuture<WorkResponse> pollForWork(String environmentId, String environmentSecret,
                                                     Long reclaimOlderThanMs);
        CompletableFuture<Void> acknowledgeWork(String environmentId, String workId, String sessionToken);
        CompletableFuture<Void> stopWork(String environmentId, String workId, boolean force);
        CompletableFuture<Void> deregisterEnvironment(String environmentId);
        CompletableFuture<Void> archiveSession(String sessionId);
        CompletableFuture<Void> reconnectSession(String environmentId, String sessionId);
        CompletableFuture<HeartbeatResult> heartbeatWork(String environmentId, String workId, String sessionToken);
        CompletableFuture<Void> sendPermissionResponseEvent(String sessionId,
                                                             PermissionResponseEvent event,
                                                             String sessionToken);
    }

    public record RegisterResult(String environmentId, String environmentSecret) {}

    public record HeartbeatResult(boolean leaseExtended, String state) {}

    /**
     * Create a BridgeApiClient backed by this service and the given deps.
     * Translated from createBridgeApiClient() in bridgeApi.ts
     */
    public BridgeApiClient createBridgeApiClient(BridgeApiDeps deps) {
        return new BridgeApiClientImpl(deps);
    }

    // ─── HTTP helpers ─────────────────────────────────────────────────────────

    private Map<String, String> getHeaders(BridgeApiDeps deps, String accessToken) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + accessToken);
        headers.put("Content-Type", "application/json");
        headers.put("anthropic-version", "2023-06-01");
        headers.put("anthropic-beta", BETA_HEADER);
        headers.put("x-environment-runner-version", deps.getRunnerVersion());
        String deviceToken = deps.getTrustedDeviceToken();
        if (deviceToken != null) {
            headers.put("X-Trusted-Device-Token", deviceToken);
        }
        return headers;
    }

    private String resolveAuth(BridgeApiDeps deps) {
        String token = deps.getAccessToken();
        if (token == null) {
            throw new BridgeFatalException(
                    com.anthropic.claudecode.model.BridgeTypes.BRIDGE_LOGIN_INSTRUCTION, 401, null);
        }
        return token;
    }

    /**
     * Build an OkHttp Request.Builder with all bridge headers attached.
     */
    private Request.Builder baseRequest(BridgeApiDeps deps, String url, String accessToken) {
        Request.Builder builder = new Request.Builder().url(url);
        getHeaders(deps, accessToken).forEach(builder::header);
        return builder;
    }

    /**
     * Execute an HTTP call and return (statusCode, responseBodyString).
     */
    private HttpResult execute(Request request) throws IOException {
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : null;
            return new HttpResult(response.code(), body);
        }
    }

    private record HttpResult(int status, String body) {}

    /**
     * Execute an OAuth-authenticated request with a single retry on 401.
     * Translated from withOAuthRetry() in bridgeApi.ts
     */
    @SuppressWarnings("unchecked")
    private <T> T withOAuthRetry(
            BridgeApiDeps deps,
            ThrowingFunction<String, T> fn,
            String context) {
        String accessToken = resolveAuth(deps);
        T result;
        try {
            result = fn.apply(accessToken);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // If result is an HttpResult and not 401, return immediately
        if (result instanceof HttpResult hr && hr.status() != 401) {
            return result;
        }
        if (!(result instanceof HttpResult)) {
            return result;
        }

        // 401 — attempt token refresh
        deps.onDebug("[bridge:api] " + context + ": 401 received, attempting token refresh");
        Boolean refreshed;
        try {
            refreshed = deps.onAuth401(accessToken).get();
        } catch (Exception e) {
            deps.onDebug("[bridge:api] " + context + ": Token refresh threw: " + e.getMessage());
            refreshed = false;
        }

        if (Boolean.TRUE.equals(refreshed)) {
            deps.onDebug("[bridge:api] " + context + ": Token refreshed, retrying request");
            String newToken = resolveAuth(deps);
            try {
                T retryResult = fn.apply(newToken);
                if (retryResult instanceof HttpResult rhr && rhr.status() != 401) {
                    return retryResult;
                }
                if (!(retryResult instanceof HttpResult)) {
                    return retryResult;
                }
                deps.onDebug("[bridge:api] " + context + ": Retry after refresh also got 401");
                return retryResult;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            deps.onDebug("[bridge:api] " + context + ": Token refresh failed");
        }
        return result;
    }

    @FunctionalInterface
    private interface ThrowingFunction<A, B> {
        B apply(A a) throws Exception;
    }

    /**
     * Translate non-success HTTP status codes into exceptions.
     * Translated from handleErrorStatus() in bridgeApi.ts
     */
    @SuppressWarnings("unchecked")
    private void handleErrorStatus(int status, String body, String context) {
        if (status == 200 || status == 204) return;

        String detail = extractErrorDetail(body);
        String errorType = extractErrorTypeFromBody(body);

        switch (status) {
            case 401 -> throw new BridgeFatalException(
                    context + ": Authentication failed (401)" + (detail != null ? ": " + detail : "") +
                    ". " + com.anthropic.claudecode.model.BridgeTypes.BRIDGE_LOGIN_INSTRUCTION,
                    401, errorType);
            case 403 -> throw new BridgeFatalException(
                    isExpiredErrorType(errorType)
                        ? "Remote Control session has expired. Please restart with `claude remote-control` or /remote-control."
                        : context + ": Access denied (403)" + (detail != null ? ": " + detail : "") +
                          ". Check your organization permissions.",
                    403, errorType);
            case 404 -> throw new BridgeFatalException(
                    detail != null ? detail :
                    context + ": Not found (404). Remote Control may not be available for this organization.",
                    404, errorType);
            case 410 -> throw new BridgeFatalException(
                    detail != null ? detail :
                    "Remote Control session has expired. Please restart with `claude remote-control` or /remote-control.",
                    410, errorType != null ? errorType : "environment_expired");
            case 429 -> throw new RuntimeException(context + ": Rate limited (429). Polling too frequently.");
            default -> throw new RuntimeException(
                    context + ": Failed with status " + status + (detail != null ? ": " + detail : ""));
        }
    }

    private String extractErrorDetail(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            Map<String, Object> map = objectMapper.readValue(body, Map.class);
            Object error = map.get("error");
            if (error instanceof Map<?, ?> errMap) {
                Object msg = errMap.get("message");
                if (msg instanceof String s) return s;
            }
            Object message = map.get("message");
            if (message instanceof String s) return s;
        } catch (Exception ignored) {}
        return null;
    }

    private String extractErrorTypeFromBody(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            Map<String, Object> map = objectMapper.readValue(body, Map.class);
            Object error = map.get("error");
            if (error instanceof Map<?, ?> errMap) {
                Object type = errMap.get("type");
                if (type instanceof String s) return s;
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ─── BridgeApiClient implementation ──────────────────────────────────────

    private class BridgeApiClientImpl implements BridgeApiClient {

        private final BridgeApiDeps deps;

        BridgeApiClientImpl(BridgeApiDeps deps) {
            this.deps = deps;
        }

        @Override
        public CompletableFuture<RegisterResult> registerBridgeEnvironment(BridgeConfig config) {
            return CompletableFuture.supplyAsync(() -> {
                deps.onDebug("[bridge:api] POST /v1/environments/bridge bridgeId=" + config.getBridgeId());

                HttpResult result = withOAuthRetry(deps, token -> {
                    Map<String, Object> body = new HashMap<>();
                    body.put("machine_name", config.getMachineName());
                    body.put("directory", config.getDir());
                    body.put("branch", config.getBranch());
                    body.put("git_repo_url", config.getGitRepoUrl());
                    body.put("max_sessions", config.getMaxSessions());
                    body.put("metadata", Map.of("worker_type", config.getWorkerType()));
                    if (config.getReuseEnvironmentId() != null) {
                        body.put("environment_id", config.getReuseEnvironmentId());
                    }

                    String json = objectMapper.writeValueAsString(body);
                    Request request = baseRequest(deps, deps.getBaseUrl() + "/v1/environments/bridge", token)
                            .post(RequestBody.create(json, JSON_MEDIA_TYPE))
                            .build();
                    return execute(request);
                }, "Registration");

                handleErrorStatus(result.status(), result.body(), "Registration");

                Map<String, Object> responseMap = parseBody(result.body());
                String envId = (String) responseMap.get("environment_id");
                String envSecret = (String) responseMap.get("environment_secret");
                deps.onDebug("[bridge:api] POST /v1/environments/bridge -> " + result.status() +
                        " environment_id=" + envId);
                return new RegisterResult(envId, envSecret);
            });
        }

        @Override
        public CompletableFuture<WorkResponse> pollForWork(
                String environmentId,
                String environmentSecret,
                Long reclaimOlderThanMs) {
            return CompletableFuture.supplyAsync(() -> {
                validateBridgeId(environmentId, "environmentId");

                int prevEmptyPolls = consecutiveEmptyPolls;
                consecutiveEmptyPolls = 0;

                String url = deps.getBaseUrl() + "/v1/environments/" + environmentId + "/work/poll";
                if (reclaimOlderThanMs != null) {
                    url += "?reclaim_older_than_ms=" + reclaimOlderThanMs;
                }

                HttpResult result;
                try {
                    Request request = baseRequest(deps, url, environmentSecret)
                            .get()
                            .build();
                    result = execute(request);
                } catch (IOException e) {
                    throw new RuntimeException("Poll request failed: " + e.getMessage(), e);
                }

                handleErrorStatus(result.status(), result.body(), "Poll");

                if (result.body() == null || result.body().isEmpty() || result.body().equals("null")) {
                    consecutiveEmptyPolls = prevEmptyPolls + 1;
                    if (consecutiveEmptyPolls == 1 || consecutiveEmptyPolls % EMPTY_POLL_LOG_INTERVAL == 0) {
                        deps.onDebug("[bridge:api] GET .../work/poll -> " + result.status() +
                                " (no work, " + consecutiveEmptyPolls + " consecutive empty polls)");
                    }
                    return null;
                }

                try {
                    WorkResponse work = objectMapper.readValue(result.body(), WorkResponse.class);
                    deps.onDebug("[bridge:api] GET .../work/poll -> " + result.status() +
                            " workId=" + work.getId() +
                            " type=" + (work.getData() != null ? work.getData().getType() : "?") +
                            (work.getData() != null ? " sessionId=" + work.getData().getId() : ""));
                    return work;
                } catch (Exception e) {
                    throw new RuntimeException("Failed to parse poll response: " + e.getMessage(), e);
                }
            });
        }

        @Override
        public CompletableFuture<Void> acknowledgeWork(
                String environmentId, String workId, String sessionToken) {
            return CompletableFuture.runAsync(() -> {
                validateBridgeId(environmentId, "environmentId");
                validateBridgeId(workId, "workId");
                deps.onDebug("[bridge:api] POST .../work/" + workId + "/ack");

                HttpResult result;
                try {
                    Request request = baseRequest(deps,
                            deps.getBaseUrl() + "/v1/environments/" + environmentId + "/work/" + workId + "/ack",
                            sessionToken)
                            .post(RequestBody.create("{}", JSON_MEDIA_TYPE))
                            .build();
                    result = execute(request);
                } catch (IOException e) {
                    throw new RuntimeException("Acknowledge request failed: " + e.getMessage(), e);
                }

                handleErrorStatus(result.status(), result.body(), "Acknowledge");
                deps.onDebug("[bridge:api] POST .../work/" + workId + "/ack -> " + result.status());
            });
        }

        @Override
        public CompletableFuture<Void> stopWork(String environmentId, String workId, boolean force) {
            return CompletableFuture.runAsync(() -> {
                validateBridgeId(environmentId, "environmentId");
                validateBridgeId(workId, "workId");
                deps.onDebug("[bridge:api] POST .../work/" + workId + "/stop force=" + force);

                HttpResult result = withOAuthRetry(deps, token -> {
                    Map<String, Object> body = Map.of("force", force);
                    String json = objectMapper.writeValueAsString(body);
                    Request request = baseRequest(deps,
                            deps.getBaseUrl() + "/v1/environments/" + environmentId + "/work/" + workId + "/stop",
                            token)
                            .post(RequestBody.create(json, JSON_MEDIA_TYPE))
                            .build();
                    return execute(request);
                }, "StopWork");

                handleErrorStatus(result.status(), result.body(), "StopWork");
                deps.onDebug("[bridge:api] POST .../work/" + workId + "/stop -> " + result.status());
            });
        }

        @Override
        public CompletableFuture<Void> deregisterEnvironment(String environmentId) {
            return CompletableFuture.runAsync(() -> {
                validateBridgeId(environmentId, "environmentId");
                deps.onDebug("[bridge:api] DELETE /v1/environments/bridge/" + environmentId);

                HttpResult result = withOAuthRetry(deps, token -> {
                    Request request = baseRequest(deps,
                            deps.getBaseUrl() + "/v1/environments/bridge/" + environmentId,
                            token)
                            .delete()
                            .build();
                    return execute(request);
                }, "Deregister");

                handleErrorStatus(result.status(), result.body(), "Deregister");
                deps.onDebug("[bridge:api] DELETE /v1/environments/bridge/" + environmentId +
                        " -> " + result.status());
            });
        }

        @Override
        public CompletableFuture<Void> archiveSession(String sessionId) {
            return CompletableFuture.runAsync(() -> {
                validateBridgeId(sessionId, "sessionId");
                deps.onDebug("[bridge:api] POST /v1/sessions/" + sessionId + "/archive");

                HttpResult result = withOAuthRetry(deps, token -> {
                    Request request = baseRequest(deps,
                            deps.getBaseUrl() + "/v1/sessions/" + sessionId + "/archive",
                            token)
                            .post(RequestBody.create("{}", JSON_MEDIA_TYPE))
                            .build();
                    return execute(request);
                }, "ArchiveSession");

                // 409 = already archived (idempotent, not an error)
                if (result.status() == 409) {
                    deps.onDebug("[bridge:api] POST /v1/sessions/" + sessionId +
                            "/archive -> 409 (already archived)");
                    return;
                }

                handleErrorStatus(result.status(), result.body(), "ArchiveSession");
                deps.onDebug("[bridge:api] POST /v1/sessions/" + sessionId +
                        "/archive -> " + result.status());
            });
        }

        @Override
        public CompletableFuture<Void> reconnectSession(String environmentId, String sessionId) {
            return CompletableFuture.runAsync(() -> {
                validateBridgeId(environmentId, "environmentId");
                validateBridgeId(sessionId, "sessionId");
                deps.onDebug("[bridge:api] POST /v1/environments/" + environmentId +
                        "/bridge/reconnect session_id=" + sessionId);

                HttpResult result = withOAuthRetry(deps, token -> {
                    Map<String, Object> body = Map.of("session_id", sessionId);
                    String json = objectMapper.writeValueAsString(body);
                    Request request = baseRequest(deps,
                            deps.getBaseUrl() + "/v1/environments/" + environmentId + "/bridge/reconnect",
                            token)
                            .post(RequestBody.create(json, JSON_MEDIA_TYPE))
                            .build();
                    return execute(request);
                }, "ReconnectSession");

                handleErrorStatus(result.status(), result.body(), "ReconnectSession");
                deps.onDebug("[bridge:api] POST .../bridge/reconnect -> " + result.status());
            });
        }

        @Override
        public CompletableFuture<HeartbeatResult> heartbeatWork(
                String environmentId, String workId, String sessionToken) {
            return CompletableFuture.supplyAsync(() -> {
                validateBridgeId(environmentId, "environmentId");
                validateBridgeId(workId, "workId");
                deps.onDebug("[bridge:api] POST .../work/" + workId + "/heartbeat");

                HttpResult result;
                try {
                    Request request = baseRequest(deps,
                            deps.getBaseUrl() + "/v1/environments/" + environmentId +
                                    "/work/" + workId + "/heartbeat",
                            sessionToken)
                            .post(RequestBody.create("{}", JSON_MEDIA_TYPE))
                            .build();
                    result = execute(request);
                } catch (IOException e) {
                    throw new RuntimeException("Heartbeat request failed: " + e.getMessage(), e);
                }

                handleErrorStatus(result.status(), result.body(), "Heartbeat");

                Map<String, Object> responseMap = parseBody(result.body());
                boolean leaseExtended = Boolean.TRUE.equals(responseMap.get("lease_extended"));
                String state = (String) responseMap.get("state");
                deps.onDebug("[bridge:api] POST .../work/" + workId + "/heartbeat -> " + result.status() +
                        " lease_extended=" + leaseExtended + " state=" + state);
                return new HeartbeatResult(leaseExtended, state);
            });
        }

        @Override
        public CompletableFuture<Void> sendPermissionResponseEvent(
                String sessionId, PermissionResponseEvent event, String sessionToken) {
            return CompletableFuture.runAsync(() -> {
                validateBridgeId(sessionId, "sessionId");
                deps.onDebug("[bridge:api] POST /v1/sessions/" + sessionId +
                        "/events type=" + event.getType());

                HttpResult result;
                try {
                    Map<String, Object> body = Map.of("events", List.of(event));
                    String json = objectMapper.writeValueAsString(body);
                    Request request = baseRequest(deps,
                            deps.getBaseUrl() + "/v1/sessions/" + sessionId + "/events",
                            sessionToken)
                            .post(RequestBody.create(json, JSON_MEDIA_TYPE))
                            .build();
                    result = execute(request);
                } catch (IOException e) {
                    throw new RuntimeException("SendPermissionResponseEvent request failed: " + e.getMessage(), e);
                }

                handleErrorStatus(result.status(), result.body(), "SendPermissionResponseEvent");
                deps.onDebug("[bridge:api] POST /v1/sessions/" + sessionId +
                        "/events -> " + result.status());
            });
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> parseBody(String body) {
            if (body == null || body.isEmpty()) return Map.of();
            try {
                return objectMapper.readValue(body, Map.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse response body: " + e.getMessage(), e);
            }
        }
    }
}
