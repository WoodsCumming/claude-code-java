package com.anthropic.claudecode.util;

/**
 * Render options for terminal output.
 * Translated from src/utils/renderOptions.ts
 */
public class RenderOptions {

    /**
     * Check if the terminal supports ANSI colors.
     */
    public static boolean supportsAnsi() {
        String term = System.getenv("TERM");
        String colorterm = System.getenv("COLORTERM");
        String noColor = System.getenv("NO_COLOR");

        if (noColor != null) return false;

        if (colorterm != null && (colorterm.equals("truecolor") || colorterm.equals("24bit"))) {
            return true;
        }

        if (term != null && (term.contains("256color") || term.contains("color"))) {
            return true;
        }

        // Check if stdout is a TTY
        return System.console() != null;
    }

    /**
     * Check if we should use full-screen mode.
     */
    public static boolean useFullScreen() {
        return !EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_NO_FULLSCREEN"))
            && System.console() != null;
    }

    /**
     * Get the terminal width.
     */
    public static int getTerminalWidth() {
        try {
            // Try to get terminal width from environment
            String columns = System.getenv("COLUMNS");
            if (columns != null) {
                return Integer.parseInt(columns);
            }

            // Default
            return 80;
        } catch (Exception e) {
            return 80;
        }
    }

    /**
     * Get the terminal height.
     */
    public static int getTerminalHeight() {
        try {
            String lines = System.getenv("LINES");
            if (lines != null) {
                return Integer.parseInt(lines);
            }
            return 24;
        } catch (Exception e) {
            return 24;
        }
    }

    private RenderOptions() {}
}
