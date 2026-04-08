package com.anthropic.claudecode.util;

/**
 * Utility methods for prompt-input mode detection and text transformation.
 * Translated from src/components/PromptInput/inputModes.ts
 *
 * Supports two input modes: "bash" (prefixed with '!') and "prompt" (default).
 */
public final class PromptInputModes {

    private PromptInputModes() {}

    /**
     * Prepends the mode-specific prefix character to a raw input string.
     * Translated from prependModeCharacterToInput() in inputModes.ts
     *
     * @param input the raw user input
     * @param mode  the current input mode ("bash" or "prompt")
     * @return the input with the appropriate prefix, or input unchanged for "prompt" mode
     */
    public static String prependModeCharacterToInput(String input, String mode) {
        return switch (mode) {
            case "bash" -> "!" + input;
            default -> input;
        };
    }

    /**
     * Infers the history mode from the raw stored input string.
     * Translated from getModeFromInput() in inputModes.ts
     *
     * @param input the raw stored input (may be prefixed with '!')
     * @return "bash" if the input starts with '!', otherwise "prompt"
     */
    public static String getModeFromInput(String input) {
        if (input != null && input.startsWith("!")) {
            return "bash";
        }
        return "prompt";
    }

    /**
     * Strips any mode-prefix character from the stored input and returns the
     * plain value the user actually typed.
     * Translated from getValueFromInput() in inputModes.ts
     *
     * @param input the raw stored input (may be prefixed with '!')
     * @return the input without its mode-prefix character
     */
    public static String getValueFromInput(String input) {
        if (input == null) {
            return "";
        }
        String mode = getModeFromInput(input);
        if ("prompt".equals(mode)) {
            return input;
        }
        // Strip the leading mode character ('!')
        return input.substring(1);
    }

    /**
     * Returns {@code true} when the single character is a mode-switch character.
     * Translated from isInputModeCharacter() in inputModes.ts
     *
     * @param input a single-character string to test
     * @return true if the character triggers a mode change ("!")
     */
    public static boolean isInputModeCharacter(String input) {
        return "!".equals(input);
    }

    // -------------------------------------------------------------------------
    // Mode constants
    // -------------------------------------------------------------------------

    /** Identifier for standard natural-language prompt mode. */
    public static final String MODE_PROMPT = "prompt";

    /** Identifier for bash command mode (input prefixed with '!'). */
    public static final String MODE_BASH = "bash";
}
