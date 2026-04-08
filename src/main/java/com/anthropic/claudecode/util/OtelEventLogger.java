package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OpenTelemetry event logger utilities.
 * Translated from src/utils/telemetry/events.ts
 *
 * <p>Emits structured events to the configured OTel EventLogger.
 * Events carry common telemetry attributes (session, version, platform)
 * plus a monotonically increasing sequence number for ordering.</p>
 */
@Slf4j
public class OtelEventLogger {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OtelEventLogger.class);


    /** Monotonically increasing counter for ordering events within a session. */
    private static final AtomicInteger eventSequence = new AtomicInteger(0);

    /** Guard to avoid spamming a warning about a missing event logger. */
    private static volatile boolean hasWarnedNoEventLogger = false;

    /**
     * OTel EventLogger interface — implementations bridge to the actual OTel SDK.
     * Mirrors the EventLogger from @opentelemetry/api-events.
     */
    public interface EventLogger {
        void emit(OtelLogRecord record);
    }

    /**
     * Minimal OTel log record (event).
     * Translated from the emit() call parameters in events.ts
     */
    public record OtelLogRecord(
            String body,
            Map<String, Object> attributes
    ) {}

    // Injected at startup by the telemetry initialization code
    private static volatile EventLogger eventLogger = null;
    private static volatile String currentPromptId = null;

    /**
     * Set the event logger instance (called during app initialization).
     */
    public static void setEventLogger(EventLogger logger) {
        eventLogger = logger;
        hasWarnedNoEventLogger = false;
    }

    /**
     * Set the current prompt ID for inclusion in event attributes.
     */
    public static void setCurrentPromptId(String promptId) {
        currentPromptId = promptId;
    }

    /**
     * Check if user-prompt content should be logged (opt-in via env var).
     * Translated from isUserPromptLoggingEnabled() in events.ts
     */
    public static boolean isUserPromptLoggingEnabled() {
        return EnvUtils.isEnvTruthy(System.getenv("OTEL_LOG_USER_PROMPTS"));
    }

    /**
     * Redact content if user-prompt logging is disabled.
     * Translated from redactIfDisabled() in events.ts
     */
    public static String redactIfDisabled(String content) {
        return isUserPromptLoggingEnabled() ? content : "<REDACTED>";
    }

    /**
     * Asynchronously emit a structured OTel event with the given name and metadata.
     *
     * Attributes automatically include:
     * <ul>
     *   <li>Common telemetry attributes (session, version, platform)</li>
     *   <li>event.name, event.timestamp, event.sequence</li>
     *   <li>prompt.id (if available)</li>
     *   <li>workspace.host_paths (if CLAUDE_CODE_WORKSPACE_HOST_PATHS is set)</li>
     *   <li>All entries from {@code metadata} (string values only)</li>
     * </ul>
     *
     * Translated from logOTelEvent() in events.ts
     */
    public static CompletableFuture<Void> logOTelEvent(
            String eventName,
            Map<String, String> metadata) {

        return CompletableFuture.runAsync(() -> {
            EventLogger logger = eventLogger;
            if (logger == null) {
                if (!hasWarnedNoEventLogger) {
                    hasWarnedNoEventLogger = true;
                    log.warn("[3P telemetry] Event dropped (no event logger initialized): {}", eventName);
                }
                return;
            }

            // Skip logging in test environment
            String nodeEnv = System.getenv("NODE_ENV");
            if ("test".equals(nodeEnv)) {
                return;
            }

            Map<String, Object> attributes = new LinkedHashMap<>();

            // Common telemetry attributes
            attributes.putAll(TelemetryAttributes.getTelemetryAttributes());

            // Event-specific attributes
            attributes.put("event.name", eventName);
            attributes.put("event.timestamp", Instant.now().toString());
            attributes.put("event.sequence", (long) eventSequence.getAndIncrement());

            // Add prompt ID to events (not to metrics — unbounded cardinality)
            String promptId = currentPromptId;
            if (promptId != null && !promptId.isBlank()) {
                attributes.put("prompt.id", promptId);
            }

            // Workspace host paths from the desktop app
            String workspaceDir = System.getenv("CLAUDE_CODE_WORKSPACE_HOST_PATHS");
            if (workspaceDir != null && !workspaceDir.isBlank()) {
                attributes.put("workspace.host_paths", Arrays.asList(workspaceDir.split("\\|")));
            }

            // Add caller-provided metadata
            if (metadata != null) {
                for (Map.Entry<String, String> entry : metadata.entrySet()) {
                    if (entry.getValue() != null) {
                        attributes.put(entry.getKey(), entry.getValue());
                    }
                }
            }

            logger.emit(new OtelLogRecord("claude_code." + eventName, Map.copyOf(attributes)));
        });
    }

    /** Convenience overload with no metadata. */
    public static CompletableFuture<Void> logOTelEvent(String eventName) {
        return logOTelEvent(eventName, Map.of());
    }

    /** Reset state for testing. */
    public static void resetForTesting() {
        eventLogger = null;
        hasWarnedNoEventLogger = false;
        eventSequence.set(0);
        currentPromptId = null;
    }

    private OtelEventLogger() {}

    /**
     * Placeholder for common telemetry attribute collection.
     * The real implementation lives in TelemetryAttributes.java.
     */
    private static class TelemetryAttributes {
        static Map<String, Object> getTelemetryAttributes() {
            // Delegate to the real TelemetryAttributes util if available
            try {
                // Reflective delegation to avoid a hard compile-time dependency
                var cls = Class.forName("com.anthropic.claudecode.util.TelemetryAttributesUtils");
                var method = cls.getMethod("getAttributes");
                @SuppressWarnings("unchecked")
                var result = (Map<String, Object>) method.invoke(null);
                return result;
            } catch (Exception e) {
                // Fallback: minimal attributes
                Map<String, Object> attrs = new LinkedHashMap<>();
                attrs.put("platform", System.getProperty("os.name", "unknown"));
                attrs.put("java.version", System.getProperty("java.version", "unknown"));
                return attrs;
            }
        }
    }
}
