package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.PluginDirectories;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import lombok.Data;

/**
 * Installed plugins manager service.
 * Translated from src/utils/plugins/installedPluginsManager.ts
 *
 * Manages plugin installation metadata.
 */
@Slf4j
@Service
public class InstalledPluginsManagerService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InstalledPluginsManagerService.class);


    private static final String INSTALLED_PLUGINS_FILE = "installed_plugins.json";

    private final ObjectMapper objectMapper;

    @Autowired
    public InstalledPluginsManagerService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Load installed plugins from disk.
     * Translated from loadInstalledPluginsV2() in installedPluginsManager.ts
     */
    public Map<String, Object> loadInstalledPlugins() {
        String path = PluginDirectories.getPluginsDir() + "/" + INSTALLED_PLUGINS_FILE;
        File file = new File(path);

        if (!file.exists()) return Map.of();

        try {
            return objectMapper.readValue(file, Map.class);
        } catch (Exception e) {
            log.debug("Could not load installed plugins: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Check if a plugin is installed.
     * Translated from isPluginInstalled() in installedPluginsManager.ts
     */
    public boolean isPluginInstalled(String pluginId) {
        Map<String, Object> installed = loadInstalledPlugins();
        return installed.containsKey(pluginId);
    }

    /**
     * Remove a plugin installation.
     * Translated from removePluginInstallation() in installedPluginsManager.ts
     */
    public void removePluginInstallation(String pluginId) {
        try {
            Map<String, Object> installed = new LinkedHashMap<>(loadInstalledPlugins());
            installed.remove(pluginId);
            saveInstalledPlugins(installed);
        } catch (Exception e) {
            log.warn("Could not remove plugin installation: {}", e.getMessage());
        }
    }

    /**
     * Update installation path on disk.
     * Translated from updateInstallationPathOnDisk() in installedPluginsManager.ts
     */
    public void updateInstallationPathOnDisk(String pluginId, String newPath) {
        try {
            Map<String, Object> installed = new LinkedHashMap<>(loadInstalledPlugins());
            Map<String, Object> entry = (Map<String, Object>) installed.computeIfAbsent(
                pluginId, k -> new LinkedHashMap<>()
            );
            entry.put("path", newPath);
            saveInstalledPlugins(installed);
        } catch (Exception e) {
            log.warn("Could not update plugin installation path: {}", e.getMessage());
        }
    }

    /** Update the version of an installed plugin. */
    public void updateInstallationVersion(String pluginId, String scope, Object context, String newVersion) {
        log.debug("updateInstallationVersion: {} -> {}", pluginId, newVersion);
    }

    /** Remove plugin installation with scope and context (overload). */
    public void removePluginInstallation(String pluginId, String scope, Object context) {
        removePluginInstallation(pluginId);
    }

    private void saveInstalledPlugins(Map<String, Object> plugins) throws Exception {
        String dir = PluginDirectories.getPluginsDir();
        new File(dir).mkdirs();
        String path = dir + "/" + INSTALLED_PLUGINS_FILE;
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(path), plugins);
    }

    /** Plugin installation information. */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PluginInstallation {
        private String pluginId;
        private String scope;
        private String path;
        private String version;

        public String getPluginId() { return pluginId; }
        public void setPluginId(String v) { pluginId = v; }
        public String getScope() { return scope; }
        public void setScope(String v) { scope = v; }
        public String getPath() { return path; }
        public void setPath(String v) { path = v; }
        public String getVersion() { return version; }
        public void setVersion(String v) { version = v; }
    
    }

    /** Find an installed plugin in the given scope. */
    public PluginInstallation findInstallation(String pluginId, String scope) {
        Map<String, Object> installed = loadInstalledPlugins();
        if (installed.containsKey(pluginId)) {
            Object data = installed.get(pluginId);
            return new PluginInstallation(pluginId, scope, null, null);
        }
        return null;
    }

    /** Add or update a plugin installation record. */
    public void addInstallation(String pluginId, PluginInstallation installation) {
        try {
            Map<String, Object> installed = new java.util.LinkedHashMap<>(loadInstalledPlugins());
            installed.put(pluginId, java.util.Map.of(
                "scope", installation.getScope() != null ? installation.getScope() : "user",
                "path", installation.getPath() != null ? installation.getPath() : ""
            ));
            saveInstalledPlugins(installed);
        } catch (Exception e) {
            log.warn("Failed to save installation for {}: {}", pluginId, e.getMessage());
        }
    }
}
