package com.anthropic.claudecode.util.ink;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;

/**
 * ANSI Parser — semantic action generator.
 *
 * <p>A streaming parser for ANSI escape sequences that produces semantic
 * {@link Action} values. Uses {@link TermioTokenizer} for escape sequence
 * boundary detection, then interprets each sequence into a structured action.
 *
 * <p>Translated from {@code src/ink/termio/parser.ts}.
 */
public final class TermioParser {

    // =========================================================================
    // Action sealed interface hierarchy
    // =========================================================================

    /** Base type for all parser actions. */
    public sealed interface Action
        permits Text, Bell, Sgr, Cursor,
                Erase, Scroll, Mode, Link,
                Unknown {}

    public record Text(List<Grapheme> graphemes, TextStyle style) implements Action {}
    public record Bell()                                             implements Action {}
    /** Internal SGR action consumed by the parser itself; not emitted to callers. */
    public record Sgr(String params)                                 implements Action {}
    public record Cursor(CursorAction action)                        implements Action {}
    public record Erase(EraseAction action)                          implements Action {}
    public record Scroll(ScrollAction action)                        implements Action {}
    public record Mode(ModeAction action)                            implements Action {}
    public record Link(LinkAction action)                            implements Action {}
    public record Unknown(String sequence)                           implements Action {}

    // -------------------------------------------------------------------------
    // Grapheme
    // -------------------------------------------------------------------------

    public record Grapheme(String value, int width) {}

    // -------------------------------------------------------------------------
    // Cursor actions
    // -------------------------------------------------------------------------

    public sealed interface CursorAction
        permits Move, NextLine, PrevLine,
                Column, Position, Row,
                Save, Restore, Show,
                Hide, Style {}

    public record Move(String direction, int count) implements CursorAction {}
    public record NextLine(int count)               implements CursorAction {}
    public record PrevLine(int count)               implements CursorAction {}
    public record Column(int col)                   implements CursorAction {}
    public record Position(int row, int col)        implements CursorAction {}
    public record Row(int row)                      implements CursorAction {}
    public record Save()                            implements CursorAction {}
    public record Restore()                         implements CursorAction {}
    public record Show()                            implements CursorAction {}
    public record Hide()                            implements CursorAction {}
    public record Style(String shape, boolean blink) implements CursorAction {}

    // -------------------------------------------------------------------------
    // Erase actions
    // -------------------------------------------------------------------------

    public sealed interface EraseAction
        permits Display, Line, Chars {}

    public record Display(String region) implements EraseAction {}
    public record Line(String region)    implements EraseAction {}
    public record Chars(int count)       implements EraseAction {}

    // -------------------------------------------------------------------------
    // Scroll actions
    // -------------------------------------------------------------------------

    public sealed interface ScrollAction
        permits Up, Down, SetRegion {}

    public record Up(int count)                    implements ScrollAction {}
    public record Down(int count)                  implements ScrollAction {}
    public record SetRegion(int top, int bottom)   implements ScrollAction {}

    // -------------------------------------------------------------------------
    // Mode actions
    // -------------------------------------------------------------------------

    public sealed interface ModeAction
        permits AlternateScreen, BracketedPaste,
                MouseTracking, FocusEvents {}

    public record AlternateScreen(boolean enabled)           implements ModeAction {}
    public record BracketedPaste(boolean enabled)            implements ModeAction {}
    public record MouseTracking(String mode)                 implements ModeAction {}
    public record FocusEvents(boolean enabled)               implements ModeAction {}

    // -------------------------------------------------------------------------
    // Link actions
    // -------------------------------------------------------------------------

    public sealed interface LinkAction
        permits Start, End {}

    public record Start(String url)  implements LinkAction {}
    public record End()              implements LinkAction {}

    // =========================================================================
    // Parser state
    // =========================================================================

    private final TermioTokenizer tokenizer = new TermioTokenizer();
    public TextStyle style   = TextStyle.defaultStyle();
    public boolean   inLink  = false;
    public String    linkUrl = null;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Reset all parser state. */
    public void reset() {
        tokenizer.reset();
        style   = TextStyle.defaultStyle();
        inLink  = false;
        linkUrl = null;
    }

    /**
     * Feed input and return the resulting actions.
     *
     * @param input raw terminal bytes/characters
     * @return list of semantic actions
     */
    public List<Action> feed(String input) {
        List<TermioTokenizer.Token> tokens = tokenizer.feed(input);
        List<Action> actions = new ArrayList<>();
        for (TermioTokenizer.Token token : tokens) {
            actions.addAll(processToken(token));
        }
        return actions;
    }

    // -------------------------------------------------------------------------
    // Internal processing
    // -------------------------------------------------------------------------

    private List<Action> processToken(TermioTokenizer.Token token) {
        return switch (token) {
            case TermioTokenizer.Token.Text t    -> processText(t.value());
            case TermioTokenizer.Token.Sequence s -> processSequence(s.value());
        };
    }

