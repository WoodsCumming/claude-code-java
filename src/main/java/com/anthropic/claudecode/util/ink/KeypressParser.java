package com.anthropic.claudecode.util.ink;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keyboard input parser — converts terminal input bytes to key events.
 *
 * <p>Uses {@link TermioTokenizer} for escape sequence boundary detection then
 * interprets sequences as keypresses, mouse events, or terminal responses.
 *
 * <p>Translated from {@code src/ink/parse-keypress.ts}.
 */
public final class KeypressParser {

    // -------------------------------------------------------------------------
    // Regex patterns (mirrors parse-keypress.ts)
    // -------------------------------------------------------------------------

    private static final Pattern META_KEY_CODE_RE       = Pattern.compile("^\u001b([a-zA-Z0-9])$");
    private static final Pattern FN_KEY_RE              = Pattern.compile(
        "^\u001b+(O|N|\\[|\\[\\[)(?:(\\d+)(?:;(\\d+))?([~^$])|(?:1;)?(\\d+)?([a-zA-Z]))");
    private static final Pattern CSI_U_RE               = Pattern.compile("^\u001b\\[(\\d+)(?:;(\\d+))?u");
    private static final Pattern MODIFY_OTHER_KEYS_RE   = Pattern.compile("^\u001b\\[27;(\\d+);(\\d+)~");
    private static final Pattern DECRPM_RE              = Pattern.compile("^\u001b\\[\\?(\\d+);(\\d+)\\$y$");
    private static final Pattern DA1_RE                 = Pattern.compile("^\u001b\\[\\?([\\d;]*)c$");
    private static final Pattern DA2_RE                 = Pattern.compile("^\u001b\\[>([\\d;]*)c$");
    private static final Pattern KITTY_FLAGS_RE         = Pattern.compile("^\u001b\\[\\?(\\d+)u$");
    private static final Pattern CURSOR_POSITION_RE     = Pattern.compile("^\u001b\\[\\?(\\d+);(\\d+)R$");
    private static final Pattern OSC_RESPONSE_RE        = Pattern.compile("^\u001b\\](\\d+);(.*?)(?:\u0007|\u001b\\\\)$",
        Pattern.DOTALL);
    private static final Pattern XTVERSION_RE           = Pattern.compile("^\u001bP>\\|(.*?)(?:\u0007|\u001b\\\\)$",
        Pattern.DOTALL);
    private static final Pattern SGR_MOUSE_RE           = Pattern.compile("^\u001b\\[<(\\d+);(\\d+);(\\d+)([Mm])$");

    // Bracketed paste
    private static final String PASTE_START = "\u001b[?2004h";
    private static final String PASTE_END   = "\u001b[?2004l";

    // -------------------------------------------------------------------------
    // Key name table (mirrors keyName record in TS)
    // -------------------------------------------------------------------------

