package com.anthropic.claudecode.util;

import java.util.List;

/**
 * Horizontal scroll window calculation for tab-bar style UI components.
 * Translated from src/utils/horizontalScroll.ts
 */
public class HorizontalScrollUtils {

    // =========================================================================
    // Types
    // =========================================================================

    /**
     * Visible window of items within a horizontal scroll container.
     * Translated from HorizontalScrollWindow in horizontalScroll.ts
     */
    public record HorizontalScrollWindow(
        int startIndex,
        int endIndex,
        boolean showLeftArrow,
        boolean showRightArrow
    ) {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Calculate the visible window of items that fit within available width,
     * ensuring the selected item is always visible. Uses edge-based scrolling:
     * the window only scrolls when the selected item would be outside the visible
     * range, and positions the selected item at the edge (not centred).
     *
     * Translated from calculateHorizontalScrollWindow() in horizontalScroll.ts
     *
     * @param itemWidths            Width of each item (each should include separator if applicable)
     * @param availableWidth        Total available width for items
     * @param arrowWidth            Width of a scroll indicator arrow (including space)
     * @param selectedIdx           Index of the selected item (must stay visible)
     * @param firstItemHasSeparator Whether the first item's width includes a separator to ignore
     * @return Visible window bounds and whether to show scroll arrows
     */
    public static HorizontalScrollWindow calculateHorizontalScrollWindow(
            List<Integer> itemWidths,
            int availableWidth,
            int arrowWidth,
            int selectedIdx,
            boolean firstItemHasSeparator) {

        int totalItems = itemWidths.size();

        if (totalItems == 0) {
            return new HorizontalScrollWindow(0, 0, false, false);
        }

        // Clamp selectedIdx to valid range
        int clampedSelected = Math.max(0, Math.min(selectedIdx, totalItems - 1));

        // If all items fit, show them all
        int totalWidth = itemWidths.stream().mapToInt(Integer::intValue).sum();
        if (totalWidth <= availableWidth) {
            return new HorizontalScrollWindow(0, totalItems, false, false);
        }

        // Pre-compute cumulative widths for O(1) range width queries
        int[] cumulative = new int[totalItems + 1];
        for (int i = 0; i < totalItems; i++) {
            cumulative[i + 1] = cumulative[i] + itemWidths.get(i);
        }

        // Width of items in range [start, end)
        // When starting after index 0 and firstItemHasSeparator is true, subtract 1
        // because the leading separator is not rendered on the first visible item.

        // Effective available width depends on whether arrows are visible
        // startIndex = 0, initially expand as far right as possible
        int startIndex = 0;
        int endIndex = 1;

        while (endIndex < totalItems
            && rangeWidth(cumulative, startIndex, endIndex + 1, firstItemHasSeparator)
               <= effectiveWidth(availableWidth, arrowWidth, startIndex, endIndex + 1, totalItems)) {
            endIndex++;
        }

        // If selected is already visible, done
        if (clampedSelected >= startIndex && clampedSelected < endIndex) {
            return new HorizontalScrollWindow(
                startIndex, endIndex, startIndex > 0, endIndex < totalItems);
        }

        if (clampedSelected >= endIndex) {
            // Selected is to the right — put it at the right edge, expand left
            endIndex = clampedSelected + 1;
            startIndex = clampedSelected;

            while (startIndex > 0
                && rangeWidth(cumulative, startIndex - 1, endIndex, firstItemHasSeparator)
                   <= effectiveWidth(availableWidth, arrowWidth, startIndex - 1, endIndex, totalItems)) {
                startIndex--;
            }
        } else {
            // Selected is to the left — put it at the left edge, expand right
            startIndex = clampedSelected;
            endIndex = clampedSelected + 1;

            while (endIndex < totalItems
                && rangeWidth(cumulative, startIndex, endIndex + 1, firstItemHasSeparator)
                   <= effectiveWidth(availableWidth, arrowWidth, startIndex, endIndex + 1, totalItems)) {
                endIndex++;
            }
        }

        return new HorizontalScrollWindow(
            startIndex, endIndex, startIndex > 0, endIndex < totalItems);
    }

    /**
     * Convenience overload with firstItemHasSeparator defaulting to true.
     * Translated from the default parameter value in horizontalScroll.ts
     */
    public static HorizontalScrollWindow calculateHorizontalScrollWindow(
            List<Integer> itemWidths,
            int availableWidth,
            int arrowWidth,
            int selectedIdx) {
        return calculateHorizontalScrollWindow(
            itemWidths, availableWidth, arrowWidth, selectedIdx, true);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Width of items in the range [start, end).
     * Subtracts 1 when start > 0 and firstItemHasSeparator is true
     * (leading separator is not rendered on the first visible item).
     */
    private static int rangeWidth(
            int[] cumulative, int start, int end, boolean firstItemHasSeparator) {
        int base = cumulative[end] - cumulative[start];
        if (firstItemHasSeparator && start > 0) return base - 1;
        return base;
    }

    /**
     * Effective available width after subtracting arrow widths.
     */
    private static int effectiveWidth(
            int availableWidth, int arrowWidth,
            int start, int end, int totalItems) {
        int width = availableWidth;
        if (start > 0) width -= arrowWidth;       // left arrow
        if (end < totalItems) width -= arrowWidth; // right arrow
        return width;
    }

    private HorizontalScrollUtils() {}
}
