package com.anthropic.claudecode.util;

import com.anthropic.claudecode.util.KeybindingParser.Chord;
import com.anthropic.claudecode.util.KeybindingParser.ParsedBinding;
import com.anthropic.claudecode.util.KeybindingParser.ParsedKeystroke;

/**
 * Keybinding matching utilities.
 * Translated from src/keybindings/match.ts
 *
 * Matches runtime key events (expressed as modifier flags + key name) against
 * parsed keybinding definitions.
 */
public class KeybindingMatch {

    /**
     * Modifier state for an incoming key event.
     * Mirrors the Ink Key boolean modifiers used in the TypeScript source.
     */
    public record InkModifiers(
            boolean ctrl,
            boolean shift,
            boolean meta,
            boolean sup  // 'super' is a Java keyword
    ) {}

    /**
     * Extract the normalized key name from key event flags and a raw input string.
     * Maps boolean key flags to string names that match ParsedKeystroke.key.
     * Translated from getKeyName() in match.ts
     *
     * @param input   raw character input (single char for printable keys)
     * @param escape    true if the Escape key was pressed
     * @param enter     true if the Enter/Return key was pressed
     * @param tab       true if Tab was pressed
     * @param backspace true if Backspace was pressed
     * @param delete    true if Delete was pressed
     * @param upArrow   true if Up Arrow was pressed
     * @param downArrow true if Down Arrow was pressed
     * @param leftArrow true if Left Arrow was pressed
     * @param rightArrow true if Right Arrow was pressed
     * @param pageUp    true if Page Up was pressed
     * @param pageDown  true if Page Down was pressed
     * @param wheelUp   true if mouse wheel scrolled up
     * @param wheelDown true if mouse wheel scrolled down
     * @param home      true if Home was pressed
     * @param end       true if End was pressed
     * @return normalized key name string, or null if unrecognized
     */
    public static String getKeyName(
            String input,
            boolean escape, boolean enter, boolean tab,
            boolean backspace, boolean delete,
            boolean upArrow, boolean downArrow, boolean leftArrow, boolean rightArrow,
            boolean pageUp, boolean pageDown,
            boolean wheelUp, boolean wheelDown,
            boolean home, boolean end) {

        if (escape) return "escape";
        if (enter) return "enter";
        if (tab) return "tab";
        if (backspace) return "backspace";
        if (delete) return "delete";
        if (upArrow) return "up";
        if (downArrow) return "down";
        if (leftArrow) return "left";
        if (rightArrow) return "right";
        if (pageUp) return "pageup";
        if (pageDown) return "pagedown";
        if (wheelUp) return "wheelup";
        if (wheelDown) return "wheeldown";
        if (home) return "home";
        if (end) return "end";
        if (input != null && input.length() == 1) return input.toLowerCase();
        return null;
    }

    /**
     * Check if all modifiers match between an incoming key event and a ParsedKeystroke.
     *
     * Alt and Meta: Ink historically sets meta=true for Alt/Option. A 'meta'
     * modifier in config is treated as an alias for 'alt' — both match when
     * the meta flag is true.
     *
     * Super (Cmd/Win): distinct from alt/meta. Only arrives via the kitty
     * keyboard protocol on supporting terminals.
     *
     * Translated from modifiersMatch() in match.ts
     */
    private static boolean modifiersMatch(InkModifiers inkMods, ParsedKeystroke target) {
        // Check ctrl modifier
        if (inkMods.ctrl() != target.ctrl()) return false;

        // Check shift modifier
        if (inkMods.shift() != target.shift()) return false;

        // Alt and meta both map to the meta flag in Ink (terminal limitation).
        // So we check if EITHER alt OR meta is required in target.
        boolean targetNeedsMeta = target.alt() || target.meta();
        if (inkMods.meta() != targetNeedsMeta) return false;

        // Super (cmd/win) is a distinct modifier from alt/meta
        if (inkMods.sup() != target.sup()) return false;

        return true;
    }

    /**
     * Check if a ParsedKeystroke matches the given key event.
     * Translated from matchesKeystroke() in match.ts
     *
     * @param input     raw character input string
     * @param inkMods   modifier flags from the key event
     * @param isEscape  whether this is an escape key event (special meta quirk handling)
     * @param target    the keystroke definition to match against
     */
    public static boolean matchesKeystroke(
            String input,
            InkModifiers inkMods,
            boolean isEscape,
            ParsedKeystroke target) {

        // Derive key name from input — caller must provide the boolean flags
        // via getKeyName() and then pass the result; this overload takes a
        // pre-resolved keyName for flexibility.
        String keyName = getKeyNameFromInput(input, inkMods, isEscape);
        if (keyName == null || !keyName.equals(target.key())) return false;

        // QUIRK: Ink sets meta=true when escape is pressed (legacy escape-sequence behavior).
        // Ignore the meta modifier when matching the escape key itself so that
        // bindings like "escape" (without modifiers) still match.
        if (isEscape) {
            return modifiersMatch(new InkModifiers(inkMods.ctrl(), inkMods.shift(), false, inkMods.sup()), target);
        }

        return modifiersMatch(inkMods, target);
    }

    /**
     * Internal helper: resolve key name from raw input and modifier state.
     * For simple single-char input the key name is the lowercase character.
     * Special keys must be indicated via a convention: pass the key name
     * directly as the input string (e.g. "escape", "enter", "up").
     * This mirrors the flag-based getKeyName() but works with pre-resolved names.
     */
    private static String getKeyNameFromInput(String input, InkModifiers mods, boolean isEscape) {
        if (isEscape) return "escape";
        if (input == null) return null;
        // Allow callers to pass special key names as the input string directly
        return switch (input) {
            case "escape" -> "escape";
            case "enter", "return" -> "enter";
            case "tab" -> "tab";
            case "backspace" -> "backspace";
            case "delete" -> "delete";
            case "up" -> "up";
            case "down" -> "down";
            case "left" -> "left";
            case "right" -> "right";
            case "pageup" -> "pageup";
            case "pagedown" -> "pagedown";
            case "wheelup" -> "wheelup";
            case "wheeldown" -> "wheeldown";
            case "home" -> "home";
            case "end" -> "end";
            default -> input.length() == 1 ? input.toLowerCase() : null;
        };
    }

    /**
     * Check if a key event matches a parsed binding's first keystroke.
     * Only works for single-keystroke bindings (chord length == 1).
     * Translated from matchesBinding() in match.ts
     *
     * @param input    raw character input
     * @param inkMods  modifier flags
     * @param isEscape whether this is an escape key event
     * @param binding  the binding to test against
     */
    public static boolean matchesBinding(
            String input,
            InkModifiers inkMods,
            boolean isEscape,
            ParsedBinding binding) {

        Chord chord = binding.chord();
        if (chord.length() != 1) return false;
        ParsedKeystroke keystroke = chord.get(0);
        if (keystroke == null) return false;
        return matchesKeystroke(input, inkMods, isEscape, keystroke);
    }

    private KeybindingMatch() {}
}
