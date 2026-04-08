package com.anthropic.claudecode.model;

import java.util.List;

/**
 * Configuration constants.
 * Translated from src/utils/configConstants.ts
 */
public class ConfigConstants {

    public static final List<String> NOTIFICATION_CHANNELS = List.of(
        "auto",
        "iterm2",
        "iterm2_with_bell",
        "terminal_bell",
        "kitty",
        "ghostty",
        "notifications_disabled"
    );

    public static final List<String> EDITOR_MODES = List.of(
        "normal",
        "vim"
    );

    public static final List<String> TEAMMATE_MODES = List.of(
        "auto",
        "tmux",
        "in-process"
    );

    private ConfigConstants() {}
}
