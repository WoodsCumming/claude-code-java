package com.anthropic.claudecode.util.ink;

/**
 * Mutable representation of a terminal text style (SGR state). This
 * is a support class used by {@link SgrUtils} and {@link TermioParser}.
 *
 * <p>Corresponds to the {@code TextStyle} type and {@code defaultStyle()}
 * factory in {@code src/ink/termio/types.ts}.
 */
public class TextStyle {

    // SGR attribute flags
    public boolean bold;
    public boolean dim;
    public boolean italic;
    /** One of: "none", "single", "double", "curly", "dotted", "dashed" */
    public String  underline = "none";
    public boolean blink;
    public boolean inverse;
    public boolean hidden;
    public boolean strikethrough;
    public boolean overline;

    // Colour slots
    public Color fg            = Color.defaultColor();
    public Color bg            = Color.defaultColor();
    public Color underlineColor = Color.defaultColor();

    /** Returns a style with all attributes reset (matches {@code defaultStyle()}). */
    public static TextStyle defaultStyle() {
        return new TextStyle();
    }

    /** Shallow copy. */
    public TextStyle copy() {
        TextStyle t = new TextStyle();
        t.bold           = this.bold;
        t.dim            = this.dim;
        t.italic         = this.italic;
        t.underline      = this.underline;
        t.blink          = this.blink;
        t.inverse        = this.inverse;
        t.hidden         = this.hidden;
        t.strikethrough  = this.strikethrough;
        t.overline       = this.overline;
        t.fg             = this.fg;
        t.bg             = this.bg;
        t.underlineColor = this.underlineColor;
        return t;
    }

    // -------------------------------------------------------------------------
    // Color
    // -------------------------------------------------------------------------

    /**
     * A terminal colour.  One of: default, named, indexed (256-colour), or
     * RGB (true-colour).  Corresponds to the union type in types.ts.
     */
    public static final class Color {
        public enum Type { DEFAULT, NAMED, INDEXED, RGB }

        public final Type   type;
        public final String name;   // used when type == NAMED
        public final int    index;  // used when type == INDEXED
        public final int    r, g, b; // used when type == RGB

        private Color(Type type, String name, int index, int r, int g, int b) {
            this.type  = type;
            this.name  = name;
            this.index = index;
            this.r = r; this.g = g; this.b = b;
        }

        public static Color defaultColor() { return new Color(Type.DEFAULT, null, 0, 0, 0, 0); }
        public static Color named(String name) { return new Color(Type.NAMED, name, 0, 0, 0, 0); }
        public static Color indexed(int index) { return new Color(Type.INDEXED, null, index, 0, 0, 0); }
        public static Color rgb(int r, int g, int b) { return new Color(Type.RGB, null, 0, r, g, b); }

        public boolean isIndexed() { return type == Type.INDEXED; }

        @Override
        public String toString() {
            return switch (type) {
                case DEFAULT  -> "default";
                case NAMED    -> name;
                case INDEXED  -> "ansi256(" + index + ")";
                case RGB      -> "rgb(" + r + "," + g + "," + b + ")";
            };
        }
    }
}
