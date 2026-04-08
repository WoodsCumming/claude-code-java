package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.PluginTypes;
import com.anthropic.claudecode.util.PluginDirectories;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Plugin loader service.
 * Translated from src/utils/plugins/pluginLoader.ts
 *
 * Discovers, loads, and validates Claude Code plugins.
 */
@Slf4j
@Service
public class PluginLoaderService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PluginLoaderService.class);


    private final ObjectMapper objectMapper;
    private volatile List<PluginTypes.LoadedPlugin> cachedPlugins;

    @Autowired
    public PluginLoaderService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Load all installed plugins.
     * Translated from loadAllPluginsCacheOnly() in pluginLoader.ts
     */
    public List<PluginTypes.LoadedPlugin> loadAllPluginsCacheOnly() {
        if (cachedPlugins != null) return cachedPlugins;
        return loadAllInstalledPlugins();
    }

    /**
     * Load all installed plugins from disk.
     */
    public List<PluginTypes.LoadedPlugin> loadAllInstalledPlugins() {
        List<PluginTypes.LoadedPlugin> plugins = new ArrayList<>();
        String pluginsDir = PluginDirectories.getPluginsDir();
        File dir = new File(pluginsDir);

        if (!dir.isDirectory()) return plugins;

        File[] subdirs = dir.listFiles(File::isDirectory);
        if (subdirs == null) return plugins;

        for (File pluginDir : subdirs) {
            try {
                PluginTypes.LoadedPlugin plugin = loadPlugin(pluginDir);
                if (plugin != null) {
                    plugins.add(plugin);
                }
            } catch (Exception e) {
                log.debug("Could not load plugin {}: {}", pluginDir.getName(), e.getMessage());
            }
        }

        cachedPlugins = plugins;
        return plugins;
    }

    private PluginTypes.LoadedPlugin loadPlugin(File pluginDir) throws Exception {
        String name = pluginDir.getName();

        // Try to load manifest
        File manifestFile = new File(pluginDir, "plugin.json");
        String version = null;
        String description = null;

        if (manifestFile.exists()) {
            try {
                Map<String, Object> manifest = objectMapper.readValue(manifestFile, Map.class);
                version = (String) manifest.get("version");
                description = (String) manifest.get("description");
            } catch (Exception e) {
                log.debug("Could not parse plugin manifest: {}", e.getMessage());
            }
        }

        return PluginTypes.LoadedPlugin.builder()
            .name(name)
            .path(pluginDir.getAbsolutePath())
            .source(PluginDirectories.getPluginsDir())
            .repository(name)
            .enabled(true)
            .isBuiltin(false)
            .version(version)
            .description(description)
            .commandsPath(new File(pluginDir, "commands").exists()
                ? new File(pluginDir, "commands").getAbsolutePath() : null)
            .agentsPath(new File(pluginDir, "agents").exists()
                ? new File(pluginDir, "agents").getAbsolutePath() : null)
            .outputStylesPath(new File(pluginDir, "output-styles").exists()
                ? new File(pluginDir, "output-styles").getAbsolutePath() : null)
            .build();
    }

    /**
     * Clear plugin cache.
     */
    public void clearCache() {
        cachedPlugins = null;
    }

    /**
     * Clear plugin cache with a reason (alias for clearCache).
     */
    public void clearPluginCache(String reason) {
        log.debug("clearPluginCache: {}", reason);
        clearCache();
    }
}
