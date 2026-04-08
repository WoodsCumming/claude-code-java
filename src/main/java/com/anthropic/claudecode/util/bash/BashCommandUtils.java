package com.anthropic.claudecode.util.bash;

import java.util.ArrayList;
import java.util.List;

/**
 * Internal utilities shared by ParsedCommand implementations.
 *
 * Provides simplified versions of splitCommandWithOperators and
 * extractOutputRedirections that are used by RegexParsedCommand_DEPRECATED
 * (the fallback path when tree-sitter is not available).
 */
public final class BashCommandUtils {

    // -----------------------------------------------------------------------
    // RedirectionExtractionResult
    // -----------------------------------------------------------------------

    public record RedirectionExtractionResult(
            String commandWithoutRedirections,
            List<ParsedCommand.OutputRedirection> redirections) {}

    // -----------------------------------------------------------------------
    // splitCommandWithOperators
    // -----------------------------------------------------------------------

    /**
     * Splits a shell command into tokens, preserving pipe (|), AND (&&),
     * OR (||) and semicolon (;) as separate tokens.
     *
     * This is a simplified equivalent of the splitCommandWithOperators helper
     * used by the TS regex fallback path.
     */
    public static List<String> splitCommandWithOperators(String command) {
        if (command == null || command.isBlank()) {
            return List.of();
        }

        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);

            if (c == '\\' && !inSingle) {
                // Escape next char
                current.append(c);
                if (i + 1 < command.length()) {
                    current.append(command.charAt(++i));
                }
                continue;
            }

            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                current.append(c);
                continue;
            }

            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                current.append(c);
                continue;
            }

            if (!inSingle && !inDouble) {
                // Check for operators: |, ||, &&, ;
                if (c == '|') {
                    if (i + 1 < command.length() && command.charAt(i + 1) == '|') {
                        // || operator
                        flushToken(parts, current);
                        parts.add("||");
                        i++;
                        continue;
                    }
                    // | operator (pipe)
                    flushToken(parts, current);
                    parts.add("|");
                    continue;
                }
                if (c == '&' && i + 1 < command.length() && command.charAt(i + 1) == '&') {
                    flushToken(parts, current);
                    parts.add("&&");
                    i++;
                    continue;
                }
                if (c == ';') {
                    flushToken(parts, current);
                    parts.add(";");
                    continue;
                }
                if (Character.isWhitespace(c)) {
                    flushToken(parts, current);
                    continue;
                }
            }

            current.append(c);
        }
        flushToken(parts, current);
        return parts;
    }

    private static void flushToken(List<String> parts, StringBuilder current) {
        if (!current.isEmpty()) {
            parts.add(current.toString());
            current.setLength(0);
        }
    }

    // -----------------------------------------------------------------------
    // extractOutputRedirections
    // -----------------------------------------------------------------------

    /**
     * Extracts output redirections (> and >>) from a shell command.
     *
     * Returns a record containing:
     * - commandWithoutRedirections: the command string with redirections removed
     * - redirections: list of OutputRedirection objects found
     */
    public static RedirectionExtractionResult extractOutputRedirections(String command) {
        if (command == null || !command.contains(">")) {
            return new RedirectionExtractionResult(
                    command == null ? "" : command,
                    List.of());
        }

        List<ParsedCommand.OutputRedirection> redirections = new ArrayList<>();
        StringBuilder result = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        int i = 0;

        while (i < command.length()) {
            char c = command.charAt(i);

            if (c == '\\' && !inSingle) {
                result.append(c);
                if (i + 1 < command.length()) {
                    result.append(command.charAt(++i));
                }
                i++;
                continue;
            }

            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                result.append(c);
                i++;
                continue;
            }

            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                result.append(c);
                i++;
                continue;
            }

            if (!inSingle && !inDouble && c == '>') {
                // Determine operator: >> or >
                String op;
                int opEnd;
                if (i + 1 < command.length() && command.charAt(i + 1) == '>') {
                    op = ">>";
                    opEnd = i + 2;
                } else {
                    op = ">";
                    opEnd = i + 1;
                }

                // Skip whitespace after operator
                int targetStart = opEnd;
                while (targetStart < command.length()
                        && Character.isWhitespace(command.charAt(targetStart))) {
                    targetStart++;
                }

                // Collect target word
                int targetEnd = targetStart;
                while (targetEnd < command.length()
                        && !Character.isWhitespace(command.charAt(targetEnd))
                        && command.charAt(targetEnd) != '>'
                        && command.charAt(targetEnd) != '<'
                        && command.charAt(targetEnd) != '|'
                        && command.charAt(targetEnd) != '&'
                        && command.charAt(targetEnd) != ';') {
                    targetEnd++;
                }

                if (targetEnd > targetStart) {
                    String target = command.substring(targetStart, targetEnd);
                    redirections.add(new ParsedCommand.OutputRedirection(target, op));
                    i = targetEnd;
                    // Remove trailing whitespace added before the redirection
                    while (!result.isEmpty()
                            && Character.isWhitespace(result.charAt(result.length() - 1))) {
                        result.deleteCharAt(result.length() - 1);
                    }
                    continue;
                }
            }

            result.append(c);
            i++;
        }

        return new RedirectionExtractionResult(result.toString().strip(), redirections);
    }

    private BashCommandUtils() {}
}
