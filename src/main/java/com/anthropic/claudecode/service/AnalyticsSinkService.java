package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Analytics sink service — routes events to Datadog and 1P event logging.
 *
 * Translated from:
 *   - src/services/analytics/sink.ts        (event routing)
 *   - src/utils/telemetry/bigqueryExporter.ts (BigQuery / Anthropic-API metrics export)
 *
 * This module contains the actual analytics routing logic. It is initialized
 * during app startup and attaches itself to the AnalyticsService as the sink.
 *
 * BigQuery (internal metrics) flow:
 *   - Enabled for API customers, C4E users, and Claude for Teams users.
 *   - Exports via POST to the Anthropic metrics endpoint every 5 minutes.
 *   - Payload format: InternalMetricsPayload (resource_attributes + metrics[]).
 *
 * Datadog flow:
 *   - Gated by the 'tengu_log_datadog_events' GrowthBook feature.
 *   - _PROTO_* keys are stripped before forwarding to Datadog.
 *
 * 1P (first-party) flow:
 *   - Receives the full payload including _PROTO_* privileged fields.
 */
@Slf4j
@Service
public class AnalyticsSinkService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AnalyticsSinkService.class);


    private static final String DATADOG_GATE_NAME = "tengu_log_datadog_events";

    // BigQuery / Anthropic metrics endpoint
    private static final String DEFAULT_METRICS_ENDPOINT =
            "https://api.anthropic.com/api/claude_code/metrics";
    private static final int BIGQUERY_EXPORT_TIMEOUT_MS = 5_000;

    private final AnalyticsService analyticsService;
    private final DatadogAnalyticsSink datadogSink;
    private final FirstPartyEventLoggerService firstPartyLogger;
    private final SinkKillswitchService sinkKillswitchService;
    private final GrowthBookService growthBookService;

    // Module-level gate state — starts null, initialized during startup
    private volatile Boolean isDatadogGateEnabled = null;

    // Tracks pending BigQuery export futures so forceFlush() can await them
    private final List<CompletableFuture<Void>> pendingBigQueryExports =
            new CopyOnWriteArrayList<>();
    private volatile boolean isBigQueryShutdown = false;

    @Autowired
    public AnalyticsSinkService(
            AnalyticsService analyticsService,
            DatadogAnalyticsSink datadogSink,
            FirstPartyEventLoggerService firstPartyLogger,
            SinkKillswitchService sinkKillswitchService,
            GrowthBookService growthBookService) {
        this.analyticsService      = analyticsService;
        this.datadogSink           = datadogSink;
        this.firstPartyLogger      = firstPartyLogger;
        this.sinkKillswitchService = sinkKillswitchService;
        this.growthBookService     = growthBookService;
    }

    // =========================================================================
    // Initialisation (sink.ts)
    // =========================================================================

    /**
     * Initialize analytics gates during startup.
     *
     * Updates gate values from server. Early events use cached values from
     * a previous session to avoid data loss during initialization.
     *
     * Translated from initializeAnalyticsGates() in sink.ts.
     */
    public void initializeAnalyticsGates() {
        isDatadogGateEnabled = checkStatsigGateCached(DATADOG_GATE_NAME);
    }

    /**
     * Initialize the analytics sink.
     *
     * Call this during app startup to attach the analytics backend.
     * Any events logged before this is called will be queued and drained.
     *
     * Idempotent: safe to call multiple times (subsequent calls are no-ops).
     * Translated from initializeAnalyticsSink() in sink.ts.
     */
    /** Convenience alias for initializeAnalyticsSink(). */
    public void initialize() {
        initializeAnalyticsSink();
    }

    public void initializeAnalyticsSink() {
        analyticsService.attachAnalyticsSink(new AnalyticsService.AnalyticsSink() {
            @Override
            public void logEvent(String eventName, Map<String, Object> metadata) {
                logEventImpl(eventName, metadata);
            }

            @Override
            public CompletableFuture<Void> logEventAsync(String eventName,
                                                          Map<String, Object> metadata) {
                return logEventAsyncImpl(eventName, metadata);
            }
        });
    }

    // =========================================================================
    // Event routing  (sink.ts)
    // =========================================================================

    /**
     * Synchronous event log implementation.
     * Translated from logEventImpl() in sink.ts.
     */
    private void logEventImpl(String eventName, Map<String, Object> metadata) {
        Double sampleResult = firstPartyLogger.shouldSampleEvent(eventName);

        // sampleResult == 0 means the event was not selected for logging
        if (sampleResult != null && sampleResult == 0.0) return;

        Map<String, Object> metadataWithSampleRate = (sampleResult != null)
                ? addSampleRate(metadata, sampleResult)
                : metadata;

        if (shouldTrackDatadog()) {
            // Datadog is general-access — strip _PROTO_* keys
            datadogSink.logEvent(eventName,
                    AnalyticsService.stripProtoFields(metadataWithSampleRate));
        }

        // 1P receives the full payload including _PROTO_* keys
        firstPartyLogger.logEventTo1P(eventName, metadataWithSampleRate);
    }

    /**
     * Asynchronous event log implementation.
     * With current fire-and-forget sinks this just wraps the sync impl.
     * Translated from logEventAsyncImpl() in sink.ts.
     */
    private CompletableFuture<Void> logEventAsyncImpl(String eventName,
                                                       Map<String, Object> metadata) {
        logEventImpl(eventName, metadata);
        return CompletableFuture.completedFuture(null);
    }

    // =========================================================================
    // Datadog gate  (sink.ts)
    // =========================================================================

    /**
     * Check if Datadog tracking is currently enabled.
     * Falls back to cached value from previous session if not yet initialized.
     * Translated from shouldTrackDatadog() in sink.ts.
     */
    private boolean shouldTrackDatadog() {
        if (sinkKillswitchService.isSinkKilled(SinkKillswitchService.SinkName.DATADOG)) {
            return false;
        }
        if (isDatadogGateEnabled != null) return isDatadogGateEnabled;
        try {
            return checkStatsigGateCached(DATADOG_GATE_NAME);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean checkStatsigGateCached(String gateName) {
        try {
            return growthBookService.isFeatureEnabled(gateName);
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, Object> addSampleRate(Map<String, Object> metadata, double sampleRate) {
        Map<String, Object> result = new LinkedHashMap<>(
                metadata != null ? metadata : Map.of());
        result.put("sample_rate", sampleRate);
        return result;
    }

    // =========================================================================
    // BigQuery metrics export  (bigqueryExporter.ts)
    // =========================================================================

    /**
     * Returns the BigQuery / Anthropic metrics endpoint URL.
     * Mirrors the constructor logic of BigQueryMetricsExporter in bigqueryExporter.ts.
     */
    public String getBigQueryEndpoint() {
        if ("ant".equals(System.getenv("USER_TYPE"))) {
            String antEndpoint = System.getenv("ANT_CLAUDE_CODE_METRICS_ENDPOINT");
            if (antEndpoint != null && !antEndpoint.isBlank()) {
                return antEndpoint + "/api/claude_code/metrics";
            }
        }
        return DEFAULT_METRICS_ENDPOINT;
    }

    /**
     * Export internal metrics payload to the Anthropic BigQuery endpoint.
     *
     * Mirrors doExport() in BigQueryMetricsExporter (bigqueryExporter.ts).
     *
     * @param payload the serialized InternalMetricsPayload JSON
     * @return CompletableFuture that completes with true on success, false on failure
     */
    public CompletableFuture<Boolean> exportBigQueryMetrics(String payloadJson) {
        if (isBigQueryShutdown) {
            log.debug("[BigQuery] Exporter has been shutdown — skipping export");
            return CompletableFuture.completedFuture(false);
        }

        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
            try {
                java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(getBigQueryEndpoint()))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", com.anthropic.claudecode.util.UserAgentUtils.getClaudeCodeUserAgent())
                        .timeout(java.time.Duration.ofMillis(BIGQUERY_EXPORT_TIMEOUT_MS))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payloadJson))
                        .build();

                java.net.http.HttpResponse<String> response =
                        client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    log.debug("[BigQuery] Metrics exported successfully ({})", response.statusCode());
                    return true;
                } else {
                    log.warn("[BigQuery] Metrics export failed: HTTP {}", response.statusCode());
                    return false;
                }
            } catch (Exception e) {
                log.warn("[BigQuery] Metrics export error: {}", e.getMessage());
                return false;
            }
        });

        pendingBigQueryExports.add(future.thenApply(r -> (Void) null));
        future.whenComplete((r, ex) ->
                pendingBigQueryExports.removeIf(CompletableFuture::isDone));

        return future;
    }

    /**
     * Transform resource-level attributes into the BigQuery resource_attributes map.
     * Mirrors transformMetricsForInternal() in BigQueryMetricsExporter (bigqueryExporter.ts).
     *
     * @param serviceVersion   service.version attribute from the OTel resource
     * @param osType           os.type attribute from the OTel resource
     * @param osVersion        os.version attribute from the OTel resource
     * @param hostArch         host.arch attribute from the OTel resource
     * @param wslVersion       wsl.version attribute — null/blank to omit
     * @param temporality      "delta" or "cumulative"
     * @return immutable resource-attributes map
     */
    public static Map<String, String> buildResourceAttributes(
            String serviceVersion,
            String osType,
            String osVersion,
            String hostArch,
            String wslVersion,
            String temporality) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("service.name",    "claude-code");
        attrs.put("service.version", orUnknown(serviceVersion));
        attrs.put("os.type",         orUnknown(osType));
        attrs.put("os.version",      orUnknown(osVersion));
        attrs.put("host.arch",       orUnknown(hostArch));
        attrs.put("aggregation.temporality", temporality != null ? temporality : "delta");

        if (wslVersion != null && !wslVersion.isBlank()) {
            attrs.put("wsl.version", wslVersion);
        }

        // Customer-type fields — mirrors the isClaudeAISubscriber() branch
        if (com.anthropic.claudecode.util.AuthUtils.isClaudeAISubscriber()) {
            attrs.put("user.customer_type", "claude_ai");
            String subType = com.anthropic.claudecode.util.AuthUtils.getSubscriptionType();
            if (subType != null) attrs.put("user.subscription_type", subType);
        } else {
            attrs.put("user.customer_type", "api");
        }

        return Collections.unmodifiableMap(attrs);
    }

    private static String orUnknown(String value) {
        return (value != null && !value.isBlank()) ? value : "unknown";
    }

    // =========================================================================
    // BigQuery exporter lifecycle  (bigqueryExporter.ts)
    // =========================================================================

    /**
     * Await all pending BigQuery exports.
     * Mirrors forceFlush() in BigQueryMetricsExporter.
     */
    public CompletableFuture<Void> forceFlushBigQuery() {
        List<CompletableFuture<Void>> pending = new ArrayList<>(pendingBigQueryExports);
        if (pending.isEmpty()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.allOf(pending.toArray(new CompletableFuture[0]));
    }

    /**
     * Shut down the BigQuery exporter (no further exports accepted).
     * Mirrors shutdown() in BigQueryMetricsExporter.
     */
    public CompletableFuture<Void> shutdownBigQuery() {
        isBigQueryShutdown = true;
        return forceFlushBigQuery().whenComplete((r, ex) ->
                log.debug("[BigQuery] Metrics exporter shutdown complete"));
    }

    /**
     * Returns the aggregation temporality used by the BigQuery exporter.
     * Always DELTA — changing this would break the CC Productivity metrics dashboard.
     * Mirrors selectAggregationTemporality() in BigQueryMetricsExporter.
     */
    public static String selectBigQueryAggregationTemporality() {
        // DO NOT CHANGE TO CUMULATIVE — see bigqueryExporter.ts
        return "delta";
    }
}