    private static final Map<String, String> KEY_NAME = new HashMap<>();
    static {
        // xterm / gnome ESC O letter
        KEY_NAME.put("OP", "f1"); KEY_NAME.put("OQ", "f2");
        KEY_NAME.put("OR", "f3"); KEY_NAME.put("OS", "f4");
        // Application keypad (numpad)
        KEY_NAME.put("Op", "0"); KEY_NAME.put("Oq", "1"); KEY_NAME.put("Or", "2");
        KEY_NAME.put("Os", "3"); KEY_NAME.put("Ot", "4"); KEY_NAME.put("Ou", "5");
        KEY_NAME.put("Ov", "6"); KEY_NAME.put("Ow", "7"); KEY_NAME.put("Ox", "8");
        KEY_NAME.put("Oy", "9");
        KEY_NAME.put("Oj", "*"); KEY_NAME.put("Ok", "+"); KEY_NAME.put("Ol", ",");
        KEY_NAME.put("Om", "-"); KEY_NAME.put("On", "."); KEY_NAME.put("Oo", "/");
        KEY_NAME.put("OM", "return");
        // xterm / rxvt number ~ sequences
        KEY_NAME.put("[11~", "f1"); KEY_NAME.put("[12~", "f2");
        KEY_NAME.put("[13~", "f3"); KEY_NAME.put("[14~", "f4");
        KEY_NAME.put("[[A", "f1"); KEY_NAME.put("[[B", "f2");
        KEY_NAME.put("[[C", "f3"); KEY_NAME.put("[[D", "f4"); KEY_NAME.put("[[E", "f5");
        KEY_NAME.put("[15~", "f5"); KEY_NAME.put("[17~", "f6"); KEY_NAME.put("[18~", "f7");
        KEY_NAME.put("[19~", "f8"); KEY_NAME.put("[20~", "f9"); KEY_NAME.put("[21~", "f10");
        KEY_NAME.put("[23~", "f11"); KEY_NAME.put("[24~", "f12");
        // Arrow keys
        KEY_NAME.put("[A", "up"); KEY_NAME.put("[B", "down");
        KEY_NAME.put("[C", "right"); KEY_NAME.put("[D", "left");
        KEY_NAME.put("[E", "clear"); KEY_NAME.put("[F", "end"); KEY_NAME.put("[H", "home");
        KEY_NAME.put("OA", "up"); KEY_NAME.put("OB", "down");
        KEY_NAME.put("OC", "right"); KEY_NAME.put("OD", "left");
        KEY_NAME.put("OE", "clear"); KEY_NAME.put("OF", "end"); KEY_NAME.put("OH", "home");
        // Navigation
        KEY_NAME.put("[1~", "home"); KEY_NAME.put("[2~", "insert");
        KEY_NAME.put("[3~", "delete"); KEY_NAME.put("[4~", "end");
        KEY_NAME.put("[5~", "pageup"); KEY_NAME.put("[6~", "pagedown");
        KEY_NAME.put("[[5~", "pageup"); KEY_NAME.put("[[6~", "pagedown");
        KEY_NAME.put("[7~", "home"); KEY_NAME.put("[8~", "end");
        // rxvt with modifiers
        KEY_NAME.put("[a", "up"); KEY_NAME.put("[b", "down");
        KEY_NAME.put("[c", "right"); KEY_NAME.put("[d", "left"); KEY_NAME.put("[e", "clear");
        KEY_NAME.put("[2$", "insert"); KEY_NAME.put("[3$", "delete");
        KEY_NAME.put("[5$", "pageup"); KEY_NAME.put("[6$", "pagedown");
        KEY_NAME.put("[7$", "home"); KEY_NAME.put("[8$", "end");
        KEY_NAME.put("Oa", "up"); KEY_NAME.put("Ob", "down");
        KEY_NAME.put("Oc", "right"); KEY_NAME.put("Od", "left"); KEY_NAME.put("Oe", "clear");
        KEY_NAME.put("[2^", "insert"); KEY_NAME.put("[3^", "delete");
        KEY_NAME.put("[5^", "pageup"); KEY_NAME.put("[6^", "pagedown");
        KEY_NAME.put("[7^", "home"); KEY_NAME.put("[8^", "end");
        KEY_NAME.put("[Z", "tab");
    }

    private static final List<String> SHIFT_KEYS = List.of(
        "[a", "[b", "[c", "[d", "[e",
        "[2$", "[3$", "[5$", "[6$", "[7$", "[8$", "[Z");
    private static final List<String> CTRL_KEYS = List.of(
        "Oa", "Ob", "Oc", "Od", "Oe",
        "[2^", "[3^", "[5^", "[6^", "[7^", "[8^");

    // -------------------------------------------------------------------------
    // Public types
    // -------------------------------------------------------------------------

    /** DECRPM status values. */
    public enum DeCrpmStatus {
        NOT_RECOGNIZED(0), SET(1), RESET(2), PERMANENTLY_SET(3), PERMANENTLY_RESET(4);
        public final int code;
        DeCrpmStatus(int c) { this.code = c; }
    }

    /** A terminal response sequence (not a keypress). */
    public sealed interface TerminalResponse
        permits Decrpm, Da1, Da2, KittyKeyboard, CursorPosition, Osc, XtVersion {}

    public record Decrpm(int mode, int status)       implements TerminalResponse {}
    public record Da1(int[] params)                  implements TerminalResponse {}
    public record Da2(int[] params)                  implements TerminalResponse {}
    public record KittyKeyboard(int flags)           implements TerminalResponse {}
    public record CursorPosition(int row, int col)   implements TerminalResponse {}
    public record Osc(int code, String data)         implements TerminalResponse {}
    public record XtVersion(String name)             implements TerminalResponse {}

    /** A parsed keypress event. */
    public static final class ParsedKey {
        public final String  kind = "key";
        public final boolean fn;
        public final String  name;
        public final boolean ctrl;
        public final boolean meta;
        public final boolean shift;
        public final boolean option;
        public final boolean superKey;
        public final String  sequence;
        public final String  raw;
        public final String  code;
        public final boolean isPasted;

        private ParsedKey(Builder b) {
            this.fn       = b.fn;
            this.name     = b.name;
            this.ctrl     = b.ctrl;
            this.meta     = b.meta;
            this.shift    = b.shift;
            this.option   = b.option;
            this.superKey = b.superKey;
            this.sequence = b.sequence;
            this.raw      = b.raw;
            this.code     = b.code;
            this.isPasted = b.isPasted;
        }

