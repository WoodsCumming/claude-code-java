package com.anthropic.claudecode.util;

import java.util.*;

/**
 * Telemetry attribute utilities.
 * Translated from src/utils/telemetryAttributes.ts
 */
public class TelemetryAttributes {

    /**
     * Get telemetry attributes for the current session.
     * Translated from getTelemetryAttributes() in telemetryAttributes.ts
     */
    public static Map<String, Object> getTelemetryAttributes(String userId, String sessionId) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("user.id", userId != null ? userId : "unknown");

        if (shouldIncludeAttribute("OTEL_METRICS_INCLUDE_SESSION_ID", true)) {
            attributes.put("session.id", sessionId != null ? sessionId : "unknown");
        }

        return attributes;
    }

    private static boolean shouldIncludeAttribute(String envVar, boolean defaultValue) {
        String envValue = System.getenv(envVar);
        if (envValue == null) return defaultValue;
        return EnvUtils.isEnvTruthy(envValue);
    }

    private TelemetryAttributes() {}
}
