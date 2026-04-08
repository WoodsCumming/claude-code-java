package com.anthropic.claudecode.util;

/**
 * Hyperlink utility functions for terminal output.
 * Translated from src/utils/hyperlink.ts
 *
 * Provides OSC 8 hyperlink escape sequences for clickable terminal links.
 */
public class HyperlinkUtils {

    // OSC 8 hyperlink escape sequences
    // Format: \e]8;;URL\e\\TEXT\e]8;;\e\\
    // Using BEL (0x07) as terminator which is more widely supported
    public static final String OSC8_START = "\u001b]8;;";
    public static final String OSC8_END = "\u0007";

    /**
     * Create a clickable hyperlink using OSC 8 escape sequences.
     * Falls back to plain text if the terminal doesn't support hyperlinks.
     * Translated from createHyperlink() in hyperlink.ts
     *
     * @param url     The URL to link to
     * @param content Optional content to display as the link text (only when hyperlinks are supported).
     *                If provided and hyperlinks are supported, this text is shown as a clickable link.
     *                If hyperlinks are not supported, content is ignored and only the URL is shown.
     * @return A hyperlink string with OSC 8 sequences, or the plain URL if unsupported
     */
    public static String createHyperlink(String url, String content) {
        if (!supportsHyperlinks()) {
            return url;
        }
        String displayText = (content != null && !content.isEmpty()) ? content : url;
        // Apply ANSI blue color (\u001b[34m ... \u001b[0m)
        String coloredText = "\u001b[34m" + displayText + "\u001b[0m";
        return OSC8_START + url + OSC8_END + coloredText + OSC8_START + OSC8_END;
    }

    /**
     * Create a clickable hyperlink using the URL as both href and display text.
     *
     * @param url The URL to link to
     * @return A hyperlink string with OSC 8 sequences, or the plain URL if unsupported
     */
    public static String createHyperlink(String url) {
        return createHyperlink(url, null);
    }

    /**
     * Determine if the current terminal supports OSC 8 hyperlinks.
     * Mirrors the logic from supports-hyperlinks npm package.
     *
     * @return true if OSC 8 hyperlinks are supported, false otherwise
     */
    public static boolean supportsHyperlinks() {
        // Check environment variables that signal hyperlink support
        String term = System.getenv("TERM");
        String termProgram = System.getenv("TERM_PROGRAM");
        String vtermVersion = System.getenv("VTE_VERSION");
        String colorfgbg = System.getenv("COLORTERM");

        // Explicit opt-in/opt-out
        String forceHyperlinks = System.getenv("FORCE_HYPERLINK");
        if (forceHyperlinks != null) {
            return !"0".equals(forceHyperlinks.trim());
        }

        // Known supporting terminals
        if ("iTerm.app".equals(termProgram) || "Hyper".equals(termProgram)) {
            return true;
        }

        // VTE-based terminals (GNOME Terminal, etc.) — need version >= 0.50
        if (vtermVersion != null) {
            try {
                int vte = Integer.parseInt(vtermVersion);
                return vte >= 5000;
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }

        // Windows Terminal supports hyperlinks
        String wtSession = System.getenv("WT_SESSION");
        if (wtSession != null) {
            return true;
        }

        // xterm-256color generally supports OSC 8
        if ("xterm-256color".equals(term) || "xterm".equals(term)) {
            return colorfgbg != null;
        }

        return false;
    }

    private HyperlinkUtils() {}
}
