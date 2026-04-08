package com.anthropic.claudecode.util.ink;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Text-selection state and operations for fullscreen mode.
 * Translated from selection.ts.
 *
 * <p>Tracks a linear (non-rectangular) selection in screen-buffer coordinates
 * (0-indexed col/row). Selection is stored as ANCHOR (mouse-down) + FOCUS
 * (current drag position); the rendered highlight normalises to start ≤ end.
 */
public final class InkSelection {

    private InkSelection() {}

    // =========================================================================
    // Screen buffer contracts  (minimal subset used by selection logic)
    // =========================================================================

    public interface Screen {
        int getWidth();
        int getHeight();
        ScreenCell cellAt(int col, int row);
        /** Soft-wrap bit for a row: {@code > 0} means it's a continuation of the previous row. */
        int getSoftWrap(int row);
    }

    public interface ScreenCell {
        String getChar();
        SearchHighlight.CellWidth getWidth();
        boolean isNoSelect();
    }

    // =========================================================================
    // Domain types
    // =========================================================================

    public record Point(int col, int row) {}

    public record AnchorSpan(Point lo, Point hi, SpanKind kind) {
        public enum SpanKind { WORD, LINE }
    }

    public record SelectionBounds(Point start, Point end) {}

    // =========================================================================
    // SelectionState
    // =========================================================================

    /**
     * Mutable selection state – mirrors the TypeScript {@code SelectionState} object.
     * Uses Lombok {@code @Data} for getters/setters.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SelectionState {
        /** Where the mouse-down occurred. {@code null} when no selection. */
        private Point anchor;
        /** Current drag position. {@code null} until the first drag motion. */
        private Point focus;
        /** True between mouse-down and mouse-up. */
        private boolean dragging;
        /** Word/line span from the initial multi-click (null = char mode). */
        private AnchorSpan anchorSpan;

        /** Text from rows that scrolled out above the viewport during drag-to-scroll. */
        private List<String> scrolledOffAbove = new ArrayList<>();
        /** Text from rows that scrolled out below the viewport during drag-to-scroll. */
        private List<String> scrolledOffBelow = new ArrayList<>();

        /** Soft-wrap bits parallel to scrolledOffAbove. */
        private List<Boolean> scrolledOffAboveSW = new ArrayList<>();
        /** Soft-wrap bits parallel to scrolledOffBelow. */
        private List<Boolean> scrolledOffBelowSW = new ArrayList<>();

        /** Pre-clamp anchor row (for virtual-tracking round-trips). */
        private Integer virtualAnchorRow;
        /** Pre-clamp focus row. */
        private Integer virtualFocusRow;
        /** True if the mouse-down had the alt modifier (macOS hint). */
        private boolean lastPressHadAlt;

