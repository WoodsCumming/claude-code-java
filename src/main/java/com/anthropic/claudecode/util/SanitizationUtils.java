package com.anthropic.claudecode.util;

import java.text.Normalizer;
import java.util.Map;

/**
 * Unicode sanitization utilities for hidden character attack mitigation.
 * Translated from src/utils/sanitization.ts
 *
 * Protects against Unicode-based hidden character attacks (ASCII Smuggling,
 * Hidden Prompt Injection) by removing dangerous Unicode characters.
 */
public class SanitizationUtils {

    private static final int MAX_ITERATIONS = 10;

    /**
     * Partially sanitize Unicode text to remove dangerous hidden characters.
     * Translated from partiallySanitizeUnicode() in sanitization.ts
     */
    public static String partiallySanitizeUnicode(String prompt) {
        if (prompt == null) return "";

        String current = prompt;
        String previous = "";
        int iterations = 0;

        while (!current.equals(previous) && iterations < MAX_ITERATIONS) {
            previous = current;

            // Apply NFKC normalization
            current = Normalizer.normalize(current, Normalizer.Form.NFKC);

            // Remove Unicode Tag characters (U+E0000-U+E007F)
            current = current.replaceAll("[\\x{E0000}-\\x{E007F}]", "");

            // Remove Unicode control characters (except common whitespace)
            current = current.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");

            // Remove Unicode format characters (bidirectional, zero-width, etc.)
            current = current.replaceAll("[\\x{200B}-\\x{200F}\\x{202A}-\\x{202E}\\x{2060}-\\x{2064}\\x{FEFF}]", "");

            // Remove Unicode private use area characters
            current = current.replaceAll("[\\x{E000}-\\x{F8FF}]", "");

            // Remove Unicode noncharacters
            current = current.replaceAll("[\\x{FDD0}-\\x{FDEF}\\x{FFFE}\\x{FFFF}]", "");

            iterations++;
        }

        return current;
    }

    /**
     * Recursively sanitize an object's string values.
     * Translated from recursiveSanitize() in sanitization.ts
     */
    @SuppressWarnings("unchecked")
    public static Object recursiveSanitize(Object obj) {
        if (obj instanceof String s) {
            return partiallySanitizeUnicode(s);
        } else if (obj instanceof java.util.List<?> list) {
            java.util.List<Object> result = new java.util.ArrayList<>();
            for (Object item : list) {
                result.add(recursiveSanitize(item));
            }
            return result;
        } else if (obj instanceof Map<?, ?> map) {
            java.util.Map<Object, Object> result = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(entry.getKey(), recursiveSanitize(entry.getValue()));
            }
            return result;
        }
        return obj;
    }

    /**
     * Sanitize a filesystem path by replacing non-safe characters.
     * Translated from sanitizePath() in paths.ts
     */
    public static String sanitizePath(String path) {
        if (path == null || path.isBlank()) return "_";
        return path.replace("/", "_").replace("\\", "_")
                .replaceAll("[^a-zA-Z0-9._-]", "_")
                .replaceAll("-+", "-")
                .replaceAll("_+", "_")
                .replaceAll("^[_-]+", "");
    }

    private SanitizationUtils() {}
}
