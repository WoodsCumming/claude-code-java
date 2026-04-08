package com.anthropic.claudecode.util.ink.layout;

/**
 * Yoga layout engine enumerations.
 * Translated from src/native-ts/yoga-layout/enums.ts
 *
 * Values match upstream yoga-layout/src/generated/YGEnums.ts exactly so
 * callers don't need to change when switching between JS and Java.
 */
public final class YogaEnums {

    private YogaEnums() {}

    // -------------------------------------------------------------------------
    // Align
    // -------------------------------------------------------------------------

    public enum Align {
        AUTO(0), FLEX_START(1), CENTER(2), FLEX_END(3),
        STRETCH(4), BASELINE(5), SPACE_BETWEEN(6), SPACE_AROUND(7), SPACE_EVENLY(8);

        public final int value;
        Align(int value) { this.value = value; }

        public static Align fromValue(int v) {
            for (Align a : values()) if (a.value == v) return a;
            throw new IllegalArgumentException("Unknown Align value: " + v);
        }
    }

    // -------------------------------------------------------------------------
    // BoxSizing
    // -------------------------------------------------------------------------

    public enum BoxSizing {
        BORDER_BOX(0), CONTENT_BOX(1);

        public final int value;
        BoxSizing(int value) { this.value = value; }

        public static BoxSizing fromValue(int v) {
            for (BoxSizing b : values()) if (b.value == v) return b;
            throw new IllegalArgumentException("Unknown BoxSizing value: " + v);
        }
    }

    // -------------------------------------------------------------------------
    // Dimension
    // -------------------------------------------------------------------------

    public enum Dimension {
        WIDTH(0), HEIGHT(1);

        public final int value;
        Dimension(int value) { this.value = value; }

        public static Dimension fromValue(int v) {
            for (Dimension d : values()) if (d.value == v) return d;
            throw new IllegalArgumentException("Unknown Dimension value: " + v);
        }
    }

    // -------------------------------------------------------------------------
    // Direction
    // -------------------------------------------------------------------------

    public enum Direction {
        INHERIT(0), LTR(1), RTL(2);

        public final int value;
        Direction(int value) { this.value = value; }

        public static Direction fromValue(int v) {
            for (Direction d : values()) if (d.value == v) return d;
            throw new IllegalArgumentException("Unknown Direction value: " + v);
        }
    }

    // -------------------------------------------------------------------------
    // Display
    // -------------------------------------------------------------------------

    public enum Display {
        FLEX(0), NONE(1), CONTENTS(2);

        public final int value;
        Display(int value) { this.value = value; }

        public static Display fromValue(int v) {
            for (Display d : values()) if (d.value == v) return d;
            throw new IllegalArgumentException("Unknown Display value: " + v);
        }
    }

    // -------------------------------------------------------------------------
    // Edge
    // -------------------------------------------------------------------------

    public enum Edge {
        LEFT(0), TOP(1), RIGHT(2), BOTTOM(3),
        START(4), END(5), HORIZONTAL(6), VERTICAL(7), ALL(8);

        public final int value;
        Edge(int value) { this.value = value; }

        public static Edge fromValue(int v) {
            for (Edge e : values()) if (e.value == v) return e;
            throw new IllegalArgumentException("Unknown Edge value: " + v);
        }
    }

    // -------------------------------------------------------------------------
    // Errata
    // -------------------------------------------------------------------------

    public enum Errata {
        NONE(0),
        STRETCH_FLEX_BASIS(1),
        ABSOLUTE_POSITION_WITHOUT_INSETS_EXCLUDES_PADDING(2),
        ABSOLUTE_PERCENT_AGAINST_INNER_SIZE(4),
        ALL(2147483647),
        CLASSIC(2147483646);

        public final int value;
        Errata(int value) { this.value = value; }

        public static Errata fromValue(int v) {
            for (Errata e : values()) if (e.value == v) return e;
            throw new IllegalArgumentException("Unknown Errata value: " + v);
        }
    }

    // -------------------------------------------------------------------------
    // ExperimentalFeature
    // -------------------------------------------------------------------------

