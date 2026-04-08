package com.anthropic.claudecode.util;

import java.text.BreakIterator;
import java.util.*;

/**
 * Cursor and text measurement utilities for multi-line text input.
 * Translated from src/utils/Cursor.ts
 *
 * This is a simplified Java port of the TypeScript Cursor / MeasuredText classes.
 * Unicode grapheme cluster handling uses Java's BreakIterator; ANSI-aware string
 * width and terminal wrapping are approximated (external library support may be
 * needed for full fidelity).
 */
public class CursorUtils {

    // -------------------------------------------------------------------------
    // Kill ring (global state, shared across input fields)
    // Translated from module-level kill ring state in Cursor.ts
    // -------------------------------------------------------------------------

    private static final int KILL_RING_MAX_SIZE = 10;
    private static final Deque<String> killRing = new ArrayDeque<>();
    private static boolean lastActionWasKill = false;
    private static boolean lastActionWasYank = false;
    private static int killRingIndex = 0;
    private static int lastYankStart = 0;
    private static int lastYankLength = 0;

    /**
     * Push text onto the kill ring.
     * Translated from pushToKillRing() in Cursor.ts
     */
    public static void pushToKillRing(String text, boolean prepend) {
        if (text == null || text.isEmpty()) return;

        if (lastActionWasKill && !killRing.isEmpty()) {
            String top = killRing.peekFirst();
            killRing.pollFirst();
            killRing.addFirst(prepend ? text + top : top + text);
        } else {
            killRing.addFirst(text);
            while (killRing.size() > KILL_RING_MAX_SIZE) {
                killRing.pollLast();
            }
        }
        lastActionWasKill = true;
        lastActionWasYank = false;
    }

    /** Translated from getLastKill() in Cursor.ts */
    public static String getLastKill() {
        return killRing.isEmpty() ? "" : killRing.peekFirst();
    }

    /** Translated from getKillRingItem() in Cursor.ts */
    public static String getKillRingItem(int index) {
        if (killRing.isEmpty()) return "";
        List<String> list = new ArrayList<>(killRing);
        int normalizedIndex = ((index % list.size()) + list.size()) % list.size();
        return list.get(normalizedIndex);
    }

    /** Translated from getKillRingSize() in Cursor.ts */
    public static int getKillRingSize() {
        return killRing.size();
    }

    /** Translated from clearKillRing() in Cursor.ts */
    public static void clearKillRing() {
        killRing.clear();
        killRingIndex = 0;
        lastActionWasKill = false;
        lastActionWasYank = false;
        lastYankStart = 0;
        lastYankLength = 0;
    }

    /** Translated from resetKillAccumulation() in Cursor.ts */
    public static void resetKillAccumulation() {
        lastActionWasKill = false;
    }

    /** Translated from recordYank() in Cursor.ts */
    public static void recordYank(int start, int length) {
        lastYankStart = start;
        lastYankLength = length;
        lastActionWasYank = true;
        killRingIndex = 0;
    }

    /** Translated from canYankPop() in Cursor.ts */
    public static boolean canYankPop() {
        return lastActionWasYank && killRing.size() > 1;
    }

    /**
     * Result of a yank-pop operation.
     * Translated from the return type of yankPop() in Cursor.ts
     */
    public record YankPopResult(String text, int start, int length) {}

    /**
     * Cycle through the kill ring after a yank.
     * Translated from yankPop() in Cursor.ts
     */
    public static YankPopResult yankPop() {
        if (!lastActionWasYank || killRing.size() <= 1) return null;
        List<String> list = new ArrayList<>(killRing);
        killRingIndex = (killRingIndex + 1) % list.size();
        String text = list.get(killRingIndex);
        return new YankPopResult(text, lastYankStart, lastYankLength);
    }

    /** Translated from updateYankLength() in Cursor.ts */
    public static void updateYankLength(int length) {
        lastYankLength = length;
    }

    /** Translated from resetYankState() in Cursor.ts */
    public static void resetYankState() {
        lastActionWasYank = false;
    }

    // -------------------------------------------------------------------------
    // Vim character classification helpers
    // Translated from isVimWordChar / isVimWhitespace / isVimPunctuation in Cursor.ts
    // -------------------------------------------------------------------------

