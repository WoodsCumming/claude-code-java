package com.anthropic.claudecode.util;

import java.util.*;
import java.util.regex.*;

/**
 * Ultraplan keyword detection utilities.
 * Translated from src/utils/ultraplan/keyword.ts
 *
 * Detects the "ultraplan" keyword in user input to trigger ultraplan mode.
 */
public class UltraplanKeyword {

    private static final String KEYWORD = "ultraplan";
    private static final Pattern KEYWORD_PATTERN = Pattern.compile(
        "(?<![/\\\\\\-\\w])" + Pattern.quote(KEYWORD) + "(?![/\\\\\\-\\w]|\\.\\w|\\?)",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Check if the input contains the ultraplan keyword.
     * Translated from hasUltraplanKeyword() in keyword.ts
     */
    public static boolean hasUltraplanKeyword(String input) {
        if (input == null || input.isBlank()) return false;
        // Don't trigger on slash commands
        if (input.startsWith("/")) return false;

        Matcher matcher = KEYWORD_PATTERN.matcher(input);
        return matcher.find();
    }

    /**
     * Replace the ultraplan keyword with an empty string.
     * Translated from replaceUltraplanKeyword() in keyword.ts
     */
    public static String replaceUltraplanKeyword(String input) {
        if (input == null) return "";
        return KEYWORD_PATTERN.matcher(input).replaceAll("").trim();
    }

    private UltraplanKeyword() {}
}
