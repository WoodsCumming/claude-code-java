package com.anthropic.claudecode.util.ink;

import lombok.Builder;
import lombok.Value;
import lombok.With;

/**
 * Layout and text styling properties for Ink-style terminal rendering.
 *
 * <p>Corresponds to the {@code Styles} and {@code TextStyles} types in
 * {@code src/ink/styles.ts}. Each property maps directly to a flexbox-based
 * layout attribute, a text-decoration attribute, or a border attribute.
 *
 * <p>Use the {@link #builder()} to construct instances. All properties are
 * optional (nullable) to distinguish "not set" from an explicit {@code 0}
 * (important for applying layout deltas).
 */
@Value
@Builder(toBuilder = true)
@With
public class InkStyles {

    // -------------------------------------------------------------------------
    // Text styles
    // -------------------------------------------------------------------------

    /** Foreground colour. Raw value, e.g. {@code "rgb(255,0,0)"} or {@code "#ff0000"}. */
    String color;

    /** Background colour. */
    String backgroundColor;

    /** Dim (faint) text. */
    Boolean dim;

    /** Bold text. */
    Boolean bold;

    /** Italic text. */
    Boolean italic;

    /** Underline text. */
    Boolean underline;

    /** Strikethrough text. */
    Boolean strikethrough;

    /** Inverse (swap fg/bg) text. */
    Boolean inverse;

    // -------------------------------------------------------------------------
    // Text wrapping
    // -------------------------------------------------------------------------

    /**
     * Text-wrap strategy. One of: {@code "wrap"}, {@code "wrap-trim"},
     * {@code "end"}, {@code "middle"}, {@code "truncate-end"}, {@code "truncate"},
     * {@code "truncate-middle"}, {@code "truncate-start"}.
     */
    String textWrap;

    // -------------------------------------------------------------------------
    // Positioning
    // -------------------------------------------------------------------------

    /** {@code "absolute"} or {@code "relative"}. */
    String position;

    /** Top offset (integer columns or percent string). */
    String top;

    /** Bottom offset. */
    String bottom;

    /** Left offset. */
    String left;

    /** Right offset. */
    String right;

    // -------------------------------------------------------------------------
    // Spacing — gaps
    // -------------------------------------------------------------------------

    /** Gap between columns (shorthand for both column and row gap). */
    Integer gap;

    /** Column gap. */
    Integer columnGap;

    /** Row gap. */
    Integer rowGap;

    // -------------------------------------------------------------------------
    // Spacing — margins
    // -------------------------------------------------------------------------

    Integer margin;
    Integer marginX;
    Integer marginY;
    Integer marginTop;
    Integer marginBottom;
    Integer marginLeft;
    Integer marginRight;

    // -------------------------------------------------------------------------
    // Spacing — padding
    // -------------------------------------------------------------------------

    Integer padding;
    Integer paddingX;
    Integer paddingY;
    Integer paddingTop;
    Integer paddingBottom;
    Integer paddingLeft;
    Integer paddingRight;

    // -------------------------------------------------------------------------
    // Flex layout
    // -------------------------------------------------------------------------

    Double  flexGrow;
    Double  flexShrink;
    /** {@code "row"}, {@code "column"}, {@code "row-reverse"}, {@code "column-reverse"}. */
    String  flexDirection;
    /** Number or percent string (e.g. {@code "50%"}). */
    String  flexBasis;
    /** {@code "nowrap"}, {@code "wrap"}, {@code "wrap-reverse"}. */
    String  flexWrap;

    /** {@code "flex-start"}, {@code "center"}, {@code "flex-end"}, {@code "stretch"}. */
    String  alignItems;
    /** {@code "flex-start"}, {@code "center"}, {@code "flex-end"}, {@code "auto"}. */
    String  alignSelf;
    /** {@code "flex-start"}, {@code "flex-end"}, {@code "space-between"}, etc. */
    String  justifyContent;

    // -------------------------------------------------------------------------
    // Dimensions
    // -------------------------------------------------------------------------

    /** Width in columns or percent string. */
    String  width;
    /** Height in rows or percent string. */
    String  height;
    String  minWidth;
    String  minHeight;
    String  maxWidth;
    String  maxHeight;

    // -------------------------------------------------------------------------
    // Display
    // -------------------------------------------------------------------------

    /** {@code "flex"} or {@code "none"}. */
    String display;

    // -------------------------------------------------------------------------
    // Overflow
    // -------------------------------------------------------------------------

    /** {@code "visible"}, {@code "hidden"}, or {@code "scroll"}. */
    String overflow;
    String overflowX;
    String overflowY;

    // -------------------------------------------------------------------------
    // Borders
    // -------------------------------------------------------------------------

    /**
     * One of: {@code "single"}, {@code "double"}, {@code "round"},
     * {@code "bold"}, {@code "singleDouble"}, {@code "doubleSingle"},
     * {@code "classic"}, etc.  {@code null} means no border.
     */
    String  borderStyle;
    Boolean borderTop;
    Boolean borderBottom;
    Boolean borderLeft;
    Boolean borderRight;
    String  borderColor;
    String  borderTopColor;
    String  borderBottomColor;
    String  borderLeftColor;
    String  borderRightColor;
    Boolean borderDimColor;
    Boolean borderTopDimColor;
    Boolean borderBottomDimColor;
    Boolean borderLeftDimColor;
    Boolean borderRightDimColor;

    // -------------------------------------------------------------------------
    // Misc
    // -------------------------------------------------------------------------

    /** Fill background with spaces (opaque box, no SGR emitted). */
    Boolean opaque;

    /**
     * Exclude this box from text selection. {@code "true"} or
     * {@code "from-left-edge"}.
     */
    String noSelect;

    // -------------------------------------------------------------------------
    // Static factory helpers
    // -------------------------------------------------------------------------

    /** An empty style (all properties null — "not set"). */
    public static InkStyles empty() {
        return InkStyles.builder().build();
    }

    // -------------------------------------------------------------------------
    // Apply helpers (mirrors styles.ts apply* functions)
    // -------------------------------------------------------------------------

    /**
     * Returns the effective overflow for layout purposes (scroll or hidden
     * wins over visible; overflowX / overflowY take precedence over overflow).
     */
    public String effectiveOverflow() {
        String y = overflowY != null ? overflowY : overflow;
        String x = overflowX != null ? overflowX : overflow;
        if ("scroll".equals(y) || "scroll".equals(x)) return "scroll";
        if ("hidden".equals(y) || "hidden".equals(x)) return "hidden";
        if (overflow != null || overflowX != null || overflowY != null) return "visible";
        return null;
    }
}
