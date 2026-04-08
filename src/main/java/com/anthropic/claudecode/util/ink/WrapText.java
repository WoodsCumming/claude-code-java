package com.anthropic.claudecode.util.ink;

import com.anthropic.claudecode.util.ink.WrapAnsi.WrapOptions;

/**
 * Text wrapping and truncation for Ink text nodes.
 * Translated from wrap-text.ts.
 *
 * <p>Mirrors the {@code wrapText(text, maxWidth, wrapType)} function, supporting
 * the same set of wrapping / truncation modes as the TypeScript original.
 */
public final class WrapText {

    private WrapText() {}

    private static final String ELLIPSIS = "…";

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Enum of supported {@code textWrap} modes (mirrors the TypeScript union type
     * on {@code Styles.textWrap}).
     */
    public enum TextWrapMode {
        WRAP,
        WRAP_TRIM,
        TRUNCATE,
        TRUNCATE_MIDDLE,
        TRUNCATE_START;

        /** Parse from the TypeScript string representation. */
        public static TextWrapMode fromString(String s) {
            if (s == null) return WRAP;
            return switch (s) {
                case "wrap"            -> WRAP;
                case "wrap-trim"       -> WRAP_TRIM;
                case "truncate-middle" -> TRUNCATE_MIDDLE;
                case "truncate-start"  -> TRUNCATE_START;
                default                -> TRUNCATE; // "truncate" and anything unknown
            };
        }
    }

    /**
     * Wrap or truncate {@code text} to fit within {@code maxWidth} visible columns.
     *
     * @param text     source text (may contain ANSI escape sequences)
     * @param maxWidth maximum visible column width
     * @param wrapMode wrapping / truncation mode
     * @return transformed text
     */
    public static String wrapText(String text, int maxWidth, TextWrapMode wrapMode) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        if (wrapMode == null) return text;

        return switch (wrapMode) {
            case WRAP      -> WrapAnsi.wrap(text, maxWidth, new WrapOptions(true, true, false));
            case WRAP_TRIM -> WrapAnsi.wrap(text, maxWidth, new WrapOptions(true, true, true));
            case TRUNCATE        -> truncate(text, maxWidth, TruncatePosition.END);
            case TRUNCATE_MIDDLE -> truncate(text, maxWidth, TruncatePosition.MIDDLE);
            case TRUNCATE_START  -> truncate(text, maxWidth, TruncatePosition.START);
        };
    }

    /** Overload accepting the TypeScript string representation of the wrap mode. */
    public static String wrapText(String text, int maxWidth, String wrapModeStr) {
        return wrapText(text, maxWidth, TextWrapMode.fromString(wrapModeStr));
    }

    // -------------------------------------------------------------------------
    // Truncation
    // -------------------------------------------------------------------------

    private enum TruncatePosition { START, MIDDLE, END }

    /**
     * Truncate {@code text} to {@code columns} visible characters, inserting an
     * ellipsis at the specified position.
     */
    private static String truncate(String text, int columns, TruncatePosition position) {
        if (columns < 1) return "";
        if (columns == 1) return ELLIPSIS;

        int length = visibleWidth(text);
        if (length <= columns) return text;

        if (position == TruncatePosition.START) {
            return ELLIPSIS + sliceFit(text, length - columns + 1, length);
        }
        if (position == TruncatePosition.MIDDLE) {
            int half = columns / 2;
            return sliceFit(text, 0, half)
                    + ELLIPSIS
                    + sliceFit(text, length - (columns - half) + 1, length);
        }
        // END
        return sliceFit(text, 0, columns - 1) + ELLIPSIS;
    }

    /**
     * Slice to visible column range [start, end), retrying with {@code end-1} if a
     * wide character overshoots by one cell – mirrors the {@code sliceFit} helper.
     */
    private static String sliceFit(String text, int start, int end) {
        String s = sliceByVisibleWidth(text, start, end);
        return visibleWidth(s) > end - start ? sliceByVisibleWidth(text, start, end - 1) : s;
    }

    // -------------------------------------------------------------------------
    // ANSI-aware string slicing / width helpers
    // -------------------------------------------------------------------------

    /**
     * Slice {@code text} by visible column positions [start, end).
     * ANSI escape sequences are not counted toward column positions.
     */
    static String sliceByVisibleWidth(String text, int start, int end) {
        StringBuilder result = new StringBuilder();
        int col = 0;
        boolean inEscape = false;
        StringBuilder escapeBuffer = new StringBuilder();

        for (int i = 0; i < text.length(); ) {
            char ch = text.charAt(i);

            if (ch == '\u001B') {
                inEscape = true;
                escapeBuffer.setLength(0);
                escapeBuffer.append(ch);
                i++;
                continue;
            }

            if (inEscape) {
                escapeBuffer.append(ch);
                if ((ch >= '@' && ch <= '~') || ch == '\u0007') {
                    inEscape = false;
                    // Always include escape sequences in the output range
                    if (col >= start && col < end) {
                        result.append(escapeBuffer);
                    }
                }
                i++;
                continue;
            }

            int cp = text.codePointAt(i);
            int cpLen = Character.charCount(cp);
            int w = charWidth(cp);

            if (col >= start && col + w <= end) {
                result.appendCodePoint(cp);
            }
            col += w;
            i += cpLen;

            if (col >= end) break;
        }

        return result.toString();
    }

    /**
     * Return the total visible display width of {@code text}, ignoring ANSI sequences.
     */
    public static int visibleWidth(String text) {
        int w = 0;
        boolean inEscape = false;
        for (int i = 0; i < text.length(); ) {
            char ch = text.charAt(i);
            if (ch == '\u001B') { inEscape = true; i++; continue; }
            if (inEscape) {
                if ((ch >= '@' && ch <= '~') || ch == '\u0007') inEscape = false;
                i++;
                continue;
            }
            int cp = text.codePointAt(i);
            w += charWidth(cp);
            i += Character.charCount(cp);
        }
        return w;
    }

    /** Display width of a Unicode code point. */
    private static int charWidth(int cp) {
        if (cp >= 0x1100 && (
                cp <= 0x115F ||
                cp == 0x2329 || cp == 0x232A ||
                (cp >= 0x2E80 && cp <= 0x303E) ||
                (cp >= 0x3040 && cp <= 0x33FF) ||
                (cp >= 0x3400 && cp <= 0x4DBF) ||
                (cp >= 0x4E00 && cp <= 0xA4CF) ||
                (cp >= 0xAC00 && cp <= 0xD7FF) ||
                (cp >= 0xF900 && cp <= 0xFAFF) ||
                (cp >= 0xFE10 && cp <= 0xFE19) ||
                (cp >= 0xFE30 && cp <= 0xFE6F) ||
                (cp >= 0xFF00 && cp <= 0xFF60) ||
                (cp >= 0xFFE0 && cp <= 0xFFE6) ||
                (cp >= 0x1B000 && cp <= 0x1B001) ||
                (cp >= 0x1F300 && cp <= 0x1F64F) ||
                (cp >= 0x1F900 && cp <= 0x1F9FF) ||
                (cp >= 0x20000 && cp <= 0x2FFFD) ||
                (cp >= 0x30000 && cp <= 0x3FFFD)
        )) {
            return 2;
        }
        return 1;
    }
}
