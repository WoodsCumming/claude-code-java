package com.anthropic.claudecode.model;

/**
 * UI figure constants.
 * Translated from src/constants/figures.ts
 */
public class Figures {

    // Platform-specific circle (macOS uses ⏺, others use ●)
    public static final String BLACK_CIRCLE = isMacOS() ? "\u23FA" : "\u25CF";
    public static final String BULLET_OPERATOR = "\u2219";
    public static final String TEARDROP_ASTERISK = "\u273B";
    public static final String UP_ARROW = "\u2191";
    public static final String DOWN_ARROW = "\u2193";
    public static final String LIGHTNING_BOLT = "\u21AF";

    // Effort level indicators
    public static final String EFFORT_LOW = "\u25CB";
    public static final String EFFORT_MEDIUM = "\u25D0";
    public static final String EFFORT_HIGH = "\u25CF";
    public static final String EFFORT_MAX = "\u25C9";

    // Media/trigger status
    public static final String PLAY_ICON = "\u25B6";
    public static final String PAUSE_ICON = "\u23F8";

    // MCP subscription indicators
    public static final String REFRESH_ARROW = "\u21BB";
    public static final String CHANNEL_ARROW = "\u2190";
    public static final String INJECTED_ARROW = "\u2192";
    public static final String FORK_GLYPH = "\u2442";

    // Review status
    public static final String DIAMOND_OPEN = "\u25C7";
    public static final String DIAMOND_FILLED = "\u25C6";
    public static final String REFERENCE_MARK = "\u203B";

    // Other
    public static final String FLAG_ICON = "\u2691";
    public static final String BLOCKQUOTE_BAR = "\u258E";
    public static final String HEAVY_HORIZONTAL = "\u2501";

    private static boolean isMacOS() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    private Figures() {}
}
