package com.anthropic.claudecode.util.ink;

/**
 * Java equivalent of tabstops.ts.
 *
 * Expands horizontal tab characters ({@code '\t'}) in a string to spaces
 * using 8-column intervals (the POSIX default, hard-coded in terminals like
 * Ghostty). ANSI / VT100 escape sequences are preserved unchanged; only
 * printable text segments have their tabs expanded.
 *
 * The TypeScript implementation uses a custom {@code createTokenizer()} to
 * split input into "sequence" tokens (ANSI escapes) and plain-text tokens.
 * We replicate the same logic here by parsing the input inline.
 */
public final class Tabstops {

    /** POSIX default tab interval — 8 columns. */
    public static final int DEFAULT_TAB_INTERVAL = 8;

    private static final char ESC = '\u001b';

    private Tabstops() {}

    /**
     * Expand tab characters in {@code text} using the given tab interval.
     * ANSI escape sequences are not counted towards column position.
     *
     * @param text     input string, may contain ANSI escapes
     * @param interval tab stop interval (columns); must be &gt; 0
     * @return text with tabs replaced by the appropriate number of spaces
     */
    public static String expandTabs(String text, int interval) {
        if (text == null || !text.contains("\t")) {
            return text == null ? "" : text;
        }

        StringBuilder result = new StringBuilder(text.length() + 16);
        int column = 0;
        int i = 0;

        while (i < text.length()) {
            char c = text.charAt(i);

            if (c == ESC) {
                // ANSI escape sequence — copy verbatim, do NOT advance column
                result.append(c);
                i++;
                if (i < text.length()) {
                    char next = text.charAt(i);
                    result.append(next);
                    i++;
                    if (next == '[') {
                        // CSI sequence: ESC [ <params> <final 0x40-0x7e>
                        while (i < text.length()) {
                            char ch = text.charAt(i);
                            result.append(ch);
                            i++;
                            if (ch >= 0x40 && ch <= 0x7e) break;
                        }
                    } else if (next == ']') {
                        // OSC sequence: ESC ] ... BEL or ESC \
                        while (i < text.length()) {
                            char ch = text.charAt(i);
                            result.append(ch);
                            i++;
                            if (ch == '\u0007') break;
                            if (ch == ESC && i < text.length() && text.charAt(i) == '\\') {
                                result.append('\\');
                                i++;
                                break;
                            }
                        }
                    }
                    // Other single-char escape: already consumed above
                }
            } else if (c == '\t') {
                int spaces = interval - (column % interval);
                for (int s = 0; s < spaces; s++) result.append(' ');
                column += spaces;
                i++;
            } else if (c == '\n') {
                result.append(c);
                column = 0;
                i++;
            } else {
                // Printable (or non-escape) character — measure its width
                int cp = text.codePointAt(i);
                result.appendCodePoint(cp);
                column += InkStringWidth.stringWidth(new String(Character.toChars(cp)));
                i += Character.charCount(cp);
            }
        }

        return result.toString();
    }

    /**
     * Expand tabs using the default interval of {@value #DEFAULT_TAB_INTERVAL} columns.
     *
     * @param text input string
     * @return text with tabs expanded
     */
    public static String expandTabs(String text) {
        return expandTabs(text, DEFAULT_TAB_INTERVAL);
    }
}
