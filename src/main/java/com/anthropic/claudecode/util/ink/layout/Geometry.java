package com.anthropic.claudecode.util.ink.layout;

/**
 * Geometry primitives for the Ink layout engine.
 * Translated from geometry.ts.
 */
public final class Geometry {

    private Geometry() {}

    // -------------------------------------------------------------------------
    // Records
    // -------------------------------------------------------------------------

    public record Point(int x, int y) {}

    public record Size(int width, int height) {}

    public record Rectangle(int x, int y, int width, int height) {}

    /** Edge insets (padding, margin, border). */
    public record Edges(int top, int right, int bottom, int left) {
        public static final Edges ZERO = new Edges(0, 0, 0, 0);
    }

    // -------------------------------------------------------------------------
    // Edges factory helpers (mirrors the overloaded edges() function in TS)
    // -------------------------------------------------------------------------

    /** Create uniform edges: all four sides equal to {@code all}. */
    public static Edges edges(int all) {
        return new Edges(all, all, all, all);
    }

    /** Create edges with distinct vertical / horizontal values. */
    public static Edges edges(int vertical, int horizontal) {
        return new Edges(vertical, horizontal, vertical, horizontal);
    }

    /** Create edges with explicit top / right / bottom / left values. */
    public static Edges edges(int top, int right, int bottom, int left) {
        return new Edges(top, right, bottom, left);
    }

    // -------------------------------------------------------------------------
    // Edges utilities
    // -------------------------------------------------------------------------

    /** Add two {@link Edges} component-wise. */
    public static Edges addEdges(Edges a, Edges b) {
        return new Edges(
                a.top() + b.top(),
                a.right() + b.right(),
                a.bottom() + b.bottom(),
                a.left() + b.left());
    }

    /**
     * Convert partial (nullable) edge values to a full {@link Edges} with
     * zero defaults – mirrors {@code resolveEdges(partial?: Partial<Edges>)}.
     */
    public static Edges resolveEdges(Integer top, Integer right, Integer bottom, Integer left) {
        return new Edges(
                top != null ? top : 0,
                right != null ? right : 0,
                bottom != null ? bottom : 0,
                left != null ? left : 0);
    }

    // -------------------------------------------------------------------------
    // Rectangle utilities
    // -------------------------------------------------------------------------

    /** Smallest rectangle that contains both {@code a} and {@code b}. */
    public static Rectangle unionRect(Rectangle a, Rectangle b) {
        int minX = Math.min(a.x(), b.x());
        int minY = Math.min(a.y(), b.y());
        int maxX = Math.max(a.x() + a.width(), b.x() + b.width());
        int maxY = Math.max(a.y() + a.height(), b.y() + b.height());
        return new Rectangle(minX, minY, maxX - minX, maxY - minY);
    }

    /** Clamp {@code rect} so it falls entirely within {@code size} bounds. */
    public static Rectangle clampRect(Rectangle rect, Size size) {
        int minX = Math.max(0, rect.x());
        int minY = Math.max(0, rect.y());
        int maxX = Math.min(size.width() - 1, rect.x() + rect.width() - 1);
        int maxY = Math.min(size.height() - 1, rect.y() + rect.height() - 1);
        return new Rectangle(
                minX,
                minY,
                Math.max(0, maxX - minX + 1),
                Math.max(0, maxY - minY + 1));
    }

    /** Return {@code true} if {@code point} is inside {@code size} (exclusive upper bound). */
    public static boolean withinBounds(Size size, Point point) {
        return point.x() >= 0
                && point.y() >= 0
                && point.x() < size.width()
                && point.y() < size.height();
    }

    // -------------------------------------------------------------------------
    // Numeric utilities
    // -------------------------------------------------------------------------

    /** Clamp {@code value} to [{@code min}, {@code max}]. Nulls mean "no bound". */
    public static int clamp(int value, Integer min, Integer max) {
        if (min != null && value < min) return min;
        if (max != null && value > max) return max;
        return value;
    }

    /** Clamp without an explicit minimum. */
    public static int clampMax(int value, int max) {
        return Math.min(value, max);
    }

    /** Clamp without an explicit maximum. */
    public static int clampMin(int value, int min) {
        return Math.max(value, min);
    }
}
