package com.anthropic.claudecode.util;

import java.util.regex.Pattern;

/**
 * User prompt keyword detection utilities.
 * Translated from src/utils/userPromptKeywords.ts
 */
public class UserPromptKeywords {

    private static final Pattern NEGATIVE_PATTERN = Pattern.compile(
        "\\b(wtf|wth|ffs|omfg|shit(ty|tiest)?|dumbass|horrible|awful|piss(ed|ing)? off|" +
        "piece of (shit|crap|junk)|what the (fuck|hell)|fucking? (broken|useless|terrible|awful|horrible)|" +
        "fuck you|screw (this|you)|so frustrating|this sucks|damn it)\\b",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern KEEP_GOING_PATTERN = Pattern.compile(
        "\\b(keep going|go on)\\b",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Check if input matches negative keyword patterns.
     * Translated from matchesNegativeKeyword() in userPromptKeywords.ts
     */
    public static boolean matchesNegativeKeyword(String input) {
        if (input == null) return false;
        return NEGATIVE_PATTERN.matcher(input).find();
    }

    /**
     * Check if input matches keep going/continuation patterns.
     * Translated from matchesKeepGoingKeyword() in userPromptKeywords.ts
     */
    public static boolean matchesKeepGoingKeyword(String input) {
        if (input == null) return false;
        String lower = input.toLowerCase().trim();

        // Match "continue" only if it's the entire prompt
        if ("continue".equals(lower)) {
            return true;
        }

        // Match "keep going" or "go on" anywhere in the input
        return KEEP_GOING_PATTERN.matcher(lower).find();
    }

    private UserPromptKeywords() {}
}
