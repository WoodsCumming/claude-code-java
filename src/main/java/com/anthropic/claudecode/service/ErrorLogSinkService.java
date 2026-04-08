package com.anthropic.claudecode.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Error log sink service — file-based error logging to disk.
 * Translated from src/utils/errorLogSink.ts
 *
 * This service is separate from general logging to avoid import cycles.
 * It handles writing error records as JSONL to per-session log files.
 *
 * Usage: The sink initializes automatically on application startup via
 * {@code @PostConstruct}. Any errors logged before initialization will
 * be buffered by the underlying log infrastructure.
 *
 * Design mirrors the TypeScript original:
 *   - One JSONL file per day, keyed by ISO date (yyyy-MM-dd).
 *   - A separate log file per MCP server name.
 *   - Only writes when {@code USER_TYPE=ant} (Anthropic-internal builds).
 */
@Slf4j
@Service
public class ErrorLogSinkService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ErrorLogSinkService.class);


    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Date string computed once at startup — mirrors the {@code DATE} constant in the TS source. */
    private static final String DATE = LocalDate.now().format(DATE_FORMATTER);

    /**
     * Buffered writers keyed by absolute log-file path.
     * We keep file handles open to batch small appends; closed on shutdown.
     */
    private final Map<String, Object> logWriterLock = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    /**
     * Initialize the error log sink.
     * Called automatically by Spring on application startup.
     * Translated from initializeErrorLogSink() in errorLogSink.ts
     */
    @PostConstruct
    public void initialize() {
        log.debug("Error log sink initialized, errors path: {}", getErrorsPath());
    }

    // -------------------------------------------------------------------------
    // Path helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the path to the per-day errors JSONL file.
     * Translated from getErrorsPath() in errorLogSink.ts
     */
    public String getErrorsPath() {
        String claudeHome = resolveClaudeHome();
        return Paths.get(claudeHome, "errors", DATE + ".jsonl").toString();
    }

    /**
     * Returns the path to the MCP server log file for the given server name.
     * Translated from getMCPLogsPath() in errorLogSink.ts
     */
    public String getMCPLogsPath(String serverName) {
        String safe = serverName.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        String claudeHome = resolveClaudeHome();
        return Paths.get(claudeHome, "mcp-logs", safe, DATE + ".jsonl").toString();
    }

    /** Resolve the Claude config home directory (mirrors getClaudeConfigHomeDir()). */
    private static String resolveClaudeHome() {
        String override = System.getenv("CLAUDE_CONFIG_DIR");
        if (override != null && !override.isEmpty()) return override;
        return System.getProperty("user.home") + File.separator + ".claude";
    }

    // -------------------------------------------------------------------------
    // Log-write helpers
    // -------------------------------------------------------------------------

    /**
     * Append a generic error record to the errors log.
     * Translated from logErrorImpl() in errorLogSink.ts
     *
     * @param error the exception to log
     */
    public void logError(Throwable error) {
        String errorStr = formatThrowable(error);
        log.error("{}: {}", error.getClass().getSimpleName(), errorStr);
        appendToLog(getErrorsPath(), Map.of("error", errorStr));
    }

    /**
     * Append an MCP server error record to the server-specific log file.
     * Translated from logMCPErrorImpl() in errorLogSink.ts
     *
     * @param serverName the MCP server name
     * @param error      the exception or message to log
     */
    public void logMCPError(String serverName, Object error) {
        String errorStr = (error instanceof Throwable t) ? formatThrowable(t) : String.valueOf(error);
        log.error("MCP server \"{}\": {}", serverName, errorStr);

        String logFile = getMCPLogsPath(serverName);
        appendToLog(logFile, Map.of(
                "error", errorStr,
                "serverName", serverName
        ));
    }

    /**
     * Append an MCP debug record to the server-specific log file.
     * Translated from logMCPDebugImpl() in errorLogSink.ts
     *
     * @param serverName the MCP server name
     * @param message    the debug message
     */
    public void logMCPDebug(String serverName, String message) {
        log.debug("MCP server \"{}\": {}", serverName, message);

        String logFile = getMCPLogsPath(serverName);
        appendToLog(logFile, Map.of(
                "debug", message,
                "serverName", serverName
        ));
    }

    // -------------------------------------------------------------------------
    // Internal JSONL writer
    // -------------------------------------------------------------------------

    /**
     * Append a message object as a JSONL line to the given log file.
     * Only writes when {@code USER_TYPE=ant} to match the TypeScript behaviour.
     * Translated from appendToLog() in errorLogSink.ts
     */
    private void appendToLog(String filePath, Map<String, Object> message) {
        String userType = System.getenv("USER_TYPE");
        if (!"ant".equals(userType)) {
            return;
        }

        String jsonLine = buildJsonLine(message);
        Path path = Paths.get(filePath);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(
                    path,
                    jsonLine + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            log.warn("Failed to append to error log {}: {}", filePath, e.getMessage());
        }
    }

    /**
     * Build a simple single-line JSON object from a string-keyed map.
     * Adds a {@code timestamp} field automatically.
     * This is intentionally minimal — a full JSON library (Jackson/Gson) is
     * preferable for production; wire in your project's existing dependency.
     */
    private String buildJsonLine(Map<String, Object> fields) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"timestamp\":\"").append(java.time.Instant.now()).append("\"");
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            sb.append(",");
            appendJsonString(sb, entry.getKey());
            sb.append(":");
            Object value = entry.getValue();
            if (value instanceof String s) {
                appendJsonString(sb, s);
            } else if (value instanceof Number || value instanceof Boolean) {
                sb.append(value);
            } else {
                appendJsonString(sb, String.valueOf(value));
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private void appendJsonString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
    }

    private String formatThrowable(Throwable t) {
        if (t == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append(t.toString());
        for (StackTraceElement ste : t.getStackTrace()) {
            sb.append("\n\tat ").append(ste);
        }
        return sb.toString();
    }
}
