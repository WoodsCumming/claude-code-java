package com.anthropic.claudecode.util;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Logging utilities: error sink pattern, session display titles, MCP logging.
 * Translated from:
 *   - src/utils/log.ts
 *   - src/utils/telemetry/logger.ts (ClaudeCodeDiagLogger)
 *
 * <p>Events are queued until a sink is attached via {@link #attachErrorLogSink}.
 * This matches the TypeScript pattern where the sink is set during app startup
 * and pre-startup errors are drained immediately.</p>
 */
@Slf4j
public class LogUtils {



    // =========================================================================
    // Error sink infrastructure
    // =========================================================================

    /**
     * Sink interface for the error logging backend.
     * Translated from ErrorLogSink in log.ts
     */
    public interface ErrorLogSink {
        void logError(Throwable error);
        void logMCPError(String serverName, Throwable error);
        void logMCPDebug(String serverName, String message);
        String getErrorsPath();
        String getMCPLogsPath(String serverName);
    }

    private sealed interface QueuedErrorEvent permits
            QueuedErrorEvent.ErrorEvent,
            QueuedErrorEvent.McpErrorEvent,
            QueuedErrorEvent.McpDebugEvent {

        record ErrorEvent(Throwable error) implements QueuedErrorEvent {}
        record McpErrorEvent(String serverName, Throwable error) implements QueuedErrorEvent {}
        record McpDebugEvent(String serverName, String message) implements QueuedErrorEvent {}
    }

    private static final int MAX_IN_MEMORY_ERRORS = 100;
    private static final List<ErrorEntry> inMemoryErrorLog = new CopyOnWriteArrayList<>();
    private static final List<QueuedErrorEvent> errorQueue = new CopyOnWriteArrayList<>();
    private static volatile ErrorLogSink errorLogSink = null;

    /** In-memory error entry (error string + ISO timestamp). */
    public record ErrorEntry(String error, String timestamp) {}

    /**
     * Attach the error log sink that will receive all error events.
     * Queued events are drained immediately to ensure no errors are lost.
     * Idempotent: if a sink is already attached, this is a no-op.
     * Translated from attachErrorLogSink() in log.ts
     */
    public static synchronized void attachErrorLogSink(ErrorLogSink newSink) {
        if (errorLogSink != null) {
            return;
        }
        errorLogSink = newSink;

        if (!errorQueue.isEmpty()) {
            List<QueuedErrorEvent> queued = new ArrayList<>(errorQueue);
            errorQueue.clear();
            for (QueuedErrorEvent event : queued) {
                switch (event) {
                    case QueuedErrorEvent.ErrorEvent e -> newSink.logError(e.error());
                    case QueuedErrorEvent.McpErrorEvent e -> newSink.logMCPError(e.serverName(), e.error());
                    case QueuedErrorEvent.McpDebugEvent e -> newSink.logMCPDebug(e.serverName(), e.message());
                }
            }
        }
    }

    private static void addToInMemoryErrorLog(String errorStr) {
        if (inMemoryErrorLog.size() >= MAX_IN_MEMORY_ERRORS) {
            if (!inMemoryErrorLog.isEmpty()) inMemoryErrorLog.remove(0);
        }
        inMemoryErrorLog.add(new ErrorEntry(errorStr, Instant.now().toString()));
    }

    /**
     * Log an error to multiple destinations.
     * Queues the event if the sink is not yet attached.
     * Translated from logError() in log.ts
     */
    public static void logError(Throwable error) {
        if (error == null) return;
        try {
            if (isErrorReportingDisabled()) return;

            String errorStr = buildErrorString(error);
            addToInMemoryErrorLog(errorStr);

            if (errorLogSink == null) {
                errorQueue.add(new QueuedErrorEvent.ErrorEvent(error));
                return;
            }
            errorLogSink.logError(error);
        } catch (Exception ignored) {
            // Never let logging itself crash
        }
    }

    /**
     * Log an error from an unknown object (catches non-Throwable cases).
     */
    public static void logError(Object error) {
        if (error instanceof Throwable t) {
            logError(t);
        } else if (error != null) {
            logError(new RuntimeException(String.valueOf(error)));
        }
    }

    /**
     * Get the in-memory error log for bug reports or display.
     * Translated from getInMemoryErrors() in log.ts
     */
    public static List<ErrorEntry> getInMemoryErrors() {
        return List.copyOf(inMemoryErrorLog);
    }

    /**
     * Log an MCP server error.
     * Translated from logMCPError() in log.ts
     */
    public static void logMCPError(String serverName, Throwable error) {
        try {
            if (errorLogSink == null) {
                errorQueue.add(new QueuedErrorEvent.McpErrorEvent(serverName, error));
                return;
            }
            errorLogSink.logMCPError(serverName, error);
        } catch (Exception ignored) {}
    }

    /**
     * Log an MCP debug message.
     * Translated from logMCPDebug() in log.ts
     */
    public static void logMCPDebug(String serverName, String message) {
        try {
            if (errorLogSink == null) {
                errorQueue.add(new QueuedErrorEvent.McpDebugEvent(serverName, message));
                return;
            }
            errorLogSink.logMCPDebug(serverName, message);
        } catch (Exception ignored) {}
    }

    // =========================================================================
    // ClaudeCodeDiagLogger — OTel diagnostics logger
    // Translated from src/utils/telemetry/logger.ts
    // =========================================================================

    /**
     * OTel diagnostics logger that routes OTEL internal messages to the
     * application's error and debug infrastructure.
     * Translated from ClaudeCodeDiagLogger in src/utils/telemetry/logger.ts
     */
    public static class ClaudeCodeDiagLogger {

        /**
         * Log an OTel error-level diagnostic message.
         * Translated from error() in ClaudeCodeDiagLogger
         */
        public void error(String message, Object... args) {
            logError(new RuntimeException(message));
            log.error("[3P telemetry] OTEL diag error: {}", message);
        }

        /**
         * Log an OTel warn-level diagnostic message.
         * Translated from warn() in ClaudeCodeDiagLogger
         */
        public void warn(String message, Object... args) {
            logError(new RuntimeException(message));
            log.warn("[3P telemetry] OTEL diag warn: {}", message);
        }

        /**
         * OTel info diagnostic — intentionally suppressed.
         * Translated from info() in ClaudeCodeDiagLogger
         */
        public void info(String message, Object... args) {
            // Intentionally suppressed — mirrors the TypeScript no-op
        }

        /**
         * OTel debug diagnostic — intentionally suppressed.
         * Translated from debug() in ClaudeCodeDiagLogger
         */
        public void debug(String message, Object... args) {
            // Intentionally suppressed
        }

        /**
         * OTel verbose diagnostic — intentionally suppressed.
         * Translated from verbose() in ClaudeCodeDiagLogger
         */
        public void verbose(String message, Object... args) {
            // Intentionally suppressed
        }
    }

    // =========================================================================
    // Display title logic
    // =========================================================================

    /**
     * Gets the display title for a log/session with fallback logic.
     * Translated from getLogDisplayTitle() in log.ts
     */
    public static String getLogDisplayTitle(SessionLogOption logOption, String defaultTitle) {
        if (logOption == null) return defaultTitle != null ? defaultTitle : "";

        String firstPrompt = logOption.getFirstPrompt();
        // Strip display-unfriendly tags early so command-only prompts fall through
        String strippedFirstPrompt = firstPrompt != null
                ? DisplayTagUtils.stripDisplayTagsAllowEmpty(firstPrompt)
                : "";

        // Skip firstPrompt if it's a tick/goal message (autonomous mode auto-prompt)
        boolean isAutonomousPrompt = firstPrompt != null && firstPrompt.startsWith("<tick>");
        boolean useFirstPrompt = !strippedFirstPrompt.isEmpty() && !isAutonomousPrompt;

        String title = Optional.ofNullable(logOption.getAgentName())
                .or(() -> Optional.ofNullable(logOption.getCustomTitle()))
                .or(() -> Optional.ofNullable(logOption.getSummary()))
                .or(() -> useFirstPrompt ? Optional.of(strippedFirstPrompt) : Optional.empty())
                .or(() -> Optional.ofNullable(defaultTitle))
                .or(() -> isAutonomousPrompt ? Optional.of("Autonomous session") : Optional.empty())
                .or(() -> logOption.getSessionId() != null
                        ? Optional.of(logOption.getSessionId().substring(
                                0, Math.min(8, logOption.getSessionId().length())))
                        : Optional.empty())
                .orElse("");

        return DisplayTagUtils.stripDisplayTags(title).trim();
    }

    // =========================================================================
    // Date / filename utilities
    // =========================================================================

    private static final DateTimeFormatter FILE_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss-SSS'Z'")
                    .withZone(ZoneId.of("UTC"));

    /**
     * Convert a date to a filename-safe ISO string (replaces : and . with -).
     * Matches the TypeScript {@code date.toISOString().replace(/[:.]/g, '-')} pattern.
     * Translated from dateToFilename() in log.ts
     */
    public static String dateToFilename(Date date) {
        if (date == null) return "";
        return FILE_DATE_FORMATTER.format(date.toInstant());
    }

    // =========================================================================
    // Log-list loading
    // =========================================================================

    /**
     * Loads the list of error logs from the errors cache directory.
     * Delegates to the persistence layer if a provider is registered; otherwise
     * returns an empty list.
     * Translated from {@code loadErrorLogs()} in log.ts
     */
    public static List<SessionLogOption> loadErrorLogs() {
        if (logListProvider != null) {
            try {
                return logListProvider.loadErrorLogs();
            } catch (Exception e) {
                log.debug("loadErrorLogs failed: {}", e.getMessage());
            }
        }
        return List.of();
    }

    // =========================================================================
    // Log-list provider hook (replaces the direct fs calls in log.ts)
    // =========================================================================

    /** Optional provider for log-list loading. Set during app startup. */
    private static volatile LogListProvider logListProvider = null;

    /**
     * Register a provider that supplies the log list (used instead of direct
     * file-system access so this utility stays testable).
     * Translated from the direct {@code loadLogList()} fs calls in log.ts
     */
    public static void setLogListProvider(LogListProvider provider) {
        logListProvider = provider;
    }

    /**
     * Provider interface for loading session/error log lists.
     * Implemented by the persistence layer.
     */
    public interface LogListProvider {
        List<SessionLogOption> loadErrorLogs();
    }

    // =========================================================================
    // captureAPIRequest
    // Translated from captureAPIRequest() in log.ts
    // =========================================================================

    /**
     * Captures the last API request for inclusion in bug reports.
     *
     * <p>Only stores requests whose {@code querySource} starts with
     * {@code "repl_main_thread"} — matches the TypeScript filter precisely.
     * Messages are stored separately from params to avoid retaining the full
     * conversation in memory for all users.</p>
     *
     * @param params      request parameters (without messages)
     * @param querySource identifies the call site (e.g. {@code "repl_main_thread"})
     * Translated from {@code captureAPIRequest()} in log.ts
     */
    public static void captureAPIRequest(Object params, String querySource) {
        if (querySource == null || !querySource.startsWith("repl_main_thread")) {
            return;
        }
        lastApiRequest = params;
        // For ant users: also keep the messages array
        if ("ant".equals(System.getenv("USER_TYPE")) && params instanceof Map<?, ?> map) {
            lastApiRequestMessages = map.get("messages");
        } else {
            lastApiRequestMessages = null;
        }
    }

    private static volatile Object lastApiRequest = null;
    private static volatile Object lastApiRequestMessages = null;

    /** Return the most recently captured API request params (may be null). */
    public static Object getLastApiRequest() { return lastApiRequest; }

    /** Return the most recently captured API request messages (ant users only, may be null). */
    public static Object getLastApiRequestMessages() { return lastApiRequestMessages; }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static boolean isErrorReportingDisabled() {
        return EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_BEDROCK"))
                || EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_VERTEX"))
                || EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_FOUNDRY"))
                || System.getenv("DISABLE_ERROR_REPORTING") != null;
    }

    private static String buildErrorString(Throwable error) {
        if (error.getClass().getSimpleName().equals("Error")) {
            return error.getMessage() != null ? error.getMessage() : "Unknown error";
        }
        String stack = ClaudeErrors.shortErrorStack(error, 10);
        return stack != null && !stack.isBlank() ? stack : error.getMessage();
    }

    /**
     * Reset error log state for testing.
     * Translated from _resetErrorLogForTesting() in log.ts
     */
    public static synchronized void resetForTesting() {
        errorLogSink = null;
        errorQueue.clear();
        inMemoryErrorLog.clear();
    }

    // =========================================================================
    // Session log option data class
    // =========================================================================

    /**
     * Log / session metadata used for display title computation.
     * Translated from LogOption in types/logs.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SessionLogOption {
        private String sessionId;
        private String agentName;
        private String customTitle;
        private String summary;
        private String firstPrompt;
        private Date created;
        private Date modified;
        private int messageCount;
        private boolean isSidechain;

        public String getSessionId() { return sessionId; }
        public void setSessionId(String v) { sessionId = v; }
        public String getAgentName() { return agentName; }
        public void setAgentName(String v) { agentName = v; }
        public String getCustomTitle() { return customTitle; }
        public void setCustomTitle(String v) { customTitle = v; }
        public String getSummary() { return summary; }
        public void setSummary(String v) { summary = v; }
        public String getFirstPrompt() { return firstPrompt; }
        public void setFirstPrompt(String v) { firstPrompt = v; }
        public Date getCreated() { return created; }
        public void setCreated(Date v) { created = v; }
        public Date getModified() { return modified; }
        public void setModified(Date v) { modified = v; }
        public int getMessageCount() { return messageCount; }
        public void setMessageCount(int v) { messageCount = v; }
        public boolean isIsSidechain() { return isSidechain; }
        public void setIsSidechain(boolean v) { isSidechain = v; }
    }

    private LogUtils() {}
}
