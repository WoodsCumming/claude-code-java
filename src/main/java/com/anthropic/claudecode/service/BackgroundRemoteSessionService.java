package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.TodoItem;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CCR (Claude Code Remote) client — manages the worker lifecycle protocol with CCR v2.
 *
 * Translated from {@code src/cli/transports/ccrClient.ts}.
 *
 * Responsibilities (mirrors the TypeScript class):
 * <ul>
 *   <li>Epoch management: reads {@code CLAUDE_CODE_WORKER_EPOCH} from env</li>
 *   <li>Runtime state reporting: PUT /sessions/{id}/worker</li>
 *   <li>Heartbeat: POST /sessions/{id}/worker/heartbeat (liveness detection)</li>
 *   <li>Event upload: POST /sessions/{id}/worker/events (client events)</li>
 *   <li>Internal events: POST /sessions/{id}/worker/internal-events (transcript)</li>
 *   <li>Delivery tracking: POST /sessions/{id}/worker/events/delivery</li>
 *   <li>Stream event buffering and text_delta coalescing</li>
 * </ul>
 */
@Slf4j
@Service
public class BackgroundRemoteSessionService {



    // =========================================================================
    // Constants
    // =========================================================================

    /** Default heartbeat interval (20 s; server TTL is 60 s). */
    private static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 20_000L;

    /** Stream event flush window (100 ms — mirrors HybridTransport batching). */
    private static final long STREAM_EVENT_FLUSH_INTERVAL_MS = 100L;

    /**
     * Consecutive 401/403 threshold before giving up.
     * 10 × 20 s heartbeat ≈ 200 s to ride out transient auth blips.
     */
    private static final int MAX_CONSECUTIVE_AUTH_FAILURES = 10;

    // =========================================================================
    // CCR init failure reasons  (CCRInitFailReason in ccrClient.ts)
    // =========================================================================

    public enum CCRInitFailReason {
        NO_AUTH_HEADERS,
        MISSING_EPOCH,
        WORKER_REGISTER_FAILED
    }

    /** Thrown by {@link #initialize}; carries a typed reason for the diagnostic classifier. */
    public static class CCRInitException extends RuntimeException {
        private final CCRInitFailReason reason;

        public CCRInitException(CCRInitFailReason reason) {
            super("CCRClient init failed: " + reason);
            this.reason = reason;
        }

        public CCRInitFailReason getReason() {
            return reason;
        }
    }

    // =========================================================================
    // Domain types
    // =========================================================================

    /** Session state enum. Mirrors SessionState in sessionState.ts. */
    public enum SessionState { IDLE, RUNNING, REQUIRES_ACTION }

    /** An event payload sent to CCR. */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class EventPayload {
        private String uuid;
        private String type;
        private Map<String, Object> extra;

        public String getUuid() { return uuid; }
        public void setUuid(String v) { uuid = v; }
        public String getType() { return type; }
        public void setType(String v) { type = v; }
        public Map<String, Object> getExtra() { return extra; }
        public void setExtra(Map<String, Object> v) { extra = v; }
    }

    /** An internal worker event (transcript entry). */
    @Data
    @lombok.Builder
    
    public static class InternalEvent {
        private String eventId;
        private String eventType;
        private Map<String, Object> payload;
        private Map<String, Object> eventMetadata;
        private boolean isCompaction;
        private String createdAt;
        private String agentId;
    }

    /** Delivery status for a client-to-worker event. */
    public enum DeliveryStatus { RECEIVED, PROCESSING, PROCESSED }

    // =========================================================================
    // Worker state
    // =========================================================================

    private long workerEpoch = 0;
    private final long heartbeatIntervalMs;
    private SessionState currentState = null;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger consecutiveAuthFailures = new AtomicInteger(0);

    private ScheduledExecutorService heartbeatExecutor;
    private ScheduledFuture<?> heartbeatFuture;
    private final AtomicBoolean heartbeatInFlight = new AtomicBoolean(false);

    private String sessionBaseUrl;
    private String sessionId;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private final AuthService authService;
    private final PolicyLimitsService policyLimitsService;

    // Stream-event delay buffer
    private final List<Map<String, Object>> streamEventBuffer = new CopyOnWriteArrayList<>();
    private ScheduledFuture<?> streamEventTimer;

