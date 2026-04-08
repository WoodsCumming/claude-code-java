package com.anthropic.claudecode.model;

import java.util.Map;

/**
 * Theme color definitions for terminal output.
 * Translated from src/utils/theme.ts
 */
public class ThemeColors {

    public enum ThemeName {
        DARK("dark"),
        LIGHT("light"),
        SYSTEM("system");

        private final String value;
        ThemeName(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    // Dark theme ANSI color codes
    public static final Map<String, String> DARK_THEME = Map.ofEntries(
        Map.entry("claude", "\u001b[38;5;208m"),      // Orange
        Map.entry("permission", "\u001b[38;5;214m"),   // Yellow-orange
        Map.entry("text", "\u001b[0m"),                // Default
        Map.entry("inverseText", "\u001b[7m"),         // Inverse
        Map.entry("inactive", "\u001b[38;5;240m"),     // Gray
        Map.entry("subtle", "\u001b[38;5;245m"),       // Light gray
        Map.entry("success", "\u001b[32m"),            // Green
        Map.entry("error", "\u001b[31m"),              // Red
        Map.entry("warning", "\u001b[33m"),            // Yellow
        Map.entry("diffAdded", "\u001b[32m"),          // Green
        Map.entry("diffRemoved", "\u001b[31m"),        // Red
        Map.entry("planMode", "\u001b[35m"),           // Purple
        Map.entry("ide", "\u001b[36m"),                // Cyan
        Map.entry("reset", "\u001b[0m")                // Reset
    );

    // Light theme ANSI color codes
    public static final Map<String, String> LIGHT_THEME = Map.ofEntries(
        Map.entry("claude", "\u001b[38;5;202m"),       // Dark orange
        Map.entry("permission", "\u001b[38;5;172m"),   // Dark yellow
        Map.entry("text", "\u001b[0m"),                // Default
        Map.entry("inverseText", "\u001b[7m"),         // Inverse
        Map.entry("inactive", "\u001b[38;5;250m"),     // Light gray
        Map.entry("subtle", "\u001b[38;5;247m"),       // Medium gray
        Map.entry("success", "\u001b[32m"),            // Green
        Map.entry("error", "\u001b[31m"),              // Red
        Map.entry("warning", "\u001b[33m"),            // Yellow
        Map.entry("diffAdded", "\u001b[32m"),          // Green
        Map.entry("diffRemoved", "\u001b[31m"),        // Red
        Map.entry("planMode", "\u001b[35m"),           // Purple
        Map.entry("ide", "\u001b[36m"),                // Cyan
        Map.entry("reset", "\u001b[0m")                // Reset
    );

    /**
     * Get color code for a theme key.
     */
    public static String getColor(String key, ThemeName theme) {
        Map<String, String> colors = theme == ThemeName.LIGHT ? LIGHT_THEME : DARK_THEME;
        return colors.getOrDefault(key, "");
    }

    /**
     * Resolve a theme color key to its hex/ANSI string value.
     * Delegates to getColor(), accepting a theme name as a String.
     */
    public static String resolve(String colorKey, String themeName) {
        ThemeName theme = "light".equalsIgnoreCase(themeName) ? ThemeName.LIGHT : ThemeName.DARK;
        return getColor(colorKey, theme);
    }

    private ThemeColors() {}
}
