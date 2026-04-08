package com.anthropic.claudecode.util;

import java.util.*;
import java.util.regex.*;

/**
 * Token budget parsing utilities.
 * Translated from src/utils/tokenBudget.ts
 *
 * Parses token budget specifications from user input.
 * Examples: "+500k", "+2M tokens", "use 1M tokens"
 */
public class TokenBudget {

    private static final Pattern SHORTHAND_START = Pattern.compile(
        "^\\s*\\+(\\d+(?:\\.\\d+)?)\\s*(k|m|b)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern SHORTHAND_END = Pattern.compile(
        "\\s\\+(\\d+(?:\\.\\d+)?)\\s*(k|m|b)\\s*[.!?]?\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern VERBOSE = Pattern.compile(
        "\\b(?:use|spend)\\s+(\\d+(?:\\.\\d+)?)\\s*(k|m|b)\\s*tokens?\\b", Pattern.CASE_INSENSITIVE);

    private static final Map<String, Long> MULTIPLIERS = Map.of(
        "k", 1_000L,
        "m", 1_000_000L,
        "b", 1_000_000_000L
    );

    /**
     * Parse a token budget from text.
     * Translated from parseTokenBudget() in tokenBudget.ts
     */
    public static Optional<Long> parseTokenBudget(String text) {
        if (text == null) return Optional.empty();

        Matcher m = SHORTHAND_START.matcher(text);
        if (m.find()) return Optional.of(parseBudgetMatch(m.group(1), m.group(2)));

        m = SHORTHAND_END.matcher(text);
        if (m.find()) return Optional.of(parseBudgetMatch(m.group(1), m.group(2)));

        m = VERBOSE.matcher(text);
        if (m.find()) return Optional.of(parseBudgetMatch(m.group(1), m.group(2)));

        return Optional.empty();
    }

    private static long parseBudgetMatch(String value, String suffix) {
        double num = Double.parseDouble(value);
        long multiplier = MULTIPLIERS.getOrDefault(suffix.toLowerCase(), 1L);
        return (long) (num * multiplier);
    }

    private TokenBudget() {}
}
