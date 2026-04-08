package com.anthropic.claudecode.util;

import java.util.Map;

/**
 * Ink color and text-property utilities.
 *
 * Translated from src/utils/ink.ts
 *
 * <p>In the TypeScript front-end, Ink renders React components to the terminal.
 * In the Java back-end we model the same color-resolution logic so that any
 * component or service that needs to map agent colors to theme keys can use
 * this utility without depending on a UI renderer.</p>
 */
public final class InkUtils {

    /**
     * Default theme color used when no agent color is specified.
     * Translated from {@code DEFAULT_AGENT_THEME_COLOR} in ink.ts
     */
    public static final String DEFAULT_AGENT_THEME_COLOR = "cyan_FOR_SUBAGENTS_ONLY";

    /**
     * Maps agent color names to theme-aware color keys.
     *
     * <p>These are the same entries as {@code AGENT_COLOR_TO_THEME_COLOR} in
     * {@code src/tools/AgentTool/agentColorManager.ts}. Theme keys are used so
     * the resolved color respects the active terminal theme (dark/light) rather
     * than hardcoding ANSI color numbers.</p>
     *
     * Translated from {@code AGENT_COLOR_TO_THEME_COLOR} in agentColorManager.ts
     */
    private static final Map<String, String> AGENT_COLOR_TO_THEME_COLOR = Map.ofEntries(
            Map.entry("blue",   "blue_FOR_SUBAGENTS_ONLY"),
            Map.entry("green",  "green_FOR_SUBAGENTS_ONLY"),
            Map.entry("yellow", "yellow_FOR_SUBAGENTS_ONLY"),
            Map.entry("red",    "red_FOR_SUBAGENTS_ONLY"),
            Map.entry("purple", "magenta_FOR_SUBAGENTS_ONLY"),
            Map.entry("cyan",   "cyan_FOR_SUBAGENTS_ONLY"),
            Map.entry("white",  "white_FOR_SUBAGENTS_ONLY"),
            Map.entry("orange", "yellow_FOR_SUBAGENTS_ONLY") // closest ANSI approximation
    );

    /**
     * Convert a color string to a theme-aware Ink color key.
     *
     * <p>Colors are typically {@code AgentColorName} values like {@code "blue"},
     * {@code "green"}, etc. Known agent colors are mapped to theme keys so they
     * respect the current terminal theme. Unknown colors fall back to a raw ANSI
     * color reference in the form {@code "ansi:{color}"}.</p>
     *
     * @param color agent color name, or {@code null} / empty for the default
     * @return theme color key string, or {@code "ansi:{color}"} for unknown colors
     * Translated from {@code toInkColor()} in ink.ts
     */
    public static String toInkColor(String color) {
        if (color == null || color.isEmpty()) {
            return DEFAULT_AGENT_THEME_COLOR;
        }
        String themeColor = AGENT_COLOR_TO_THEME_COLOR.get(color);
        if (themeColor != null) {
            return themeColor;
        }
        // Fall back to raw ANSI color for unknown colors
        return "ansi:" + color;
    }

    private InkUtils() {}
}
