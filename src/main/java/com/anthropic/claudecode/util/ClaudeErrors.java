package com.anthropic.claudecode.util;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Custom error types and error utility functions for Claude Code.
 * Translated from src/utils/errors.ts
 */
public class ClaudeErrors {

    // =========================================================================
    // Error classes
    // =========================================================================

    /**
     * Base error class for Claude Code errors.
     * Translated from ClaudeError in errors.ts
     */
    public static class ClaudeError extends RuntimeException {
        public ClaudeError(String message) {
            super(message);
        }

        public ClaudeError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Error for malformed slash commands.
     * Translated from MalformedCommandError in errors.ts
     */
    public static class MalformedCommandError extends RuntimeException {
        public MalformedCommandError(String message) {
            super(message);
        }
    }

    /**
     * Error for aborted operations.
     * Translated from AbortError in errors.ts
     */
    public static class AbortError extends RuntimeException {
        public AbortError() {
            super("Operation aborted");
        }

        public AbortError(String message) {
            super(message != null ? message : "Operation aborted");
        }
    }

    /**
     * Error for configuration file parsing failures.
     * Includes the file path and the default configuration that should be used.
     * Translated from ConfigParseError in errors.ts
     */
    public static class ConfigParseError extends RuntimeException {
        private final String filePath;
        private final Object defaultConfig;

        public ConfigParseError(String message, String filePath, Object defaultConfig) {
            super(message);
            this.filePath = filePath;
            this.defaultConfig = defaultConfig;
        }

        public String getFilePath() { return filePath; }
        public Object getDefaultConfig() { return defaultConfig; }
    }

    /**
     * Error for shell command failures.
     * Translated from ShellError in errors.ts
     */
    public static class ShellError extends RuntimeException {
        private final String stdout;
        private final String stderr;
        private final int code;
        private final boolean interrupted;

        public ShellError(String stdout, String stderr, int code, boolean interrupted) {
            super("Shell command failed");
            this.stdout = stdout;
            this.stderr = stderr;
            this.code = code;
            this.interrupted = interrupted;
        }

        public String getStdout() { return stdout; }
        public String getStderr() { return stderr; }
        public int getCode() { return code; }
        public boolean isInterrupted() { return interrupted; }
    }

    /**
     * Error for Teleport environment operations.
     * Translated from TeleportOperationError in errors.ts
     */
    public static class TeleportOperationError extends RuntimeException {
        private final String formattedMessage;

        public TeleportOperationError(String message, String formattedMessage) {
            super(message);
            this.formattedMessage = formattedMessage;
        }

        public String getFormattedMessage() { return formattedMessage; }
    }

    /**
     * Error with a message that is safe to log to telemetry.
     * Use the long name to confirm you've verified the message contains no
     * sensitive data (file paths, URLs, code snippets).
     *
     * Single-arg: same message for user and telemetry.
     * Two-arg: different messages (e.g., full message has file path, telemetry doesn't).
     * Translated from TelemetrySafeError_I_VERIFIED_THIS_IS_NOT_CODE_OR_FILEPATHS in errors.ts
     */
    public static class TelemetrySafeError extends RuntimeException {
        private final String telemetryMessage;

        public TelemetrySafeError(String message) {
            super(message);
            this.telemetryMessage = message;
        }

        public TelemetrySafeError(String message, String telemetryMessage) {
            super(message);
            this.telemetryMessage = telemetryMessage != null ? telemetryMessage : message;
        }

        public String getTelemetryMessage() { return telemetryMessage; }
    }

    // =========================================================================
    // Error predicates
    // =========================================================================

    /**
     * True if the error is any of the abort-shaped errors the codebase encounters:
     * AbortError, InterruptedException, or any error named "AbortError".
     * Translated from isAbortError() in errors.ts
     */
    public static boolean isAbortError(Throwable e) {
        if (e == null) return false;
        if (e instanceof AbortError) return true;
        if (e instanceof InterruptedException) return true;
        return "AbortError".equals(e.getClass().getSimpleName());
    }

    /**
     * True if the error has exactly the given message.
     * Translated from hasExactErrorMessage() in errors.ts
     */
    public static boolean hasExactErrorMessage(Throwable error, String message) {
        if (!(error instanceof Exception)) return false;
        return message.equals(error.getMessage());
    }

    // =========================================================================
    // Error normalisation
    // =========================================================================

    /**
     * Normalize an unknown value into a RuntimeException.
     * Use at catch-site boundaries when you need an exception instance.
     * Translated from toError() in errors.ts
     */
    public static RuntimeException toError(Object e) {
        if (e instanceof RuntimeException re) return re;
        if (e instanceof Throwable t) return new RuntimeException(t.getMessage(), t);
        return new RuntimeException(String.valueOf(e));
    }

    /**
     * Extract a string message from an unknown error-like value.
     * Use when you only need the message (e.g., for logging or display).
     * Translated from errorMessage() in errors.ts
     */
    public static String errorMessage(Throwable e) {
        if (e == null) return "null";
        String msg = e.getMessage();
        return msg != null ? msg : e.getClass().getSimpleName();
    }

    /**
     * Extract a string message from any object.
     */
    public static String errorMessage(Object e) {
        if (e == null) return "null";
        if (e instanceof Throwable t) return errorMessage(t);
        return String.valueOf(e);
    }

