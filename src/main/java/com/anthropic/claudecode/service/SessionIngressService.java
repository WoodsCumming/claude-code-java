package com.anthropic.claudecode.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;

/**
 * Session ingress service for remote session logging.
 * Translated from src/services/api/sessionIngress.ts
 *
 * Sends session transcript entries to the Claude.ai session-ingress API
 * using optimistic concurrency control (Last-Uuid header). Provides:
 * - appendSessionLog   (JWT-auth, sequential per session)
 * - getSessionLogs     (JWT-auth, for hydration)
 * - getSessionLogsViaOAuth  (OAuth, for teleport)
 * - getTeleportEvents  (CCR v2 Sessions API, paginated)
 * - clearSession / clearAllSessions
 */
@Slf4j
@Service
public class SessionIngressService {



    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    private static final int  MAX_RETRIES    = 10;
    private static final long BASE_DELAY_MS  = 500;
    private static final long MAX_DELAY_MS   = 8_000;
    private static final int  TELEPORT_PAGE_LIMIT = 1_000;
    private static final int  TELEPORT_MAX_PAGES  = 100;

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    /** Last successfully stored UUID per session (for the Last-Uuid chain). */
    private final Map<String, String> lastUuidMap = new ConcurrentHashMap<>();

    /**
     * Per-session sequential executor (single-thread) — prevents concurrent
     * append calls for the same session from interleaving.
     */
    private final Map<String, ExecutorService> sessionExecutors = new ConcurrentHashMap<>();

    // -----------------------------------------------------------------------
    // Dependencies
    // -----------------------------------------------------------------------

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SessionIngressAuthProvider authProvider;
    private final OAuthService oauthService;

    @Autowired
    public SessionIngressService(OkHttpClient httpClient,
                                 ObjectMapper objectMapper,
                                 SessionIngressAuthProvider authProvider,
                                 OAuthService oauthService) {
        this.httpClient   = httpClient;
        this.objectMapper = objectMapper;
        this.authProvider = authProvider;
        this.oauthService = oauthService;
    }

    // -----------------------------------------------------------------------
    // Domain types
    // -----------------------------------------------------------------------

    /** A single transcript entry (TranscriptMessage in TypeScript). */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TranscriptEntry {
        private String uuid;
        private Object payload;  // any serialisable message type

        public String getUuid() { return uuid; }
        public void setUuid(String v) { uuid = v; }
        public Object getPayload() { return payload; }
        public void setPayload(Object v) { payload = v; }
    }

    // -----------------------------------------------------------------------
    // appendSessionLog
    // -----------------------------------------------------------------------

    /**
     * Append a log entry to the session using a JWT token.
     * Executes sequentially per session to prevent race conditions.
     * Translated from appendSessionLog() in sessionIngress.ts
     */
    public CompletableFuture<Boolean> appendSessionLog(
            String sessionId, TranscriptEntry entry, String url) {

        String sessionToken = authProvider.getSessionIngressAuthToken();
        if (sessionToken == null || sessionToken.isBlank()) {
            log.debug("No session token available for session persistence");
            return CompletableFuture.completedFuture(false);
        }

        Map<String, String> headers = Map.of(
            "Authorization", "Bearer " + sessionToken,
            "Content-Type", "application/json"
        );

        ExecutorService executor = sessionExecutors.computeIfAbsent(
            sessionId, id -> Executors.newSingleThreadExecutor());

        return CompletableFuture.supplyAsync(
            () -> appendSessionLogImpl(sessionId, entry, url, headers),
            executor);
    }

    // -----------------------------------------------------------------------
    // getSessionLogs  (JWT-auth)
    // -----------------------------------------------------------------------

    /**
     * Fetch all session logs for hydration via JWT token.
     * Translated from getSessionLogs() in sessionIngress.ts
     */
    public CompletableFuture<List<JsonNode>> getSessionLogs(String sessionId, String url) {
        String sessionToken = authProvider.getSessionIngressAuthToken();
        if (sessionToken == null || sessionToken.isBlank()) {
            log.debug("No session token available for fetching session logs");
            return CompletableFuture.completedFuture(null);
        }

        Map<String, String> headers = Map.of("Authorization", "Bearer " + sessionToken);

        return CompletableFuture.supplyAsync(() -> {
            List<JsonNode> logs = fetchSessionLogsFromUrl(sessionId, url, headers);
            if (logs != null && !logs.isEmpty()) {
                // Update lastUuid to the last entry's UUID
                for (int i = logs.size() - 1; i >= 0; i--) {
                    String uuid = logs.get(i).path("uuid").asText(null);
                    if (uuid != null && !uuid.isBlank()) {
                        lastUuidMap.put(sessionId, uuid);
                        break;
                    }
                }
            }
            return logs;
        });
    }

    // -----------------------------------------------------------------------
    // getSessionLogsViaOAuth
    // -----------------------------------------------------------------------

