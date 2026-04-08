package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Plugin-only policy service.
 * Translated from src/utils/settings/pluginOnlyPolicy.ts
 *
 * Checks whether customization surfaces are locked to plugin-only sources.
 */
@Slf4j
@Service
public class PluginOnlyPolicyService {



    public static final List<String> CUSTOMIZATION_SURFACES = List.of(
        "commands", "agents", "outputStyles", "hooks"
    );

    private final SettingsService settingsService;

    @Autowired
    public PluginOnlyPolicyService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * Check if a customization surface is locked to plugin-only sources.
     * Translated from isRestrictedToPluginOnly() in pluginOnlyPolicy.ts
     */
    public boolean isRestrictedToPluginOnly(String surface) {
        Map<String, Object> policySettings = settingsService.getSettingsForSource("policySettings");
        if (policySettings == null) return false;

        Object policy = policySettings.get("strictPluginOnlyCustomization");
        if (policy == null) return false;

        if (Boolean.TRUE.equals(policy)) return true;

        if (policy instanceof List) {
            return ((List<?>) policy).contains(surface);
        }

        return false;
    }
}
