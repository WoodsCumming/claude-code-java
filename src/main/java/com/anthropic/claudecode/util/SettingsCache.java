package com.anthropic.claudecode.util;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Settings cache utilities.
 * Translated from src/utils/settings/settingsCache.ts
 *
 * Per-source cache for settings.
 */
public class SettingsCache {

    private static volatile Map<String, Object> sessionSettingsCache;
    private static final Map<String, Map<String, Object>> perSourceCache = new ConcurrentHashMap<>();

    /**
     * Get the session settings cache.
     * Translated from getSessionSettingsCache() in settingsCache.ts
     */
    public static Map<String, Object> getSessionSettingsCache() {
        return sessionSettingsCache;
    }

    /**
     * Set the session settings cache.
     * Translated from setSessionSettingsCache() in settingsCache.ts
     */
    public static void setSessionSettingsCache(Map<String, Object> value) {
        sessionSettingsCache = value;
    }

    /**
     * Get per-source cache.
     * Translated from getPerSourceCache() in settingsCache.ts
     */
    public static Map<String, Object> getPerSourceCache(String source) {
        return perSourceCache.get(source);
    }

    /**
     * Set per-source cache.
     */
    public static void setPerSourceCache(String source, Map<String, Object> settings) {
        perSourceCache.put(source, settings);
    }

    /**
     * Reset all settings caches.
     * Translated from resetSettingsCache() in settingsCache.ts
     */
    public static void resetSettingsCache() {
        sessionSettingsCache = null;
        perSourceCache.clear();
    }

    private SettingsCache() {}
}