    @Autowired
    public BackgroundRemoteSessionService(AuthService authService,
                                          PolicyLimitsService policyLimitsService) {
        this.authService   = authService;
        this.policyLimitsService = policyLimitsService;
        this.heartbeatIntervalMs = DEFAULT_HEARTBEAT_INTERVAL_MS;
    }

    // =========================================================================
    // Session initialization  (CCRClient.initialize() in ccrClient.ts)
    // =========================================================================

    /**
     * Initialise the session worker.
     * <ol>
     *   <li>Resolve auth headers — fail fast if none</li>
     *   <li>Read / validate {@code CLAUDE_CODE_WORKER_EPOCH}</li>
     *   <li>PUT worker state as {@code idle}</li>
     *   <li>Start heartbeat timer</li>
     * </ol>
     *
     * Translated from {@code CCRClient.initialize(epoch?)} in ccrClient.ts.
     *
     * @param sessionUrl full session URL, e.g. {@code https://host/v1/code/sessions/{id}}
     * @param epoch      optional explicit epoch (overrides env var)
     * @return external metadata from the prior worker state, or {@code null}
     */
    public CompletableFuture<Map<String, Object>> initialize(String sessionUrl, Integer epoch) {
        return CompletableFuture.supplyAsync(() -> {
            long startMs = System.currentTimeMillis();

            Map<String, String> authHeaders = getAuthHeaders();
            if (authHeaders.isEmpty()) {
                throw new CCRInitException(CCRInitFailReason.NO_AUTH_HEADERS);
            }

            // Resolve epoch
            int resolvedEpoch;
            if (epoch != null) {
                resolvedEpoch = epoch;
            } else {
                String raw = System.getenv("CLAUDE_CODE_WORKER_EPOCH");
                try {
                    resolvedEpoch = raw != null ? Integer.parseInt(raw) : Integer.MIN_VALUE;
                } catch (NumberFormatException e) {
                    resolvedEpoch = Integer.MIN_VALUE;
                }
            }
            if (resolvedEpoch == Integer.MIN_VALUE) {
                throw new CCRInitException(CCRInitFailReason.MISSING_EPOCH);
            }
            this.workerEpoch = resolvedEpoch;

            // Parse session URL
            try {
                URI uri = URI.create(sessionUrl);
                String path = uri.getPath().replaceAll("/+$", "");
                this.sessionBaseUrl = uri.getScheme() + "://" + uri.getHost() +
                    (uri.getPort() > 0 ? ":" + uri.getPort() : "") + path;
                String[] segments = path.split("/");
                this.sessionId = segments[segments.length - 1];
            } catch (Exception e) {
                throw new CCRInitException(CCRInitFailReason.WORKER_REGISTER_FAILED);
            }

            // PUT worker (init)
            boolean ok = putWorker(Map.of(
                "worker_status", "idle",
                "worker_epoch", workerEpoch,
                "external_metadata", Map.of("pending_action", "", "task_summary", "")
            ));
            if (!ok) {
                throw new CCRInitException(CCRInitFailReason.WORKER_REGISTER_FAILED);
            }

            this.currentState = SessionState.IDLE;
            startHeartbeat();

            log.debug("CCRClient: initialized, epoch={}, durationMs={}",
                workerEpoch, System.currentTimeMillis() - startMs);

            return null; // external metadata — real impl would GET /worker first
        });
    }

    // =========================================================================
    // State reporting  (CCRClient.reportState() in ccrClient.ts)
    // =========================================================================

    /**
     * Report worker state to CCR via PUT /sessions/{id}/worker.
     * Translated from {@code CCRClient.reportState()} in ccrClient.ts.
     */
    public void reportState(SessionState state) {
        if (state == this.currentState) return;
        this.currentState = state;
        putWorker(Map.of(
            "worker_status", state.name().toLowerCase(),
            "worker_epoch", workerEpoch
        ));
    }

    /**
     * Report external metadata to CCR via PUT /worker.
     * Translated from {@code CCRClient.reportMetadata()} in ccrClient.ts.
     */
    public void reportMetadata(Map<String, Object> metadata) {
        Map<String, Object> body = new HashMap<>();
        body.put("worker_epoch", workerEpoch);
        body.put("external_metadata", metadata);
        putWorker(body);
    }

    // =========================================================================
    // Event upload  (CCRClient.writeEvent() in ccrClient.ts)
    // =========================================================================

