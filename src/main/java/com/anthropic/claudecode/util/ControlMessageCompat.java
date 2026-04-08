package com.anthropic.claudecode.util;

import java.util.Map;

/**
 * Control message compatibility utilities.
 * Translated from src/utils/controlMessageCompat.ts
 *
 * Normalizes camelCase requestId to snake_case request_id for older clients.
 */
public class ControlMessageCompat {

    /**
     * Normalize control message keys.
     * Translated from normalizeControlMessageKeys() in controlMessageCompat.ts
     *
     * Converts camelCase `requestId` → snake_case `request_id` on incoming
     * control messages for backward compatibility with older iOS clients.
     */
    @SuppressWarnings("unchecked")
    public static void normalizeControlMessageKeys(Map<String, Object> message) {
        if (message == null) return;

        // Normalize top-level requestId
        if (message.containsKey("requestId") && !message.containsKey("request_id")) {
            message.put("request_id", message.get("requestId"));
            message.remove("requestId");
        }

        // Normalize nested response.requestId
        Object responseObj = message.get("response");
        if (responseObj instanceof Map) {
            Map<String, Object> response = (Map<String, Object>) responseObj;
            if (response.containsKey("requestId") && !response.containsKey("request_id")) {
                response.put("request_id", response.get("requestId"));
                response.remove("requestId");
            }
        }
    }

    private ControlMessageCompat() {}
}
