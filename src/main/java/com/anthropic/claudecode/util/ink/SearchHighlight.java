package com.anthropic.claudecode.util.ink;

import java.util.ArrayList;
import java.util.List;

/**
 * Search-highlight overlay for the screen buffer.
 * Translated from searchHighlight.ts.
 *
 * <p>Scans every row of the screen buffer for occurrences of a case-insensitive
 * query string and applies an "inverse" style to each matching cell cluster.
 * Wide characters (CJK, emoji) are handled correctly by building a per-row
 * {@code col → char} map that skips spacer cells.
 *
 * <p>Returns {@code true} if any match was applied (damage gate).
 */
public final class SearchHighlight {

    private SearchHighlight() {}

    // =========================================================================
    // Screen buffer contract
    // =========================================================================

    /**
     * A single rendered cell in the screen buffer.
     */
    public interface ScreenCell {
        /** The grapheme cluster stored in this cell. */
        String getChar();

        /** Display width: {@code NARROW}=1, {@code WIDE}=2, {@code SPACER_TAIL}=0, {@code SPACER_HEAD}=0. */
        CellWidth getWidth();

        /** Style identifier (used with the StylePool). */
        int getStyleId();

        /** Whether this cell is marked as non-selectable (gutter, line-number, etc.). */
        boolean isNoSelect();
    }

    /** Display-width classification of a rendered cell. */
    public enum CellWidth {
        NARROW,
        WIDE,
        SPACER_TAIL,
        SPACER_HEAD
    }

    /**
     * Minimal screen buffer interface.
     */
    public interface Screen {
        int getWidth();
        int getHeight();
        ScreenCell cellAt(int col, int row);
    }

    /**
     * Style-pool that can derive a new style ID with the SGR "inverse" attribute
     * applied (mirrors {@code StylePool.withInverse}).
     */
    @FunctionalInterface
    public interface StylePool {
        int withInverse(int styleId);
    }

    /**
     * Callback invoked to update a cell's style ID in the buffer.
     */
    @FunctionalInterface
    public interface CellStyleSetter {
        void setStyle(int col, int row, int newStyleId);
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Highlight all visible occurrences of {@code query} in {@code screen} by
     * inverting cell styles (SGR 7).
     *
     * <p>Case-insensitive.  Non-overlapping matches (like grep / vim / Ctrl+F).
     *
     * @param screen     screen buffer to scan
     * @param query      search string (empty = no-op, returns {@code false})
     * @param stylePool  style pool used to produce the inverse style
     * @param styleSetter callback to apply the new style ID to a cell
     * @return {@code true} if at least one match was highlighted
     */
    public static boolean applySearchHighlight(
            Screen screen,
            String query,
            StylePool stylePool,
            CellStyleSetter styleSetter) {

        if (query == null || query.isEmpty()) return false;

        String lq     = query.toLowerCase();
        int    qlen   = lq.length();
        int    width  = screen.getWidth();
        int    height = screen.getHeight();
        boolean applied = false;

        for (int row = 0; row < height; row++) {
            // Build per-row text (lowercased) + code-unit → cell-index map.
            // Three skip conditions:
            //   - SpacerTail: 2nd cell of a wide char, no char of its own
            //   - SpacerHead: end-of-line padding when a wide char wraps
            //   - noSelect: gutters / line-numbers – same exclusion as
            //     applySelectionOverlay in selection.ts
            StringBuilder text = new StringBuilder();
            List<Integer> colOf = new ArrayList<>();          // charIdx → col
            List<Integer> codeUnitToCell = new ArrayList<>(); // code-unit idx → charIdx

            for (int col = 0; col < width; col++) {
                ScreenCell cell = screen.cellAt(col, row);
                if (cell == null) continue;
                if (cell.getWidth() == CellWidth.SPACER_TAIL
                        || cell.getWidth() == CellWidth.SPACER_HEAD
                        || cell.isNoSelect()) {
                    continue;
                }
                String lc = cell.getChar().toLowerCase();
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
                applied = true;
                int startCi = codeUnitToCell.get(pos);
                int endCi   = codeUnitToCell.get(pos + qlen - 1);
                for (int ci = startCi; ci <= endCi; ci++) {
                    int col = colOf.get(ci);
                    ScreenCell cell = screen.cellAt(col, row);
                    if (cell != null) {
                        int newStyle = stylePool.withInverse(cell.getStyleId());
                        styleSetter.setStyle(col, row, newStyle);
                    }
                }
                // Non-overlapping advance
                pos = rowText.indexOf(lq, pos + qlen);
            }
        }

        return applied;
    }
}
