package com.anthropic.claudecode.util;

/**
 * File edit utilities.
 * Translated from src/tools/FileEditTool/utils.ts
 */
public class FileEditUtils {

    // Curly quote constants
    public static final char LEFT_SINGLE_CURLY_QUOTE = '\u2018';
    public static final char RIGHT_SINGLE_CURLY_QUOTE = '\u2019';
    public static final char LEFT_DOUBLE_CURLY_QUOTE = '\u201C';
    public static final char RIGHT_DOUBLE_CURLY_QUOTE = '\u201D';

    /**
     * Normalize quotes in a string.
     * Translated from normalizeQuotes() in utils.ts
     */
    public static String normalizeQuotes(String str) {
        if (str == null) return "";
        return str
            .replace(LEFT_SINGLE_CURLY_QUOTE, '\'')
            .replace(RIGHT_SINGLE_CURLY_QUOTE, '\'')
            .replace(LEFT_DOUBLE_CURLY_QUOTE, '"')
            .replace(RIGHT_DOUBLE_CURLY_QUOTE, '"');
    }

    /**
     * Strip trailing whitespace from each line.
     * Translated from stripTrailingWhitespace() in utils.ts
     */
    public static String stripTrailingWhitespace(String str) {
        if (str == null) return "";
        String[] lines = str.split("(?<=\n)");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            // Strip trailing whitespace but preserve line ending
            String stripped = line.replaceAll("\\s+(\r?\n?)$", "$1");
            sb.append(stripped);
        }
        return sb.toString();
    }

    /**
     * Find the actual string in content (case-insensitive search fallback).
     * Translated from findActualString() in utils.ts
     */
    public static String findActualString(String content, String target) {
        if (content == null || target == null) return target;

        // Try exact match first
        if (content.contains(target)) return target;

        // Try case-insensitive
        String lower = content.toLowerCase();
        String targetLower = target.toLowerCase();
        int idx = lower.indexOf(targetLower);
        if (idx >= 0) {
            return content.substring(idx, idx + target.length());
        }

        return target;
    }

    /**
     * Check if two file edit inputs are equivalent.
     * Translated from areFileEditsInputsEquivalent() in utils.ts
     */
    public static boolean areFileEditsInputsEquivalent(
            String oldString1, String newString1,
            String oldString2, String newString2) {
        return java.util.Objects.equals(normalizeQuotes(oldString1), normalizeQuotes(oldString2))
            && java.util.Objects.equals(normalizeQuotes(newString1), normalizeQuotes(newString2));
    }

    private FileEditUtils() {}
}