    /**
     * Fetch session logs via OAuth (for teleporting sessions from Sessions API).
     * Translated from getSessionLogsViaOAuth() in sessionIngress.ts
     */
    public CompletableFuture<List<JsonNode>> getSessionLogsViaOAuth(
            String sessionId, String accessToken, String orgUuid) {

        String url = oauthService.getBaseApiUrl()
            + "/v1/session_ingress/session/" + sessionId;
        log.debug("[session-ingress] Fetching session logs from: {}", url);

        Map<String, String> headers = new HashMap<>();
        headers.putAll(oauthService.getOAuthHeaders(accessToken));
        headers.put("x-organization-uuid", orgUuid);

        return CompletableFuture.supplyAsync(
            () -> fetchSessionLogsFromUrl(sessionId, url, headers));
    }

    // -----------------------------------------------------------------------
    // getTeleportEvents  (CCR v2 Sessions API, paginated)
    // -----------------------------------------------------------------------

    /**
     * Fetch worker-event transcript via the CCR v2 Sessions API.
     * Paginated (1000/page). Returns null on unrecoverable error.
     * Translated from getTeleportEvents() in sessionIngress.ts
     */
    public CompletableFuture<List<JsonNode>> getTeleportEvents(
            String sessionId, String accessToken, String orgUuid) {

        return CompletableFuture.supplyAsync(() -> {
            String baseUrl = oauthService.getBaseApiUrl()
                + "/v1/code/sessions/" + sessionId + "/teleport-events";

            Map<String, String> headers = new HashMap<>();
            headers.putAll(oauthService.getOAuthHeaders(accessToken));
            headers.put("x-organization-uuid", orgUuid);

            log.debug("[teleport] Fetching events from: {}", baseUrl);

            List<JsonNode> all = new ArrayList<>();
            String cursor = null;
            int pages = 0;

            while (pages < TELEPORT_MAX_PAGES) {
                HttpUrl.Builder urlBuilder = Objects.requireNonNull(HttpUrl.parse(baseUrl))
                    .newBuilder()
                    .addQueryParameter("limit", String.valueOf(TELEPORT_PAGE_LIMIT));
                if (cursor != null) urlBuilder.addQueryParameter("cursor", cursor);

                Request.Builder rb = new Request.Builder().url(urlBuilder.build()).get();
                headers.forEach(rb::header);
                Request request = rb.build();

                JsonNode body;
                int status;
                try (Response response = httpClient.newCall(request).execute()) {
                    status = response.code();
                    String bodyStr = response.body() != null ? response.body().string() : "{}";
                    body = objectMapper.readTree(bodyStr);
                } catch (IOException e) {
                    log.error("Teleport events fetch failed: {}", e.getMessage());
                    return null;
                }

                if (status == 404) {
                    log.debug("[teleport] Session {} not found (page {})", sessionId, pages);
                    return pages == 0 ? null : all;
                }
                if (status == 401) {
                    throw new RuntimeException("Your session has expired. Please run /login to sign in again.");
                }
                if (status != 200) {
                    log.error("Teleport events returned {}", status);
                    return null;
                }

                JsonNode data = body.path("data");
                if (!data.isArray()) {
                    log.error("Teleport events invalid response shape");
                    return null;
                }

                for (JsonNode ev : data) {
                    JsonNode payload = ev.path("payload");
                    if (!payload.isNull() && !payload.isMissingNode()) {
                        all.add(payload);
                    }
                }

                pages++;

                JsonNode nextCursorNode = body.path("next_cursor");
                if (nextCursorNode.isNull() || nextCursorNode.isMissingNode()) break;
                cursor = nextCursorNode.asText(null);
                if (cursor == null) break;
            }

            if (pages >= TELEPORT_MAX_PAGES) {
                log.warn("Teleport events hit page cap ({}) for {}", TELEPORT_MAX_PAGES, sessionId);
            }

            log.debug("[teleport] Fetched {} events over {} page(s) for {}", all.size(), pages, sessionId);
            return all;
        });
    }

    // -----------------------------------------------------------------------
    // clearSession / clearAllSessions
    // -----------------------------------------------------------------------

    /** Clear cached state for a single session. */
    public void clearSession(String sessionId) {
        lastUuidMap.remove(sessionId);
        ExecutorService exec = sessionExecutors.remove(sessionId);
        if (exec != null) exec.shutdown();
    }

    /** Clear all cached session state. */
    public void clearAllSessions() {
        lastUuidMap.clear();
        sessionExecutors.forEach((id, exec) -> exec.shutdown());
        sessionExecutors.clear();
    }

    // -----------------------------------------------------------------------
    // Private: appendSessionLogImpl  (with retry and 409 conflict handling)
    // -----------------------------------------------------------------------

