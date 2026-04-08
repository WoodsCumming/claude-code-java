package com.anthropic.claudecode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/**
 * First-party event logger service.
 * Translated from src/services/analytics/firstPartyEventLogger.ts
 *
 * Logs events to Anthropic's first-party event logging system. Events are
 * batched and exported to /api/event_logging/batch.
 */
@Slf4j
@Service
public class FirstPartyEventLoggerService {



    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final String EVENT_SAMPLING_CONFIG_NAME = "tengu_event_sampling_config";
    private static final String BATCH_CONFIG_NAME = "tengu_1p_event_batch_config";

    private static final int DEFAULT_LOGS_EXPORT_INTERVAL_MS = 10_000;
    private static final int DEFAULT_MAX_EXPORT_BATCH_SIZE = 200;
    private static final int DEFAULT_MAX_QUEUE_SIZE = 8192;

    // -------------------------------------------------------------------------
    // Nested types
    // -------------------------------------------------------------------------

    /**
     * Per-event sampling configuration.
     * Translated from EventSamplingConfig in firstPartyEventLogger.ts
     */
    public record EventSamplingEntry(double sample_rate) {}

    /**
     * Batch export configuration from GrowthBook.
     * Translated from BatchConfig in firstPartyEventLogger.ts
     */
    public record BatchConfig(
        Integer scheduledDelayMillis,
        Integer maxExportBatchSize,
        Integer maxQueueSize,
        Boolean skipAuth,
        Integer maxAttempts,
        String path,
        String baseUrl
    ) {
        public static BatchConfig defaultConfig() {
            return new BatchConfig(null, null, null, null, null, null, null);
        }
    }

    /**
     * GrowthBook experiment event data.
     * Translated from GrowthBookExperimentData in firstPartyEventLogger.ts
     */
    public record GrowthBookExperimentData(
        String experimentId,
        int variationId,
        Map<String, Object> userAttributes,
        Map<String, Object> experimentMetadata
    ) {}

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final AnalyticsConfigService analyticsConfigService;
    private final GrowthBookService growthBookService;
    private final SinkKillswitchService sinkKillswitchService;
    private final ObjectMapper objectMapper;

    private volatile boolean initialized = false;
    private volatile BatchConfig lastBatchConfig = null;

