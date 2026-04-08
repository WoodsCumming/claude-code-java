package com.anthropic.claudecode.util;

import java.util.Optional;

/**
 * PDF page range utilities.
 * Translated from src/utils/pdfUtils.ts
 */
public class PdfPageUtils {

    /**
     * Parse a PDF page range string.
     * Translated from parsePDFPageRange() in pdfUtils.ts
     *
     * Supported formats:
     * - "5" → { firstPage: 5, lastPage: 5 }
     * - "1-10" → { firstPage: 1, lastPage: 10 }
     * - "3-" → { firstPage: 3, lastPage: MAX }
     */
    public static Optional<PageRange> parsePDFPageRange(String pages) {
        if (pages == null || pages.isBlank()) return Optional.empty();

        String trimmed = pages.trim();

        // Open-ended range "N-"
        if (trimmed.endsWith("-")) {
            try {
                int first = Integer.parseInt(trimmed.substring(0, trimmed.length() - 1));
                if (first < 1) return Optional.empty();
                return Optional.of(new PageRange(first, Integer.MAX_VALUE));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }

        int dashIndex = trimmed.indexOf('-');
        if (dashIndex < 0) {
            // Single page
            try {
                int page = Integer.parseInt(trimmed);
                if (page < 1) return Optional.empty();
                return Optional.of(new PageRange(page, page));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }

        // Range "N-M"
        try {
            int first = Integer.parseInt(trimmed.substring(0, dashIndex));
            int last = Integer.parseInt(trimmed.substring(dashIndex + 1));
            if (first < 1 || last < first) return Optional.empty();
            return Optional.of(new PageRange(first, last));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Check if a file extension is a PDF.
     * Translated from isPDFExtension() in pdfUtils.ts
     */
    public static boolean isPDFExtension(String path) {
        if (path == null) return false;
        return path.toLowerCase().endsWith(".pdf");
    }

    /**
     * Check if PDF is supported.
     * Translated from isPDFSupported() in pdfUtils.ts
     */
    public static boolean isPDFSupported(String model) {
        // All current Claude models support PDFs
        return true;
    }

    public record PageRange(int firstPage, int lastPage) {}

    private PdfPageUtils() {}
}