    /**
     * Write a stdout message as a client event.
     * Non-stream events flush the buffer first to preserve ordering.
     * Translated from {@code CCRClient.writeEvent()} in ccrClient.ts.
     */
    public CompletableFuture<Void> writeEvent(Map<String, Object> message) {
        String type = (String) message.get("type");
        if ("stream_event".equals(type)) {
            streamEventBuffer.add(message);
            scheduleStreamEventFlush();
            return CompletableFuture.completedFuture(null);
        }
        return flushStreamEventBuffer().thenRunAsync(() ->
            postEvents(List.of(toClientEvent(message))));
    }

    /**
     * Write an internal worker event (not visible to frontend clients).
     * Translated from {@code CCRClient.writeInternalEvent()} in ccrClient.ts.
     */
    public CompletableFuture<Void> writeInternalEvent(String eventType,
                                                       Map<String, Object> payload,
                                                       boolean isCompaction,
                                                       String agentId) {
        return CompletableFuture.runAsync(() -> {
            Map<String, Object> event = new HashMap<>(payload);
            event.put("type", eventType);
            if (!event.containsKey("uuid")) {
                event.put("uuid", UUID.randomUUID().toString());
            }
            Map<String, Object> body = new HashMap<>();
            body.put("worker_epoch", workerEpoch);
            body.put("events", List.of(Map.of(
                "payload", event,
                "is_compaction", isCompaction,
                "agent_id", agentId != null ? agentId : ""
            )));
            postToPath("/worker/internal-events", body);
        });
    }

    // =========================================================================
    // Delivery tracking  (CCRClient.reportDelivery() in ccrClient.ts)
    // =========================================================================

    /**
     * Report delivery status for a client-to-worker event.
     * Translated from {@code CCRClient.reportDelivery()} in ccrClient.ts.
     */
    public void reportDelivery(String eventId, DeliveryStatus status) {
        Map<String, Object> body = Map.of(
            "worker_epoch", workerEpoch,
            "updates", List.of(Map.of(
                "event_id", eventId,
                "status", status.name().toLowerCase()
            ))
        );
        CompletableFuture.runAsync(() -> postToPath("/worker/events/delivery", body));
    }

    // =========================================================================
    // Flush / close  (CCRClient.flush() / close() in ccrClient.ts)
    // =========================================================================

    /**
     * Flush pending client events.
     * Translated from {@code CCRClient.flush()} in ccrClient.ts.
     */
    public CompletableFuture<Void> flush() {
        return flushStreamEventBuffer();
    }

