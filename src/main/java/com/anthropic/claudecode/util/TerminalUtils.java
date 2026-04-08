package com.anthropic.claudecode.util;

/**
 * Terminal text rendering utilities.
 * Translated from src/utils/terminal.ts
 *
 * Provides line-based truncation for terminal display, matching the behaviour
 * of renderTruncatedContent() and isOutputLineTruncated() in the TypeScript source.
 */
public final class TerminalUtils {

    /** Maximum lines shown above the fold before truncation hint appears. */
    private static final int MAX_LINES_TO_SHOW = 3;

    /**
     * Account for MessageResponse prefix ("  ⎿ " = 5 chars) + parent width
     * reduction (columns - 5 in tool result rendering).
     */
    private static final int PADDING_TO_PREVENT_OVERFLOW = 10;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Result of wrapping text into terminal-width lines.
     *
     * @param aboveTheFold  the lines to display (at most MAX_LINES_TO_SHOW)
     * @param remainingLines number of wrapped lines that were hidden
     */
    public record WrapResult(String aboveTheFold, int remainingLines) {}

    /**
     * Render content with line-based truncation for terminal display.
     * If the content exceeds the maximum number of lines it truncates and appends
     * a "+N lines" hint (unless {@code suppressExpandHint} is true).
     * Translated from renderTruncatedContent() in terminal.ts
     *
     * @param content           raw content to render
     * @param terminalWidth     terminal width in visible characters
     * @param suppressExpandHint suppress the "ctrl+o to expand" hint
     * @return rendered string, possibly truncated
     */
    public static String renderTruncatedContent(String content,
                                                 int terminalWidth,
                                                 boolean suppressExpandHint) {
        if (content == null) return "";
        String trimmed = content.stripTrailing();
        if (trimmed.isEmpty()) return "";

        int wrapWidth = Math.max(terminalWidth - PADDING_TO_PREVENT_OVERFLOW, 10);

        // Pre-truncate huge inputs to avoid O(n) work on e.g. 64 MB binary dumps.
        int maxChars = MAX_LINES_TO_SHOW * wrapWidth * 4;
        boolean preTruncated = trimmed.length() > maxChars;
        String contentForWrapping = preTruncated ? trimmed.substring(0, maxChars) : trimmed;

        WrapResult wrap = wrapText(contentForWrapping, wrapWidth);

        int estimatedRemaining = preTruncated
            ? Math.max(wrap.remainingLines(),
                (int) Math.ceil((double) trimmed.length() / wrapWidth) - MAX_LINES_TO_SHOW)
            : wrap.remainingLines();

        StringBuilder result = new StringBuilder(wrap.aboveTheFold());
        if (estimatedRemaining > 0) {
            String hint = suppressExpandHint
                ? "… +" + estimatedRemaining + " lines"
                : "… +" + estimatedRemaining + " lines (ctrl+o to expand)";
            if (!result.isEmpty()) result.append("\n");
            result.append(hint);
        }
        return result.toString();
    }

    /** Overload with suppressExpandHint defaulting to false. */
    public static String renderTruncatedContent(String content, int terminalWidth) {
        return renderTruncatedContent(content, terminalWidth, false);
    }

    /**
     * Fast check: would renderTruncatedContent truncate this content?
     * Counts raw newlines only (ignores terminal-width wrapping), so it may
     * return false for a single very long line that wraps past 3 visual rows —
     * acceptable, since the common case is multi-line output.
     * Translated from isOutputLineTruncated() in terminal.ts
     */
    public static boolean isOutputLineTruncated(String content) {
        if (content == null) return false;
        int pos = 0;
        // Need more than MAX_LINES_TO_SHOW newlines.
        // The +1 accounts for wrapText showing an extra line when remainingLines==1.
        for (int i = 0; i <= MAX_LINES_TO_SHOW; i++) {
            pos = content.indexOf('\n', pos);
            if (pos == -1) return false;
            pos++;
        }
        // A trailing newline is a terminator, not a new line — match trimEnd() behaviour.
        return pos < content.length();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Wrap text at {@code wrapWidth} visible characters and return the above-the-fold
     * slice together with the remaining line count.
     * Note: This implementation does not perform ANSI-aware slicing (the TypeScript
     * source uses sliceAnsi); ANSI escape sequences may count as extra characters
     * when computing line breaks. For plain-text output this is fully accurate.
     * Translated from wrapText() in terminal.ts
     */
    static WrapResult wrapText(String text, int wrapWidth) {
        if (text == null || text.isEmpty()) return new WrapResult("", 0);

        String[] lines = text.split("\n", -1);
        java.util.List<String> wrappedLines = new java.util.ArrayList<>();

        for (String line : lines) {
            if (line.length() <= wrapWidth) {
                wrappedLines.add(line.stripTrailing());
            } else {
                int position = 0;
                while (position < line.length()) {
                    int end = Math.min(position + wrapWidth, line.length());
                    wrappedLines.add(line.substring(position, end).stripTrailing());
                    position += wrapWidth;
                }
            }
        }

        int remainingLines = wrappedLines.size() - MAX_LINES_TO_SHOW;

        // If there's only 1 line after the fold, show it directly instead of the hint.
        if (remainingLines == 1) {
            String allContent = String.join("\n",
                wrappedLines.subList(0, Math.min(MAX_LINES_TO_SHOW + 1, wrappedLines.size())))
                .stripTrailing();
            return new WrapResult(allContent, 0);
        }

        // Standard: show at most MAX_LINES_TO_SHOW lines.
        String aboveTheFold = String.join("\n",
            wrappedLines.subList(0, Math.min(MAX_LINES_TO_SHOW, wrappedLines.size())))
            .stripTrailing();

        return new WrapResult(aboveTheFold, Math.max(0, remainingLines));
    }

    private TerminalUtils() {}
}