    // In-memory event queue (replaces OTel BatchLogRecordProcessor for Java)
    private final List<Map<String, Object>> eventQueue = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "1p-event-logger");
            t.setDaemon(true);
            return t;
        });

    @Autowired
    public FirstPartyEventLoggerService(
            AnalyticsConfigService analyticsConfigService,
            GrowthBookService growthBookService,
            SinkKillswitchService sinkKillswitchService,
            ObjectMapper objectMapper) {
        this.analyticsConfigService = analyticsConfigService;
        this.growthBookService = growthBookService;
        this.sinkKillswitchService = sinkKillswitchService;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Initialize 1P event logging infrastructure.
     * Translated from initialize1PEventLogging() in firstPartyEventLogger.ts
     */
    public synchronized void initialize1PEventLogging() {
        if (!is1PEventLoggingEnabled()) {
            if ("ant".equals(System.getenv("USER_TYPE"))) {
                log.debug("1P event logging not enabled");
            }
            return;
        }

        BatchConfig batchConfig = getBatchConfig();
        lastBatchConfig = batchConfig;

        int scheduledDelayMillis = Optional.ofNullable(batchConfig.scheduledDelayMillis())
            .orElseGet(() -> {
                String envVal = System.getenv("OTEL_LOGS_EXPORT_INTERVAL");
                if (envVal != null) {
                    try { return Integer.parseInt(envVal); } catch (NumberFormatException ignored) {}
                }
                return DEFAULT_LOGS_EXPORT_INTERVAL_MS;
            });

        int maxBatchSize = Optional.ofNullable(batchConfig.maxExportBatchSize())
            .orElse(DEFAULT_MAX_EXPORT_BATCH_SIZE);

        // Schedule periodic export
        scheduler.scheduleAtFixedRate(
            () -> exportBatch(maxBatchSize),
            scheduledDelayMillis,
            scheduledDelayMillis,
            TimeUnit.MILLISECONDS
        );

        initialized = true;
        if ("ant".equals(System.getenv("USER_TYPE"))) {
            log.debug("1P event logging initialized (delay={}ms, batchSize={})",
                scheduledDelayMillis, maxBatchSize);
        }
    }

    /**
     * Flush and shut down the 1P event logger.
     * Call as the final step before process exit.
     * Translated from shutdown1PEventLogging() in firstPartyEventLogger.ts
     */
    public CompletableFuture<Void> shutdown1PEventLogging() {
        if (!initialized) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            try {
                scheduler.shutdown();
                exportBatch(Integer.MAX_VALUE);
                if ("ant".equals(System.getenv("USER_TYPE"))) {
                    log.debug("1P event logging: final shutdown complete");
                }
            } catch (Exception e) {
                log.debug("1P event logging shutdown error: {}", e.getMessage());
            }
        });
    }

    /**
     * Rebuild the 1P event logging pipeline if the batch config changed.
     * Translated from reinitialize1PEventLoggingIfConfigChanged() in firstPartyEventLogger.ts
     */
    public CompletableFuture<Void> reinitialize1PEventLoggingIfConfigChanged() {
        if (!is1PEventLoggingEnabled() || !initialized) {
            return CompletableFuture.completedFuture(null);
        }

        BatchConfig newConfig = getBatchConfig();
        if (Objects.equals(newConfig, lastBatchConfig)) {
            return CompletableFuture.completedFuture(null);
        }

        if ("ant".equals(System.getenv("USER_TYPE"))) {
            log.debug("1P event logging: {} changed, reinitializing", BATCH_CONFIG_NAME);
        }

        return CompletableFuture.runAsync(() -> {
            // Drain queued events before reinit
            exportBatch(Integer.MAX_VALUE);
            initialized = false;
            initialize1PEventLogging();
        });
    }

    // -------------------------------------------------------------------------
    // Sampling
    // -------------------------------------------------------------------------

    /**
     * Determine if an event should be sampled based on its configured sample rate.
     *
     * Returns the sample rate (positive) if the event should be logged,
     * {@code 0.0} if it should be dropped, or {@code null} if no sampling config
     * exists for this event (log at 100%).
     *
     * Translated from shouldSampleEvent() in firstPartyEventLogger.ts
     */
    public Double shouldSampleEvent(String eventName) {
        Map<String, Object> config = getEventSamplingConfig();
        Object eventConfigObj = config.get(eventName);

        // No config for this event → log at 100% rate
        if (!(eventConfigObj instanceof Map<?, ?> eventConfig)) {
            return null;
        }

        Object sampleRateObj = eventConfig.get("sample_rate");
        if (!(sampleRateObj instanceof Number)) {
            return null;
        }

        double sampleRate = ((Number) sampleRateObj).doubleValue();

        // Validate range
        if (sampleRate < 0 || sampleRate > 1) {
            return null;
        }
        // Rate of 1 → log everything (no metadata needed)
        if (sampleRate >= 1.0) {
            return null;
        }
        // Rate of 0 → drop everything
        if (sampleRate <= 0.0) {
            return 0.0;
        }

        // Random sampling
        return Math.random() < sampleRate ? sampleRate : 0.0;
    }

    // -------------------------------------------------------------------------
    // Event logging
    // -------------------------------------------------------------------------

    /**
     * Check if 1P event logging is enabled.
     * Translated from is1PEventLoggingEnabled() in firstPartyEventLogger.ts
     */
    public boolean is1PEventLoggingEnabled() {
        return !analyticsConfigService.isAnalyticsDisabled();
    }

    /**
     * Log a 1st-party event for internal analytics.
     * Events are batched and exported to /api/event_logging/batch.
     * Translated from logEventTo1P() in firstPartyEventLogger.ts
     */
    public void logEventTo1P(String eventName, Map<String, Object> metadata) {
        if (!is1PEventLoggingEnabled()) {
            return;
        }
        if (!initialized
                || sinkKillswitchService.isSinkKilled(SinkKillswitchService.SinkName.FIRST_PARTY)) {
            return;
        }

        // Fire and forget async enrichment
        CompletableFuture.runAsync(() -> enqueueEvent(eventName, metadata));
    }

    /**
     * Log a GrowthBook experiment assignment event to 1P.
     * Translated from logGrowthBookExperimentTo1P() in firstPartyEventLogger.ts
     */
    public void logGrowthBookExperimentTo1P(GrowthBookExperimentData data) {
        if (!is1PEventLoggingEnabled()) {
            return;
        }
        if (!initialized
                || sinkKillswitchService.isSinkKilled(SinkKillswitchService.SinkName.FIRST_PARTY)) {
            return;
        }

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("event_type", "GrowthbookExperimentEvent");
        attributes.put("event_id", UUID.randomUUID().toString());
        attributes.put("experiment_id", data.experimentId());
        attributes.put("variation_id", data.variationId());
        attributes.put("environment", "production");

        if (data.userAttributes() != null) {
            try {
                attributes.put("user_attributes", objectMapper.writeValueAsString(data.userAttributes()));
            } catch (Exception ignored) {}
        }
        if (data.experimentMetadata() != null) {
            try {
                attributes.put("experiment_metadata",
                    objectMapper.writeValueAsString(data.experimentMetadata()));
            } catch (Exception ignored) {}
        }

        if ("ant".equals(System.getenv("USER_TYPE"))) {
            log.debug("[ANT-ONLY] 1P GrowthBook experiment: {} variation={}",
                data.experimentId(), data.variationId());
        }

        enqueueEvent("growthbook_experiment", attributes);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void enqueueEvent(String eventName, Map<String, Object> metadata) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("event_id", UUID.randomUUID().toString());
        record.put("event_name", eventName);
        record.put("timestamp", System.currentTimeMillis());
        if (metadata != null) {
            record.put("event_metadata", new LinkedHashMap<>(metadata));
        }

        if ("ant".equals(System.getenv("USER_TYPE"))) {
            log.debug("[ANT-ONLY] 1P event: {} {}", eventName, metadata);
        }

        int maxQueueSize = Optional.ofNullable(lastBatchConfig)
            .map(BatchConfig::maxQueueSize)
            .filter(Objects::nonNull)
            .orElse(DEFAULT_MAX_QUEUE_SIZE);

        if (eventQueue.size() < maxQueueSize) {
            eventQueue.add(record);
        } else {
            log.debug("1P event queue full, dropping event: {}", eventName);
        }
    }

    private synchronized void exportBatch(int maxBatch) {
        if (eventQueue.isEmpty()) return;

        int size = Math.min(eventQueue.size(), maxBatch);
        List<Map<String, Object>> toExport = new ArrayList<>(eventQueue.subList(0, size));
        // Remove exported events
        for (int i = 0; i < size; i++) {
            if (!eventQueue.isEmpty()) eventQueue.remove(0);
        }

        if (toExport.isEmpty()) return;

        // In production this would POST to /api/event_logging/batch
        // For now just debug-log the export count
        if ("ant".equals(System.getenv("USER_TYPE"))) {
            log.debug("1P event export: {} events", toExport.size());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getEventSamplingConfig() {
        try {
            Object raw = growthBookService.getDynamicConfig(EVENT_SAMPLING_CONFIG_NAME, Map.of());
            if (raw instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        } catch (Exception ignored) {}
        return Map.of();
    }

    private BatchConfig getBatchConfig() {
        try {
            Object raw = growthBookService.getDynamicConfig(BATCH_CONFIG_NAME, Map.of());
            if (raw instanceof Map<?, ?> map) {
                return new BatchConfig(
                    getIntOrNull(map, "scheduledDelayMillis"),
                    getIntOrNull(map, "maxExportBatchSize"),
                    getIntOrNull(map, "maxQueueSize"),
                    getBoolOrNull(map, "skipAuth"),
                    getIntOrNull(map, "maxAttempts"),
                    getStringOrNull(map, "path"),
                    getStringOrNull(map, "baseUrl")
                );
            }
        } catch (Exception ignored) {}
        return BatchConfig.defaultConfig();
    }

    private static Integer getIntOrNull(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v instanceof Number ? ((Number) v).intValue() : null;
    }

    private static Boolean getBoolOrNull(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v instanceof Boolean ? (Boolean) v : null;
    }

    private static String getStringOrNull(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v instanceof String ? (String) v : null;
    }
}
