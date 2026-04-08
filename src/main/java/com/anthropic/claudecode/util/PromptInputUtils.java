package com.anthropic.claudecode.util;

/**
 * Utility methods for prompt-input key handling, display instructions, and
 * keystroke classification.
 * Translated from src/components/PromptInput/utils.ts
 */
public final class PromptInputUtils {

    private PromptInputUtils() {}

    // -------------------------------------------------------------------------
    // Vim-mode helper
    // -------------------------------------------------------------------------

    /**
     * Checks whether vim mode is currently enabled in the global config.
     * Translated from isVimModeEnabled() in utils.ts
     *
     * @param editorMode the editorMode value from the global configuration
     *                   (e.g. "vim" or "default")
     * @return true if editorMode equals "vim"
     */
    public static boolean isVimModeEnabled(String editorMode) {
        return "vim".equals(editorMode);
    }

    // -------------------------------------------------------------------------
    // Newline instruction helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the user-facing instruction string for inserting a newline.
     * Mirrors the terminal/platform detection logic in getNewlineInstructions().
     * Translated from getNewlineInstructions() in utils.ts
     *
     * @param terminal             the TERM / terminal identifier (e.g. "Apple_Terminal", "iTerm2")
     * @param platform             the OS platform identifier (e.g. "darwin", "win32")
     * @param shiftEnterInstalled  whether the Shift+Enter key binding is installed
     * @param hasUsedBackslash     whether the user has previously used backslash+return
     * @return a human-readable newline instruction string
     */
    public static String getNewlineInstructions(
            String terminal,
            String platform,
            boolean shiftEnterInstalled,
            boolean hasUsedBackslash) {

        // Apple Terminal on macOS uses native modifier key detection for Shift+Enter
        if ("Apple_Terminal".equals(terminal) && "darwin".equals(platform)) {
            return "shift + \u23ce for newline";
        }

        // For iTerm2 and VSCode, show Shift+Enter instructions if installed
        if (shiftEnterInstalled) {
            return "shift + \u23ce for newline";
        }

        // Otherwise show backslash+return instructions
        return hasUsedBackslash
                ? "\\\u23ce for newline"
                : "backslash (\\) + return (\u23ce) for newline";
    }

    // -------------------------------------------------------------------------
    // Key classification
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} when the keystroke is a printable character that does
     * not begin with whitespace – i.e. a normal letter, digit, or symbol the user
     * typed. Used to gate the lazy space inserted after an image pill.
     * Translated from isNonSpacePrintable() in utils.ts
     *
     * @param input the raw input string produced by the keystroke
     * @param key   a snapshot of the key modifiers and special-key flags
     * @return true if the input is a non-whitespace printable character
     */
    public static boolean isNonSpacePrintable(String input, KeySnapshot key) {
        if (key == null) {
            return false;
        }
        if (key.ctrl() || key.meta() || key.escape() || key.enter()
                || key.tab() || key.backspace() || key.delete()
                || key.upArrow() || key.downArrow()
                || key.leftArrow() || key.rightArrow()
                || key.pageUp() || key.pageDown()
                || key.home() || key.end()) {
            return false;
        }
        if (input == null || input.isEmpty()) {
            return false;
        }
        // Must not start with whitespace or an ANSI escape sequence
        char first = input.charAt(0);
        return !Character.isWhitespace(first) && !input.startsWith("\u001b");
    }

    // -------------------------------------------------------------------------
    // Supporting record
    // -------------------------------------------------------------------------

    /**
     * Immutable snapshot of modifier and special-key flags for a single keystroke.
     * Translates the Key interface from ink.js used in isNonSpacePrintable().
     */
    public record KeySnapshot(
            boolean ctrl,
            boolean meta,
            boolean escape,
            boolean enter,
            boolean tab,
            boolean backspace,
            boolean delete,
            boolean upArrow,
            boolean downArrow,
            boolean leftArrow,
            boolean rightArrow,
            boolean pageUp,
            boolean pageDown,
            boolean home,
            boolean end) {

        /** Convenience factory – creates a snapshot with all flags false. */
        public static KeySnapshot plain() {
            return new KeySnapshot(false, false, false, false, false, false, false,
                    false, false, false, false, false, false, false, false);
        }
    }
}
