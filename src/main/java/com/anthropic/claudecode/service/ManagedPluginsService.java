package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Managed plugins service.
 * Translated from src/utils/plugins/managedPlugins.ts
 *
 * Manages plugins locked by org policy.
 */
@Slf4j
@Service
public class ManagedPluginsService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ManagedPluginsService.class);


    private final SettingsService settingsService;

    @Autowired
    public ManagedPluginsService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * Get plugin names locked by org policy.
     * Translated from getManagedPluginNames() in managedPlugins.ts
     */
    public Optional<Set<String>> getManagedPluginNames() {
        Map<String, Object> policySettings = settingsService.getSettingsForSource("policySettings");
        if (policySettings == null) return Optional.empty();

        Object enabledPlugins = policySettings.get("enabledPlugins");
        if (!(enabledPlugins instanceof Map)) return Optional.empty();

        Map<String, Object> pluginsMap = (Map<String, Object>) enabledPlugins;
        Set<String> names = new HashSet<>();

        for (Map.Entry<String, Object> entry : pluginsMap.entrySet()) {
            String pluginId = entry.getKey();
            Object value = entry.getValue();

            // Only boolean entries with @ in plugin ID
            if (value instanceof Boolean && pluginId.contains("@")) {
                names.add(pluginId);
            }
        }

        return Optional.of(names);
    }

    /**
     * Check if a plugin is managed (locked by policy).
     */
    public boolean isPluginManaged(String pluginId) {
        return getManagedPluginNames()
            .map(names -> names.contains(pluginId))
            .orElse(false);
    }
}
