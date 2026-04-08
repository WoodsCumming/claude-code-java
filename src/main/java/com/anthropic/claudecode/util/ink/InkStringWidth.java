package com.anthropic.claudecode.util.ink;

/**
 * Java equivalent of stringWidth.ts.
 *
 * Computes the display width of a string as it would appear in a terminal,
 * handling Unicode, ANSI escape sequences, wide (CJK/emoji) characters, and
 * zero-width combining marks.
 *
 * The TypeScript source uses Bun.stringWidth when available and a pure-JS
 * fallback otherwise. In Java we always use the pure implementation since
 * there is no Bun runtime. East-Asian width data and emoji detection are
 * approximated with Unicode block ranges that match the TS logic.
 */
public final class InkStringWidth {

    // Visible-space ANSI endCodes (matching StylePool constants)
    private static final char ESC = '\u001b';

    private InkStringWidth() {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Returns the display width of {@code str} in terminal columns.
     * Strips ANSI escape codes before measuring.
     */
    public static int stringWidth(String str) {
        if (str == null || str.isEmpty()) return 0;

        // Fast path: pure ASCII (no escapes, no wide chars)
        boolean isPureAscii = true;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c >= 127 || c == ESC) {
                isPureAscii = false;
                break;
            }
        }
        if (isPureAscii) {
            int width = 0;
            for (int i = 0; i < str.length(); i++) {
                if (str.charAt(i) > 0x1f) width++;
            }
            return width;
        }

        // Strip ANSI if escape is present
        if (str.indexOf(ESC) >= 0) {
            str = stripAnsi(str);
            if (str.isEmpty()) return 0;
        }

        // Fast path: no segmentation-sensitive characters
        if (!needsSegmentation(str)) {
            int width = 0;
            for (int i = 0; i < str.length(); ) {
                int cp = str.codePointAt(i);
                if (!isZeroWidth(cp)) {
                    width += eastAsianWidth(cp);
                }
                i += Character.charCount(cp);
            }
            return width;
        }

