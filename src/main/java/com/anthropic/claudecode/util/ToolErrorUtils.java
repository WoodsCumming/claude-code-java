package com.anthropic.claudecode.util;

import com.anthropic.claudecode.util.ClaudeErrors.AbortError;
import com.anthropic.claudecode.util.ClaudeErrors.ShellError;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tool error formatting and Zod-equivalent validation error utilities.
 * Translated from src/utils/toolErrors.ts
 */
public final class ToolErrorUtils {

    public static final String INTERRUPT_MESSAGE_FOR_TOOL_USE =
        "Tool use was interrupted";

    // -------------------------------------------------------------------------
    // Error formatting
    // -------------------------------------------------------------------------

    /**
     * Format a throwable for human-readable display.
     * Very long messages are truncated (keeping head and tail) to 10 000 chars.
     * Translated from formatError() in toolErrors.ts
     */
    public static String formatError(Throwable error) {
        if (error == null) return "Unknown error";

        if (error instanceof AbortError) {
            String msg = error.getMessage();
            return (msg != null && !msg.isBlank()) ? msg : INTERRUPT_MESSAGE_FOR_TOOL_USE;
        }

        List<String> parts = getErrorParts(error);
        String fullMessage = String.join("\n",
            parts.stream().filter(s -> s != null && !s.isBlank()).toList()).trim();
        if (fullMessage.isEmpty()) fullMessage = "Command failed with no output";

        if (fullMessage.length() <= 10_000) return fullMessage;

        int halfLength = 5_000;
        String start = fullMessage.substring(0, halfLength);
        String end   = fullMessage.substring(fullMessage.length() - halfLength);
        return start + "\n\n... [" + (fullMessage.length() - 10_000)
            + " characters truncated] ...\n\n" + end;
    }

    /**
     * Decompose a throwable into its constituent display strings.
     * Translated from getErrorParts() in toolErrors.ts
     */
    public static List<String> getErrorParts(Throwable error) {
        List<String> parts = new ArrayList<>();
        if (error instanceof ShellError shellError) {
            parts.add("Exit code " + shellError.getCode());
            if (shellError.isInterrupted()) parts.add(INTERRUPT_MESSAGE_FOR_TOOL_USE);
            if (shellError.getStderr() != null && !shellError.getStderr().isBlank())
                parts.add(shellError.getStderr());
            if (shellError.getStdout() != null && !shellError.getStdout().isBlank())
                parts.add(shellError.getStdout());
        } else {
            if (error.getMessage() != null) parts.add(error.getMessage());
        }
        return parts;
    }

    // -------------------------------------------------------------------------
    // Validation error formatting (equivalent of formatZodValidationError)
    // -------------------------------------------------------------------------

    /**
     * Represents a single validation issue (analogous to a Zod issue).
     */
    public sealed interface ValidationIssue permits
            ValidationIssue.MissingParam,
            ValidationIssue.UnexpectedParam,
            ValidationIssue.TypeMismatch {

        record MissingParam(String path) implements ValidationIssue {}

        record UnexpectedParam(String paramName) implements ValidationIssue {}

        record TypeMismatch(String path, String expected, String received)
            implements ValidationIssue {}
    }

    /**
     * Convert a list of validation issues into a human-readable and LLM-friendly
     * error message.
     * Translated from formatZodValidationError() in toolErrors.ts
     */
    public static String formatValidationError(String toolName,
                                                List<ValidationIssue> issues) {
        if (issues == null || issues.isEmpty()) return toolName + " failed with no details.";

        List<String> errorParts = new ArrayList<>();

        for (ValidationIssue issue : issues) {
            switch (issue) {
                case ValidationIssue.MissingParam mp ->
                    errorParts.add("The required parameter `" + mp.path() + "` is missing");

                case ValidationIssue.UnexpectedParam up ->
                    errorParts.add("An unexpected parameter `" + up.paramName()
                        + "` was provided");

                case ValidationIssue.TypeMismatch tm ->
                    errorParts.add("The parameter `" + tm.path()
                        + "` type is expected as `" + tm.expected()
                        + "` but provided as `" + tm.received() + "`");
            }
        }

        if (errorParts.isEmpty()) return toolName + " failed with no details.";

        return toolName + " failed due to the following "
            + (errorParts.size() > 1 ? "issues" : "issue") + ":\n"
            + String.join("\n", errorParts);
    }

    // -------------------------------------------------------------------------
    // Validation path helper
    // -------------------------------------------------------------------------

    /**
     * Format a validation path (list of string/integer segments) into a
     * readable string, e.g. ["todos", 0, "activeForm"] → "todos[0].activeForm".
     * Translated from formatValidationPath() in toolErrors.ts
     */
    public static String formatValidationPath(List<Object> path) {
        if (path == null || path.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.size(); i++) {
            Object segment = path.get(i);
            if (segment instanceof Integer n) {
                sb.append('[').append(n).append(']');
            } else {
                String s = String.valueOf(segment);
                if (i == 0) sb.append(s);
                else sb.append('.').append(s);
            }
        }
        return sb.toString();
    }

    private ToolErrorUtils() {}
}
