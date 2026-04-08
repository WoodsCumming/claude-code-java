package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.util.*;
import java.util.regex.*;

/**
 * Shell quoting utilities.
 * Translated from src/utils/bash/shellQuote.ts
 *
 * Provides safe wrappers for shell command parsing and quoting.
 */
@Slf4j
public class ShellQuoteUtils {



    /**
     * Quote shell arguments for safe use in shell commands.
     * Translated from quote() / tryQuoteShellArgs() in shellQuote.ts
     */
    public static String quote(List<String> args) {
        if (args == null || args.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(" ");
            sb.append(quoteSingleArg(args.get(i)));
        }
        return sb.toString();
    }

    /**
     * Quote a single shell argument.
     * Uses single-quote wrapping to safely escape the argument.
     */
    public static String quoteSingleArg(String arg) {
        if (arg == null) return "''";
        if (arg.isEmpty()) return "''";

        // Check if arg needs quoting
        if (arg.matches("[a-zA-Z0-9._/-]+")) {
            return arg; // Safe without quoting
        }

        // Single-quote wrap, escaping any embedded single quotes
        return "'" + arg.replace("'", "'\\''") + "'";
    }

    /**
     * Parse a shell command into tokens.
     * Simplified version of shell-quote parse().
     */
    public static List<String> parseShellCommand(String cmd) {
        if (cmd == null || cmd.isBlank()) return List.of();

        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaped = false;

        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);

            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }

            if (c == '\\' && !inSingleQuote) {
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

            if (Character.isWhitespace(c) && !inSingleQuote && !inDoubleQuote) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }

            current.append(c);
        }

        if (current.length() > 0) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    private ShellQuoteUtils() {}
}