    // =========================================================================
    // Errno / filesystem error utilities
    // =========================================================================

    /**
     * Extract the errno-like code from a caught exception, if available.
     * Java exceptions don't have POSIX codes, so we inspect the message.
     * Translated from getErrnoCode() in errors.ts
     */
    public static String getErrnoCode(Throwable e) {
        if (e instanceof java.nio.file.NoSuchFileException) return "ENOENT";
        if (e instanceof java.nio.file.AccessDeniedException) return "EACCES";
        if (e instanceof java.nio.file.NotDirectoryException) return "ENOTDIR";
        if (e instanceof java.nio.file.FileAlreadyExistsException) return "EEXIST";
        if (e instanceof java.nio.file.DirectoryNotEmptyException) return "ENOTEMPTY";
        if (e == null) return null;
        // Check for errno strings in the message (cross-platform fallback)
        String msg = e.getMessage();
        if (msg == null) return null;
        for (String code : new String[]{"ENOENT", "EACCES", "EPERM", "ENOTDIR", "ELOOP", "EEXIST", "ENOTEMPTY"}) {
            if (msg.contains(code)) return code;
        }
        return null;
    }

    /**
     * True if the error is ENOENT (file or directory does not exist).
     * Translated from isENOENT() in errors.ts
     */
    public static boolean isENOENT(Throwable e) {
        return "ENOENT".equals(getErrnoCode(e));
    }

    /**
     * True if the error means the path is missing, inaccessible, or structurally unreachable.
     * Covers: ENOENT, EACCES, EPERM, ENOTDIR, ELOOP.
     * Translated from isFsInaccessible() in errors.ts
     */
    public static boolean isFsInaccessible(Throwable e) {
        String code = getErrnoCode(e);
        return "ENOENT".equals(code)
                || "EACCES".equals(code)
                || "EPERM".equals(code)
                || "ENOTDIR".equals(code)
                || "ELOOP".equals(code);
    }

    // =========================================================================
    // Stack trace utilities
    // =========================================================================

    /**
     * Extract error message + top N stack frames from an error.
     * Keeps only the most relevant frames; avoids flooding context with internal frames.
     * Translated from shortErrorStack() in errors.ts
     */
    public static String shortErrorStack(Throwable e, int maxFrames) {
        if (e == null) return "null";
        StackTraceElement[] frames = e.getStackTrace();
        if (frames == null || frames.length == 0) return e.getMessage();

        String header = e.getClass().getSimpleName() + ": " + e.getMessage();
        if (frames.length <= maxFrames) {
            return header + "\n" + Arrays.stream(frames)
                    .map(f -> "    at " + f)
                    .collect(Collectors.joining("\n"));
        }
        return header + "\n" + Arrays.stream(frames)
                .limit(maxFrames)
                .map(f -> "    at " + f)
                .collect(Collectors.joining("\n"));
    }

    /** Overload with default maxFrames = 5. */
    public static String shortErrorStack(Throwable e) {
        return shortErrorStack(e, 5);
    }

    // =========================================================================
    // HTTP / Axios-style error classification
    // =========================================================================

    /**
     * Kinds of HTTP/network errors.
     * Translated from AxiosErrorKind in errors.ts
     */
    public enum HttpErrorKind {
        /** 401/403 — caller typically sets skipRetry. */
        AUTH,
        /** ECONNABORTED / connection timeout. */
        TIMEOUT,
        /** ECONNREFUSED / ENOTFOUND — network unreachable. */
        NETWORK,
        /** Other HTTP error (may have status). */
        HTTP,
        /** Not an HTTP error. */
        OTHER
    }

    /**
     * Classification result for an HTTP error.
     */
    public record HttpErrorClassification(
            HttpErrorKind kind,
            Integer status,
            String message
    ) {}

    /**
     * Classify a caught exception from an HTTP request.
     * Checks for common Spring/OkHttp/JAX-RS status codes and connection errors.
     * Translated from classifyAxiosError() in errors.ts
     */
    public static HttpErrorClassification classifyHttpError(Throwable e) {
        String message = errorMessage(e);
        if (e == null) return new HttpErrorClassification(HttpErrorKind.OTHER, null, message);

        // Check for HTTP status in message (rough heuristic for non-SDK exceptions)
        if (message.contains("401") || message.contains("403")) {
            return new HttpErrorClassification(HttpErrorKind.AUTH, null, message);
        }
        if (message.contains("timed out") || message.contains("timeout") || message.contains("ECONNABORTED")) {
            return new HttpErrorClassification(HttpErrorKind.TIMEOUT, null, message);
        }
        if (message.contains("ECONNREFUSED") || message.contains("ENOTFOUND")
                || message.contains("Connection refused") || message.contains("UnknownHost")) {
            return new HttpErrorClassification(HttpErrorKind.NETWORK, null, message);
        }
        if (e instanceof java.net.ConnectException || e instanceof java.net.SocketTimeoutException) {
            return new HttpErrorClassification(HttpErrorKind.NETWORK, null, message);
        }

        return new HttpErrorClassification(HttpErrorKind.OTHER, null, message);
    }

    private ClaudeErrors() {}
}
