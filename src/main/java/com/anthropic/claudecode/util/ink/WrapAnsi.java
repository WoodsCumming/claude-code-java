package com.anthropic.claudecode.util.ink;

import java.util.ArrayList;
import java.util.List;

/**
 * ANSI-aware text wrapping.
 * Translated from wrapAnsi.ts (which delegated to the {@code wrap-ansi} npm package).
 *
 * <p>This implementation wraps plain text and text that contains ANSI SGR escape
 * sequences at a given column width, optionally trimming leading whitespace on
 * wrapped lines and using hard (character-level) wrapping.
 *
 * <p>ANSI escape sequences are passed through unchanged and are not counted in the
 * visible column width.
 */
public final class WrapAnsi {

    private WrapAnsi() {}

    // ANSI escape sequence pattern: ESC [ ... m  (SGR) and other CSI sequences
    // We recognise the minimal set used by Ink: SGR + OSC 8 hyperlinks.
    private static final String ANSI_PATTERN = "\u001B(?:\\[[0-9;]*[mKJH]|\\][^\\u0007]*\\u0007|\\[[^@-~]*[@-~])";

    public record WrapOptions(boolean hard, boolean wordWrap, boolean trim) {
        public static final WrapOptions DEFAULT = new WrapOptions(false, true, true);
    }

    /**
     * Wrap {@code input} to at most {@code columns} visible characters per line.
     *
     * @param input   text to wrap (may contain ANSI escape sequences)
     * @param columns maximum visible column width
     * @param options wrapping options
     * @return wrapped text with newlines inserted
     */
    public static String wrap(String input, int columns, WrapOptions options) {
        if (columns <= 0) return "";
        if (input == null || input.isEmpty()) return input;

        boolean hard     = options != null && options.hard();
        boolean wordWrap = options == null || options.wordWrap();
        boolean trim     = options == null || options.trim();

        StringBuilder result = new StringBuilder();
        String[] inputLines = input.split("\n", -1);

        for (int lineIdx = 0; lineIdx < inputLines.length; lineIdx++) {
            String line = inputLines[lineIdx];
            if (lineIdx > 0) result.append('\n');
            wrapLine(line, columns, hard, wordWrap, trim, result);
        }

        return result.toString();
    }

    /** Convenience overload with default options ({@code hard=false, wordWrap=true, trim=true}). */
    public static String wrap(String input, int columns) {
        return wrap(input, columns, WrapOptions.DEFAULT);
    }

    // -------------------------------------------------------------------------
    // Internal per-line wrapping
    // -------------------------------------------------------------------------