    /** Translated from isVimWordChar in Cursor.ts */
    public static boolean isVimWordChar(String ch) {
        if (ch == null || ch.isEmpty()) return false;
        int cp = ch.codePointAt(0);
        return Character.isLetterOrDigit(cp) || cp == '_'
                || Character.getType(cp) == Character.NON_SPACING_MARK
                || Character.getType(cp) == Character.COMBINING_SPACING_MARK;
    }

    /** Translated from isVimWhitespace in Cursor.ts */
    public static boolean isVimWhitespace(String ch) {
        if (ch == null || ch.isEmpty()) return false;
        return Character.isWhitespace(ch.codePointAt(0));
    }

    /** Translated from isVimPunctuation in Cursor.ts */
    public static boolean isVimPunctuation(String ch) {
        return ch != null && !ch.isEmpty()
                && !isVimWhitespace(ch) && !isVimWordChar(ch);
    }

    // -------------------------------------------------------------------------
    // Position record
    // -------------------------------------------------------------------------

    /** Line/column position within a measured text.
     * Translated from the Position type in Cursor.ts */
    public record Position(int line, int column) {}

    // -------------------------------------------------------------------------
    // MeasuredText
    // Translated from class MeasuredText in Cursor.ts
    // -------------------------------------------------------------------------

    /**
     * Text with pre-computed grapheme boundaries and optional line-wrapping.
     * Translated from class MeasuredText in Cursor.ts
     */
    public static class MeasuredText {

        public final String text;
        public final int columns;
        private int[] graphemeBoundaries;
        private List<String> wrappedLines;
        private final Map<String, Integer> navigationCache = new HashMap<>();

        public MeasuredText(String text, int columns) {
            this.text = text == null ? "" : normalizeNfc(text);
            this.columns = columns;
        }

        /** NFC normalization via Java String API */
        private static String normalizeNfc(String s) {
            return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC);
        }

        // -- Grapheme boundaries --

        private int[] getGraphemeBoundaries() {
            if (graphemeBoundaries == null) {
                BreakIterator it = BreakIterator.getCharacterInstance();
                it.setText(text);
                List<Integer> bounds = new ArrayList<>();
                int pos = it.first();
                while (pos != BreakIterator.DONE) {
                    bounds.add(pos);
                    pos = it.next();
                }
                graphemeBoundaries = bounds.stream().mapToInt(Integer::intValue).toArray();
            }
            return graphemeBoundaries;
        }

        /**
         * Return the offset of the next grapheme boundary after {@code offset}.
         * Translated from nextOffset() in MeasuredText in Cursor.ts
         */
        public int nextOffset(int offset) {
            return navigationCache.computeIfAbsent("next:" + offset, k -> {
                int[] bounds = getGraphemeBoundaries();
                for (int b : bounds) {
                    if (b > offset) return b;
                }
                return text.length();
            });
        }

        /**
         * Return the offset of the previous grapheme boundary before {@code offset}.
         * Translated from prevOffset() in MeasuredText in Cursor.ts
         */
        public int prevOffset(int offset) {
            if (offset <= 0) return 0;
            return navigationCache.computeIfAbsent("prev:" + offset, k -> {
                int[] bounds = getGraphemeBoundaries();
                int result = 0;
                for (int b : bounds) {
                    if (b < offset) result = b;
                    else break;
                }
                return result;
            });
        }

        /**
         * Snap an arbitrary offset to the nearest grapheme boundary.
         * Translated from snapToGraphemeBoundary() in MeasuredText in Cursor.ts
         */
        public int snapToGraphemeBoundary(int offset) {
            if (offset <= 0) return 0;
            if (offset >= text.length()) return text.length();
            int[] bounds = getGraphemeBoundaries();
            int result = 0;
            for (int b : bounds) {
                if (b <= offset) result = b;
                else break;
            }
            return result;
        }

        // -- Wrapped lines (simple column-based wrapping, no ANSI stripping) --

        private List<String> getWrappedLines() {
            if (wrappedLines == null) {
                wrappedLines = wrapText(text, columns);
            }
            return wrappedLines;
        }

        private static List<String> wrapText(String text, int cols) {
            List<String> result = new ArrayList<>();
            for (String logicalLine : text.split("\n", -1)) {
                if (logicalLine.isEmpty()) {
                    result.add("");
                    continue;
                }
                int start = 0;
                while (start < logicalLine.length()) {
                    int end = Math.min(start + cols, logicalLine.length());
                    result.add(logicalLine.substring(start, end));
                    start = end;
                }
            }
            return result;
        }

