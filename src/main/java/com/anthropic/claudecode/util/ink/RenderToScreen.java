package com.anthropic.claudecode.util.ink;

import lombok.extern.slf4j.Slf4j;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Java equivalent of render-to-screen.ts.
 *
 * render-to-screen.ts provides two public functions used for search:
 *
 * 1. {@link #renderToScreen} — render a React element into an isolated
 *    {@link InkScreen.Screen} buffer at a given width. Returns the buffer
 *    and its natural height.
 *
 * 2. {@link #scanPositions} — scan a {@link InkScreen.Screen} buffer for
 *    occurrences of a query string and return their positions.
 *
 * In the Java translation there is no React or Yoga, so {@link #renderToScreen}
 * accepts a pre-rendered {@link InkScreen.Screen} directly. The scan logic is
 * translated faithfully from the TypeScript source.
 *
 * A third helper, {@link #applyPositionedHighlight}, applies the "current match"
 * highlight style to a specific match in the screen buffer.
 */
@Slf4j
public final class RenderToScreen {


    private static final int LOG_EVERY = 20;

    /** Shared timing counters. */
    private static long scanTotal = 0L;
    private static int calls = 0;

    private RenderToScreen() {}

    // -----------------------------------------------------------------------
    // MatchPosition
    // -----------------------------------------------------------------------

    /**
     * Position of a query match within a rendered screen buffer.
     * Row 0 = buffer top; add the message's screen-row offset to get the
     * real screen row.
     *
     * Mirrors the TypeScript {@code MatchPosition} type.
     */
    public record MatchPosition(int row, int col, int len) {}

    // -----------------------------------------------------------------------
    // renderToScreen
    // -----------------------------------------------------------------------

    /**
     * Wrap a pre-rendered screen for use in search. In the TypeScript source
     * this function actually ran React + Yoga; here callers supply a fully-
     * rendered {@link InkScreen.Screen} from their own pipeline.
     *
     * @param screen the pre-rendered cell buffer
     * @param height natural height of the rendered content
     * @return a simple record holding the screen and height
     */
    public static RenderResult renderToScreen(InkScreen.Screen screen, int height) {
        return new RenderResult(screen, height);
    }

    /** Return value of {@link #renderToScreen}. */
    public record RenderResult(InkScreen.Screen screen, int height) {}

    // -----------------------------------------------------------------------
    // scanPositions
    // -----------------------------------------------------------------------

    /**
     * Scan {@code screen} for all non-overlapping occurrences of {@code query}
     * (case-insensitive). Returns positions relative to the buffer (row 0 = top).
     *
     * <p>Mirrors the TypeScript {@code scanPositions()} function including the
     * SpacerTail/SpacerHead/noSelect skip logic and the {@code codeUnitToCell}
     * mapping for multi-unit characters.
     *
     * @param screen the cell buffer to scan
     * @param query  the search query
     * @return list of match positions
     */
    public static List<MatchPosition> scanPositions(InkScreen.Screen screen, String query) {
        if (query == null || query.isEmpty()) return List.of();
        String lq = query.toLowerCase();
        int qlen = lq.length();
        int w = screen.width;
        int h = screen.height;
        byte[] noSelect = screen.noSelect;
        List<MatchPosition> positions = new ArrayList<>();

        long t0 = System.nanoTime();
        for (int row = 0; row < h; row++) {
            int rowOff = row * w;
            StringBuilder text = new StringBuilder();
            List<Integer> colOf = new ArrayList<>();
            List<Integer> codeUnitToCell = new ArrayList<>();

            for (int col = 0; col < w; col++) {
                int idx = rowOff + col;
                InkScreen.Cell cell = InkScreen.cellAtIndex(screen, idx);
                if (cell.width == InkScreen.CellWidth.SPACER_TAIL
                        || cell.width == InkScreen.CellWidth.SPACER_HEAD
                        || (idx < noSelect.length && noSelect[idx] == 1)) {
                    continue;
                }
                String lc = cell.charStr.toLowerCase();
                int cellIdx = colOf.size();
                for (int i = 0; i < lc.length(); i++) {
                    codeUnitToCell.add(cellIdx);
                }
                text.append(lc);
                colOf.add(col);
            }

            String rowText = text.toString();
            int pos = rowText.indexOf(lq);
            while (pos >= 0) {
                int startCi = codeUnitToCell.get(pos);
                int endCi   = codeUnitToCell.get(pos + qlen - 1);
                int col    = colOf.get(startCi);
                int endCol = colOf.get(endCi) + 1;
                positions.add(new MatchPosition(row, col, endCol - col));
                pos = rowText.indexOf(lq, pos + qlen);
            }
        }
        long elapsed = System.nanoTime() - t0;
        scanTotal += elapsed;

        if (++calls % LOG_EVERY == 0) {
            log.debug("scanPositions: {} calls, total scan time={:.1f}ms, avg={:.3f}ms/call",
                      calls, scanTotal / 1_000_000.0, (scanTotal / 1_000_000.0) / calls);
        }
        return positions;
    }

    // -----------------------------------------------------------------------
    // applyPositionedHighlight
    // -----------------------------------------------------------------------

    /**
     * Apply the "current match" highlight style to {@code positions[currentIdx]}
     * in {@code screen}, offset by {@code rowOffset}.
     *
     * @param screen     the cell buffer to mutate
     * @param stylePool  style pool for the "current match" style
     * @param positions  list of all match positions
     * @param rowOffset  screen-row offset to add to position rows
     * @param currentIdx index of the current (highlighted) match
     * @return true if a highlight was applied
     */
    public static boolean applyPositionedHighlight(
            InkScreen.Screen screen,
            InkScreen.StylePool stylePool,
            List<MatchPosition> positions,
            int rowOffset,
            int currentIdx) {
        if (currentIdx < 0 || currentIdx >= positions.size()) return false;
        MatchPosition p = positions.get(currentIdx);
        int row = p.row() + rowOffset;
        if (row < 0 || row >= screen.height) return false;
        int rowOff = row * screen.width;
        for (int col = p.col(); col < p.col() + p.len(); col++) {
            if (col < 0 || col >= screen.width) continue;
            InkScreen.Cell cell = InkScreen.cellAtIndex(screen, rowOff + col);
            InkScreen.setCellStyleId(screen, col, row, stylePool.withCurrentMatch(cell.styleId));
        }
        return true;
    }
}
