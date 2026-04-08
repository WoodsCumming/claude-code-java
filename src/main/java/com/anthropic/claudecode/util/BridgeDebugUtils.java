package com.anthropic.claudecode.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bridge debug utility functions.
 * Translated from src/bridge/debugUtils.ts
 *
 * Provides secret redaction, debug truncation, axios error description,
 * HTTP status extraction, and error detail extraction for bridge logging.
 */
@Slf4j
public final class BridgeDebugUtils {



    private static final int DEBUG_MSG_LIMIT = 2000;
    private static final int REDACT_MIN_LENGTH = 16;

    private static final String[] SECRET_FIELD_NAMES = {
        "session_ingress_token",
        "environment_secret",
        "access_token",
        "secret",
        "token"
    };

    private static final Pattern SECRET_PATTERN;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static {
        String fields = String.join("|", SECRET_FIELD_NAMES);
        SECRET_PATTERN = Pattern.compile("\"(" + fields + ")\"\\s*:\\s*\"([^\"]*)\"");
    }

    private BridgeDebugUtils() {}

    /**
     * Redact sensitive field values in a JSON-like string.
     */
    public static String redactSecrets(String s) {
        Matcher matcher = SECRET_PATTERN.matcher(s);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String field = matcher.group(1);
            String value = matcher.group(2);
            String replacement;
            if (value.length() < REDACT_MIN_LENGTH) {
                replacement = "\"" + field + "\":\"[REDACTED]\"";
            } else {
                String redacted = value.substring(0, 8) + "..." + value.substring(value.length() - 4);
                replacement = "\"" + field + "\":\"" + redacted + "\"";
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Truncate a string for debug logging, collapsing newlines.
     */
    public static String debugTruncate(String s) {
        String flat = s.replace("\n", "\\n");
        if (flat.length() <= DEBUG_MSG_LIMIT) {
            return flat;
        }
        return flat.substring(0, DEBUG_MSG_LIMIT) + "... (" + flat.length() + " chars)";
    }

    /**
     * Truncate a JSON-serializable value for debug logging.
     */
    public static String debugBody(Object data) {
        String raw;
        if (data instanceof String s) {
            raw = s;
        } else {
            try {
                raw = OBJECT_MAPPER.writeValueAsString(data);
            } catch (Exception e) {
                raw = String.valueOf(data);
            }
        }
        String s = redactSecrets(raw);
        if (s.length() <= DEBUG_MSG_LIMIT) {
            return s;
        }
        return s.substring(0, DEBUG_MSG_LIMIT) + "... (" + s.length() + " chars)";
    }

    /**
     * Extract a descriptive error message from an HTTP error response.
     * For HTTP errors, appends the server's response body message if available.
     */
    public static String describeHttpError(Exception err, Object responseData) {
        String msg = err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName();
        if (responseData instanceof Map<?, ?> dataMap) {
            Object detail = extractErrorDetail(dataMap);
            if (detail instanceof String detailStr && !detailStr.isEmpty()) {
                return msg + ": " + detailStr;
            }
        }
        return msg;
    }

    /**
     * Extract a descriptive error message from an exception.
     */
    public static String describeAxiosError(Exception err) {
        return err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName();
    }

    /**
     * Pull a human-readable message out of an API error response body.
     * Checks data.message first, then data.error.message.
     */
    public static String extractErrorDetail(Object data) {
        if (data == null) return null;
        if (!(data instanceof Map)) {
            // Try to interpret as a map via JSON
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = OBJECT_MAPPER.convertValue(data, Map.class);
                return extractErrorDetail(map);
            } catch (Exception e) {
                return null;
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) data;
        Object message = map.get("message");
        if (message instanceof String s && !s.isEmpty()) {
            return s;
        }
        Object error = map.get("error");
        if (error instanceof Map<?, ?> errorMap) {
            Object errMsg = errorMap.get("message");
            if (errMsg instanceof String s && !s.isEmpty()) {
                return s;
            }
        }
        return null;
    }

    /**
     * Log a bridge init skip — debug message + analytics event.
     */
    public static void logBridgeSkip(String reason, String debugMsg, Boolean v2) {
        if (debugMsg != null) {
            log.debug("{}", debugMsg);
        }
        log.debug("[analytics] tengu_bridge_repl_skipped reason={}{}", reason,
                v2 != null ? " v2=" + v2 : "");
    }

    /**
     * Log a bridge init skip.
     */
    public static void logBridgeSkip(String reason) {
        logBridgeSkip(reason, null, null);
    }
}
