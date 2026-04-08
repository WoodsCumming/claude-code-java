package com.anthropic.claudecode.service;

import com.anthropic.claudecode.service.SecureStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Plugin options storage service.
 * Translated from src/utils/plugins/pluginOptionsStorage.ts
 *
 * Manages plugin configuration options storage.
 */
@Slf4j
@Service
public class PluginOptionsStorageService {

    public String getPluginStorageId(Object plugin) { return plugin != null ? plugin.toString() : null; }


    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PluginOptionsStorageService.class);


    private final SecureStorageService secureStorageService;
    private final SettingsService settingsService;

    @Autowired
    public PluginOptionsStorageService(
            SecureStorageService secureStorageService,
            SettingsService settingsService) {
        this.secureStorageService = secureStorageService;
        this.settingsService = settingsService;
    }

    /**
     * Load plugin options.
     * Translated from loadPluginOptions() in pluginOptionsStorage.ts
     */
    public Map<String, Object> loadPluginOptions(String pluginId) {
        Map<String, Object> options = new LinkedHashMap<>();

        // Load from settings
        Map<String, Object> settings = settingsService.getUserSettings();
        Object pluginConfigs = settings.get("pluginConfigs");
        if (pluginConfigs instanceof Map) {
            Object pluginConfig = ((Map<?, ?>) pluginConfigs).get(pluginId);
            if (pluginConfig instanceof Map) {
                Object pluginOptions = ((Map<?, ?>) pluginConfig).get("options");
                if (pluginOptions instanceof Map) {
                    options.putAll((Map<String, Object>) pluginOptions);
                }
            }
        }

        // Load secure options from secure storage
        String secureKey = "plugin_secrets_" + pluginId;
        secureStorageService.get(secureKey).ifPresent(json -> {
            try {
                Map<String, Object> secureOptions = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, Map.class);
                options.putAll(secureOptions);
            } catch (Exception e) {
                log.debug("Could not load secure plugin options: {}", e.getMessage());
            }
        });

        return options;
    }

    /**
     * Substitute plugin variables in content.
     * Translated from substitutePluginVariables() in pluginOptionsStorage.ts
     */
    public String substitutePluginVariables(String content, String pluginId) {
        if (content == null) return "";

        Map<String, Object> options = loadPluginOptions(pluginId);
        String result = content;

        for (Map.Entry<String, Object> entry : options.entrySet()) {
            String placeholder = "${" + entry.getKey() + "}";
            result = result.replace(placeholder,
                entry.getValue() != null ? entry.getValue().toString() : "");
        }

        return result;
    }

    /**
     * Substitute user config variables in content.
     * Translated from substituteUserConfigInContent() in pluginOptionsStorage.ts
     */
    public String substituteUserConfigInContent(String content, Map<String, Object> config) {
        if (content == null) return "";
        if (config == null) return content;

        String result = content;
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            String placeholder = "${" + entry.getKey() + "}";
            result = result.replace(placeholder,
                entry.getValue() != null ? entry.getValue().toString() : "");
        }

        return result;
    }
}