    private List<Action> processText(String text) {
        List<Action> actions = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (cp == AnsiCodes.C0_BEL) {
                if (!current.isEmpty()) {
                    List<Grapheme> gs = segmentGraphemes(current.toString());
                    if (!gs.isEmpty()) actions.add(new Text(gs, style.copy()));
                    current.setLength(0);
                }
                actions.add(new Bell());
                i += Character.charCount(cp);
            } else {
                i += Character.charCount(cp);
                current.appendCodePoint(cp);
            }
        }

        if (!current.isEmpty()) {
            List<Grapheme> gs = segmentGraphemes(current.toString());
            if (!gs.isEmpty()) actions.add(new Text(gs, style.copy()));
        }
        return actions;
    }

    private List<Action> processSequence(String seq) {
        String seqType = identifySequence(seq);
        return switch (seqType) {
            case "csi" -> {
                Action a = parseCSI(seq);
                if (a == null) yield List.of();
                if (a instanceof Sgr sgr) {
                    style = SgrUtils.applySGR(sgr.params(), style);
                    yield List.of();
                }
                yield List.of(a);
            }
            case "osc" -> {
                String content = seq.substring(2);
                if (content.endsWith("\u0007")) content = content.substring(0, content.length() - 1);
                else if (content.endsWith("\u001b\\")) content = content.substring(0, content.length() - 2);
                Action a = parseOSC(content);
                if (a instanceof Link link) {
                    if (link.action() instanceof Start s) {
                        inLink  = true;
                        linkUrl = s.url();
                    } else {
                        inLink  = false;
                        linkUrl = null;
                    }
                }
                yield a != null ? List.of(a) : List.of();
            }
            case "esc" -> {
                Action a = parseEsc(seq.substring(1));
                yield a != null ? List.of(a) : List.of();
            }
            case "ss3" -> List.of(new Unknown(seq));
            default    -> List.of(new Unknown(seq));
        };
    }

    // =========================================================================
    // Sequence parsing helpers
    // =========================================================================

    private static String identifySequence(String seq) {
        if (seq.length() < 2) return "unknown";
        if (seq.charAt(0) != '\u001b') return "unknown";
        int second = seq.charAt(1);
        if (second == 0x5b) return "csi";
        if (second == 0x5d) return "osc";
        if (second == 0x4f) return "ss3";
        return "esc";
    }

    private static final int[] ERASE_DISPLAY_MAP = {0, 1, 2, 3}; // index → code
    private static final String[] ERASE_DISPLAY_REGIONS = {"toEnd", "toStart", "all", "savedLines"};
    private static final String[] ERASE_LINE_REGIONS = {"toEnd", "toStart", "all"};

    private static Action parseCSI(String raw) {
        String inner = raw.substring(2);
        if (inner.isEmpty()) return null;

        char finalChar = inner.charAt(inner.length() - 1);
        int finalByte  = finalChar;
        String beforeFinal = inner.substring(0, inner.length() - 1);

        String privateMode = "";
        String paramStr    = beforeFinal;
        if (!paramStr.isEmpty() && "?>=".indexOf(paramStr.charAt(0)) >= 0) {
            privateMode = String.valueOf(paramStr.charAt(0));
            paramStr    = paramStr.substring(1);
        }

        // Strip trailing intermediate bytes
        int iIdx = paramStr.length() - 1;
        while (iIdx >= 0) {
            char c = paramStr.charAt(iIdx);
            if ((c >= '0' && c <= '9') || c == ';' || c == ':') break;
            iIdx--;
        }
        paramStr = paramStr.substring(0, iIdx + 1);

        int[] params = parseCsiParams(paramStr);
        int p0 = params.length > 0 ? params[0] : 1;
        int p1 = params.length > 1 ? params[1] : 1;

        // SGR
        if (finalByte == 0x6d && privateMode.isEmpty()) return new Sgr(paramStr);

        // Cursor movement
        if (finalByte == 0x41) return new Cursor(new Move("up",      Math.max(1, p0)));
        if (finalByte == 0x42) return new Cursor(new Move("down",    Math.max(1, p0)));
        if (finalByte == 0x43) return new Cursor(new Move("forward", Math.max(1, p0)));
        if (finalByte == 0x44) return new Cursor(new Move("back",    Math.max(1, p0)));
        if (finalByte == 0x45) return new Cursor(new NextLine(Math.max(1, p0)));
        if (finalByte == 0x46) return new Cursor(new PrevLine(Math.max(1, p0)));
        if (finalByte == 0x47) return new Cursor(new Column(p0));
        if (finalByte == 0x48 || finalByte == 0x66) return new Cursor(new Position(p0, p1));
        if (finalByte == 0x64) return new Cursor(new Row(p0));

        // Erase
        if (finalByte == 0x4a) {
            int idx = params.length > 0 ? params[0] : 0;
            String region = idx < ERASE_DISPLAY_REGIONS.length ? ERASE_DISPLAY_REGIONS[idx] : "toEnd";
            return new Erase(new Display(region));
        }
        if (finalByte == 0x4b) {
            int idx = params.length > 0 ? params[0] : 0;
            String region = idx < ERASE_LINE_REGIONS.length ? ERASE_LINE_REGIONS[idx] : "toEnd";
            return new Erase(new Line(region));
        }
        if (finalByte == 0x58) return new Erase(new Chars(p0));

        // Scroll
        if (finalByte == 0x53) return new Scroll(new Up(p0));
        if (finalByte == 0x54) return new Scroll(new Down(p0));
        if (finalByte == 0x72) return new Scroll(new SetRegion(p0, p1)); // DECSTBM

        // Cursor save / restore (SCO)
        if (finalByte == 0x73) return new Cursor(new Save());
        if (finalByte == 0x75) return new Cursor(new Restore());

        // Private modes
        if ("?".equals(privateMode) && (finalByte == 0x68 || finalByte == 0x6c)) {
            boolean enabled = finalByte == 0x68; // h = set, l = reset
            if (p0 == 25)   return new Cursor(enabled ? new Show() : new Hide());
            if (p0 == 1049 || p0 == 47) return new Mode(new AlternateScreen(enabled));
            if (p0 == 2004) return new Mode(new BracketedPaste(enabled));
            if (p0 == 1000) return new Mode(new MouseTracking(enabled ? "normal" : "off"));
            if (p0 == 1002) return new Mode(new MouseTracking(enabled ? "button" : "off"));
            if (p0 == 1003) return new Mode(new MouseTracking(enabled ? "any"    : "off"));
            if (p0 == 1004) return new Mode(new FocusEvents(enabled));
        }

        return new Unknown(raw);
    }

    private static Action parseOSC(String content) {
        // OSC 8 hyperlink: "8;params;uri" or "8;;"
        if (content.startsWith("8;")) {
            String rest = content.substring(2);
            int semi = rest.indexOf(';');
            if (semi >= 0) {
                String uri = rest.substring(semi + 1);
                if (uri.isEmpty()) {
                    return new Link(new End());
                }
                return new Link(new Start(uri));
            }
        }
        return null;
    }

    private static Action parseEsc(String escContent) {
        // Common two-character escape sequences: ESC followed by one final byte
        if (escContent.length() >= 1) {
            char c = escContent.charAt(0);
            if (c == '7') return new Cursor(new Save());    // DECSC
            if (c == '8') return new Cursor(new Restore()); // DECRC
        }
        return null;
    }

    private static int[] parseCsiParams(String paramStr) {
        if (paramStr == null || paramStr.isEmpty()) return new int[0];
        String[] parts = paramStr.split("[;:]");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]);
        }
        return result;
    }

    // =========================================================================
    // Grapheme segmentation helpers
    // =========================================================================

    private static List<Grapheme> segmentGraphemes(String s) {
        List<Grapheme> result = new ArrayList<>();
        BreakIterator it = BreakIterator.getCharacterInstance();
        it.setText(s);
        int start = it.first();
        for (int end = it.next(); end != BreakIterator.DONE; start = end, end = it.next()) {
            String g = s.substring(start, end);
            result.add(new Grapheme(g, graphemeWidth(g)));
        }
        return result;
    }

    private static int graphemeWidth(String g) {
        if (g.codePointCount(0, g.length()) > 1) return 2;
        int cp = g.codePointAt(0);
        if (isEmoji(cp) || isEastAsianWide(cp)) return 2;
        return 1;
    }

    private static boolean isEmoji(int cp) {
        return (cp >= 0x2600 && cp <= 0x26ff)
            || (cp >= 0x2700 && cp <= 0x27bf)
            || (cp >= 0x1f300 && cp <= 0x1f9ff)
            || (cp >= 0x1fa00 && cp <= 0x1faff)
            || (cp >= 0x1f1e0 && cp <= 0x1f1ff);
    }

    private static boolean isEastAsianWide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115f)
            || (cp >= 0x2e80 && cp <= 0x9fff)
            || (cp >= 0xac00 && cp <= 0xd7a3)
            || (cp >= 0xf900 && cp <= 0xfaff)
            || (cp >= 0xfe10 && cp <= 0xfe1f)
            || (cp >= 0xfe30 && cp <= 0xfe6f)
            || (cp >= 0xff00 && cp <= 0xff60)
            || (cp >= 0xffe0 && cp <= 0xffe6)
            || (cp >= 0x20000 && cp <= 0x2fffd)
            || (cp >= 0x30000 && cp <= 0x3fffd);
    }
}