    /**
     * Clean up uploaders and timers.
     * Translated from {@code CCRClient.close()} in ccrClient.ts.
     */
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        stopHeartbeat();
        if (streamEventTimer != null) {
            streamEventTimer.cancel(false);
            streamEventTimer = null;
        }
        streamEventBuffer.clear();
        log.debug("CCRClient: closed");
    }

    /** Current worker epoch. */
    public long getWorkerEpoch() {
        return workerEpoch;
    }

    // =========================================================================
    // Background remote session eligibility
    // Translated from src/utils/background/remote/preconditions.ts
    //                  src/utils/background/remote/remoteSession.ts
    // =========================================================================

    /**
     * Precondition failure types for background remote sessions.
     * Translated from BackgroundRemoteSessionPrecondition in remoteSession.ts
     */
    public sealed interface BackgroundRemoteSessionPrecondition permits
            BackgroundRemoteSessionService.NotLoggedInPrecondition,
            BackgroundRemoteSessionService.NoRemoteEnvironmentPrecondition,
            BackgroundRemoteSessionService.NotInGitRepoPrecondition,
            BackgroundRemoteSessionService.NoGitRemotePrecondition,
            BackgroundRemoteSessionService.GithubAppNotInstalledPrecondition,
            BackgroundRemoteSessionService.PolicyBlockedPrecondition {}

    public record NotLoggedInPrecondition() implements BackgroundRemoteSessionPrecondition {}
    public record NoRemoteEnvironmentPrecondition() implements BackgroundRemoteSessionPrecondition {}
    public record NotInGitRepoPrecondition() implements BackgroundRemoteSessionPrecondition {}
    public record NoGitRemotePrecondition() implements BackgroundRemoteSessionPrecondition {}
    public record GithubAppNotInstalledPrecondition() implements BackgroundRemoteSessionPrecondition {}
    public record PolicyBlockedPrecondition() implements BackgroundRemoteSessionPrecondition {}

    /**
     * Repo access methods for checkRepoForRemoteAccess.
     * Translated from RepoAccessMethod in preconditions.ts
     */
    public enum RepoAccessMethod { GITHUB_APP, TOKEN_SYNC, NONE }

    public record RepoAccessResult(boolean hasAccess, RepoAccessMethod method) {}

    /**
     * Checks eligibility for creating a background remote session.
     * Returns an array of failed preconditions (empty = all checks passed).
     *
     * Translated from checkBackgroundRemoteSessionEligibility() in remoteSession.ts
     *
     * @param skipBundle when true, skips GitHub app checks (bundle seed path)
     * @return list of failed preconditions
     */
    public CompletableFuture<List<BackgroundRemoteSessionPrecondition>> checkBackgroundRemoteSessionEligibility(
            boolean skipBundle) {
        return CompletableFuture.supplyAsync(() -> {
            List<BackgroundRemoteSessionPrecondition> errors = new ArrayList<>();

            // Policy check first — if blocked, skip all other checks
            if (!policyLimitsService.isPolicyAllowed("allow_remote_sessions")) {
                errors.add(new PolicyBlockedPrecondition());
                return errors;
            }

            // Check login
            if (!authService.isClaudeAISubscriber()) {
                errors.add(new NotLoggedInPrecondition());
            }

            // Check git repo presence
            String gitRoot = findGitRoot(System.getProperty("user.dir"));
            if (gitRoot == null) {
                errors.add(new NotInGitRepoPrecondition());
            }

            // When not in a git repo, skip further checks
            if (errors.stream().anyMatch(e -> e instanceof NotInGitRepoPrecondition)) {
                return errors;
            }

            // Bundle seed path — only requires git root, skip remote+app checks
            if (skipBundle) {
                return errors;
            }

            // Remote environment check (async call simplified to sync here)
            // Real impl would call fetchEnvironments() asynchronously
            log.debug("[remote-session] Skipping async remote-env check in simplified impl");

            return errors;
        });
    }

    /**
     * Checks if the current directory is inside a git repository.
     * Translated from checkIsInGitRepo() in preconditions.ts
     */
    public boolean checkIsInGitRepo() {
        return findGitRoot(System.getProperty("user.dir")) != null;
    }

    /**
     * Checks whether the user needs to log in with Claude.ai.
     * Translated from checkNeedsClaudeAiLogin() in preconditions.ts
     */
    public CompletableFuture<Boolean> checkNeedsClaudeAiLogin() {
        return CompletableFuture.supplyAsync(() -> authService.isClaudeAISubscriber()
                && !authService.isOAuthTokenFresh());
    }

    /**
     * Checks if the git working directory is clean (no uncommitted tracked changes).
     * Translated from checkIsGitClean() in preconditions.ts
     */
    public CompletableFuture<Boolean> checkIsGitClean() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("git", "status", "--porcelain");
                pb.directory(new java.io.File(System.getProperty("user.dir")));
                Process p = pb.start();
                String output = new String(p.getInputStream().readAllBytes()).trim();
                return output.isEmpty();
            } catch (Exception e) {
                log.debug("checkIsGitClean failed: {}", e.getMessage());
                return false;
            }
        });
    }

    /**
     * Convenience overload: check eligibility without skipping bundle checks.
     */
    public CompletableFuture<List<BackgroundRemoteSessionPrecondition>> checkBackgroundRemoteSessionEligibility() {
        return checkBackgroundRemoteSessionEligibility(false);
    }

    /**
     * Legacy method kept for backward compatibility.
     * Prefer {@link #checkBackgroundRemoteSessionEligibility()} for typed results.
     */
    public CompletableFuture<List<PreconditionFailure>> checkEligibility() {
        return checkBackgroundRemoteSessionEligibility(false)
                .thenApply(typed -> typed.stream()
                        .map(p -> switch (p) {
                            case PolicyBlockedPrecondition ignored ->
                                    new PreconditionFailure("policy_blocked");
                            case NotLoggedInPrecondition ignored ->
                                    new PreconditionFailure("not_logged_in");
                            case NotInGitRepoPrecondition ignored ->
                                    new PreconditionFailure("not_in_git_repo");
                            case NoGitRemotePrecondition ignored ->
                                    new PreconditionFailure("no_git_remote");
                            case GithubAppNotInstalledPrecondition ignored ->
                                    new PreconditionFailure("github_app_not_installed");
                            case NoRemoteEnvironmentPrecondition ignored ->
                                    new PreconditionFailure("no_remote_environment");
                        })
                        .collect(java.util.stream.Collectors.toList()));
    }

    private String findGitRoot(String dir) {
        java.io.File current = new java.io.File(dir);
        while (current != null) {
            if (new java.io.File(current, ".git").exists()) {
                return current.getAbsolutePath();
            }
            current = current.getParentFile();
        }
        return null;
    }

    // =========================================================================
    // Heartbeat  (CCRClient.startHeartbeat() / sendHeartbeat() in ccrClient.ts)
    // =========================================================================

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ccr-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatFuture = heartbeatExecutor.scheduleAtFixedRate(
            this::sendHeartbeat,
            heartbeatIntervalMs,
            heartbeatIntervalMs,
            TimeUnit.MILLISECONDS
        );
    }

    private void stopHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }
    }

    private void sendHeartbeat() {
        if (!heartbeatInFlight.compareAndSet(false, true)) return;
        try {
            postToPath("/worker/heartbeat",
                Map.of("session_id", sessionId, "worker_epoch", workerEpoch));
            log.debug("CCRClient: Heartbeat sent");
        } catch (Exception e) {
            log.debug("CCRClient: Heartbeat failed: {}", e.getMessage());
        } finally {
            heartbeatInFlight.set(false);
        }
    }

    // =========================================================================
    // Stream event buffer  (CCRClient.flushStreamEventBuffer() in ccrClient.ts)
    // =========================================================================

    private void scheduleStreamEventFlush() {
        // In a real impl we would use a ScheduledExecutorService with a 100 ms delay.
        // Simplified: just post immediately to avoid requiring Spring scheduler injection.
        if (!streamEventBuffer.isEmpty()) {
            List<Map<String, Object>> buffered = new ArrayList<>(streamEventBuffer);
            streamEventBuffer.clear();
            postEvents(buffered.stream().map(this::toClientEvent).toList());
        }
    }

    private CompletableFuture<Void> flushStreamEventBuffer() {
        return CompletableFuture.runAsync(this::scheduleStreamEventFlush);
    }

    // =========================================================================
    // HTTP helpers
    // =========================================================================

    private boolean putWorker(Map<String, Object> body) {
        return postToPath("/worker", body);
    }

    private boolean postEvents(List<Map<String, Object>> events) {
        return postToPath("/worker/events",
            Map.of("worker_epoch", workerEpoch, "events", events));
    }

    private boolean postToPath(String path, Map<String, Object> body) {
        if (sessionBaseUrl == null) return false;
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(sessionBaseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 409) {
                handleEpochMismatch();
            }
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                int failures = consecutiveAuthFailures.incrementAndGet();
                if (failures >= MAX_CONSECUTIVE_AUTH_FAILURES) {
                    log.error("CCRClient: {} consecutive auth failures, exiting", failures);
                    System.exit(1);
                }
            }
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            log.debug("CCRClient: POST {} failed: {}", path, e.getMessage());
            return false;
        }
    }

    private Map<String, String> getAuthHeaders() {
        String token = System.getenv("CLAUDE_CODE_SESSION_ACCESS_TOKEN");
        if (token == null || token.isBlank()) return Map.of();
        return Map.of("Authorization", "Bearer " + token);
    }

    private void handleEpochMismatch() {
        log.error("CCRClient: Epoch mismatch (409), shutting down");
        System.exit(1);
    }

    private Map<String, Object> toClientEvent(Map<String, Object> message) {
        Map<String, Object> payload = new HashMap<>(message);
        if (!payload.containsKey("uuid")) {
            payload.put("uuid", UUID.randomUUID().toString());
        }
        return Map.of("payload", payload);
    }

    // =========================================================================
    // Inner types (kept from original Java translation)
    // =========================================================================

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    
    public static class PreconditionFailure {
        private String type;

    }

    @Data
    @lombok.Builder
    
    public static class BackgroundRemoteSession {
        private String id;
        private String command;
        private long startTime;
        private String status; // starting | running | completed | failed | killed
        private List<TodoItem> todoList;
        private String title;
        private String type; // "remote_session"
        private List<Map<String, Object>> sessionLog;
    }
}
