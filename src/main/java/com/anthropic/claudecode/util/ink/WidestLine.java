package com.anthropic.claudecode.util.ink;

/**
 * Java equivalent of widest-line.ts.
 *
 * Computes the display width of the widest line in a multi-line string by
 * splitting on {@code '\n'} and calling {@link LineWidthCache#lineWidth} on
 * each segment.
 */
public final class WidestLine {

    private WidestLine() {}

    /**
     * Returns the display width (in terminal columns) of the widest line in
     * {@code string}. Lines are separated by {@code '\n'}.
     *
     * @param string the (possibly multi-line) string to measure
     * @return maximum line width, or {@code 0} for an empty string
     */
    public static int widestLine(String string) {
        if (string == null || string.isEmpty()) return 0;

        int maxWidth = 0;
        int start = 0;

        while (start <= string.length()) {
            int end = string.indexOf('\n', start);
            String line = (end == -1) ? string.substring(start) : string.substring(start, end);

            int w = LineWidthCache.lineWidth(line);
            if (w > maxWidth) maxWidth = w;

            if (end == -1) break;
            start = end + 1;
        }

        return maxWidth;
    }
}
