package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.DxtUtils;
import com.anthropic.claudecode.util.EnvUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Plugin zip cache service.
 * Translated from src/utils/plugins/zipCache.ts
 *
 * Manages plugins as ZIP archives in a mounted directory.
 */
@Slf4j
@Service
public class PluginZipCacheService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PluginZipCacheService.class);


    /**
     * Check if zip cache mode is enabled.
     * Translated from isZipCacheEnabled() in zipCache.ts
     */
    public boolean isZipCacheEnabled() {
        return EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_PLUGIN_USE_ZIP_CACHE"))
            && System.getenv("CLAUDE_CODE_PLUGIN_CACHE_DIR") != null;
    }

    /**
     * Get the zip cache directory.
     */
    public String getZipCacheDir() {
        return System.getenv("CLAUDE_CODE_PLUGIN_CACHE_DIR");
    }

    /**
     * Extract a plugin from zip cache.
     * Translated from extractPluginFromZipCache() in zipCache.ts
     */
    public CompletableFuture<Optional<String>> extractPluginFromZipCache(
            String pluginId,
            String targetDir) {

        return CompletableFuture.supplyAsync(() -> {
            if (!isZipCacheEnabled()) return Optional.empty();

            String cacheDir = getZipCacheDir();
            String zipPath = cacheDir + "/plugins/" + pluginId + ".zip";

            File zipFile = new File(zipPath);
            if (!zipFile.exists()) return Optional.empty();

            try {
                Path targetPath = Paths.get(targetDir, pluginId);
                Files.createDirectories(targetPath);
                DxtUtils.extractDxt(zipFile.toPath(), targetPath);
                log.debug("Extracted plugin {} from zip cache", pluginId);
                return Optional.of(targetPath.toString());
            } catch (Exception e) {
                log.debug("Could not extract plugin {} from zip cache: {}", pluginId, e.getMessage());
                return Optional.empty();
            }
        });
    }
}