    private boolean appendSessionLogImpl(
            String sessionId,
            TranscriptEntry entry,
            String url,
            Map<String, String> baseHeaders) {

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                Map<String, String> requestHeaders = new HashMap<>(baseHeaders);
                String lastUuid = lastUuidMap.get(sessionId);
                if (lastUuid != null) requestHeaders.put("Last-Uuid", lastUuid);

                String body = objectMapper.writeValueAsString(entry);

                Request.Builder rb = new Request.Builder()
                    .url(url)
                    .put(RequestBody.create(body, MediaType.parse("application/json")));
                requestHeaders.forEach(rb::header);
                Request request = rb.build();

                try (Response response = httpClient.newCall(request).execute()) {
                    int status = response.code();

                    if (status == 200 || status == 201) {
                        lastUuidMap.put(sessionId, entry.getUuid());
                        log.debug("Successfully persisted session log entry for session {}", sessionId);
                        return true;
                    }

                    if (status == 409) {
                        String serverLastUuid = response.header("x-last-uuid");
                        if (entry.getUuid().equals(serverLastUuid)) {
                            // Our entry IS already on the server (stale local state)
                            lastUuidMap.put(sessionId, entry.getUuid());
                            log.debug("Session entry {} already present on server, recovering from stale state",
                                entry.getUuid());
                            return true;
                        }
                        if (serverLastUuid != null && !serverLastUuid.isBlank()) {
                            lastUuidMap.put(sessionId, serverLastUuid);
                            log.debug("Session 409: adopting server lastUuid={} from header, retrying entry {}",
                                serverLastUuid, entry.getUuid());
                        } else {
                            // Re-fetch to find current chain head
                            List<JsonNode> logs = fetchSessionLogsFromUrl(sessionId, url, baseHeaders);
                            String adopted = findLastUuid(logs);
                            if (adopted != null) {
                                lastUuidMap.put(sessionId, adopted);
                                log.debug("Session 409: re-fetched {} entries, adopting lastUuid={}, retrying {}",
                                    logs != null ? logs.size() : 0, adopted, entry.getUuid());
                            } else {
                                log.error("Session persistence conflict: UUID mismatch for session {}, entry {}",
                                    sessionId, entry.getUuid());
                                return false;
                            }
                        }
                        continue; // retry with updated lastUuid
                    }

                    if (status == 401) {
                        log.debug("Session token expired or invalid");
                        return false; // non-retryable
                    }

                    log.debug("Failed to persist session log: {} on attempt {}/{}", status, attempt, MAX_RETRIES);
                }

            } catch (Exception e) {
                log.error("Error persisting session log: {}", e.getMessage());
            }

            if (attempt == MAX_RETRIES) {
                log.debug("Remote persistence failed after {} attempts", MAX_RETRIES);
                return false;
            }

            long delay = Math.min(BASE_DELAY_MS * (1L << (attempt - 1)), MAX_DELAY_MS);
            log.debug("Remote persistence attempt {}/{} failed, retrying in {}ms…", attempt, MAX_RETRIES, delay);
            try { Thread.sleep(delay); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
        }

        return false;
    }

    // -----------------------------------------------------------------------
    // Private: fetchSessionLogsFromUrl
    // -----------------------------------------------------------------------

    private List<JsonNode> fetchSessionLogsFromUrl(
            String sessionId, String url, Map<String, String> headers) {
        try {
            HttpUrl.Builder urlBuilder = Objects.requireNonNull(HttpUrl.parse(url)).newBuilder();
            String afterLastCompact = System.getenv("CLAUDE_AFTER_LAST_COMPACT");
            if ("true".equalsIgnoreCase(afterLastCompact) || "1".equals(afterLastCompact)) {
                urlBuilder.addQueryParameter("after_last_compact", "true");
            }

            Request.Builder rb = new Request.Builder().url(urlBuilder.build()).get();
            headers.forEach(rb::header);

            try (Response response = httpClient.newCall(rb.build()).execute()) {
                int status = response.code();
                String body = response.body() != null ? response.body().string() : "{}";

                if (status == 200) {
                    JsonNode data = objectMapper.readTree(body);
                    JsonNode loglines = data.path("loglines");
                    if (!loglines.isArray()) {
                        log.error("Invalid session logs response format");
                        return null;
                    }
                    List<JsonNode> logs = new ArrayList<>();
                    loglines.forEach(logs::add);
                    log.debug("Fetched {} session logs for session {}", logs.size(), sessionId);
                    return logs;
                }
                if (status == 404) {
                    log.debug("No existing logs for session {}", sessionId);
                    return List.of();
                }
                if (status == 401) {
                    throw new RuntimeException("Your session has expired. Please run /login to sign in again.");
                }
                log.debug("Failed to fetch session logs: {}", status);
                return null;
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching session logs: {}", e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Private: findLastUuid
    // -----------------------------------------------------------------------

    /** Walk backward through entries to find the last one with a uuid field. */
    private String findLastUuid(List<JsonNode> logs) {
        if (logs == null) return null;
        for (int i = logs.size() - 1; i >= 0; i--) {
            String uuid = logs.get(i).path("uuid").asText(null);
            if (uuid != null && !uuid.isBlank()) return uuid;
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Auth provider interface (injected so tests can stub it)
    // -----------------------------------------------------------------------

    public interface SessionIngressAuthProvider {
        String getSessionIngressAuthToken();
    }
}