    private static void wrapLine(
            String line,
            int columns,
            boolean hard,
            boolean wordWrap,
            boolean trim,
            StringBuilder out) {

        // Tokenise: alternate between ANSI escapes (invisible) and visible text
        List<Token> tokens = tokenise(line);

        // Current output line being built
        StringBuilder currentLine = new StringBuilder();
        // Pending ANSI sequences before the next visible character
        StringBuilder pendingAnsi = new StringBuilder();
        int col = 0; // visible width of currentLine
        boolean firstOutputLine = true;

        for (Token token : tokens) {
            if (token.isAnsi()) {
                pendingAnsi.append(token.text());
                continue;
            }

            String text = token.text();
            int i = 0;
            while (i < text.length()) {
                // Determine the width of the next grapheme cluster (simple: 1 for ASCII)
                int charWidth = charWidth(text, i);
                char ch = text.charAt(i);
                int cpLen = Character.isSurrogatePair(text.charAt(i),
                        i + 1 < text.length() ? text.charAt(i + 1) : '\0') ? 2 : 1;

                if (col + charWidth > columns) {
                    // Need to wrap
                    if (wordWrap && !hard && ch != ' ') {
                        // Back-track to last word boundary
                        int wrapAt = findWordBoundary(currentLine, col);
                        if (wrapAt > 0) {
                            String remainder = currentLine.substring(wrapAt);
                            String before = currentLine.substring(0, wrapAt);
                            if (trim) before = before.stripTrailing();
                            flushLine(out, before, firstOutputLine);
                            firstOutputLine = false;
                            currentLine.setLength(0);
                            col = 0;
                            if (trim) {
                                // skip leading whitespace on continuation
                                int skip = 0;
                                while (skip < remainder.length() && remainder.charAt(skip) == ' ') skip++;
                                remainder = remainder.substring(skip);
                            }
                            currentLine.append(pendingAnsi).append(remainder);
                            col = visibleWidth(remainder);
                            pendingAnsi.setLength(0);
                            continue; // re-evaluate current char
                        }
                    }
                    // Hard wrap or no word-boundary found: break here
                    if (trim && col > 0) {
                        // strip trailing spaces from the filled line
                        trimTrailingSpaces(currentLine);
                    }
                    flushLine(out, currentLine.toString(), firstOutputLine);
                    firstOutputLine = false;
                    currentLine.setLength(0);
                    col = 0;
                    if (trim && ch == ' ') {
                        i += cpLen; // skip the space that caused the break
                        continue;
                    }
                }

                // Append pending ANSI sequences then the character
                currentLine.append(pendingAnsi);
                pendingAnsi.setLength(0);
                for (int k = 0; k < cpLen; k++) {
                    currentLine.append(text.charAt(i + k));
                }
                col += charWidth;
                i += cpLen;
            }
        }

        // Flush remaining ANSI (reset sequences etc.)
        currentLine.append(pendingAnsi);
        flushLine(out, currentLine.toString(), firstOutputLine);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void flushLine(StringBuilder out, String line, boolean first) {
        if (!first) out.append('\n');
        out.append(line);
    }

    private static void trimTrailingSpaces(StringBuilder sb) {
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') end--;
        sb.setLength(end);
    }

    /**
     * Find the last word boundary (space) in {@code sb} that is within
     * {@code maxCol} visible columns.  Returns -1 if none found.
     */
    private static int findWordBoundary(StringBuilder sb, int maxCol) {
        int idx = -1;
        int col = 0;
        for (int i = 0; i < sb.length(); i++) {
            char ch = sb.charAt(i);
            // Skip ANSI sequences embedded in the string
            if (ch == '\u001B') {
                while (i < sb.length() && sb.charAt(i) != 'm') i++;
                continue;
            }
            if (ch == ' ' && col <= maxCol) idx = i + 1;
            col++;
        }
        return idx;
    }

    /**
     * Visible display width of a plain text string (ANSI sequences have width 0).
     */
    private static int visibleWidth(String s) {
        int w = 0;
        boolean inEscape = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\u001B') { inEscape = true; continue; }
            if (inEscape) {
                if (ch == 'm') inEscape = false;
                continue;
            }
            w += charWidth(s, i);
        }
        return w;
    }

    /**
     * Returns the display width of the character starting at {@code index}.
     * Wide (East Asian Full-Width) characters have width 2; surrogate pairs have
     * width 2; ordinary ASCII has width 1.
     */
    private static int charWidth(String s, int index) {
        int cp = s.codePointAt(index);
        // CJK Unified Ideographs, Hangul, wide symbols, etc.
        if (cp >= 0x1100 && (
                cp <= 0x115F ||                        // Hangul Jamo
                cp == 0x2329 || cp == 0x232A ||
                (cp >= 0x2E80 && cp <= 0x303E) ||
                (cp >= 0x3040 && cp <= 0x33FF) ||
                (cp >= 0x3400 && cp <= 0x4DBF) ||
                (cp >= 0x4E00 && cp <= 0xA4CF) ||
                (cp >= 0xA960 && cp <= 0xA97F) ||
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

    // -------------------------------------------------------------------------
    // Tokeniser
    // -------------------------------------------------------------------------

    private record Token(String text, boolean isAnsi) {}

    /**
     * Split {@code line} into alternating ANSI / visible tokens.
     */
    private static List<Token> tokenise(String line) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        StringBuilder plain = new StringBuilder();

        while (i < line.length()) {
            char ch = line.charAt(i);
            if (ch == '\u001B') {
                // Flush accumulated plain text
                if (!plain.isEmpty()) {
                    tokens.add(new Token(plain.toString(), false));
                    plain.setLength(0);
                }
                // Consume ANSI sequence
                int start = i;
                i++; // skip ESC
                if (i < line.length()) {
                    char next = line.charAt(i);
                    if (next == '[') {
                        // CSI sequence: ESC [ <params> <final>
                        i++;
                        while (i < line.length()) {
                            char c = line.charAt(i++);
                            if (c >= '@' && c <= '~') break; // final byte
                        }
                    } else if (next == ']') {
                        // OSC sequence: ESC ] ... BEL
                        i++;
                        while (i < line.length() && line.charAt(i) != '\u0007') i++;
                        if (i < line.length()) i++; // skip BEL
                    } else {
                        i++; // two-character escape
                    }
                }
                tokens.add(new Token(line.substring(start, i), true));
            } else {
                plain.append(ch);
                i++;
            }
        }
        if (!plain.isEmpty()) {
            tokens.add(new Token(plain.toString(), false));
        }
        return tokens;
    }
}
