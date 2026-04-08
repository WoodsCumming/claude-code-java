package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.Map;

/**
 * Plugin policy service.
 * Translated from src/utils/plugins/pluginPolicy.ts
 *
 * Checks plugin policies from managed settings.
 */
@Slf4j
@Service
public class PluginPolicyService {



    private final SettingsService settingsService;

    @Autowired
    public PluginPolicyService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * Check if a plugin is blocked by org policy.
     * Translated from isPluginBlockedByPolicy() in pluginPolicy.ts
     */
    public boolean isPluginBlockedByPolicy(String pluginId) {
        Map<String, Object> policySettings = settingsService.getUserSettings();
        Object enabledPlugins = policySettings.get("enabledPlugins");

        if (!(enabledPlugins instanceof Map)) return false;

        Map<String, Object> enabled = (Map<String, Object>) enabledPlugins;
        Object value = enabled.get(pluginId);
        return Boolean.FALSE.equals(value);
    }
}