        public int getLineCount() {
            return getWrappedLines().size();
        }

        public int getLineLength(int line) {
            List<String> lines = getWrappedLines();
            if (line < 0 || line >= lines.size()) return 0;
            return lines.get(line).length();
        }

        /**
         * Convert a line/column position to a flat character offset.
         * Translated from getOffsetFromPosition() in MeasuredText in Cursor.ts
         */
        public int getOffsetFromPosition(Position position) {
            List<String> lines = getWrappedLines();
            int cumulativeOffset = 0;
            for (int i = 0; i < lines.size(); i++) {
                String lineText = lines.get(i);
                if (i == position.line()) {
                    return cumulativeOffset + Math.min(position.column(), lineText.length());
                }
                // +1 for the newline character between lines
                cumulativeOffset += lineText.length() + 1;
            }
            return text.length();
        }

        /**
         * Convert a flat character offset to a line/column position.
         * Translated from getPositionFromOffset() in MeasuredText in Cursor.ts
         */
        public Position getPositionFromOffset(int offset) {
            List<String> lines = getWrappedLines();
            int cumulativeOffset = 0;
            for (int i = 0; i < lines.size(); i++) {
                String lineText = lines.get(i);
                int lineEnd = cumulativeOffset + lineText.length();
                if (offset <= lineEnd) {
                    return new Position(i, offset - cumulativeOffset);
                }
                cumulativeOffset = lineEnd + 1; // +1 for newline
            }
            // Past the end — clamp to last line
            int lastLine = Math.max(0, lines.size() - 1);
            String lastLineText = lines.isEmpty() ? "" : lines.get(lastLine);
            return new Position(lastLine, lastLineText.length());
        }
    }

    // -------------------------------------------------------------------------
    // Cursor
    // Translated from class Cursor in Cursor.ts
    // -------------------------------------------------------------------------

    /**
     * Cursor within a {@link MeasuredText}, supporting rich navigation and
     * editing operations (Emacs/Vim style).
     * Translated from class Cursor in Cursor.ts
     */
    public static class Cursor {

        public final MeasuredText measuredText;
        public final int offset;
        public final int selection;

        public Cursor(MeasuredText measuredText, int offset, int selection) {
            this.measuredText = measuredText;
            this.selection = selection;
            int clamped = Math.max(0, Math.min(measuredText.text.length(), offset));
            this.offset = clamped;
        }

        public Cursor(MeasuredText measuredText, int offset) {
            this(measuredText, offset, 0);
        }

        /** Translated from Cursor.fromText() in Cursor.ts */
        public static Cursor fromText(String text, int columns, int offset, int selection) {
            return new Cursor(new MeasuredText(text, columns - 1), offset, selection);
        }

        public static Cursor fromText(String text, int columns) {
            return fromText(text, columns, 0, 0);
        }

        public String getText() { return measuredText.text; }

        public boolean isAtStart() { return offset == 0; }
        public boolean isAtEnd() { return offset >= measuredText.text.length(); }

        public Position getPosition() {
            return measuredText.getPositionFromOffset(offset);
        }

        // -- Navigation --

        /** Translated from left() in Cursor.ts */
        public Cursor left() {
            if (offset == 0) return this;
            ImageRef chip = imageRefEndingAt(offset);
            if (chip != null) return new Cursor(measuredText, chip.start());
            return new Cursor(measuredText, measuredText.prevOffset(offset));
        }

        /** Translated from right() in Cursor.ts */
        public Cursor right() {
            if (offset >= measuredText.text.length()) return this;
            ImageRef chip = imageRefStartingAt(offset);
            if (chip != null) return new Cursor(measuredText, chip.end());
            int next = measuredText.nextOffset(offset);
            return new Cursor(measuredText, Math.min(next, measuredText.text.length()));
        }

        /** Translated from up() in Cursor.ts */
        public Cursor up() {
            Position pos = getPosition();
            if (pos.line() == 0) return this;
            List<String> lines = measuredText.getWrappedLines();
            String prevLine = lines.get(pos.line() - 1);
            int col = Math.min(pos.column(), prevLine.length());
            return new Cursor(measuredText,
                    measuredText.getOffsetFromPosition(new Position(pos.line() - 1, col)), 0);
        }

        /** Translated from down() in Cursor.ts */
        public Cursor down() {
            Position pos = getPosition();
            List<String> lines = measuredText.getWrappedLines();
            if (pos.line() >= lines.size() - 1) return this;
            String nextLine = lines.get(pos.line() + 1);
            int col = Math.min(pos.column(), nextLine.length());
            return new Cursor(measuredText,
                    measuredText.getOffsetFromPosition(new Position(pos.line() + 1, col)), 0);
        }

        /** Translated from startOfLine() in Cursor.ts */
        public Cursor startOfLine() {
            Position pos = getPosition();
            if (pos.column() == 0 && pos.line() > 0) {
                return new Cursor(measuredText,
                        measuredText.getOffsetFromPosition(new Position(pos.line() - 1, 0)), 0);
            }
            return new Cursor(measuredText,
                    measuredText.getOffsetFromPosition(new Position(pos.line(), 0)), 0);
        }

        /** Translated from endOfLine() in Cursor.ts */
        public Cursor endOfLine() {
            Position pos = getPosition();
            int lineLen = measuredText.getLineLength(pos.line());
            return new Cursor(measuredText,
                    measuredText.getOffsetFromPosition(new Position(pos.line(), lineLen)), 0);
        }

        /** Translated from startOfLogicalLine() in Cursor.ts */
        public Cursor startOfLogicalLine() {
            int prev = measuredText.text.lastIndexOf('\n', offset - 1);
            return new Cursor(measuredText, prev == -1 ? 0 : prev + 1);
        }

        /** Translated from endOfLogicalLine() in Cursor.ts */
        public Cursor endOfLogicalLine() {
            int next = measuredText.text.indexOf('\n', offset);
            return new Cursor(measuredText, next == -1 ? measuredText.text.length() : next);
        }

        /** Translated from startOfFirstLine() in Cursor.ts */
        public Cursor startOfFirstLine() {
            return new Cursor(measuredText, 0, 0);
        }

        /** Translated from startOfLastLine() in Cursor.ts */
        public Cursor startOfLastLine() {
            int lastNl = measuredText.text.lastIndexOf('\n');
            if (lastNl == -1) return startOfLine();
            return new Cursor(measuredText, lastNl + 1, 0);
        }

        /** Translated from endOfFile() in Cursor.ts */
        public Cursor endOfFile() {
            return new Cursor(measuredText, measuredText.text.length(), 0);
        }

        /** Translated from goToLine() in Cursor.ts */
        public Cursor goToLine(int lineNumber) {
            String[] logicalLines = measuredText.text.split("\n", -1);
            int target = Math.min(Math.max(0, lineNumber - 1), logicalLines.length - 1);
            int off = 0;
            for (int i = 0; i < target; i++) {
                off += logicalLines[i].length() + 1;
            }
            return new Cursor(measuredText, off, 0);
        }

        // -- Word navigation --

        /** Translated from nextWord() in Cursor.ts */
        public Cursor nextWord() {
            if (isAtEnd()) return this;
            String text = measuredText.text;
            BreakIterator wordIt = BreakIterator.getWordInstance();
            wordIt.setText(text);
            int pos = wordIt.following(offset);
            // Skip non-word boundaries
            while (pos != BreakIterator.DONE) {
                int prev = wordIt.previous();
                String seg = text.substring(prev, wordIt.next() == BreakIterator.DONE
                        ? text.length() : wordIt.current());
                wordIt.following(pos);
                if (!seg.isBlank()) return new Cursor(measuredText, pos);
                pos = wordIt.next();
            }
            return new Cursor(measuredText, text.length());
        }

        /** Translated from prevWord() in Cursor.ts */
        public Cursor prevWord() {
            if (isAtStart()) return this;
            String text = measuredText.text;
            BreakIterator wordIt = BreakIterator.getWordInstance();
            wordIt.setText(text);
            int pos = wordIt.preceding(offset);
            while (pos != BreakIterator.DONE) {
                String seg = text.substring(pos, Math.min(wordIt.next(), text.length()));
                wordIt.preceding(pos);
                if (!seg.isBlank()) return new Cursor(measuredText, pos);
                pos = wordIt.previous();
            }
            return new Cursor(measuredText, 0);
        }

        // -- Editing --

        /** Translated from modifyText() in Cursor.ts */
        public Cursor modifyText(Cursor end, String insertString) {
            int startOff = this.offset;
            int endOff = end.offset;
            String newText = measuredText.text.substring(0, startOff)
                    + (insertString != null ? insertString : "")
                    + measuredText.text.substring(endOff);
            String normalized = insertString != null
                    ? MeasuredText.normalizeNfc(insertString) : "";
            return Cursor.fromText(newText, measuredText.columns + 1, startOff + normalized.length(), 0);
        }

        /** Translated from insert() in Cursor.ts */
        public Cursor insert(String text) {
            return modifyText(this, text);
        }

        /** Translated from del() in Cursor.ts */
        public Cursor del() {
            if (isAtEnd()) return this;
            return modifyText(right(), null);
        }

        /** Translated from backspace() in Cursor.ts */
        public Cursor backspace() {
            if (isAtStart()) return this;
            return left().modifyText(this, null);
        }

        /** Result of a kill-to-line operation.
         * Translated from the return type of deleteToLineStart/End in Cursor.ts */
        public record KillResult(Cursor cursor, String killed) {}

        /** Translated from deleteToLineStart() in Cursor.ts */
        public KillResult deleteToLineStart() {
            if (offset > 0 && measuredText.text.charAt(offset - 1) == '\n') {
                return new KillResult(left().modifyText(this, null), "\n");
            }
            Cursor startCursor = startOfLine();
            String killed = measuredText.text.substring(startCursor.offset, offset);
            return new KillResult(startCursor.modifyText(this, null), killed);
        }

        /** Translated from deleteToLineEnd() in Cursor.ts */
        public KillResult deleteToLineEnd() {
            if (offset < measuredText.text.length() && measuredText.text.charAt(offset) == '\n') {
                return new KillResult(modifyText(right(), null), "\n");
            }
            Cursor endCursor = endOfLine();
            String killed = measuredText.text.substring(offset, endCursor.offset);
            return new KillResult(modifyText(endCursor, null), killed);
        }

        /** Translated from deleteWordBefore() in Cursor.ts */
        public KillResult deleteWordBefore() {
            if (isAtStart()) return new KillResult(this, "");
            int target = snapOutOfImageRef(prevWord().offset, false);
            Cursor prev = new Cursor(measuredText, target);
            String killed = measuredText.text.substring(prev.offset, offset);
            return new KillResult(prev.modifyText(this, null), killed);
        }

        /** Translated from deleteWordAfter() in Cursor.ts */
        public Cursor deleteWordAfter() {
            if (isAtEnd()) return this;
            int target = snapOutOfImageRef(nextWord().offset, true);
            return modifyText(new Cursor(measuredText, target), null);
        }

        // -- Image ref helpers --

        private record ImageRef(int start, int end) {}
        private static final java.util.regex.Pattern IMAGE_REF_PAT
                = java.util.regex.Pattern.compile("\\[Image #\\d+\\]");

        /** Translated from imageRefEndingAt() in Cursor.ts */
        private ImageRef imageRefEndingAt(int off) {
            java.util.regex.Matcher m = IMAGE_REF_PAT.matcher(
                    measuredText.text.substring(0, off));
            ImageRef last = null;
            while (m.find()) {
                if (m.end() == off) last = new ImageRef(m.start(), m.end());
            }
            return last;
        }

        /** Translated from imageRefStartingAt() in Cursor.ts */
        private ImageRef imageRefStartingAt(int off) {
            java.util.regex.Matcher m = IMAGE_REF_PAT.matcher(
                    measuredText.text.substring(off));
            if (m.find() && m.start() == 0) {
                return new ImageRef(off, off + m.end());
            }
            return null;
        }

        /** Translated from snapOutOfImageRef() in Cursor.ts */
        public int snapOutOfImageRef(int off, boolean toEnd) {
            java.util.regex.Matcher m = IMAGE_REF_PAT.matcher(measuredText.text);
            while (m.find()) {
                if (off > m.start() && off < m.end()) {
                    return toEnd ? m.end() : m.start();
                }
            }
            return off;
        }

        // -- Comparison --

        /** Translated from equals() in Cursor.ts */
        public boolean equalsTo(Cursor other) {
            return offset == other.offset && measuredText == other.measuredText;
        }
    }

    private CursorUtils() {}
}
