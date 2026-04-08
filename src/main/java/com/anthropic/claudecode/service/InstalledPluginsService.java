package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Service for accessing installed plugin state (v2 registry).
 * Translated from src/utils/installedPlugins.ts
 *
 * Provides access to the v2 plugin installation registry which maps
 * plugin IDs to their installation records.
 */
@Slf4j
@Service
public class InstalledPluginsService {



    private final InstalledPluginsManagerService installedPluginsManagerService;

    @Autowired
    public InstalledPluginsService(InstalledPluginsManagerService installedPluginsManagerService) {
        this.installedPluginsManagerService = installedPluginsManagerService;
    }

    /**
     * A single plugin installation record.
     * Translated from PluginInstallation in installedPlugins.ts
     */
    public record PluginInstallation(
            String pluginId,
            Path installPath,
            String version,
            String marketplaceName
    ) {}

    /**
     * The v2 plugin registry structure.
     * Translated from PluginsV2Data in installedPlugins.ts
     */
    public record PluginsV2Data(
            Map<String, List<PluginInstallation>> plugins
    ) {}

    /**
     * Load the v2 installed plugins registry.
     * Translated from loadInstalledPluginsV2() in installedPlugins.ts
     *
     * @return the v2 plugin registry; returns an empty registry on failure
     */
    public PluginsV2Data loadInstalledPluginsV2() {
        try {
            Map<String, Object> raw = installedPluginsManagerService.loadInstalledPlugins();
            Map<String, List<PluginInstallation>> plugins = new java.util.LinkedHashMap<>();

            if (raw != null) {
                for (Map.Entry<String, Object> entry : raw.entrySet()) {
                    String pluginId = entry.getKey();
                    // Each entry may be a list of installation records or a single record
                    if (entry.getValue() instanceof List<?> list) {
                        List<PluginInstallation> installs = new java.util.ArrayList<>();
                        for (Object item : list) {
                            if (item instanceof Map<?, ?> m) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> map = (Map<String, Object>) m;
                                Object pathObj = map.get("installPath");
                                Path installPath = pathObj != null ? Path.of(pathObj.toString()) : null;
                                String version = map.get("version") instanceof String s ? s : null;
                                String marketplace = map.get("marketplaceName") instanceof String ms ? ms : null;
                                installs.add(new PluginInstallation(pluginId, installPath, version, marketplace));
                            }
                        }
                        plugins.put(pluginId, installs);
                    }
                }
            }

            return new PluginsV2Data(plugins);
        } catch (Exception e) {
            log.debug("[InstalledPluginsService] Failed to load v2 registry: {}", e.getMessage());
            return new PluginsV2Data(Map.of());
        }
    }

    /**
     * Check if a plugin is installed.
     * Translated from isPluginInstalled() in installedPlugins.ts
     */
    public boolean isPluginInstalled(String pluginId) {
        return installedPluginsManagerService.isPluginInstalled(pluginId);
    }
}
