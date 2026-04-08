package com.anthropic.claudecode.util.ink;

import java.util.ArrayList;
import java.util.List;

/**
 * SGR (Select Graphic Rendition) parser and applier.
 *
 * <p>Parses SGR parameters and applies them to a {@link TextStyle}. Handles
 * both semicolon (;) and colon (:) separated parameters. Translated from
 * src/ink/termio/sgr.ts.
 */
public final class SgrUtils {

    private SgrUtils() {}

    // Named colours in SGR order (codes 30–37 / 40–47 / 90–97 / 100–107).
    private static final String[] NAMED_COLORS = {
        "black", "red", "green", "yellow",
        "blue", "magenta", "cyan", "white",
        "brightBlack", "brightRed", "brightGreen", "brightYellow",
        "brightBlue", "brightMagenta", "brightCyan", "brightWhite"
    };

    // Underline style names in order (CSI 4 : n m).
    private static final String[] UNDERLINE_STYLES = {
        "none", "single", "double", "curly", "dotted", "dashed"
    };

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Applies an SGR parameter string to the given style and returns the
     * resulting style.  The input style is not mutated; a copy is returned.
     *
     * @param paramStr the raw parameter string from a CSI … m sequence
     * @param style    the current text style
     * @return updated {@link TextStyle}
     */
    public static TextStyle applySGR(String paramStr, TextStyle style) {
        List<Param> params = parseParams(paramStr);
        TextStyle s = style.copy();
        int i = 0;

        while (i < params.size()) {
            Param p = params.get(i);
            int code = p.value != null ? p.value : 0;

            if (code == 0)  { s = TextStyle.defaultStyle(); i++; continue; }
            if (code == 1)  { s.bold = true;            i++; continue; }
            if (code == 2)  { s.dim = true;             i++; continue; }
            if (code == 3)  { s.italic = true;          i++; continue; }
            if (code == 4)  {
                s.underline = p.colon
                    ? (p.subparams.size() >= 1 && p.subparams.get(0) < UNDERLINE_STYLES.length
                        ? UNDERLINE_STYLES[p.subparams.get(0)]
                        : "single")
                    : "single";
                i++; continue;
            }
            if (code == 5 || code == 6) { s.blink = true;        i++; continue; }
            if (code == 7)  { s.inverse = true;         i++; continue; }
            if (code == 8)  { s.hidden = true;          i++; continue; }
            if (code == 9)  { s.strikethrough = true;   i++; continue; }
            if (code == 21) { s.underline = "double";   i++; continue; }
            if (code == 22) { s.bold = false; s.dim = false; i++; continue; }
            if (code == 23) { s.italic = false;         i++; continue; }
            if (code == 24) { s.underline = "none";     i++; continue; }
            if (code == 25) { s.blink = false;          i++; continue; }
            if (code == 27) { s.inverse = false;        i++; continue; }
            if (code == 28) { s.hidden = false;         i++; continue; }
            if (code == 29) { s.strikethrough = false;  i++; continue; }
            if (code == 53) { s.overline = true;        i++; continue; }
            if (code == 55) { s.overline = false;       i++; continue; }

            if (code >= 30 && code <= 37) {
                s.fg = TextStyle.Color.named(NAMED_COLORS[code - 30]);
                i++; continue;
            }
            if (code == 39) { s.fg = TextStyle.Color.defaultColor(); i++; continue; }
            if (code >= 40 && code <= 47) {
                s.bg = TextStyle.Color.named(NAMED_COLORS[code - 40]);
                i++; continue;
            }
            if (code == 49) { s.bg = TextStyle.Color.defaultColor(); i++; continue; }
            if (code >= 90 && code <= 97) {
                s.fg = TextStyle.Color.named(NAMED_COLORS[code - 90 + 8]);
                i++; continue;
            }
            if (code >= 100 && code <= 107) {
                s.bg = TextStyle.Color.named(NAMED_COLORS[code - 100 + 8]);
                i++; continue;
            }

            if (code == 38) {
                TextStyle.Color c = parseExtendedColor(params, i);
                if (c != null) {
                    s.fg = c;
                    i += p.colon ? 1 : (c.isIndexed() ? 3 : 5);
                    continue;
                }
            }
            if (code == 48) {
                TextStyle.Color c = parseExtendedColor(params, i);
                if (c != null) {
                    s.bg = c;
                    i += p.colon ? 1 : (c.isIndexed() ? 3 : 5);
                    continue;
                }
            }
            if (code == 58) {
                TextStyle.Color c = parseExtendedColor(params, i);
                if (c != null) {
                    s.underlineColor = c;
                    i += p.colon ? 1 : (c.isIndexed() ? 3 : 5);
                    continue;
                }
            }
            if (code == 59) { s.underlineColor = TextStyle.Color.defaultColor(); i++; continue; }

            i++;
        }
        return s;
    }

    // -------------------------------------------------------------------------
    // Param parsing internals
    // -------------------------------------------------------------------------

    private static final class Param {
        Integer value;
        final List<Integer> subparams = new ArrayList<>();
        boolean colon;
    }

    private static List<Param> parseParams(String str) {
        if (str == null || str.isEmpty()) {
            Param p = new Param();
            p.value = 0;
            return List.of(p);
        }

        List<Param> result = new ArrayList<>();
        Param current = new Param();
        StringBuilder num = new StringBuilder();
        boolean inSub = false;

        for (int i = 0; i <= str.length(); i++) {
            char c = i < str.length() ? str.charAt(i) : 0;
            if (c == ';' || i == str.length()) {
                Integer n = num.length() == 0 ? null : Integer.parseInt(num.toString());
                if (inSub) {
                    if (n != null) current.subparams.add(n);
                } else {
                    current.value = n;
                }
                result.add(current);
                current = new Param();
                num.setLength(0);
                inSub = false;
            } else if (c == ':') {
                Integer n = num.length() == 0 ? null : Integer.parseInt(num.toString());
                if (!inSub) {
                    current.value = n;
                    current.colon = true;
                    inSub = true;
                } else {
                    if (n != null) current.subparams.add(n);
                }
                num.setLength(0);
            } else if (c >= '0' && c <= '9') {
                num.append(c);
            }
        }
        return result;
    }

    private static TextStyle.Color parseExtendedColor(List<Param> params, int idx) {
        if (idx >= params.size()) return null;
        Param p = params.get(idx);

        if (p.colon && !p.subparams.isEmpty()) {
            int sub0 = p.subparams.get(0);
            if (sub0 == 5 && p.subparams.size() >= 2) {
                return TextStyle.Color.indexed(p.subparams.get(1));
            }
            if (sub0 == 2 && p.subparams.size() >= 4) {
                int off = p.subparams.size() >= 5 ? 1 : 0;
                return TextStyle.Color.rgb(
                    p.subparams.get(1 + off),
                    p.subparams.get(2 + off),
                    p.subparams.get(3 + off));
            }
        }

        if (idx + 1 >= params.size()) return null;
        Param next = params.get(idx + 1);

        if (next.value != null && next.value == 5
                && idx + 2 < params.size()
                && params.get(idx + 2).value != null) {
            return TextStyle.Color.indexed(params.get(idx + 2).value);
        }
        if (next.value != null && next.value == 2) {
            if (idx + 4 < params.size()) {
                Integer r = params.get(idx + 2).value;
                Integer g = params.get(idx + 3).value;
                Integer b = params.get(idx + 4).value;
                if (r != null && g != null && b != null) {
                    return TextStyle.Color.rgb(r, g, b);
                }
            }
        }
        return null;
    }
}
