package com.anthropic.claudecode.util.ink;

import java.util.Map;

/**
 * Border rendering for Ink box nodes.
 * Translated from render-border.ts.
 *
 * <p>Renders the four sides of a CSS-style box border by writing Unicode
 * box-drawing characters into the {@link InkOutput} buffer.  Supports named
 * border styles (single, double, round, classic, bold, singleDouble,
 * doubleSingle, arrow, dashed), individual per-side visibility toggles,
 * per-side colours, and optional text embedded in the top / bottom border line.
 */
public final class RenderBorder {

    private RenderBorder() {}

    // =========================================================================
    // Border-style definitions  (mirrors cli-boxes + CUSTOM_BORDER_STYLES)
    // =========================================================================

    /**
     * Box-drawing character set for a single border style.
     * Mirrors the {@code BoxStyle} type from cli-boxes.
     */
    public record BoxStyle(
            String top,
            String bottom,
            String left,
            String right,
            String topLeft,
            String topRight,
            String bottomLeft,
            String bottomRight) {}

    /** All built-in named border styles (keys match cli-boxes names + custom). */
    public static final Map<String, BoxStyle> BORDER_STYLES = Map.ofEntries(
            Map.entry("single",       new BoxStyle("─", "─", "│", "│", "┌", "┐", "└", "┘")),
            Map.entry("double",       new BoxStyle("═", "═", "║", "║", "╔", "╗", "╚", "╝")),
            Map.entry("round",        new BoxStyle("─", "─", "│", "│", "╭", "╮", "╰", "╯")),
            Map.entry("bold",         new BoxStyle("━", "━", "┃", "┃", "┏", "┓", "┗", "┛")),
            Map.entry("singleDouble", new BoxStyle("─", "─", "║", "║", "╓", "╖", "╙", "╜")),
            Map.entry("doubleSingle", new BoxStyle("═", "═", "│", "│", "╒", "╕", "╘", "╛")),
            Map.entry("classic",      new BoxStyle("-", "-", "|", "|", "+", "+", "+", "+")),
            Map.entry("arrow",        new BoxStyle("─", "─", "│", "│", "↑", "↑", "↓", "↓")),
            // Custom (from CUSTOM_BORDER_STYLES in render-border.ts)
            Map.entry("dashed",       new BoxStyle("╌", "╌", "╎", "╎", " ", " ", " ", " "))
    );

    // =========================================================================
    // Domain types
    // =========================================================================

    /**
     * Options for embedding text inside a border line.
     * Mirrors {@code BorderTextOptions}.
     */
    public record BorderTextOptions(
            String content,
            Position position,
            Align align,
            int offset) {

        public enum Position { TOP, BOTTOM }
        public enum Align    { START, END, CENTER }

        public BorderTextOptions(String content, Position position, Align align) {
            this(content, position, align, 0);
        }
    }

    /**
     * All border-related style properties for a node.  Callers populate only the
     * fields they need; unset fields use their defaults (no border, no colour).
     */
    public static final class BorderStyle {
        public String borderStyleName;   // named style (key in BORDER_STYLES)
        public BoxStyle borderStyleBox;  // or explicit box (takes precedence)

        public boolean borderTop    = true;
        public boolean borderBottom = true;
        public boolean borderLeft   = true;
        public boolean borderRight  = true;

        public String borderColor;
        public String borderTopColor;
        public String borderBottomColor;
        public String borderLeftColor;
        public String borderRightColor;

        public boolean borderDimColor;
        public boolean borderTopDimColor;
        public boolean borderBottomDimColor;
        public boolean borderLeftDimColor;
        public boolean borderRightDimColor;

        public BorderTextOptions borderText;
    }

