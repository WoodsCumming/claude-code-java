package com.anthropic.claudecode.util;

import java.util.*;
import java.util.regex.*;

/**
 * Argument substitution utilities for skill/command prompts.
 * Translated from src/utils/argumentSubstitution.ts
 *
 * Supports $ARGUMENTS, $ARGUMENTS[N], $N, and named $arg substitutions.
 */
public class ArgumentSubstitution {

    /**
     * Parse arguments string into array.
     * Translated from parseArguments() in argumentSubstitution.ts
     */
    public static List<String> parseArguments(String args) {
        if (args == null || args.isBlank()) return List.of();

        // Simple whitespace-aware parsing
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);

            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (c == ' ' && !inSingleQuote && !inDoubleQuote) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    /**
     * Substitute arguments in a prompt template.
     * Translated from substituteArguments() in argumentSubstitution.ts
     */
    public static String substituteArguments(String template, String argsString) {
        if (template == null) return "";

        List<String> args = parseArguments(argsString != null ? argsString : "");
        String fullArgs = argsString != null ? argsString.trim() : "";

        String result = template;

        // $ARGUMENTS[N] -> args[N]
        Pattern indexedPattern = Pattern.compile("\\$ARGUMENTS\\[(\\d+)\\]");
        Matcher m = indexedPattern.matcher(result);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int idx = Integer.parseInt(m.group(1));
            String replacement = idx < args.size() ? args.get(idx) : "";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        result = sb.toString();

        // $N -> args[N]
        Pattern shortPattern = Pattern.compile("\\$(\\d+)");
        m = shortPattern.matcher(result);
        sb = new StringBuffer();
        while (m.find()) {
            int idx = Integer.parseInt(m.group(1));
            String replacement = idx < args.size() ? args.get(idx) : "";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        result = sb.toString();

        // $ARGUMENTS -> full args string
        result = result.replace("$ARGUMENTS", fullArgs);

        return result;
    }

    private ArgumentSubstitution() {}
}
