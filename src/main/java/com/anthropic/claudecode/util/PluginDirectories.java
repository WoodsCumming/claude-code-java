package com.anthropic.claudecode.util;

import java.io.File;

/**
 * Plugin directory configuration utilities.
 * Translated from src/utils/plugins/pluginDirectories.ts
 */
public class PluginDirectories {

    private static final String PLUGINS_DIR = "plugins";
    private static final String COWORK_PLUGINS_DIR = "cowork_plugins";

    /**
     * Get the plugins directory name.
     * Translated from getPluginsDirectoryName() in pluginDirectories.ts
     */
    public static String getPluginsDirectoryName() {
        if (EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_COWORK_PLUGINS"))) {
            return COWORK_PLUGINS_DIR;
        }
        return PLUGINS_DIR;
    }

    /**
     * Get the plugins base directory.
     * Translated from getPluginsBaseDir() in pluginDirectories.ts
     */
    public static String getPluginsBaseDir() {
        String cacheDir = System.getenv("CLAUDE_CODE_PLUGIN_CACHE_DIR");
        if (cacheDir != null && !cacheDir.isBlank()) return cacheDir;
        return EnvUtils.getClaudeConfigHomeDir();
    }

    /**
     * Get the plugins directory path.
     * Translated from getPluginsDir() in pluginDirectories.ts
     */
    public static String getPluginsDir() {
        return getPluginsBaseDir() + "/" + getPluginsDirectoryName();
    }

    /**
     * Get the plugin data directory for a specific plugin.
     * Translated from getPluginDataDir() in pluginDirectories.ts
     */
    public static String getPluginDataDir(String pluginName) {
        return getPluginsDir() + "/" + pluginName;
    }

    /**
     * Delete a plugin data directory.
     * Translated from deletePluginDataDir() in pluginDirectories.ts
     */
    public static boolean deletePluginDataDir(String pluginName) {
        File dir = new File(getPluginDataDir(pluginName));
        return deleteDirectory(dir);
    }

    private static boolean deleteDirectory(File dir) {
        if (!dir.exists()) return true;
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    deleteDirectory(f);
                }
            }
        }
        return dir.delete();
    }

    private PluginDirectories() {}
}
