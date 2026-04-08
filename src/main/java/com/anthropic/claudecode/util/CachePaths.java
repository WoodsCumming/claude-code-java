package com.anthropic.claudecode.util;

import java.io.File;

/**
 * Cache path utilities.
 * Translated from src/utils/cachePaths.ts
 *
 * Provides paths for various cache directories.
 */
public class CachePaths {

    private static final int MAX_SANITIZED_LENGTH = 200;

    /**
     * Get the base cache directory.
     */
    public static String getBaseCacheDir() {
        String cacheDir = System.getenv("XDG_CACHE_HOME");
        if (cacheDir == null || cacheDir.isBlank()) {
            cacheDir = System.getProperty("user.home") + File.separator + ".cache";
        }
        return cacheDir + File.separator + "claude-cli";
    }

    /**
     * Sanitize a path component.
     * Translated from sanitizePath() in cachePaths.ts
     */
    public static String sanitizePath(String name) {
        if (name == null) return "";
        String sanitized = name.replaceAll("[^a-zA-Z0-9]", "-");
        if (sanitized.length() <= MAX_SANITIZED_LENGTH) {
            return sanitized;
        }
        int hash = Math.abs(djb2Hash(name));
        return sanitized.substring(0, MAX_SANITIZED_LENGTH) + "-" + Integer.toString(hash, 36);
    }

    /**
     * Get the project-specific cache directory.
     */
    public static String getProjectCacheDir(String cwd) {
        return getBaseCacheDir() + File.separator + sanitizePath(cwd);
    }

    /**
     * Get the errors cache directory.
     */
    public static String getErrorsCacheDir() {
        return getProjectCacheDir(System.getProperty("user.dir")) + File.separator + "errors";
    }

    /**
     * Get the messages cache directory.
     */
    public static String getMessagesCacheDir() {
        return getProjectCacheDir(System.getProperty("user.dir")) + File.separator + "messages";
    }

    /**
     * DJB2 hash function.
     * Translated from djb2Hash() in hash.ts
     */
    public static int djb2Hash(String str) {
        int hash = 5381;
        for (char c : str.toCharArray()) {
            hash = ((hash << 5) + hash) + c;
        }
        return hash;
    }

    private CachePaths() {}
}
