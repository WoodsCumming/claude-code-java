package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Telemetry instrumentation bootstrap for Claude Code.
 *
 * Translated from src/utils/telemetry/instrumentation.ts
 *
 * Responsibilities:
 *   - bootstrapTelemetry() — copy ANT_OTEL_* → OTEL_* env overrides before SDK init
 *   - parseExporterTypes() — parse comma-separated exporter type lists, filtering "none"
 *   - Exporter protocol constants and configuration helpers
 *   - isTelemetryEnabled() / isBigQueryMetricsEnabled() — enablement gates
 *   - Shutdown / flush helpers
 *
 * In the TypeScript codebase, initializeTelemetry() wires up OTEL SDK providers
 * (MeterProvider, LoggerProvider, BasicTracerProvider) using dynamic imports.
 * In Java, provider wiring is done via Spring Boot auto-configuration and the
 * opentelemetry-spring-boot-starter; this class exposes the configuration
 * helpers and enablement checks consumed by that layer.
 *
 * Key constants:
 *   DEFAULT_METRICS_EXPORT_INTERVAL_MS = 60_000
 *   DEFAULT_LOGS_EXPORT_INTERVAL_MS    =  5_000
 *   DEFAULT_TRACES_EXPORT_INTERVAL_MS  =  5_000
 *   BigQuery exporter interval         =  5 * 60_000 (5 minutes)
 */
@Slf4j
public final class TelemetryInstrumentation {



    // =========================================================================
    // Export interval defaults (instrumentation.ts constants)
    // =========================================================================

    public static final int DEFAULT_METRICS_EXPORT_INTERVAL_MS = 60_000;
    public static final int DEFAULT_LOGS_EXPORT_INTERVAL_MS    =  5_000;
    public static final int DEFAULT_TRACES_EXPORT_INTERVAL_MS  =  5_000;

    /** BigQuery metrics exporter interval (5 minutes) — reduces backend load. */
    public static final int BIGQUERY_METRICS_EXPORT_INTERVAL_MS = 5 * 60_000;

    // =========================================================================
    // Exporter protocol values
    // =========================================================================

    public static final String PROTOCOL_GRPC          = "grpc";
    public static final String PROTOCOL_HTTP_JSON      = "http/json";
    public static final String PROTOCOL_HTTP_PROTOBUF  = "http/protobuf";

    public static final String EXPORTER_CONSOLE    = "console";
    public static final String EXPORTER_OTLP       = "otlp";
    public static final String EXPORTER_PROMETHEUS  = "prometheus";
    public static final String EXPORTER_NONE        = "none";

    // =========================================================================
    // bootstrapTelemetry()  (instrumentation.ts)
    // =========================================================================

    /**
     * Copy ANT_OTEL_* build-time env vars into the standard OTEL_* env vars so
     * the OpenTelemetry SDK picks them up automatically.
     *
     * Also sets the default metrics temporality to 'delta' unless the env var
     * is already configured.
     *
     * Translated from bootstrapTelemetry() in instrumentation.ts.
     */
    public static void bootstrapTelemetry() {
        if ("ant".equals(System.getenv("USER_TYPE"))) {
            copyAntEnvVar("ANT_OTEL_METRICS_EXPORTER",          "OTEL_METRICS_EXPORTER");
            copyAntEnvVar("ANT_OTEL_LOGS_EXPORTER",             "OTEL_LOGS_EXPORTER");
            copyAntEnvVar("ANT_OTEL_TRACES_EXPORTER",           "OTEL_TRACES_EXPORTER");
            copyAntEnvVar("ANT_OTEL_EXPORTER_OTLP_PROTOCOL",    "OTEL_EXPORTER_OTLP_PROTOCOL");
            copyAntEnvVar("ANT_OTEL_EXPORTER_OTLP_ENDPOINT",    "OTEL_EXPORTER_OTLP_ENDPOINT");
            copyAntEnvVar("ANT_OTEL_EXPORTER_OTLP_HEADERS",     "OTEL_EXPORTER_OTLP_HEADERS");
        }

        // Default temporality to 'delta' — required for CC Productivity metrics dashboard.
        if (System.getenv("OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE") == null) {
            // System property fallback: set via -D flag in JVM args when env is not injectable
            if (System.getProperty("otel.exporter.otlp.metrics.temporality.preference") == null) {
                System.setProperty("otel.exporter.otlp.metrics.temporality.preference", "delta");
            }
        }
    }