        static final class Builder {
            String  name = "";
            boolean fn, ctrl, meta, shift, option, superKey, isPasted;
            String  sequence, raw, code;

            ParsedKey build() { return new ParsedKey(this); }
        }
    }

    /** An SGR mouse event. */
    public record ParsedMouse(
        int    button,
        String action,   // "press" or "release"
        int    col,
        int    row,
        String sequence) {}

    /** A terminal-response token extracted from the input stream. */
    public record ParsedResponse(String sequence, TerminalResponse response) {}

    /** Everything that can come out of the input parser. */
    public sealed interface ParsedInput
        permits Key, Mouse, Response {}

    public record Key(ParsedKey key)          implements ParsedInput {}
    public record Mouse(ParsedMouse mouse)    implements ParsedInput {}
    public record Response(ParsedResponse r)  implements ParsedInput {}

    // -------------------------------------------------------------------------
    // Parser state
    // -------------------------------------------------------------------------

    public static final class KeyParseState {
        public enum Mode { NORMAL, IN_PASTE }
        public final Mode   mode;
        public final String incomplete;
        public final String pasteBuffer;
        final TermioTokenizer tokenizer;

        public static final KeyParseState INITIAL =
            new KeyParseState(Mode.NORMAL, "", "", null);

        KeyParseState(Mode mode, String incomplete, String pasteBuffer, TermioTokenizer tokenizer) {
            this.mode        = mode;
            this.incomplete  = incomplete;
            this.pasteBuffer = pasteBuffer;
            this.tokenizer   = tokenizer;
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Parse input bytes/string, returning the list of parsed events and the
     * updated parser state.
     *
     * @param prevState previous parser state (use {@link KeyParseState#INITIAL} first call)
     * @param input     raw input; {@code null} means "flush"
     * @return pair of [events, newState]
     */
    public static ParseResult parseMultipleKeypresses(
            KeyParseState prevState, String input) {

        boolean isFlush = input == null;
        String inputString = isFlush ? "" : input;

        TermioTokenizer tokenizer = prevState.tokenizer != null
            ? prevState.tokenizer
            : new TermioTokenizer(true);

        List<TermioTokenizer.Token> tokens = isFlush
            ? tokenizer.flush()
            : tokenizer.feed(inputString);

        List<ParsedInput> keys = new ArrayList<>();
        boolean inPaste    = prevState.mode == KeyParseState.Mode.IN_PASTE;
        String  pasteBuffer = prevState.pasteBuffer;

        for (TermioTokenizer.Token token : tokens) {
            switch (token) {
                case TermioTokenizer.Token.Sequence s -> {
                    String val = s.value();
                    if (val.equals(PASTE_START)) {
                        inPaste = true;
                        pasteBuffer = "";
                    } else if (val.equals(PASTE_END)) {
                        keys.add(new Key(createPasteKey(pasteBuffer)));
                        inPaste = false;
                        pasteBuffer = "";
                    } else if (inPaste) {
                        pasteBuffer += val;
                    } else {
                        TerminalResponse resp = parseTerminalResponse(val);
                        if (resp != null) {
                            keys.add(new Response(new ParsedResponse(val, resp)));
                        } else {
                            ParsedMouse mouse = parseMouseEvent(val);
                            if (mouse != null) {
                                keys.add(new Mouse(mouse));
                            } else {
                                keys.add(new Key(parseKeypress(val)));
                            }
                        }
                    }
                }
                case TermioTokenizer.Token.Text t -> {
                    String val = t.value();
                    if (inPaste) {
                        pasteBuffer += val;
                    } else {
                        keys.add(new Key(parseKeypress(val)));
                    }
                }
            }
        }

        if (isFlush && inPaste && !pasteBuffer.isEmpty()) {
            keys.add(new Key(createPasteKey(pasteBuffer)));
            inPaste = false;
            pasteBuffer = "";
        }

        KeyParseState newState = new KeyParseState(
            inPaste ? KeyParseState.Mode.IN_PASTE : KeyParseState.Mode.NORMAL,
            tokenizer.getBuffer(),
            pasteBuffer,
            tokenizer);

        return new ParseResult(keys, newState);
    }

    /** Result holder. */
    public record ParseResult(List<ParsedInput> events, KeyParseState newState) {}

    // -------------------------------------------------------------------------
    // Terminal response parsing
    // -------------------------------------------------------------------------

    private static TerminalResponse parseTerminalResponse(String s) {
        if (s.startsWith("\u001b[")) {
            Matcher m;
            if ((m = DECRPM_RE.matcher(s)).matches())
                return new Decrpm(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            if ((m = DA1_RE.matcher(s)).matches())
                return new Da1(splitParams(m.group(1)));
            if ((m = DA2_RE.matcher(s)).matches())
                return new Da2(splitParams(m.group(1)));
            if ((m = KITTY_FLAGS_RE.matcher(s)).matches())
                return new KittyKeyboard(Integer.parseInt(m.group(1)));
            if ((m = CURSOR_POSITION_RE.matcher(s)).matches())
                return new CursorPosition(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            return null;
        }
        if (s.startsWith("\u001b]")) {
            Matcher m = OSC_RESPONSE_RE.matcher(s);
            if (m.matches()) return new Osc(Integer.parseInt(m.group(1)), m.group(2));
        }
        if (s.startsWith("\u001bP")) {
            Matcher m = XTVERSION_RE.matcher(s);
            if (m.matches()) return new XtVersion(m.group(1));
        }
        return null;
    }

    private static int[] splitParams(String raw) {
        if (raw == null || raw.isEmpty()) return new int[0];
        String[] parts = raw.split(";");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++)
            result[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]);
        return result;
    }

    // -------------------------------------------------------------------------
    // Mouse event parsing
    // -------------------------------------------------------------------------

    private static ParsedMouse parseMouseEvent(String s) {
        Matcher m = SGR_MOUSE_RE.matcher(s);
        if (!m.matches()) return null;
        int button = Integer.parseInt(m.group(1));
        if ((button & 0x40) != 0) return null; // wheel events stay as ParsedKey
        return new ParsedMouse(
            button,
            "M".equals(m.group(4)) ? "press" : "release",
            Integer.parseInt(m.group(2)),
            Integer.parseInt(m.group(3)),
            s);
    }

    // -------------------------------------------------------------------------
    // Keypress parsing
    // -------------------------------------------------------------------------

    private static ParsedKey createPasteKey(String content) {
        ParsedKey.Builder b = new ParsedKey.Builder();
        b.name = ""; b.sequence = content; b.raw = content; b.isPasted = true;
        return b.build();
    }

    private static ParsedKey createNavKey(String s, String name, boolean ctrl) {
        ParsedKey.Builder b = new ParsedKey.Builder();
        b.name = name; b.ctrl = ctrl; b.sequence = s; b.raw = s;
        return b.build();
    }

    private static Mods decodeModifier(int modifier) {
        int m = modifier - 1;
        return new Mods((m & 1) != 0, (m & 2) != 0, (m & 4) != 0, (m & 8) != 0);
    }

    private record Mods(boolean shift, boolean meta, boolean ctrl, boolean superKey) {}

    private static String keycodeToName(int kc) {
        return switch (kc) {
            case 9   -> "tab";
            case 13  -> "return";
            case 27  -> "escape";
            case 32  -> "space";
            case 127 -> "backspace";
            case 57399 -> "0"; case 57400 -> "1"; case 57401 -> "2";
            case 57402 -> "3"; case 57403 -> "4"; case 57404 -> "5";
            case 57405 -> "6"; case 57406 -> "7"; case 57407 -> "8";
            case 57408 -> "9"; case 57409 -> "."; case 57410 -> "/";
            case 57411 -> "*"; case 57412 -> "-"; case 57413 -> "+";
            case 57414 -> "return"; case 57415 -> "=";
            default -> kc >= 32 && kc <= 126
                ? String.valueOf((char) kc).toLowerCase()
                : null;
        };
    }

    private static ParsedKey parseKeypress(String s) {
        if (s == null) s = "";

        ParsedKey.Builder key = new ParsedKey.Builder();
        key.sequence = s; key.raw = s; key.name = "";

        Matcher m;

        // CSI u (Kitty keyboard protocol)
        if ((m = CSI_U_RE.matcher(s)).matches()) {
            int codepoint = Integer.parseInt(m.group(1));
            int modifier  = m.group(2) != null ? Integer.parseInt(m.group(2)) : 1;
            Mods mods = decodeModifier(modifier);
            key.name = keycodeToName(codepoint);
            key.ctrl = mods.ctrl(); key.meta = mods.meta();
            key.shift = mods.shift(); key.superKey = mods.superKey();
            return key.build();
        }

        // xterm modifyOtherKeys
        if ((m = MODIFY_OTHER_KEYS_RE.matcher(s)).matches()) {
            Mods mods = decodeModifier(Integer.parseInt(m.group(1)));
            key.name = keycodeToName(Integer.parseInt(m.group(2)));
            key.ctrl = mods.ctrl(); key.meta = mods.meta();
            key.shift = mods.shift(); key.superKey = mods.superKey();
            return key.build();
        }

        // SGR mouse wheel
        if ((m = SGR_MOUSE_RE.matcher(s)).matches()) {
            int button = Integer.parseInt(m.group(1));
            if ((button & 0x43) == 0x40) return createNavKey(s, "wheelup",   false);
            if ((button & 0x43) == 0x41) return createNavKey(s, "wheeldown", false);
            return createNavKey(s, "mouse", false);
        }

        // X10 mouse wheel
        if (s.length() == 6 && s.startsWith("\u001b[M")) {
            int button = s.charAt(3) - 32;
            if ((button & 0x43) == 0x40) return createNavKey(s, "wheelup",   false);
            if ((button & 0x43) == 0x41) return createNavKey(s, "wheeldown", false);
            return createNavKey(s, "mouse", false);
        }

        if (s.equals("\r"))              { key.raw = null; key.name = "return"; }
        else if (s.equals("\n"))         { key.name = "enter"; }
        else if (s.equals("\t"))         { key.name = "tab"; }
        else if (s.equals("\b") || s.equals("\u001b\b")) {
            key.name = "backspace"; key.meta = s.charAt(0) == '\u001b';
        } else if (s.equals("\u007f") || s.equals("\u001b\u007f")) {
            key.name = "backspace"; key.meta = s.charAt(0) == '\u001b';
        } else if (s.equals("\u001b") || s.equals("\u001b\u001b")) {
            key.name = "escape"; key.meta = s.length() == 2;
        } else if (s.equals(" ") || s.equals("\u001b ")) {
            key.name = "space"; key.meta = s.length() == 2;
        } else if (s.equals("\u001f")) {
            key.name = "_"; key.ctrl = true;
        } else if (s.length() == 1 && s.charAt(0) <= '\u001a') {
            key.name = String.valueOf((char)(s.charAt(0) + 'a' - 1)); key.ctrl = true;
        } else if (s.length() == 1 && s.charAt(0) >= '0' && s.charAt(0) <= '9') {
            key.name = "number";
        } else if (s.length() == 1 && s.charAt(0) >= 'a' && s.charAt(0) <= 'z') {
            key.name = s;
        } else if (s.length() == 1 && s.charAt(0) >= 'A' && s.charAt(0) <= 'Z') {
            key.name = s.toLowerCase(); key.shift = true;
        } else if ((m = META_KEY_CODE_RE.matcher(s)).matches()) {
            key.meta = true;
            key.shift = m.group(1).matches("[A-Z]");
        } else if ((m = FN_KEY_RE.matcher(s)).matches()) {
            if (s.length() >= 2 && s.charAt(0) == '\u001b' && s.charAt(1) == '\u001b') {
                key.option = true;
            }
            StringBuilder code = new StringBuilder();
            if (m.group(1) != null) code.append(m.group(1));
            if (m.group(2) != null) code.append(m.group(2));
            if (m.group(4) != null) code.append(m.group(4));
            if (m.group(6) != null) code.append(m.group(6));
            String codeStr = code.toString();
            key.code = codeStr;

            int rawMod = 0;
            if (m.group(3) != null) rawMod = Integer.parseInt(m.group(3));
            else if (m.group(5) != null) rawMod = Integer.parseInt(m.group(5));
            int modifier = rawMod > 0 ? rawMod - 1 : 0;
            key.ctrl  = (modifier & 4) != 0;
            key.meta  = (modifier & 2) != 0;
            key.superKey = (modifier & 8) != 0;
            key.shift = (modifier & 1) != 0;
            key.name  = KEY_NAME.get(codeStr);
            key.shift = SHIFT_KEYS.contains(codeStr) || key.shift;
            key.ctrl  = CTRL_KEYS.contains(codeStr)  || key.ctrl;
        }

        // iTerm natural text editing
        if ("\u001bb".equals(key.raw)) { key.meta = true; key.name = "left"; }
        else if ("\u001bf".equals(key.raw)) { key.meta = true; key.name = "right"; }

        // Well-known sequences with explicit overrides
        return switch (s) {
            case "\u001b[1~" -> createNavKey(s, "home",     false);
            case "\u001b[4~" -> createNavKey(s, "end",      false);
            case "\u001b[5~" -> createNavKey(s, "pageup",   false);
            case "\u001b[6~" -> createNavKey(s, "pagedown", false);
            case "\u001b[1;5D" -> createNavKey(s, "left",  true);
            case "\u001b[1;5C" -> createNavKey(s, "right", true);
            default -> key.build();
        };
    }
}
