package com.anthropic.claudecode.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for slicing strings that contain ANSI escape sequences.
 *
 * Unlike naive character-count slicing, this implementation:
 *   - Counts display cells (full-width chars = 2, combining marks = 0)
 *   - Properly opens/closes ANSI style runs so the slice is self-contained
 *   - Handles OSC 8 hyperlink sequences
 *
 * Translated from src/utils/sliceAnsi.ts
 */
public class SliceAnsiUtils {

    // Matches a single ANSI escape sequence (CSI, OSC, or simple ESC codes)
    private static final Pattern ANSI_ESCAPE = Pattern.compile(
            "\u001B(?:"
            + "\\[[0-9;]*[mGKHfABCDsuJr]"       // CSI sequences (colours, cursor, etc.)
            + "|\\]8;;[^\u001B]*\u001B\\\\"       // OSC 8 hyperlink open  (ESC ] 8 ; ; url ST)
            + "|\\]8;;\u001B\\\\"                 // OSC 8 hyperlink close (ESC ] 8 ;; ST)
            + "|\\[[0-9;]*[A-Za-z]"               // other CSI
            + ")"
    );

    // Reset-all sequence used to terminate dangling style runs
    private static final String RESET = "\u001B[0m";

    /**
     * Token produced by the internal tokeniser.
     *
     * @param type    either "ansi" (escape sequence) or "text" (printable chars)
     * @param value   raw string value of this token
     * @param width   display-cell width (0 for ANSI tokens and combining marks)
     */
    private record Token(String type, String value, int width) {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Slice {@code str} from display-cell {@code start} (inclusive) to
     * {@code end} (exclusive). When {@code end} is negative or
     * {@link Integer#MAX_VALUE} the slice extends to the end of the string.
     *
     * @param str   string possibly containing ANSI escape sequences
     * @param start first display cell to include (0-based)
     * @param end   first display cell to exclude; use {@link Integer#MAX_VALUE}
     *              to mean "no limit"
     * @return properly escaped sub-string
     */
    public static String sliceAnsi(String str, int start, int end) {
        List<Token> tokens = tokenize(str);

        List<String> activeCodes = new ArrayList<>();
        int position = 0;
        StringBuilder result = new StringBuilder();
        boolean include = false;

        for (Token token : tokens) {
            int width = token.width();

            // Break after trailing zero-width marks (combining chars attach to
            // the preceding base char and must travel with it).
            if (end != Integer.MAX_VALUE && position >= end) {
                if ("ansi".equals(token.type()) || width > 0 || !include) break;
            }

            if ("ansi".equals(token.type())) {
                activeCodes.add(token.value());
                if (include) {
                    result.append(token.value());
                }
            } else {
                if (!include && position >= start) {
                    // Skip leading zero-width marks that belong to the left half
                    if (start > 0 && width == 0) continue;

                    include = true;
                    // Reduce to only the last active code per attribute group
                    // and emit them so the slice is self-contained.
                    List<String> reduced = reduceActiveCodes(activeCodes);
                    activeCodes = new ArrayList<>(reduced);
                    for (String code : reduced) {
                        result.append(code);
                    }
                }

                if (include) {
                    result.append(token.value());
                }

                position += width;
            }
        }

        // Close any dangling style runs
        List<String> activeStartCodes = filterStartCodes(reduceActiveCodes(activeCodes));
        if (!activeStartCodes.isEmpty()) {
            result.append(RESET);
        }

        return result.toString();
    }

    /**
     * Convenience overload with no upper bound.
     */
    public static String sliceAnsi(String str, int start) {
        return sliceAnsi(str, start, Integer.MAX_VALUE);
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Split {@code str} into alternating ANSI-escape tokens and text tokens,
     * each annotated with its display-cell width.
     */
    private static List<Token> tokenize(String str) {
        List<Token> tokens = new ArrayList<>();
        if (str == null || str.isEmpty()) return tokens;

        Matcher m = ANSI_ESCAPE.matcher(str);
        int lastEnd = 0;

        while (m.find()) {
            if (m.start() > lastEnd) {
                String text = str.substring(lastEnd, m.start());
                for (Token t : splitToCharTokens(text)) tokens.add(t);
            }
            tokens.add(new Token("ansi", m.group(), 0));
            lastEnd = m.end();
        }

        if (lastEnd < str.length()) {
            String text = str.substring(lastEnd);
            for (Token t : splitToCharTokens(text)) tokens.add(t);
        }

        return tokens;
    }

    /**
     * Split plain text into per-codepoint tokens, each carrying its display width.
     */
    private static List<Token> splitToCharTokens(String text) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            int w = displayWidth(cp);
            tokens.add(new Token("text", ch, w));
            i += Character.charCount(cp);
        }
        return tokens;
    }

    /**
     * Approximate display-cell width for a Unicode code point.
     *
     * <ul>
     *   <li>East-Asian full-width characters → 2</li>
     *   <li>Combining / zero-width characters → 0</li>
     *   <li>Everything else → 1</li>
     * </ul>
     */
    private static int displayWidth(int cp) {
        // Zero-width / combining
        int type = Character.getType(cp);
        if (type == Character.NON_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.FORMAT
                || cp == 0x200B  // ZWSP
                || cp == 0xFEFF  // BOM / ZWNBSP
        ) {
            return 0;
        }

        // CJK full-width blocks (simplified subset — covers the common cases)
        if ((cp >= 0x1100 && cp <= 0x115F)       // Hangul Jamo
                || cp == 0x2329 || cp == 0x232A
                || (cp >= 0x2E80 && cp <= 0x303E)  // CJK Radicals etc.
                || (cp >= 0x3040 && cp <= 0x33FF)  // Hiragana … CJK Compatibility
                || (cp >= 0x3400 && cp <= 0x4DBF)  // CJK Extension A
                || (cp >= 0x4E00 && cp <= 0xA4C6)  // CJK Unified Ideographs
                || (cp >= 0xA960 && cp <= 0xA97C)  // Hangul Jamo Extended-A
                || (cp >= 0xAC00 && cp <= 0xD7A3)  // Hangul Syllables
                || (cp >= 0xF900 && cp <= 0xFAFF)  // CJK Compatibility Ideographs
                || (cp >= 0xFE10 && cp <= 0xFE19)  // Vertical forms
                || (cp >= 0xFE30 && cp <= 0xFE6B)  // CJK Compatibility Forms
                || (cp >= 0xFF01 && cp <= 0xFF60)  // Fullwidth Latin / Katakana
                || (cp >= 0xFFE0 && cp <= 0xFFE6)  // Fullwidth signs
                || (cp >= 0x1B000 && cp <= 0x1B001) // Kana Supplement
                || (cp >= 0x1F004 && cp <= 0x1F0CF)
                || (cp >= 0x1F200 && cp <= 0x1F251)
                || (cp >= 0x20000 && cp <= 0x2FFFD)  // CJK Extension B–F
                || (cp >= 0x30000 && cp <= 0x3FFFD)
        ) {
            return 2;
        }

        return 1;
    }

    /**
     * Keep only the last occurrence of each distinct style attribute (naïve
     * reduction — sufficient for standard SGR and hyperlink sequences).
     */
    private static List<String> reduceActiveCodes(List<String> codes) {
        // Walk backwards and emit only codes not already covered.
        // Treat reset (\e[0m or \e[m) as wiping all prior codes.
        List<String> result = new ArrayList<>();
        boolean resetSeen = false;

        for (int i = codes.size() - 1; i >= 0; i--) {
            String code = codes.get(i);
            if (isReset(code)) {
                if (!resetSeen) {
                    resetSeen = true;
                    // Everything before a reset is cancelled
                    break;
                }
            } else if (!resetSeen) {
                // Simple dedup: keep first occurrence when scanning backwards
                if (!result.contains(code)) {
                    result.add(0, code);
                }
            }
        }

        return result;
    }

    /**
     * Filter to only "start" codes — i.e. codes that open a style run (not
     * closing codes like hyperlink-close or explicit resets).
     */
    private static List<String> filterStartCodes(List<String> codes) {
        List<String> result = new ArrayList<>();
        for (String code : codes) {
            if (!isEndCode(code)) {
                result.add(code);
            }
        }
        return result;
    }

    /** True when the code is a reset or an explicit close (OSC 8 ;; ST). */
    private static boolean isEndCode(String code) {
        return isReset(code) || code.equals("\u001B]8;;\u001B\\");
    }

    private static boolean isReset(String code) {
        return "\u001B[0m".equals(code) || "\u001B[m".equals(code);
    }
}
