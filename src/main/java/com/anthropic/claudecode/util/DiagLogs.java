package com.anthropic.claudecode.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Diagnostic logging utilities.
 * Translated from src/utils/diagLogs.ts
 *
 * Logs diagnostic information (NO PII) for monitoring.
 */
@Slf4j
public class DiagLogs {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DiagLogs.class);


    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Log diagnostic information (no PII).
     * Translated from logForDiagnosticsNoPII() in diagLogs.ts
     *
     * IMPORTANT: Must NOT be called with PII (file paths, prompts, etc.)
     */
    public static void logForDiagnosticsNoPII(String level, String event) {
        logForDiagnosticsNoPII(level, event, Map.of());
    }

    public static void logForDiagnosticsNoPII(String level, String event, Map<String, Object> data) {
        String logFile = getDiagnosticLogFile();
        if (logFile == null) return;

        try {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("timestamp", Instant.now().toString());
            entry.put("level", level);
            entry.put("event", event);
            if (data != null && !data.isEmpty()) {
                entry.put("data", data);
            }

            String json = OBJECT_MAPPER.writeValueAsString(entry) + "\n";
            Files.writeString(Paths.get(logFile), json,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        } catch (Exception e) {
            // Silently fail - diagnostic logging is best-effort
        }
    }

    private static String getDiagnosticLogFile() {
        return System.getenv("CLAUDE_CODE_DIAGNOSTIC_LOG_FILE");
    }

    private DiagLogs() {}
}
