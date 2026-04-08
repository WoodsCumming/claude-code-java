package com.anthropic.claudecode.util;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Bash command parsing utilities.
 * Translated from src/utils/bash/commands.ts
 *
 * Provides utilities for parsing and analyzing bash commands.
 */
public class BashUtils {

    // Operators that split commands
    private static final Pattern OPERATOR_PATTERN = Pattern.compile("[|;&]");

    /**
     * Split a command by operators (|, &&, ||, ;).
     * Translated from splitCommandWithOperators() in commands.ts
     */
    public static List<String> splitCommandWithOperators(String command) {
        if (command == null || command.isBlank()) return List.of();

        List<String> parts = new ArrayList<>();
        String[] tokens = OPERATOR_PATTERN.split(command);
        for (String token : tokens) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }

    private static final long DEFAULT_TIMEOUT_MS = 120_000L;
    private static final long MAX_TIMEOUT_MS = 600_000L;

    /**
     * Get the default bash timeout in milliseconds.
     * Translated from getDefaultBashTimeoutMs() in timeouts.ts
     */
    public static long getDefaultBashTimeoutMs() {
        String envVal = System.getenv("BASH_DEFAULT_TIMEOUT_MS");
        if (envVal != null) {
            try {
                long parsed = Long.parseLong(envVal);
                if (parsed > 0) return parsed;
            } catch (NumberFormatException ignored) {}
        }
        return DEFAULT_TIMEOUT_MS;
    }

    /**
     * Get the maximum bash timeout in milliseconds.
     * Translated from getMaxBashTimeoutMs() in timeouts.ts
     */
    public static long getMaxBashTimeoutMs() {
        String envVal = System.getenv("BASH_MAX_TIMEOUT_MS");
        if (envVal != null) {
            try {
                long parsed = Long.parseLong(envVal);
                if (parsed > 0) return Math.max(parsed, getDefaultBashTimeoutMs());
            } catch (NumberFormatException ignored) {}
        }
        return Math.max(MAX_TIMEOUT_MS, getDefaultBashTimeoutMs());
    }

    /**
     * Split a command into its parts (command + arguments).
     * Translated from splitCommand_DEPRECATED() in commands.ts
     */
    public static List<String> splitCommand(String command) {
        if (command == null || command.isBlank()) return List.of();

        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaped = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);

            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }

            if (c == '\\') {
                escaped = true;
                continue;
            }

            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                continue;
            }

            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }

            if (c == ' ' && !inSingleQuote && !inDoubleQuote) {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current = new StringBuilder();
                }
                continue;
            }

            current.append(c);
        }

        if (current.length() > 0) {
            parts.add(current.toString());
        }

        return parts;
    }

    /**
     * Get the base command name from a command string.
     */
    public static String getBaseCommand(String command) {
        if (command == null || command.isBlank()) return "";
        List<String> parts = splitCommand(command.trim());
        if (parts.isEmpty()) return "";
        String cmd = parts.get(0);
        // Handle paths like /usr/bin/grep
        int lastSlash = cmd.lastIndexOf('/');
        return lastSlash >= 0 ? cmd.substring(lastSlash + 1) : cmd;
    }

    /**
     * Check if a command contains any cd operations.
     * Translated from commandHasAnyCd() in BashTool.tsx
     */
    public static boolean commandHasAnyCd(String command) {
        if (command == null) return false;
        List<String> parts = splitCommandWithOperators(command);
        for (String part : parts) {
            String baseCmd = getBaseCommand(part);
            if ("cd".equals(baseCmd)) return true;
        }
        return false;
    }

    /**
     * Check if a command is a wildcard match.
     * Translated from matchWildcardPattern() in shellRuleMatching.ts
     */
    public static boolean matchWildcardPattern(String command, String pattern) {
        if (pattern == null || command == null) return false;
        if ("*".equals(pattern)) return true;
        if (pattern.equals(command)) return true;

        // Convert wildcard pattern to regex
        String regex = "^" + Pattern.quote(pattern).replace("\\*", ".*").replace("\\?", ".") + "$";
        return command.matches(regex);
    }

    private BashUtils() {}
}