        public Point getAnchor() { return anchor; }
        public void setAnchor(Point v) { anchor = v; }
        public Point getFocus() { return focus; }
        public void setFocus(Point v) { focus = v; }
        public boolean isDragging() { return dragging; }
        public void setDragging(boolean v) { dragging = v; }
        public AnchorSpan getAnchorSpan() { return anchorSpan; }
        public void setAnchorSpan(AnchorSpan v) { anchorSpan = v; }
        public List<String> getScrolledOffAbove() { return scrolledOffAbove; }
        public void setScrolledOffAbove(List<String> v) { scrolledOffAbove = v; }
        public List<String> getScrolledOffBelow() { return scrolledOffBelow; }
        public void setScrolledOffBelow(List<String> v) { scrolledOffBelow = v; }
        public List<Boolean> getScrolledOffAboveSW() { return scrolledOffAboveSW; }
        public void setScrolledOffAboveSW(List<Boolean> v) { scrolledOffAboveSW = v; }
        public List<Boolean> getScrolledOffBelowSW() { return scrolledOffBelowSW; }
        public void setScrolledOffBelowSW(List<Boolean> v) { scrolledOffBelowSW = v; }
        public Integer getVirtualAnchorRow() { return virtualAnchorRow; }
        public void setVirtualAnchorRow(Integer v) { virtualAnchorRow = v; }
        public Integer getVirtualFocusRow() { return virtualFocusRow; }
        public void setVirtualFocusRow(Integer v) { virtualFocusRow = v; }
        public boolean isLastPressHadAlt() { return lastPressHadAlt; }
        public void setLastPressHadAlt(boolean v) { lastPressHadAlt = v; }
    }

    // =========================================================================
    // Factory
    // =========================================================================

    public static SelectionState createSelectionState() {
        return new SelectionState();
    }

    // =========================================================================
    // Basic state mutations
    // =========================================================================

    public static void startSelection(SelectionState s, int col, int row) {
        s.setAnchor(new Point(col, row));
        s.setFocus(null);
        s.setDragging(true);
        s.setAnchorSpan(null);
        s.getScrolledOffAbove().clear();
        s.getScrolledOffBelow().clear();
        s.getScrolledOffAboveSW().clear();
        s.getScrolledOffBelowSW().clear();
        s.setVirtualAnchorRow(null);
        s.setVirtualFocusRow(null);
        s.setLastPressHadAlt(false);
    }

    public static void updateSelection(SelectionState s, int col, int row) {
        if (!s.isDragging()) return;
        if (s.getFocus() == null && s.getAnchor() != null
                && s.getAnchor().col() == col && s.getAnchor().row() == row) {
            return; // sub-pixel tremor at anchor – ignore
        }
        s.setFocus(new Point(col, row));
    }

    public static void finishSelection(SelectionState s) {
        s.setDragging(false);
    }

    public static void clearSelection(SelectionState s) {
        s.setAnchor(null);
        s.setFocus(null);
        s.setDragging(false);
        s.setAnchorSpan(null);
        s.getScrolledOffAbove().clear();
        s.getScrolledOffBelow().clear();
        s.getScrolledOffAboveSW().clear();
        s.getScrolledOffBelowSW().clear();
        s.setVirtualAnchorRow(null);
        s.setVirtualFocusRow(null);
        s.setLastPressHadAlt(false);
    }

    // =========================================================================
    // Word / line selection
    // =========================================================================

    // iTerm2 default word characters: letters, digits, and /-+\~_.
    private static final Pattern WORD_CHAR = Pattern.compile("[\\p{L}\\p{N}_/\\.\\-+~\\\\]");

    private static int charClass(String c) {
        if (c == null || c.isEmpty() || c.equals(" ")) return 0;
        if (WORD_CHAR.matcher(c).matches()) return 1;
        return 2;
    }

    /**
     * Find the character-class run around (col, row).
     * Returns {@code null} if out of bounds or on a noSelect cell.
     */
    private static int[] wordBoundsAt(Screen screen, int col, int row) {
        if (row < 0 || row >= screen.getHeight()) return null;
        int width = screen.getWidth();

        int c = col;
        if (c > 0) {
            ScreenCell cell = screen.cellAt(c, row);
            if (cell != null && cell.getWidth() == SearchHighlight.CellWidth.SPACER_TAIL) c--;
        }
        if (c < 0 || c >= width) return null;
        ScreenCell startCell = screen.cellAt(c, row);
        if (startCell == null || startCell.isNoSelect()) return null;

        int cls = charClass(startCell.getChar());

        int lo = c;
        while (lo > 0) {
            int prev = lo - 1;
            ScreenCell pc = screen.cellAt(prev, row);
            if (pc == null || pc.isNoSelect()) break;
            if (pc.getWidth() == SearchHighlight.CellWidth.SPACER_TAIL) {
                if (prev == 0) break;
                ScreenCell head = screen.cellAt(prev - 1, row);
                if (head == null || charClass(head.getChar()) != cls) break;
                lo = prev - 1;
                continue;
            }
            if (charClass(pc.getChar()) != cls) break;
            lo = prev;
        }

        int hi = c;
        while (hi < width - 1) {
            int next = hi + 1;
            ScreenCell nc = screen.cellAt(next, row);
            if (nc == null || nc.isNoSelect()) break;
            if (nc.getWidth() == SearchHighlight.CellWidth.SPACER_TAIL) {
                hi = next;
                continue;
            }
            if (charClass(nc.getChar()) != cls) break;
            hi = next;
        }

        return new int[]{lo, hi};
    }

    public static void selectWordAt(SelectionState s, Screen screen, int col, int row) {
        int[] b = wordBoundsAt(screen, col, row);
        if (b == null) return;
        Point lo = new Point(b[0], row);
        Point hi = new Point(b[1], row);
        s.setAnchor(lo);
        s.setFocus(hi);
        s.setDragging(true);
        s.setAnchorSpan(new AnchorSpan(lo, hi, AnchorSpan.SpanKind.WORD));
    }

    public static void selectLineAt(SelectionState s, Screen screen, int row) {
        if (row < 0 || row >= screen.getHeight()) return;
        Point lo = new Point(0, row);
        Point hi = new Point(screen.getWidth() - 1, row);
        s.setAnchor(lo);
        s.setFocus(hi);
        s.setDragging(true);
        s.setAnchorSpan(new AnchorSpan(lo, hi, AnchorSpan.SpanKind.LINE));
    }

    public static void extendSelection(SelectionState s, Screen screen, int col, int row) {
        if (!s.isDragging() || s.getAnchorSpan() == null) return;
        AnchorSpan span = s.getAnchorSpan();
        Point mLo, mHi;
        if (span.kind() == AnchorSpan.SpanKind.WORD) {
            int[] b = wordBoundsAt(screen, col, row);
            mLo = new Point(b != null ? b[0] : col, row);
            mHi = new Point(b != null ? b[1] : col, row);
        } else {
            int r = clamp(row, 0, screen.getHeight() - 1);
            mLo = new Point(0, r);
            mHi = new Point(screen.getWidth() - 1, r);
        }
        if (comparePoints(mHi, span.lo()) < 0) {
            s.setAnchor(span.hi());
            s.setFocus(mLo);
        } else if (comparePoints(mLo, span.hi()) > 0) {
            s.setAnchor(span.lo());
            s.setFocus(mHi);
        } else {
            s.setAnchor(span.lo());
            s.setFocus(span.hi());
        }
    }

    // =========================================================================
    // Keyboard focus movement
    // =========================================================================

    /** Semantic keyboard focus moves. */
    public enum FocusMove {
        LEFT, RIGHT, UP, DOWN, LINE_START, LINE_END
    }

    public static void moveFocus(SelectionState s, int col, int row) {
        if (s.getFocus() == null) return;
        s.setAnchorSpan(null);
        s.setFocus(new Point(col, row));
        s.setVirtualFocusRow(null);
    }

    // =========================================================================
    // Scroll-shift operations
    // =========================================================================

    public static void shiftSelection(
            SelectionState s, int dRow, int minRow, int maxRow, int width) {
        if (s.getAnchor() == null || s.getFocus() == null) return;

        int vAnchor = (s.getVirtualAnchorRow() != null ? s.getVirtualAnchorRow() : s.getAnchor().row()) + dRow;
        int vFocus  = (s.getVirtualFocusRow()  != null ? s.getVirtualFocusRow()  : s.getFocus().row())  + dRow;

        if ((vAnchor < minRow && vFocus < minRow) || (vAnchor > maxRow && vFocus > maxRow)) {
            clearSelection(s);
            return;
        }

        int oldMin = Math.min(
                s.getVirtualAnchorRow() != null ? s.getVirtualAnchorRow() : s.getAnchor().row(),
                s.getVirtualFocusRow()  != null ? s.getVirtualFocusRow()  : s.getFocus().row());
        int oldMax = Math.max(
                s.getVirtualAnchorRow() != null ? s.getVirtualAnchorRow() : s.getAnchor().row(),
                s.getVirtualFocusRow()  != null ? s.getVirtualFocusRow()  : s.getFocus().row());

        int oldAboveDebt = Math.max(0, minRow - oldMin);
        int oldBelowDebt = Math.max(0, oldMax - maxRow);
        int newAboveDebt = Math.max(0, minRow - Math.min(vAnchor, vFocus));
        int newBelowDebt = Math.max(0, Math.max(vAnchor, vFocus) - maxRow);

        if (newAboveDebt < oldAboveDebt) {
            int drop = oldAboveDebt - newAboveDebt;
            trimEnd(s.getScrolledOffAbove(), drop);
            trimEnd(s.getScrolledOffAboveSW(), drop);
        }
        if (newBelowDebt < oldBelowDebt) {
            int drop = oldBelowDebt - newBelowDebt;
            trimFront(s.getScrolledOffBelow(), drop);
            trimFront(s.getScrolledOffBelowSW(), drop);
        }
        if (s.getScrolledOffAbove().size() > newAboveDebt) {
            keepEnd(s.getScrolledOffAbove(), newAboveDebt);
            keepEnd(s.getScrolledOffAboveSW(), newAboveDebt);
        }
        if (s.getScrolledOffBelow().size() > newBelowDebt) {
            keepFront(s.getScrolledOffBelow(), newBelowDebt);
            keepFront(s.getScrolledOffBelowSW(), newBelowDebt);
        }

        s.setAnchor(shiftPoint(s.getAnchor(), vAnchor, minRow, maxRow, width, dRow));
        s.setFocus(shiftPoint(s.getFocus(), vFocus, minRow, maxRow, width, dRow));
        s.setVirtualAnchorRow(vAnchor < minRow || vAnchor > maxRow ? vAnchor : null);
        s.setVirtualFocusRow(vFocus < minRow || vFocus > maxRow ? vFocus : null);

        if (s.getAnchorSpan() != null) {
            s.setAnchorSpan(new AnchorSpan(
                    shiftSpanPoint(s.getAnchorSpan().lo(), dRow, minRow, maxRow, width),
                    shiftSpanPoint(s.getAnchorSpan().hi(), dRow, minRow, maxRow, width),
                    s.getAnchorSpan().kind()));
        }
    }

    public static void shiftAnchor(SelectionState s, int dRow, int minRow, int maxRow) {
        if (s.getAnchor() == null) return;
        int raw = (s.getVirtualAnchorRow() != null ? s.getVirtualAnchorRow() : s.getAnchor().row()) + dRow;
        s.setAnchor(new Point(s.getAnchor().col(), clamp(raw, minRow, maxRow)));
        s.setVirtualAnchorRow(raw < minRow || raw > maxRow ? raw : null);
        if (s.getAnchorSpan() != null) {
            s.setAnchorSpan(new AnchorSpan(
                    shiftSpanPoint(s.getAnchorSpan().lo(), dRow, minRow, maxRow, Integer.MAX_VALUE),
                    shiftSpanPoint(s.getAnchorSpan().hi(), dRow, minRow, maxRow, Integer.MAX_VALUE),
                    s.getAnchorSpan().kind()));
        }
    }

    /** Returns {@code true} if the selection was cleared (scrolled entirely off top). */
    public static boolean shiftSelectionForFollow(
            SelectionState s, int dRow, int minRow, int maxRow) {
        if (s.getAnchor() == null) return false;
        int rawAnchor = (s.getVirtualAnchorRow() != null ? s.getVirtualAnchorRow() : s.getAnchor().row()) + dRow;
        Integer rawFocus = s.getFocus() != null
                ? (s.getVirtualFocusRow() != null ? s.getVirtualFocusRow() : s.getFocus().row()) + dRow
                : null;
        if (rawAnchor < minRow && rawFocus != null && rawFocus < minRow) {
            clearSelection(s);
            return true;
        }
        s.setAnchor(new Point(s.getAnchor().col(), clamp(rawAnchor, minRow, maxRow)));
        if (s.getFocus() != null && rawFocus != null) {
            s.setFocus(new Point(s.getFocus().col(), clamp(rawFocus, minRow, maxRow)));
        }
        s.setVirtualAnchorRow(rawAnchor < minRow || rawAnchor > maxRow ? rawAnchor : null);
        s.setVirtualFocusRow(rawFocus != null && (rawFocus < minRow || rawFocus > maxRow) ? rawFocus : null);
        if (s.getAnchorSpan() != null) {
            s.setAnchorSpan(new AnchorSpan(
                    shiftSpanPoint(s.getAnchorSpan().lo(), dRow, minRow, maxRow, Integer.MAX_VALUE),
                    shiftSpanPoint(s.getAnchorSpan().hi(), dRow, minRow, maxRow, Integer.MAX_VALUE),
                    s.getAnchorSpan().kind()));
        }
        return false;
    }

    // =========================================================================
    // Query helpers
    // =========================================================================

    public static boolean hasSelection(SelectionState s) {
        return s.getAnchor() != null && s.getFocus() != null;
    }

    public static SelectionBounds selectionBounds(SelectionState s) {
        if (s.getAnchor() == null || s.getFocus() == null) return null;
        return comparePoints(s.getAnchor(), s.getFocus()) <= 0
                ? new SelectionBounds(s.getAnchor(), s.getFocus())
                : new SelectionBounds(s.getFocus(), s.getAnchor());
    }

    public static boolean isCellSelected(SelectionState s, int col, int row) {
        SelectionBounds b = selectionBounds(s);
        if (b == null) return false;
        if (row < b.start().row() || row > b.end().row()) return false;
        if (row == b.start().row() && col < b.start().col()) return false;
        if (row == b.end().row() && col > b.end().col()) return false;
        return true;
    }

    // =========================================================================
    // Text extraction
    // =========================================================================

    public static String getSelectedText(SelectionState s, Screen screen) {
        SelectionBounds b = selectionBounds(s);
        if (b == null) return "";
        List<String> lines = new ArrayList<>();

        for (int i = 0; i < s.getScrolledOffAbove().size(); i++) {
            joinRow(lines, s.getScrolledOffAbove().get(i), s.getScrolledOffAboveSW().get(i));
        }
        for (int row = b.start().row(); row <= b.end().row(); row++) {
            int colStart = row == b.start().row() ? b.start().col() : 0;
            int colEnd   = row == b.end().row()   ? b.end().col()   : screen.getWidth() - 1;
            joinRow(lines, extractRowText(screen, row, colStart, colEnd),
                    screen.getSoftWrap(row) > 0);
        }
        for (int i = 0; i < s.getScrolledOffBelow().size(); i++) {
            joinRow(lines, s.getScrolledOffBelow().get(i), s.getScrolledOffBelowSW().get(i));
        }
        return String.join("\n", lines);
    }

    public static void captureScrolledRows(
            SelectionState s, Screen screen, int firstRow, int lastRow, String side) {
        SelectionBounds b = selectionBounds(s);
        if (b == null || firstRow > lastRow) return;
        int lo = Math.max(firstRow, b.start().row());
        int hi = Math.min(lastRow,  b.end().row());
        if (lo > hi) return;

        int width = screen.getWidth();
        List<String> captured  = new ArrayList<>();
        List<Boolean> capturedSW = new ArrayList<>();
        for (int row = lo; row <= hi; row++) {
            int colStart = row == b.start().row() ? b.start().col() : 0;
            int colEnd   = row == b.end().row()   ? b.end().col()   : width - 1;
            captured.add(extractRowText(screen, row, colStart, colEnd));
            capturedSW.add(screen.getSoftWrap(row) > 0);
        }

        if ("above".equals(side)) {
            s.getScrolledOffAbove().addAll(captured);
            s.getScrolledOffAboveSW().addAll(capturedSW);
            if (s.getAnchor() != null && s.getAnchor().row() == b.start().row() && lo == b.start().row()) {
                s.setAnchor(new Point(0, s.getAnchor().row()));
                if (s.getAnchorSpan() != null) {
                    s.setAnchorSpan(new AnchorSpan(
                            new Point(0, s.getAnchorSpan().lo().row()),
                            new Point(width - 1, s.getAnchorSpan().hi().row()),
                            s.getAnchorSpan().kind()));
                }
            }
        } else { // below
            for (int i = captured.size() - 1; i >= 0; i--) {
                s.getScrolledOffBelow().add(0, captured.get(i));
                s.getScrolledOffBelowSW().add(0, capturedSW.get(i));
            }
            if (s.getAnchor() != null && s.getAnchor().row() == b.end().row() && hi == b.end().row()) {
                s.setAnchor(new Point(width - 1, s.getAnchor().row()));
                if (s.getAnchorSpan() != null) {
                    s.setAnchorSpan(new AnchorSpan(
                            new Point(0, s.getAnchorSpan().lo().row()),
                            new Point(width - 1, s.getAnchorSpan().hi().row()),
                            s.getAnchorSpan().kind()));
                }
            }
        }
    }

    // =========================================================================
    // Plain-text URL detection
    // =========================================================================

    private static boolean isUrlChar(char c) {
        return c >= 0x21 && c <= 0x7e
                && c != '<' && c != '>' && c != '"' && c != '\'' && c != '`' && c != ' ';
    }

    public static String findPlainTextUrlAt(Screen screen, int col, int row) {
        if (row < 0 || row >= screen.getHeight()) return null;
        int width = screen.getWidth();

        int c = col;
        if (c > 0) {
            ScreenCell cell = screen.cellAt(c, row);
            if (cell != null && cell.getWidth() == SearchHighlight.CellWidth.SPACER_TAIL) c--;
        }
        if (c < 0 || c >= width) return null;
        ScreenCell startCell = screen.cellAt(c, row);
        if (startCell == null || startCell.isNoSelect()) return null;
        if (startCell.getChar().length() != 1 || !isUrlChar(startCell.getChar().charAt(0))) return null;

        int lo = c, hi = c;
        while (lo > 0) {
            ScreenCell pc = screen.cellAt(lo - 1, row);
            if (pc == null || pc.isNoSelect() || pc.getChar().length() != 1 || !isUrlChar(pc.getChar().charAt(0))) break;
            lo--;
        }
        while (hi < width - 1) {
            ScreenCell nc = screen.cellAt(hi + 1, row);
            if (nc == null || nc.isNoSelect() || nc.getChar().length() != 1 || !isUrlChar(nc.getChar().charAt(0))) break;
            hi++;
        }

        StringBuilder token = new StringBuilder();
        for (int i = lo; i <= hi; i++) token.append(screen.cellAt(i, row).getChar());
        String t = token.toString();

        int clickIdx = c - lo;
        Pattern schemeRe = Pattern.compile("(?:https?|file)://");
        java.util.regex.Matcher m = schemeRe.matcher(t);
        int urlStart = -1, urlEnd = t.length();
        while (m.find()) {
            if (m.start() > clickIdx) { urlEnd = m.start(); break; }
            urlStart = m.start();
        }
        if (urlStart < 0) return null;
        String url = t.substring(urlStart, urlEnd);

        // Strip trailing sentence / unbalanced bracket punctuation
        while (!url.isEmpty()) {
            char last = url.charAt(url.length() - 1);
            if (".,;:!?".indexOf(last) >= 0) { url = url.substring(0, url.length() - 1); continue; }
            char opener = switch (last) { case ')' -> '('; case ']' -> '['; case '}' -> '{'; default -> 0; };
            if (opener == 0) break;
            long opens = url.chars().filter(ch -> ch == opener).count();
            long closes = url.chars().filter(ch -> ch == last).count();
            if (closes > opens) url = url.substring(0, url.length() - 1); else break;
        }

        if (clickIdx >= urlStart + url.length()) return null;
        return url;
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private static int comparePoints(Point a, Point b) {
        if (a.row() != b.row()) return a.row() < b.row() ? -1 : 1;
        if (a.col() != b.col()) return a.col() < b.col() ? -1 : 1;
        return 0;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Point shiftPoint(Point p, int vRow, int minRow, int maxRow, int width, int dRow) {
        if (vRow < minRow) return new Point(0, minRow);
        if (vRow > maxRow) return new Point(width - 1, maxRow);
        return new Point(p.col(), vRow);
    }

    private static Point shiftSpanPoint(Point p, int dRow, int minRow, int maxRow, int width) {
        int r = p.row() + dRow;
        if (r < minRow) return new Point(0, minRow);
        if (r > maxRow) return new Point(Math.min(width - 1, p.col()), maxRow);
        return new Point(p.col(), r);
    }

    private static void joinRow(List<String> lines, String text, Boolean sw) {
        if (Boolean.TRUE.equals(sw) && !lines.isEmpty()) {
            lines.set(lines.size() - 1, lines.get(lines.size() - 1) + text);
        } else {
            lines.add(text);
        }
    }

    private static String extractRowText(Screen screen, int row, int colStart, int colEnd) {
        int width = screen.getWidth();
        int contentEnd = row + 1 < screen.getHeight() ? screen.getSoftWrap(row + 1) : 0;
        int lastCol = contentEnd > 0 ? Math.min(colEnd, contentEnd - 1) : colEnd;
        StringBuilder line = new StringBuilder();
        for (int col = colStart; col <= lastCol; col++) {
            ScreenCell cell = screen.cellAt(col, row);
            if (cell == null || cell.isNoSelect()) continue;
            if (cell.getWidth() == SearchHighlight.CellWidth.SPACER_TAIL
                    || cell.getWidth() == SearchHighlight.CellWidth.SPACER_HEAD) continue;
            line.append(cell.getChar());
        }
        String result = line.toString();
        return contentEnd > 0 ? result : result.stripTrailing();
    }

    private static <T> void trimEnd(List<T> list, int count) {
        int newSize = Math.max(0, list.size() - count);
        list.subList(newSize, list.size()).clear();
    }

    private static <T> void trimFront(List<T> list, int count) {
        int remove = Math.min(count, list.size());
        list.subList(0, remove).clear();
    }

    private static <T> void keepEnd(List<T> list, int count) {
        if (count <= 0) { list.clear(); return; }
        if (list.size() > count) list.subList(0, list.size() - count).clear();
    }

    private static <T> void keepFront(List<T> list, int count) {
        if (count <= 0) { list.clear(); return; }
        if (list.size() > count) list.subList(count, list.size()).clear();
    }
}
