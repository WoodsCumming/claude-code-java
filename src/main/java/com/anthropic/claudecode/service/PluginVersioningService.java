package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.GitFilesystem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.security.MessageDigest;
import java.util.*;

/**
 * Plugin versioning service.
 * Translated from src/utils/plugins/pluginVersioning.ts
 *
 * Handles version calculation for plugins from various sources.
 */
@Slf4j
@Service
public class PluginVersioningService {



    /**
     * Calculate the version for a plugin.
     * Translated from calculatePluginVersion() in pluginVersioning.ts
     *
     * Version sources (in order of preference):
     * 1. Explicit version from plugin.json
     * 2. Git commit SHA
     * 3. Fallback timestamp
     */
    public String calculatePluginVersion(
            String pluginPath,
            String manifestVersion,
            String sourceType) {

        // Use explicit version if available
        if (manifestVersion != null && !manifestVersion.isBlank()) {
            return manifestVersion;
        }

        // Try git commit SHA
        if ("github".equals(sourceType) || "git".equals(sourceType)) {
            Optional<String> sha = GitFilesystem.readHeadSha(pluginPath);
            if (sha.isPresent()) {
                return sha.get().substring(0, 7);
            }
        }

        // Fallback to timestamp-based version
        return "local-" + Long.toString(System.currentTimeMillis(), 36);
    }

    /**
     * Get the versioned cache path for a plugin.
     * Translated from getVersionedCachePath() in pluginVersioning.ts
     */
    public String getVersionedCachePath(String baseCachePath, String version) {
        if (version == null || version.isBlank()) return baseCachePath;
        return baseCachePath + "/" + version;
    }

    /**
     * Hash a plugin path for stable cache key.
     */
    public String hashPluginPath(String path) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(path.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString().substring(0, 16);
        } catch (Exception e) {
            return Long.toString(path.hashCode(), 36);
        }
    }
}
