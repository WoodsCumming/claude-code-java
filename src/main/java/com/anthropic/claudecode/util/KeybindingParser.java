package com.anthropic.claudecode.util;

import com.anthropic.claudecode.model.KeybindingSchema.KeybindingBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Keybinding parsing utilities.
 * Translated from src/keybindings/parser.ts
 */
public class KeybindingParser {

    /**
     * A single parsed keystroke with modifier flags.
     * Translated from ParsedKeystroke in types.ts
     */
    public record ParsedKeystroke(
            String key,
            boolean ctrl,
            boolean alt,
            boolean shift,
            boolean meta,
            boolean sup // 'super' is a Java keyword, using 'sup'
    ) {
        public static ParsedKeystroke empty() {
            return new ParsedKeystroke("", false, false, false, false, false);
        }
    }

    /**
     * A chord is a sequence of keystrokes (for multi-key combinations like ctrl+k ctrl+s).
     */
    public record Chord(List<ParsedKeystroke> keystrokes) {
        public int length() {
            return keystrokes.size();
        }

        public ParsedKeystroke get(int index) {
            return keystrokes.get(index);
        }
    }

    /**
     * A fully parsed keybinding: chord + action + context.
     * Translated from ParsedBinding in types.ts
     */
    public record ParsedBinding(
            Chord chord,
            String action,
            String context
    ) {}

    /**
     * Parse a keystroke string like "ctrl+shift+k" into a ParsedKeystroke.
     * Supports various modifier aliases (ctrl/control, alt/opt/option/meta,
     * cmd/command/super/win).
     * Translated from parseKeystroke() in parser.ts
     */
    public static ParsedKeystroke parseKeystroke(String input) {
        String[] parts = input.split("\\+");
        String key = "";
        boolean ctrl = false;
        boolean alt = false;
        boolean shift = false;
        boolean meta = false;
        boolean sup = false;

        for (String part : parts) {
            String lower = part.toLowerCase();
            switch (lower) {
                case "ctrl", "control" -> ctrl = true;
                case "alt", "opt", "option" -> alt = true;
                case "shift" -> shift = true;
                case "meta" -> meta = true;
                case "cmd", "command", "super", "win" -> sup = true;
                case "esc" -> key = "escape";
                case "return" -> key = "enter";
                case "space" -> key = " ";
                case "\u2191" -> key = "up";    // ↑
                case "\u2193" -> key = "down";  // ↓
                case "\u2190" -> key = "left";  // ←
                case "\u2192" -> key = "right"; // →
                default -> key = lower;
            }
        }

        return new ParsedKeystroke(key, ctrl, alt, shift, meta, sup);
    }

    /**
     * Parse a chord string like "ctrl+k ctrl+s" into a Chord.
     * Translated from parseChord() in parser.ts
     */
    public static Chord parseChord(String input) {
        // A lone space character IS the space key binding, not a separator
        if (" ".equals(input)) {
            return new Chord(List.of(parseKeystroke("space")));
        }
        String[] parts = input.trim().split("\\s+");
        List<ParsedKeystroke> keystrokes = new ArrayList<>();
        for (String part : parts) {
            keystrokes.add(parseKeystroke(part));
        }
        return new Chord(keystrokes);
    }

    /**
     * Convert a ParsedKeystroke to its canonical string representation for display.
     * Translated from keystrokeToString() in parser.ts
     */
    public static String keystrokeToString(ParsedKeystroke ks) {
        List<String> parts = new ArrayList<>();
        if (ks.ctrl()) parts.add("ctrl");
        if (ks.alt()) parts.add("alt");
        if (ks.shift()) parts.add("shift");
        if (ks.meta()) parts.add("meta");
        if (ks.sup()) parts.add("cmd");
        parts.add(keyToDisplayName(ks.key()));
        return String.join("+", parts);
    }

    /**
     * Map internal key names to human-readable display names.
     * Translated from keyToDisplayName() in parser.ts
     */
    private static String keyToDisplayName(String key) {
        return switch (key) {
            case "escape" -> "Esc";
            case " " -> "Space";
            case "tab" -> "tab";
            case "enter" -> "Enter";
            case "backspace" -> "Backspace";
            case "delete" -> "Delete";
            case "up" -> "\u2191";
            case "down" -> "\u2193";
            case "left" -> "\u2190";
            case "right" -> "\u2192";
            case "pageup" -> "PageUp";
            case "pagedown" -> "PageDown";
            case "home" -> "Home";
            case "end" -> "End";
            default -> key;
        };
    }

    /**
     * Convert a Chord to its canonical string representation for display.
     * Translated from chordToString() in parser.ts
     */
    public static String chordToString(Chord chord) {
        List<String> parts = new ArrayList<>();
        for (ParsedKeystroke ks : chord.keystrokes()) {
            parts.add(keystrokeToString(ks));
        }
        return String.join(" ", parts);
    }

    /**
     * Display platform type — a subset of Platform relevant for display.
     * WSL and unknown are treated as linux for display purposes.
     * Translated from DisplayPlatform in parser.ts
     */
    public enum DisplayPlatform {
        MACOS, WINDOWS, LINUX, WSL, UNKNOWN
    }

    /**
     * Convert a ParsedKeystroke to a platform-appropriate display string.
     * Uses "opt" for alt on macOS, "alt" elsewhere.
     * Translated from keystrokeToDisplayString() in parser.ts
     */
    public static String keystrokeToDisplayString(ParsedKeystroke ks, DisplayPlatform platform) {
        if (platform == null) platform = DisplayPlatform.LINUX;
        List<String> parts = new ArrayList<>();
        if (ks.ctrl()) parts.add("ctrl");
        // Alt/meta are equivalent in terminals, show platform-appropriate name
        if (ks.alt() || ks.meta()) {
            parts.add(platform == DisplayPlatform.MACOS ? "opt" : "alt");
        }
        if (ks.shift()) parts.add("shift");
        if (ks.sup()) {
            parts.add(platform == DisplayPlatform.MACOS ? "cmd" : "super");
        }
        parts.add(keyToDisplayName(ks.key()));
        return String.join("+", parts);
    }

    /**
     * Convert a Chord to a platform-appropriate display string.
     * Translated from chordToDisplayString() in parser.ts
     */
    public static String chordToDisplayString(Chord chord, DisplayPlatform platform) {
        List<String> parts = new ArrayList<>();
        for (ParsedKeystroke ks : chord.keystrokes()) {
            parts.add(keystrokeToDisplayString(ks, platform));
        }
        return String.join(" ", parts);
    }

    /**
     * Parse keybinding blocks (from JSON config) into a flat list of ParsedBindings.
     * Translated from parseBindings() in parser.ts
     */
    public static List<ParsedBinding> parseBindings(List<KeybindingBlock> blocks) {
        List<ParsedBinding> bindings = new ArrayList<>();
        for (KeybindingBlock block : blocks) {
            for (Map.Entry<String, String> entry : block.bindings().entrySet()) {
                bindings.add(new ParsedBinding(
                        parseChord(entry.getKey()),
                        entry.getValue(),
                        block.context()
                ));
            }
        }
        return bindings;
    }

    private KeybindingParser() {}
}
