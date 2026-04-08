package com.anthropic.claudecode.util;

import java.util.Map;

/**
 * Keyboard shortcut utilities for macOS Option+key detection.
 * Translated from src/utils/keyboardShortcuts.ts
 */
public class KeyboardShortcutsUtils {

    private KeyboardShortcutsUtils() {}

    /**
     * Special characters that macOS Option+key produces, mapped to their
     * keybinding equivalents. Used to detect Option+key shortcuts on macOS
     * terminals that don't have "Option as Meta" enabled.
     * Translated from MACOS_OPTION_SPECIAL_CHARS in keyboardShortcuts.ts
     */
    public static final Map<String, String> MACOS_OPTION_SPECIAL_CHARS = Map.of(
            "\u2020", "alt+t",  // Option+T -> thinking toggle  (†)
            "\u03c0", "alt+p",  // Option+P -> model picker     (π)
            "\u00f8", "alt+o"   // Option+O -> fast mode        (ø)
    );

    /**
     * Check whether a character is a macOS Option+key special character.
     * Translated from isMacosOptionChar() in keyboardShortcuts.ts
     *
     * @param ch The single character string to test
     * @return {@code true} if {@code ch} is one of the known Option+key characters
     */
    public static boolean isMacosOptionChar(String ch) {
        return MACOS_OPTION_SPECIAL_CHARS.containsKey(ch);
    }

    /**
     * Resolve a macOS Option+key character to its keybinding string, e.g. "alt+t".
     *
     * @param ch The single character to resolve
     * @return The keybinding string, or {@code null} if not a known Option+key char
     */
    public static String resolveOptionChar(String ch) {
        return MACOS_OPTION_SPECIAL_CHARS.get(ch);
    }
}