        // Full grapheme-cluster path
        return measureWithGraphemeClusters(str);
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Strip ANSI / VT100 escape sequences (CSI, OSC, etc.) from {@code str}.
     * Mirrors the behaviour of the {@code strip-ansi} npm package.
     */
    static String stripAnsi(String str) {
        // Regex: ESC [ ... m  (CSI sequences)  + OSC sequences + single-char escapes
        // Simplified: remove ESC followed by [ up to final byte 0x40-0x7e,
        // ESC ] up to BEL or ST, and bare ESC + single char.
        StringBuilder sb = new StringBuilder(str.length());
        int i = 0;
        while (i < str.length()) {
            char c = str.charAt(i);
            if (c == ESC && i + 1 < str.length()) {
                char next = str.charAt(i + 1);
                if (next == '[') {
                    // CSI: ESC [ <params> <final>
                    i += 2;
                    while (i < str.length()) {
                        char ch = str.charAt(i++);
                        if (ch >= 0x40 && ch <= 0x7e) break;
                    }
                } else if (next == ']') {
                    // OSC: ESC ] ... BEL or ESC \
                    i += 2;
                    while (i < str.length()) {
                        char ch = str.charAt(i++);
                        if (ch == '\u0007') break; // BEL
                        if (ch == ESC && i < str.length() && str.charAt(i) == '\\') {
                            i++;
                            break;
                        }
                    }
                } else {
                    // Other: skip ESC + one char
                    i += 2;
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * Returns true when the string contains codepoints that require grapheme
     * segmentation (emoji, variation selectors, ZWJ).
     */
    private static boolean needsSegmentation(String str) {
        for (int i = 0; i < str.length(); ) {
            int cp = str.codePointAt(i);
            // Emoji ranges
            if (cp >= 0x1F300 && cp <= 0x1FAFF) return true;
            if (cp >= 0x2600 && cp <= 0x27BF) return true;
            if (cp >= 0x1F1E6 && cp <= 0x1F1FF) return true;
            // Variation selectors, ZWJ
            if (cp >= 0xFE00 && cp <= 0xFE0F) return true;
            if (cp == 0x200D) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    /**
     * Full path: iterate over Unicode extended grapheme clusters using
     * {@link java.text.BreakIterator} and measure each cluster.
     */
    private static int measureWithGraphemeClusters(String str) {
        java.text.BreakIterator bi = java.text.BreakIterator.getCharacterInstance();
        bi.setText(str);
        int width = 0;
        int start = bi.first();
        for (int end = bi.next(); end != java.text.BreakIterator.DONE; start = end, end = bi.next()) {
            String grapheme = str.substring(start, end);
            if (isEmojiGrapheme(grapheme)) {
                width += getEmojiWidth(grapheme);
            } else {
                // Count width of first non-zero-width codepoint in cluster
                for (int i = 0; i < grapheme.length(); ) {
                    int cp = grapheme.codePointAt(i);
                    if (!isZeroWidth(cp)) {
                        width += eastAsianWidth(cp);
                        break;
                    }
                    i += Character.charCount(cp);
                }
            }
        }
        return width;
    }

    private static boolean isEmojiGrapheme(String grapheme) {
        if (grapheme.isEmpty()) return false;
        int cp = grapheme.codePointAt(0);
        if (cp >= 0x1F300 && cp <= 0x1FAFF) return true;
        if (cp >= 0x2600 && cp <= 0x27BF) return true;
        if (cp >= 0x1F1E6 && cp <= 0x1F1FF) return true;
        // VS16 makes text emoji presentation become emoji presentation
        if (grapheme.length() >= 2) {
            for (int i = 0; i < grapheme.length(); i++) {
                if (grapheme.charAt(i) == 0xFE0F) return true;
            }
        }
        return false;
    }

    private static int getEmojiWidth(String grapheme) {
        int first = grapheme.codePointAt(0);
        // Regional indicators: pair = 2, single = 1
        if (first >= 0x1F1E6 && first <= 0x1F1FF) {
            int count = grapheme.codePointCount(0, grapheme.length());
            return count == 1 ? 1 : 2;
        }
        // Incomplete keycap: digit/symbol + VS16 without U+20E3
        if (grapheme.length() == 2) {
            int second = grapheme.codePointAt(Character.charCount(first));
            if (second == 0xFE0F
                    && ((first >= 0x30 && first <= 0x39) || first == 0x23 || first == 0x2A)) {
                return 1;
            }
        }
        return 2;
    }

    /**
     * East-Asian width classification. Mirrors the TS {@code eastAsianWidth()}
     * call with {@code ambiguousAsWide: false} — ambiguous chars are width 1.
     */
    static int eastAsianWidth(int codePoint) {
        // Wide / fullwidth blocks
        if ((codePoint >= 0x1100 && codePoint <= 0x115F) // Hangul Jamo
                || codePoint == 0x2329 || codePoint == 0x232A
                || (codePoint >= 0x2E80 && codePoint <= 0x303E)
                || (codePoint >= 0x3040 && codePoint <= 0xA4CF)
                || (codePoint >= 0xA960 && codePoint <= 0xA97F)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7FF)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || (codePoint >= 0xFE10 && codePoint <= 0xFE19)
                || (codePoint >= 0xFE30 && codePoint <= 0xFE6F)
                || (codePoint >= 0xFF01 && codePoint <= 0xFF60)
                || (codePoint >= 0xFFE0 && codePoint <= 0xFFE6)
                || (codePoint >= 0x1B000 && codePoint <= 0x1B0FF)
                || (codePoint >= 0x1F004 && codePoint <= 0x1F0CF)
                || (codePoint >= 0x1F200 && codePoint <= 0x1F2FF)
                || (codePoint >= 0x20000 && codePoint <= 0x2FFFD)
                || (codePoint >= 0x30000 && codePoint <= 0x3FFFD)) {
            return 2;
        }
        return 1;
    }

    /**
     * Returns true for codepoints that contribute 0 columns (combining marks,
     * zero-width spaces, variation selectors, control characters, etc.).
     */
    static boolean isZeroWidth(int cp) {
        // Fast path for printable ASCII
        if (cp >= 0x20 && cp < 0x7F) return false;
        if (cp >= 0xA0 && cp < 0x0300) return cp == 0x00AD;

        // Control characters
        if (cp <= 0x1F || (cp >= 0x7F && cp <= 0x9F)) return true;

        // Zero-width and invisible characters
        if ((cp >= 0x200B && cp <= 0x200D)
                || cp == 0xFEFF
                || (cp >= 0x2060 && cp <= 0x2064)) return true;

        // Variation selectors
        if ((cp >= 0xFE00 && cp <= 0xFE0F)
                || (cp >= 0xE0100 && cp <= 0xE01EF)) return true;

        // Combining diacritical marks
        if ((cp >= 0x0300 && cp <= 0x036F)
                || (cp >= 0x1AB0 && cp <= 0x1AFF)
                || (cp >= 0x1DC0 && cp <= 0x1DFF)
                || (cp >= 0x20D0 && cp <= 0x20FF)
                || (cp >= 0xFE20 && cp <= 0xFE2F)) return true;

        // Indic combining marks (Devanagari → Malayalam)
        if (cp >= 0x0900 && cp <= 0x0D4F) {
            int offset = cp & 0x7F;
            if (offset <= 0x03) return true;
            if (offset >= 0x3A && offset <= 0x4F) return true;
            if (offset >= 0x51 && offset <= 0x57) return true;
            if (offset >= 0x62 && offset <= 0x63) return true;
        }

        // Thai / Lao combining marks
        if (cp == 0x0E31
                || (cp >= 0x0E34 && cp <= 0x0E3A)
                || (cp >= 0x0E47 && cp <= 0x0E4E)
                || cp == 0x0EB1
                || (cp >= 0x0EB4 && cp <= 0x0EBC)
                || (cp >= 0x0EC8 && cp <= 0x0ECD)) return true;

        // Arabic formatting
        if ((cp >= 0x0600 && cp <= 0x0605)
                || cp == 0x06DD || cp == 0x070F || cp == 0x08E2) return true;

        // Surrogates / tag characters
        if (cp >= 0xD800 && cp <= 0xDFFF) return true;
        if (cp >= 0xE0000 && cp <= 0xE007F) return true;

        return false;
    }
}
