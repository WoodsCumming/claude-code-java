package com.anthropic.claudecode.util;

/**
 * Text truncation utilities.
 * Translated from src/utils/truncate.ts
 *
 * Provides width-aware text truncation for terminal display.
 */
public class TruncateUtils {

    /**
     * Truncate a file path in the middle to preserve both directory context and filename.
     * Translated from truncatePathMiddle() in truncate.ts
     */
    public static String truncatePathMiddle(String path, int maxLength) {
        if (path == null) return "";
        if (path.length() <= maxLength) return path;
        if (maxLength <= 0) return "\u2026"; // …
        if (maxLength < 5) return truncateToWidth(path, maxLength);

        // Find the filename (last path segment)
        int lastSlash = path.lastIndexOf('/');
        String filename = lastSlash >= 0 ? path.substring(lastSlash) : path;
        String directory = lastSlash >= 0 ? path.substring(0, lastSlash) : "";
        int filenameWidth = filename.length();

        // If filename alone is too long, truncate from start
        if (filenameWidth >= maxLength - 1) {
            return "\u2026" + filename.substring(Math.max(0, filename.length() - (maxLength - 1)));
        }

        // Available space for directory
        int availableForDir = maxLength - filenameWidth - 1; // -1 for "…"
        if (availableForDir <= 0) {
            return "\u2026" + filename;
        }

        // Truncate directory from the end
        String truncatedDir = directory.length() <= availableForDir
            ? directory
            : directory.substring(0, availableForDir);

        return truncatedDir + "\u2026" + filename;
    }

    /**
     * Truncate text to a maximum width.
     * Translated from truncateToWidth() in truncate.ts
     */
    public static String truncateToWidth(String text, int maxWidth) {
        if (text == null) return "";
        if (text.length() <= maxWidth) return text;
        if (maxWidth <= 0) return "";
        if (maxWidth == 1) return "\u2026";
        return text.substring(0, maxWidth - 1) + "\u2026";
    }

    /**
     * Truncate text and add ellipsis if needed.
     */
    public static String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 1) + "\u2026";
    }

    private TruncateUtils() {}
}