    public enum ExperimentalFeature {
        WEB_FLEX_BASIS(0);

        public final int value;
        ExperimentalFeature(int value) { this.value = value; }

        public static ExperimentalFeature fromValue(int v) {
            for (ExperimentalFeature f : values()) if (f.value == v) return f;
            throw new IllegalArgumentException("Unknown ExperimentalFeature value: " + v);
        }
    }

    // -------------------------------------------------------------------------
    // FlexDirection
    // -------------------------------------------------------------------------

    public enum FlexDirection {
        COLUMN(0), COLUMN_REVERSE(1), ROW(2), ROW_REVERSE(3);

        public final int value;
        FlexDirection(int value) { this.value = value; }

        public static FlexDirection fromValue(int v) {
            for (FlexDirection f : values()) if (f.value == v) return f;
            throw new IllegalArgumentException("Unknown FlexDirection value: " + v);
        }
    }

    // -------------------------------------------------------------------------
    // Gutter
    // -------------------------------------------------------------------------

    public enum Gutter {
        COLUMN(0), ROW(1), ALL(2);

        public final int value;
        Gutter(int value) { this.value = value; }

        public static Gutter fromValue(int v) {
            for (Gutter g : values()) if (g.value == v) return g;
            throw new IllegalArgumentException("Unknown Gutter value: " + v);
        }
    }

    // -------------------------------------------------------------------------
    // Justify
    // -------------------------------------------------------------------------

    public enum Justify {
        FLEX_START(0), CENTER(1), FLEX_END(2),
        SPACE_BETWEEN(3), SPACE_AROUND(4), SPACE_EVENLY(5);

        public final int value;
        Justify(int value) { this.value = value; }

        public static Justify fromValue(int v) {
            for (Justify j : values()) if (j.value == v) return j;
            throw new IllegalArgumentException("Unknown Justify value: " + v);
        }
    }

    // -------------------------------------------------------------------------
    // MeasureMode
    // -------------------------------------------------------------------------

    public enum MeasureMode {
        UNDEFINED(0), EXACTLY(1), AT_MOST(2);

        public final int value;
        MeasureMode(int value) { this.value = value; }

        public static MeasureMode fromValue(int v) {
            for (MeasureMode m : values()) if (m.value == v) return m;
            throw new IllegalArgumentException("Unknown MeasureMode value: " + v);
        }
    }

    // -------------------------------------------------------------------------
    // Overflow
    // -------------------------------------------------------------------------

    public enum Overflow {
        VISIBLE(0), HIDDEN(1), SCROLL(2);

        public final int value;
        Overflow(int value) { this.value = value; }

        public static Overflow fromValue(int v) {
            for (Overflow o : values()) if (o.value == v) return o;
            throw new IllegalArgumentException("Unknown Overflow value: " + v);
        }
    }

    // -------------------------------------------------------------------------
    // PositionType
    // -------------------------------------------------------------------------

    public enum PositionType {
        STATIC(0), RELATIVE(1), ABSOLUTE(2);

        public final int value;
        PositionType(int value) { this.value = value; }

        public static PositionType fromValue(int v) {
            for (PositionType p : values()) if (p.value == v) return p;
            throw new IllegalArgumentException("Unknown PositionType value: " + v);
        }
    }

    // -------------------------------------------------------------------------
    // Unit
    // -------------------------------------------------------------------------

    public enum Unit {
        UNDEFINED(0), POINT(1), PERCENT(2), AUTO(3);

        public final int value;
        Unit(int value) { this.value = value; }

        public static Unit fromValue(int v) {
            for (Unit u : values()) if (u.value == v) return u;
            throw new IllegalArgumentException("Unknown Unit value: " + v);
        }
    }

    // -------------------------------------------------------------------------
    // Wrap
    // -------------------------------------------------------------------------

    public enum Wrap {
        NO_WRAP(0), WRAP(1), WRAP_REVERSE(2);

        public final int value;
        Wrap(int value) { this.value = value; }

        public static Wrap fromValue(int v) {
            for (Wrap w : values()) if (w.value == v) return w;
            throw new IllegalArgumentException("Unknown Wrap value: " + v);
        }
    }
}
