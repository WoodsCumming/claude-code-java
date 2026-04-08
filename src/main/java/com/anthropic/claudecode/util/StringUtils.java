package com.anthropic.claudecode.util;

import java.util.regex.Pattern;

/**
 * String utility functions.
 * Translated from src/utils/stringUtils.ts
 */
public class StringUtils {

    /**
     * Escape special regex characters.
     * Translated from escapeRegExp() in stringUtils.ts
     */
    public static String escapeRegExp(String str) {
        if (str == null) return "";
        return str.replaceAll("[.*+?^${}()|\\[\\]\\\\]", "\\\\$0");
    }

    /**
     * Capitalize the first character of a string.
     * Translated from capitalize() in stringUtils.ts
     */
    public static String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    /**
     * Return singular or plural form based on count.
     * Translated from plural() in stringUtils.ts
     */
    public static String plural(int n, String word) {
        return plural(n, word, word + "s");
    }

    public static String plural(int n, String word, String pluralWord) {
        return n == 1 ? word : pluralWord;
    }

    /**
     * Get the first line of a string.
     * Translated from firstLineOf() in stringUtils.ts
     */
    public static String firstLineOf(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return nl == -1 ? s : s.substring(0, nl);
    }

    /**
     * Count occurrences of a character in a string.
     * Translated from countCharInString() in stringUtils.ts
     */
    public static int countCharInString(String str, char ch) {
        if (str == null) return 0;
        int count = 0;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == ch) count++;
        }
        return count;
    }

    /**
     * Truncate a string to a maximum length with ellipsis.
     */
    public static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }

    /**
     * Add line numbers to text content.
     * Translated from addLineNumbers() in file.ts
     */
    public static String addLineNumbers(String content) {
        if (content == null) return "";
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(String.format("%d\t%s%n", i + 1, lines[i]));
        }
        return sb.toString();
    }

    private StringUtils() {}
}
