package com.anthropic.claudecode.util;

import java.io.File;

/**
 * XDG Base Directory utilities.
 * Translated from src/utils/xdg.ts
 *
 * Implements the XDG Base Directory specification for organizing
 * native installer components across appropriate system directories.
 *
 * @see https://specifications.freedesktop.org/basedir-spec/latest/
 */
public class XdgUtils {

    /**
     * Get XDG state home directory.
     * Default: ~/.local/state
     */
    public static String getXDGStateHome() {
        String xdgStateHome = System.getenv("XDG_STATE_HOME");
        if (xdgStateHome != null && !xdgStateHome.isBlank()) return xdgStateHome;
        return System.getProperty("user.home") + File.separator + ".local" + File.separator + "state";
    }

    /**
     * Get XDG cache home directory.
     * Default: ~/.cache
     */
    public static String getXDGCacheHome() {
        String xdgCacheHome = System.getenv("XDG_CACHE_HOME");
        if (xdgCacheHome != null && !xdgCacheHome.isBlank()) return xdgCacheHome;
        return System.getProperty("user.home") + File.separator + ".cache";
    }

    /**
     * Get XDG data home directory.
     * Default: ~/.local/share
     */
    public static String getXDGDataHome() {
        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        if (xdgDataHome != null && !xdgDataHome.isBlank()) return xdgDataHome;
        return System.getProperty("user.home") + File.separator + ".local" + File.separator + "share";
    }

    /**
     * Get XDG config home directory.
     * Default: ~/.config
     */
    public static String getXDGConfigHome() {
        String xdgConfigHome = System.getenv("XDG_CONFIG_HOME");
        if (xdgConfigHome != null && !xdgConfigHome.isBlank()) return xdgConfigHome;
        return System.getProperty("user.home") + File.separator + ".config";
    }

    /**
     * Get XDG bin home directory.
     * Default: ~/.local/bin
     */
    public static String getUserBinDir() {
        return System.getProperty("user.home") + File.separator + ".local" + File.separator + "bin";
    }

    private XdgUtils() {}
}
