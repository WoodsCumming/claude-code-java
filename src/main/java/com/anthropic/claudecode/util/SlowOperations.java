package com.anthropic.claudecode.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Slow operation tracking utilities.
 * Translated from src/utils/slowOperations.ts
 *
 * Wraps JSON operations and file writes to track slow operations.
 * Operations taking longer than SLOW_THRESHOLD_MS will be logged for debugging.
 */
@Slf4j
public class SlowOperations {



    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Threshold in milliseconds for logging slow operations.
     * - Override: set CLAUDE_CODE_SLOW_OPERATION_THRESHOLD_MS env var
     * - Dev/test: 20ms
     * - Ant users (USER_TYPE=ant): 300ms
     * - Default: no limit (Long.MAX_VALUE equivalent)
     */
    public static final long SLOW_OPERATION_THRESHOLD_MS;

    static {
        String envValue = System.getenv("CLAUDE_CODE_SLOW_OPERATION_THRESHOLD_MS");
        if (envValue != null) {
            long parsed = -1;
            try {
                parsed = Long.parseLong(envValue);
            } catch (NumberFormatException ignored) {}
            SLOW_OPERATION_THRESHOLD_MS = parsed >= 0 ? parsed : Long.MAX_VALUE;
        } else {
            String nodeEnv = System.getenv("NODE_ENV");
            String userType = System.getenv("USER_TYPE");
            if ("development".equals(nodeEnv)) {
                SLOW_OPERATION_THRESHOLD_MS = 20;
            } else if ("ant".equals(userType)) {
                SLOW_OPERATION_THRESHOLD_MS = 300;
            } else {
                SLOW_OPERATION_THRESHOLD_MS = Long.MAX_VALUE;
            }
        }
    }

    /**
     * Extract a readable caller location from the current stack trace.
     * Only called when an operation is actually slow — never on the fast path.
     * Translated from callerFrame() in slowOperations.ts
     */
    public static String callerFrame() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();
            if (className.contains("SlowOperations")) continue;
            if (className.contains("Thread")) continue;
            String fileName = frame.getFileName() != null ? frame.getFileName() : className;
            return " @ " + fileName + ":" + frame.getLineNumber();
        }
        return "";
    }

    /**
     * Parse JSON with slow operation tracking.
     * Translated from jsonParse() in slowOperations.ts
     */
    public static Object jsonParse(String json) {
        long start = System.currentTimeMillis();
        try {
            Object result = OBJECT_MAPPER.readValue(json, Object.class);
            logIfSlow("JSON.parse", json, System.currentTimeMillis() - start);
            return result;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * Parse JSON into a typed class with slow operation tracking.
     */
    public static <T> T jsonParse(String json, Class<T> type) {
        long start = System.currentTimeMillis();
        try {
            T result = OBJECT_MAPPER.readValue(json, type);
            logIfSlow("JSON.parse", json, System.currentTimeMillis() - start);
            return result;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * Stringify to JSON with slow operation tracking.
     * Translated from jsonStringify() in slowOperations.ts
     */
    public static String jsonStringify(Object value) {
        long start = System.currentTimeMillis();
        try {
            String result = OBJECT_MAPPER.writeValueAsString(value);
            logIfSlow("JSON.stringify", value, System.currentTimeMillis() - start);
            return result;
        } catch (JsonProcessingException e) {
            return "null";
        }
    }

    /**
     * Stringify to pretty-printed JSON with slow operation tracking.
     */
    public static String jsonStringifyPretty(Object value) {
        long start = System.currentTimeMillis();
        try {
            String result = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
            logIfSlow("JSON.stringify(pretty)", value, System.currentTimeMillis() - start);
            return result;
        } catch (JsonProcessingException e) {
            return "null";
        }
    }

    /**
     * Deep clone an object via JSON round-trip with slow operation tracking.
     * Translated from clone() / cloneDeep() in slowOperations.ts
     */
    @SuppressWarnings("unchecked")
    public static <T> T clone(T value) {
        if (value == null) return null;
        long start = System.currentTimeMillis();
        try {
            String json = OBJECT_MAPPER.writeValueAsString(value);
            T result = (T) OBJECT_MAPPER.readValue(json, value.getClass());
            logIfSlow("structuredClone", value, System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            return value;
        }
    }

    /**
     * Write a file with slow operation tracking.
     * Translated from writeFileSync_DEPRECATED() in slowOperations.ts
     *
     * @deprecated Use async file writes instead for non-blocking I/O.
     */
    @Deprecated
    public static void writeFileSync(Path filePath, String data) throws IOException {
        long start = System.currentTimeMillis();
        Files.writeString(filePath, data,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        logIfSlow("fs.writeFileSync", filePath, System.currentTimeMillis() - start);
    }

    private static void logIfSlow(String operation, Object value, long durationMs) {
        if (durationMs > SLOW_OPERATION_THRESHOLD_MS) {
            String description = describeValue(value);
            String caller = callerFrame();
            log.debug("[SLOW OPERATION DETECTED] {}({}) ({}ms){}",
                    operation, description, durationMs, caller);
        }
    }

    private static String describeValue(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) {
            return s.length() > 80 ? s.substring(0, 80) + "…" : s;
        }
        if (value instanceof java.util.Collection<?> c) {
            return "Array[" + c.size() + "]";
        }
        if (value instanceof java.util.Map<?, ?> m) {
            return "Object{" + m.size() + " keys}";
        }
        return value.getClass().getSimpleName();
    }

    private SlowOperations() {}
}