    /** Minimal output abstraction – write a string at (x, y). */
    public interface InkOutput {
        void write(float x, float y, String text);
        int getWidth();
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Render the border of a node into {@code output}.
     *
     * @param x      computed left position of the node
     * @param y      computed top position of the node
     * @param width  computed width of the node
     * @param height computed height of the node
     * @param style  border style properties
     * @param output output buffer to write into
     */
    public static void renderBorder(
            float x,
            float y,
            float width,
            float height,
            BorderStyle style,
            InkOutput output) {

        if (style == null) return;
        BoxStyle box = resolveBox(style);
        if (box == null) return;

        int w = (int) Math.floor(width);
        int h = (int) Math.floor(height);

        boolean showTop    = style.borderTop;
        boolean showBottom = style.borderBottom;
        boolean showLeft   = style.borderLeft;
        boolean showRight  = style.borderRight;

        // --- resolve per-side colours ---
        String topColor    = style.borderTopColor    != null ? style.borderTopColor    : style.borderColor;
        String bottomColor = style.borderBottomColor != null ? style.borderBottomColor : style.borderColor;
        String leftColor   = style.borderLeftColor   != null ? style.borderLeftColor   : style.borderColor;
        String rightColor  = style.borderRightColor  != null ? style.borderRightColor  : style.borderColor;

        boolean dimTop    = style.borderTopDimColor    || style.borderDimColor;
        boolean dimBottom = style.borderBottomDimColor || style.borderDimColor;
        boolean dimLeft   = style.borderLeftDimColor   || style.borderDimColor;
        boolean dimRight  = style.borderRightDimColor  || style.borderDimColor;

        int contentWidth = Math.max(0, w - (showLeft ? 1 : 0) - (showRight ? 1 : 0));

        // --- top border ---
        if (showTop) {
            String topLine = (showLeft ? box.topLeft() : "")
                    + box.top().repeat(contentWidth)
                    + (showRight ? box.topRight() : "");

            String topBorder;
            if (style.borderText != null
                    && style.borderText.position() == BorderTextOptions.Position.TOP) {
                String[] parts = embedTextInBorder(
                        topLine, style.borderText.content(), style.borderText.align(),
                        style.borderText.offset(), box.top());
                topBorder = styledLine(parts[0], topColor, dimTop)
                        + parts[1]
                        + styledLine(parts[2], topColor, dimTop);
            } else {
                topBorder = styledLine(topLine, topColor, dimTop);
            }
            output.write(x, y, topBorder);
        }

        // --- vertical borders ---
        int verticalHeight = h - (showTop ? 1 : 0) - (showBottom ? 1 : 0);
        verticalHeight = Math.max(0, verticalHeight);
        int offsetY = showTop ? 1 : 0;

        if (showLeft && verticalHeight > 0) {
            StringBuilder lb = new StringBuilder();
            for (int i = 0; i < verticalHeight; i++) {
                lb.append(styledLine(box.left(), leftColor, dimLeft));
                if (i < verticalHeight - 1) lb.append('\n');
            }
            output.write(x, y + offsetY, lb.toString());
        }

        if (showRight && verticalHeight > 0) {
            StringBuilder rb = new StringBuilder();
            for (int i = 0; i < verticalHeight; i++) {
                rb.append(styledLine(box.right(), rightColor, dimRight));
                if (i < verticalHeight - 1) rb.append('\n');
            }
            output.write(x + w - 1, y + offsetY, rb.toString());
        }

        // --- bottom border ---
        if (showBottom) {
            String bottomLine = (showLeft ? box.bottomLeft() : "")
                    + box.bottom().repeat(contentWidth)
                    + (showRight ? box.bottomRight() : "");

            String bottomBorder;
            if (style.borderText != null
                    && style.borderText.position() == BorderTextOptions.Position.BOTTOM) {
                String[] parts = embedTextInBorder(
                        bottomLine, style.borderText.content(), style.borderText.align(),
                        style.borderText.offset(), box.bottom());
                bottomBorder = styledLine(parts[0], bottomColor, dimBottom)
                        + parts[1]
                        + styledLine(parts[2], bottomColor, dimBottom);
            } else {
                bottomBorder = styledLine(bottomLine, bottomColor, dimBottom);
            }
            output.write(x, y + h - 1, bottomBorder);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Choose the {@link BoxStyle} from a {@link BorderStyle}, preferring an explicit
     * box over a named style.
     */
    private static BoxStyle resolveBox(BorderStyle style) {
        if (style.borderStyleBox != null) return style.borderStyleBox;
        if (style.borderStyleName != null) return BORDER_STYLES.get(style.borderStyleName);
        return null;
    }

    /**
     * Apply ANSI colour / dim formatting to a border line segment.
     * Mirrors {@code styleBorderLine} in render-border.ts.
     *
     * <p>This implementation uses a minimal inline SGR wrapper. A production
     * implementation would delegate to the project's color utility.
     */
    private static String styledLine(String line, String color, boolean dim) {
        if (line == null || line.isEmpty()) return line == null ? "" : line;
        String s = applyAnsiColor(line, color);
        if (dim) s = "\u001B[2m" + s + "\u001B[22m";
        return s;
    }

    /**
     * Wraps {@code text} with a basic ANSI foreground color escape.
     * Only a small subset of named colors is mapped here; production code should
     * use the project's full color utilities.
     */
    private static String applyAnsiColor(String text, String color) {
        if (color == null || color.isBlank()) return text;
        // Map common color names to SGR codes
        String code = switch (color.toLowerCase()) {
            case "black"   -> "30";
            case "red"     -> "31";
            case "green"   -> "32";
            case "yellow"  -> "33";
            case "blue"    -> "34";
            case "magenta" -> "35";
            case "cyan"    -> "36";
            case "white"   -> "37";
            case "gray", "grey" -> "90";
            default -> null;
        };
        if (code == null) return text;
        return "\u001B[" + code + "m" + text + "\u001B[39m";
    }

    /**
     * Embed {@code text} into {@code borderLine} at the requested alignment,
     * returning a three-element array: [before, text, after].
     *
     * <p>Mirrors {@code embedTextInBorder} from render-border.ts.
     */
    private static String[] embedTextInBorder(
            String borderLine,
            String text,
            BorderTextOptions.Align align,
            int offset,
            String borderChar) {

        int textLength  = WrapText.visibleWidth(text);
        int borderLength = borderLine.length(); // single-width border chars

        if (textLength >= borderLength - 2) {
            return new String[]{"", text.substring(0, Math.min(text.length(), borderLength)), ""};
        }

        int position;
        if (align == BorderTextOptions.Align.CENTER) {
            position = (borderLength - textLength) / 2;
        } else if (align == BorderTextOptions.Align.START) {
            position = offset + 1;
        } else { // END
            position = borderLength - textLength - offset - 1;
        }

        position = Math.max(1, Math.min(position, borderLength - textLength - 1));

        String before = borderLine.substring(0, 1) + borderChar.repeat(position - 1);
        String after  = borderChar.repeat(borderLength - position - textLength - 1)
                + borderLine.substring(borderLength - 1);

        return new String[]{before, text, after};
    }
}
