package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * Datadog analytics sink.
 * Translated from src/services/analytics/datadog.ts
 *
 * Sends analytics events to Datadog in batches.
 */
@Slf4j
@Service
public class DatadogAnalyticsSink implements AnalyticsService.AnalyticsSink {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DatadogAnalyticsSink.class);


    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final String DATADOG_LOGS_ENDPOINT =
        "https://http-intake.logs.us5.datadoghq.com/api/v2/logs";
    private static final String DATADOG_CLIENT_TOKEN = "pubbbf48e6d78dae54bceaa4acf463299bf";
    private static final long DEFAULT_FLUSH_INTERVAL_MS = 15_000L;
    private static final int MAX_BATCH_SIZE = 100;
    private static final int NETWORK_TIMEOUT_SECONDS = 5;
    private static final int NUM_USER_BUCKETS = 30;

    /** Events that may be forwarded to Datadog (all others are dropped). */
    private static final Set<String> DATADOG_ALLOWED_EVENTS = Set.of(
        "chrome_bridge_connection_succeeded",
        "chrome_bridge_connection_failed",
        "chrome_bridge_disconnected",
        "chrome_bridge_tool_call_completed",
        "chrome_bridge_tool_call_error",
        "chrome_bridge_tool_call_started",
        "chrome_bridge_tool_call_timeout",
        "tengu_api_error",
        "tengu_api_success",
        "tengu_brief_mode_enabled",
        "tengu_brief_mode_toggled",
        "tengu_brief_send",
        "tengu_cancel",
        "tengu_compact_failed",
        "tengu_exit",
        "tengu_flicker",
        "tengu_init",
        "tengu_model_fallback_triggered",
        "tengu_oauth_error",
        "tengu_oauth_success",
        "tengu_oauth_token_refresh_failure",
        "tengu_oauth_token_refresh_success",
        "tengu_oauth_token_refresh_lock_acquiring",
        "tengu_oauth_token_refresh_lock_acquired",
        "tengu_oauth_token_refresh_starting",
        "tengu_oauth_token_refresh_completed",
        "tengu_oauth_token_refresh_lock_releasing",
        "tengu_oauth_token_refresh_lock_released",
        "tengu_query_error",
        "tengu_session_file_read",
        "tengu_started",
        "tengu_tool_use_error",
        "tengu_tool_use_granted_in_prompt_permanent",
        "tengu_tool_use_granted_in_prompt_temporary",
        "tengu_tool_use_rejected_in_prompt",
        "tengu_tool_use_success",
        "tengu_uncaught_exception",
        "tengu_unhandled_rejection",
        "tengu_voice_recording_started",
        "tengu_voice_toggled",
        "tengu_team_mem_sync_pull",
        "tengu_team_mem_sync_push",
        "tengu_team_mem_sync_started",
        "tengu_team_mem_entries_capped"
    );

    /** Fields promoted to Datadog tags for faceted filtering. */
    private static final List<String> TAG_FIELDS = List.of(
        "arch",
        "clientType",
        "errorType",
        "http_status_range",
        "http_status",
        "kairosActive",
        "model",
        "platform",
        "provider",
        "skillMode",
        "subscriptionType",
        "toolName",
        "userBucket",
        "userType",
        "version",
        "versionBase"
    );

    // Pattern for truncating dev version strings:
    // "2.0.53-dev.20251124.t173302.sha526cc6a" -> "2.0.53-dev.20251124"
    private static final Pattern DEV_VERSION_PATTERN =
        Pattern.compile("^(\\d+\\.\\d+\\.\\d+-dev\\.\\d{8})\\.t\\d+\\.sha[a-f0-9]+$");

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final ObjectMapper objectMapper;
    private final AnalyticsMetadataService metadataService;

    private final List<Map<String, Object>> logBatch = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "datadog-flush");
            t.setDaemon(true);
            return t;
        });
    private volatile ScheduledFuture<?> flushTimer = null;

    /** null = not yet initialized, false = disabled, true = enabled */
    private volatile Boolean datadogInitialized = null;

    // Cached user bucket (memoized)
    private volatile Integer cachedUserBucket = null;

    @Autowired
    public DatadogAnalyticsSink(ObjectMapper objectMapper,
                                AnalyticsMetadataService metadataService) {
        this.objectMapper = objectMapper;
        this.metadataService = metadataService;
    }

    // -------------------------------------------------------------------------
    // AnalyticsSink interface
    // -------------------------------------------------------------------------

    @Override
    public void logEvent(String eventName, Map<String, Object> metadata) {
        trackDatadogEvent(eventName, metadata);
    }

    @Override
    public CompletableFuture<Void> logEventAsync(String eventName, Map<String, Object> metadata) {
        return trackDatadogEventAsync(eventName, metadata);
    }

    // -------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------->

    /**
     * Async Datadog event tracking (fire-and-forget from calling code).
     * Translated from trackDatadogEvent() in datadog.ts
     */
    public CompletableFuture<Void> trackDatadogEventAsync(
            String eventName,
            Map<String, Object> properties) {
        return CompletableFuture.runAsync(() -> trackDatadogEvent(eventName, properties));
    }

    /**
     * Track a Datadog event synchronously (batching + async flush).
     * Translated from trackDatadogEvent() in datadog.ts
     */
    public void trackDatadogEvent(String eventName, Map<String, Object> properties) {
        if (!"production".equals(System.getenv("NODE_ENV"))) {
            return;
        }

        // Don't send events for 3P providers (Bedrock, Vertex, Foundry)
        String provider = getAPIProvider();
        if (!"firstParty".equals(provider)) {
            return;
        }

        // Initialize if needed
        if (datadogInitialized == null) {
            datadogInitialized = initializeDatadog();
        }
        if (!Boolean.TRUE.equals(datadogInitialized)) {
            return;
        }

        if (!DATADOG_ALLOWED_EVENTS.contains(eventName)) {
            return;
        }

        try {
            // Enrich with metadata (simplified: merge properties + envContext inline)
            Map<String, Object> allData = new LinkedHashMap<>();
            allData.putAll(properties != null ? properties : Map.of());
            allData.put("userBucket", getUserBucket());

            // Normalize MCP tool names to "mcp" for cardinality reduction
            if (allData.get("toolName") instanceof String tn && tn.startsWith("mcp__")) {
                allData.put("toolName", "mcp");
            }

            // Normalize model names for cardinality reduction (external users only)
            if (!"ant".equals(System.getenv("USER_TYPE"))
                    && allData.get("model") instanceof String modelStr) {
                allData.put("model", normalizeModelName(modelStr));
            }

            // Truncate dev version: remove timestamp and sha components
            if (allData.get("version") instanceof String version) {
                var m = DEV_VERSION_PATTERN.matcher(version);
                if (m.matches()) {
                    allData.put("version", m.group(1));
                }
            }

            // Transform status -> http_status + http_status_range
            // (avoids collision with Datadog's reserved "status" field)
            if (allData.containsKey("status")) {
                String statusCode = String.valueOf(allData.remove("status"));
                allData.put("http_status", statusCode);
                if (!statusCode.isEmpty()) {
                    char first = statusCode.charAt(0);
                    if (first >= '1' && first <= '5') {
                        allData.put("http_status_range", first + "xx");
                    }
                }
            }

            // Build ddtags — event:<name> is prepended for searchability
            List<String> tags = new ArrayList<>();
            tags.add("event:" + eventName);
            for (String field : TAG_FIELDS) {
                Object val = allData.get(field);
                if (val != null) {
                    tags.add(camelToSnakeCase(field) + ":" + val);
                }
            }

            Map<String, Object> logEntry = new LinkedHashMap<>();
            logEntry.put("ddsource", "java");
            logEntry.put("ddtags", String.join(",", tags));
            logEntry.put("message", eventName);
            logEntry.put("service", "claude-code");
            logEntry.put("hostname", "claude-code");
            logEntry.put("env", System.getenv("USER_TYPE"));

            // Add all data fields in snake_case
            for (Map.Entry<String, Object> entry : allData.entrySet()) {
                Object val = entry.getValue();
                if (val != null) {
                    logEntry.put(camelToSnakeCase(entry.getKey()), val);
                }
            }

            logBatch.add(logEntry);

            // Flush immediately if batch is full, otherwise schedule
            if (logBatch.size() >= MAX_BATCH_SIZE) {
                cancelFlushTimer();
                flushLogs();
            } else {
                scheduleFlush();
            }

        } catch (Exception e) {
            log.debug("Datadog trackEvent failed: {}", e.getMessage());
        }
    }

    /**
     * Flush remaining logs and shut down.
     * Call before process exit to ensure all queued events are sent.
     * Translated from shutdownDatadog() in datadog.ts
     */
    public void shutdownDatadog() {
        cancelFlushTimer();
        scheduler.shutdown();
        flushLogs();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Initialize Datadog analytics.
     * Translated from initializeDatadog() in datadog.ts (memoized)
     */
    private synchronized boolean initializeDatadog() {
        if (datadogInitialized != null) return datadogInitialized;
        try {
            // Reuse the analytics-disabled check
            String nodeEnv = System.getenv("NODE_ENV");
            boolean bedrockEnabled = EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_BEDROCK"));
            boolean vertexEnabled = EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_VERTEX"));
            boolean foundryEnabled = EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_FOUNDRY"));
            if ("test".equals(nodeEnv) || bedrockEnabled || vertexEnabled || foundryEnabled) {
                datadogInitialized = false;
                return false;
            }
            datadogInitialized = true;
            return true;
        } catch (Exception e) {
            log.debug("Datadog init failed: {}", e.getMessage());
            datadogInitialized = false;
            return false;
        }
    }

    private void scheduleFlush() {
        if (flushTimer != null && !flushTimer.isDone()) return;
        long intervalMs = getFlushIntervalMs();
        flushTimer = scheduler.schedule(this::flushLogs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void cancelFlushTimer() {
        if (flushTimer != null) {
            flushTimer.cancel(false);
            flushTimer = null;
        }
    }

    private synchronized void flushLogs() {
        if (logBatch.isEmpty()) return;

        List<Map<String, Object>> toSend = new ArrayList<>(logBatch);
        logBatch.clear();

        CompletableFuture.runAsync(() -> {
            try {
                String body = objectMapper.writeValueAsString(toSend);

                HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(NETWORK_TIMEOUT_SECONDS))
                    .build();
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(DATADOG_LOGS_ENDPOINT))
                    .header("DD-API-KEY", DATADOG_CLIENT_TOKEN)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(java.time.Duration.ofSeconds(NETWORK_TIMEOUT_SECONDS))
                    .build();

                client.send(request, HttpResponse.BodyHandlers.discarding());

            } catch (Exception e) {
                log.debug("Datadog flush failed: {}", e.getMessage());
            }
        });
    }

    /**
     * Convert camelCase to snake_case.
     * Translated from camelToSnakeCase() in datadog.ts
     */
    private static String camelToSnakeCase(String str) {
        if (str == null) return "";
        return str.replaceAll("([A-Z])", "_$1").toLowerCase();
    }

    /**
     * Get the user's numeric bucket (0..NUM_USER_BUCKETS-1) for cardinality
     * reduction in alerting. Memoized.
     * Translated from getUserBucket() in datadog.ts
     */
    private int getUserBucket() {
        if (cachedUserBucket != null) return cachedUserBucket;
        try {
            String userId = Optional.ofNullable(System.getenv("CLAUDE_CODE_USER_ID"))
                .orElse("default-user");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(userId.getBytes(StandardCharsets.UTF_8));
            // Take first 4 bytes as unsigned int
            long hashInt = ((hash[0] & 0xFFL) << 24)
                | ((hash[1] & 0xFFL) << 16)
                | ((hash[2] & 0xFFL) << 8)
                | (hash[3] & 0xFFL);
            cachedUserBucket = (int) (hashInt % NUM_USER_BUCKETS);
        } catch (Exception e) {
            cachedUserBucket = 0;
        }
        return cachedUserBucket;
    }

    /**
     * Normalize model name to canonical short name for cardinality reduction.
     */
    private String normalizeModelName(String model) {
        if (model == null) return "other";
        // Remove "[1m]" suffix if present, return known short names
        String clean = model.replaceAll("(?i)\\[1m\\]$", "").trim();
        // Map known prefixes to canonical names
        if (clean.startsWith("claude-3-5-sonnet")) return "claude-3-5-sonnet";
        if (clean.startsWith("claude-3-opus")) return "claude-3-opus";
        if (clean.startsWith("claude-3-sonnet")) return "claude-3-sonnet";
        if (clean.startsWith("claude-3-haiku")) return "claude-3-haiku";
        if (clean.startsWith("claude-opus-4")) return "claude-opus-4";
        if (clean.startsWith("claude-sonnet-4")) return "claude-sonnet-4";
        if (clean.startsWith("claude-haiku-3-5")) return "claude-haiku-3-5";
        return "other";
    }

    /**
     * Determine the API provider from environment.
     * Translated from getAPIProvider() in providers.ts
     */
    private String getAPIProvider() {
        if (EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_BEDROCK"))) return "bedrock";
        if (EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_VERTEX"))) return "vertex";
        if (EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_FOUNDRY"))) return "foundry";
        return "firstParty";
    }

    /**
     * Get flush interval in milliseconds (overridable for testing).
     * Translated from getFlushIntervalMs() in datadog.ts
     */
    private long getFlushIntervalMs() {
        String override = System.getenv("CLAUDE_CODE_DATADOG_FLUSH_INTERVAL_MS");
        if (override != null && !override.isEmpty()) {
            try {
                return Long.parseLong(override);
            } catch (NumberFormatException ignored) {}
        }
        return DEFAULT_FLUSH_INTERVAL_MS;
    }
}