    /**
     * Internal helper: copy an ANT_* env var into a standard OTEL_* env var if
     * the ANT_* value is set.  Uses a system-property override map as a proxy
     * because Java does not allow mutating process environment at runtime.
     *
     * In production, these are set before JVM launch via a wrapper script.
     */
    private static void copyAntEnvVar(String antKey, String otelKey) {
        String antVal = System.getenv(antKey);
        if (antVal != null && !antVal.isBlank()) {
            // Surface the override as a JVM system property for SDK auto-config.
            String sdkProp = otelKey.toLowerCase().replace('_', '.');
            if (System.getProperty(sdkProp) == null) {
                System.setProperty(sdkProp, antVal);
                log.debug("[Telemetry] Copied {}={} → {}", antKey, antVal, sdkProp);
            }
        }
    }

    // =========================================================================
    // parseExporterTypes()  (instrumentation.ts)
    // =========================================================================

    /**
     * Parse a comma-separated exporter-type list, filtering blank entries and
     * the special "none" sentinel value.
     *
     * Per the OTEL spec, "none" means no automatically configured exporter for
     * this signal.
     *
     * Translated from parseExporterTypes() in instrumentation.ts.
     *
     * @param value the raw env-var value, may be null
     * @return ordered list of non-empty, non-"none" exporter type tokens
     */
    public static List<String> parseExporterTypes(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String token : value.split(",")) {
            String t = token.trim();
            if (!t.isEmpty() && !EXPORTER_NONE.equals(t)) {
                result.add(t);
            }
        }
        return Collections.unmodifiableList(result);
    }

    // =========================================================================
    // Enablement gates  (instrumentation.ts)
    // =========================================================================

    /**
     * Check if 3P (customer) telemetry is enabled.
     * Requires CLAUDE_CODE_ENABLE_TELEMETRY=1.
     * Translated from isTelemetryEnabled() in instrumentation.ts.
     */
    public static boolean isTelemetryEnabled() {
        return EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_ENABLE_TELEMETRY"));
    }

    /**
     * Check if BigQuery metrics export is enabled for the current user.
     *
     * BigQuery metrics are enabled for:
     *   1. API customers (excluding Claude.ai subscribers and Bedrock/Vertex)
     *   2. Claude for Enterprise (C4E) users
     *   3. Claude for Teams users
     *
     * Translated from isBigQueryMetricsEnabled() in instrumentation.ts.
     *
     * The actual subscription/customer-type lookup is delegated to
     * AuthUtils, which mirrors auth.ts in the TypeScript codebase.
     */
    public static boolean isBigQueryMetricsEnabled() {
        return AuthUtils.is1PApiCustomer() || isC4EOrTeamUser();
    }

    /**
     * Returns true if the current user is a Claude.ai Enterprise or Teams subscriber.
     * Mirrors the isC4EOrTeamUser check in instrumentation.ts.
     */
    private static boolean isC4EOrTeamUser() {
        if (!AuthUtils.isClaudeAISubscriber()) return false;
        String subscriptionType = AuthUtils.getSubscriptionType();
        return "enterprise".equals(subscriptionType) || "team".equals(subscriptionType);
    }

    // =========================================================================
    // OTLP exporter configuration helpers  (instrumentation.ts)
    // =========================================================================

    /**
     * Parse the OTEL_EXPORTER_OTLP_HEADERS env var into a key→value map.
     * Header pairs are comma-separated "key=value" tokens.
     * Translated from parseOtelHeadersEnvVar() in instrumentation.ts.
     */
    public static Map<String, String> parseOtelHeadersEnvVar() {
        Map<String, String> headers = new LinkedHashMap<>();
        String envHeaders = System.getenv("OTEL_EXPORTER_OTLP_HEADERS");
        if (envHeaders == null || envHeaders.isBlank()) return headers;

        for (String pair : envHeaders.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String key   = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (!key.isEmpty()) headers.put(key, value);
        }
        return Collections.unmodifiableMap(headers);
    }

    /**
     * Determine the OTLP export protocol for a given signal.
     *
     * Precedence: signal-specific env var → general env var.
     * For traces: OTEL_EXPORTER_OTLP_TRACES_PROTOCOL  → OTEL_EXPORTER_OTLP_PROTOCOL
     * For metrics: OTEL_EXPORTER_OTLP_METRICS_PROTOCOL → OTEL_EXPORTER_OTLP_PROTOCOL
     * For logs:    OTEL_EXPORTER_OTLP_LOGS_PROTOCOL    → OTEL_EXPORTER_OTLP_PROTOCOL
     *
     * Mirrors the protocol resolution logic in getOtlpTraceExporters /
     * getOtlpMetricReaders / getOtlpLogExporters in instrumentation.ts.
     */
    public static String resolveOtlpProtocol(Signal signal) {
        String signalVar = switch (signal) {
            case TRACES  -> System.getenv("OTEL_EXPORTER_OTLP_TRACES_PROTOCOL");
            case METRICS -> System.getenv("OTEL_EXPORTER_OTLP_METRICS_PROTOCOL");
            case LOGS    -> System.getenv("OTEL_EXPORTER_OTLP_LOGS_PROTOCOL");
        };
        if (signalVar != null && !signalVar.isBlank()) return signalVar.trim();

        String general = System.getenv("OTEL_EXPORTER_OTLP_PROTOCOL");
        return general != null ? general.trim() : PROTOCOL_HTTP_PROTOBUF;
    }

    /**
     * OTel signal type.
     * Mirrors the three signal branches in instrumentation.ts.
     */
    public enum Signal {
        TRACES, METRICS, LOGS
    }

    // =========================================================================
    // Shutdown timeout helpers  (instrumentation.ts)
    // =========================================================================

    /**
     * Return the configured OTEL shutdown timeout in milliseconds.
     * Defaults to 2_000 ms if CLAUDE_CODE_OTEL_SHUTDOWN_TIMEOUT_MS is not set.
     * Translated from the parseInt() calls in shutdownTelemetry() in instrumentation.ts.
     */
    public static int getShutdownTimeoutMs() {
        String raw = System.getenv("CLAUDE_CODE_OTEL_SHUTDOWN_TIMEOUT_MS");
        if (raw != null) {
            try { return Integer.parseInt(raw.trim()); } catch (NumberFormatException ignored) {}
        }
        return 2_000;
    }

    /**
     * Return the configured OTEL flush timeout in milliseconds.
     * Defaults to 5_000 ms if CLAUDE_CODE_OTEL_FLUSH_TIMEOUT_MS is not set.
     * Translated from the parseInt() calls in flushTelemetry() in instrumentation.ts.
     */
    public static int getFlushTimeoutMs() {
        String raw = System.getenv("CLAUDE_CODE_OTEL_FLUSH_TIMEOUT_MS");
        if (raw != null) {
            try { return Integer.parseInt(raw.trim()); } catch (NumberFormatException ignored) {}
        }
        return 5_000;
    }

    /**
     * Return the configured metrics export interval in milliseconds.
     * Reads OTEL_METRIC_EXPORT_INTERVAL; defaults to DEFAULT_METRICS_EXPORT_INTERVAL_MS.
     */
    public static int getMetricsExportIntervalMs() {
        String raw = System.getenv("OTEL_METRIC_EXPORT_INTERVAL");
        if (raw != null) {
            try { return Integer.parseInt(raw.trim()); } catch (NumberFormatException ignored) {}
        }
        return DEFAULT_METRICS_EXPORT_INTERVAL_MS;
    }

    /**
     * Return the configured logs export interval in milliseconds.
     * Reads OTEL_LOGS_EXPORT_INTERVAL; defaults to DEFAULT_LOGS_EXPORT_INTERVAL_MS.
     */
    public static int getLogsExportIntervalMs() {
        String raw = System.getenv("OTEL_LOGS_EXPORT_INTERVAL");
        if (raw != null) {
            try { return Integer.parseInt(raw.trim()); } catch (NumberFormatException ignored) {}
        }
        return DEFAULT_LOGS_EXPORT_INTERVAL_MS;
    }

    /**
     * Return the configured traces export interval in milliseconds.
     * Reads OTEL_TRACES_EXPORT_INTERVAL; defaults to DEFAULT_TRACES_EXPORT_INTERVAL_MS.
     */
    public static int getTracesExportIntervalMs() {
        String raw = System.getenv("OTEL_TRACES_EXPORT_INTERVAL");
        if (raw != null) {
            try { return Integer.parseInt(raw.trim()); } catch (NumberFormatException ignored) {}
        }
        return DEFAULT_TRACES_EXPORT_INTERVAL_MS;
    }

    // =========================================================================
    // Beta tracing initialisation helpers  (instrumentation.ts)
    // =========================================================================

    /**
     * Retrieve the beta tracing endpoint.
     * Translated from the BETA_TRACING_ENDPOINT usage in initializeBetaTracing()
     * in instrumentation.ts.
     *
     * @return the endpoint base URL, or null if not configured.
     */
    public static String getBetaTracingEndpoint() {
        String ep = System.getenv("BETA_TRACING_ENDPOINT");
        return (ep != null && !ep.isBlank()) ? ep : null;
    }

    /**
     * Build the trace endpoint URL for beta tracing.
     * Appends "/v1/traces" to the base endpoint.
     */
    public static String getBetaTracingTraceUrl() {
        String ep = getBetaTracingEndpoint();
        return ep != null ? ep + "/v1/traces" : null;
    }

    /**
     * Build the logs endpoint URL for beta tracing.
     * Appends "/v1/logs" to the base endpoint.
     */
    public static String getBetaTracingLogsUrl() {
        String ep = getBetaTracingEndpoint();
        return ep != null ? ep + "/v1/logs" : null;
    }

    // =========================================================================
    // Private constructor — utility class
    // =========================================================================
    private TelemetryInstrumentation() {}
}
