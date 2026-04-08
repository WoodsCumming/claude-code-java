package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Plugin installation helpers service.
 * Translated from src/utils/plugins/pluginInstallationHelpers.ts
 *
 * Common utilities for plugin installation.
 */
@Slf4j
@Service
public class PluginInstallationHelpersService {



    private final AnalyticsService analyticsService;

    @Autowired
    public PluginInstallationHelpersService(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    /**
     * Atomically replace a directory.
     * Translated from atomicReplaceDir() in pluginInstallationHelpers.ts
     */
    public CompletableFuture<Void> atomicReplaceDir(String tempDir, String targetDir) {
        return CompletableFuture.runAsync(() -> {
            try {
                File target = new File(targetDir);
                File temp = new File(tempDir);

                // Move existing to backup
                if (target.exists()) {
                    File backup = new File(targetDir + ".old." + System.currentTimeMillis());
                    target.renameTo(backup);
                    // Clean up backup asynchronously
                    CompletableFuture.runAsync(() -> deleteDirectory(backup));
                }

                // Move temp to target
                temp.renameTo(target);
            } catch (Exception e) {
                log.error("Failed to atomically replace directory: {}", e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    private void deleteDirectory(File dir) {
        try {
            Files.walk(dir.toPath())
                .sorted(java.util.Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        } catch (Exception e) {
            log.debug("Could not delete directory {}: {}", dir, e.getMessage());
        }
    }

    /**
     * Track a plugin installation event.
     * Translated from trackPluginInstall() in pluginInstallationHelpers.ts
     */
    public void trackPluginInstall(String pluginId, String marketplace, boolean success) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("plugin_id", pluginId);
        props.put("marketplace", marketplace);
        props.put("success", success);

        analyticsService.logEvent("tengu_plugin_installed", props);
    }
}
