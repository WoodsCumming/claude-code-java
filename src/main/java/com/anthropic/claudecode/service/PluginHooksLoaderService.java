package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.PluginTypes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Plugin hooks loader service.
 * Translated from src/utils/plugins/loadPluginHooks.ts
 *
 * Loads and registers hooks from installed plugins.
 */
@Slf4j
@Service
public class PluginHooksLoaderService {



    private final PluginLoaderService pluginLoaderService;
    private final HookService hookService;

    @Autowired
    public PluginHooksLoaderService(
            PluginLoaderService pluginLoaderService,
            HookService hookService) {
        this.pluginLoaderService = pluginLoaderService;
        this.hookService = hookService;
    }

    /**
     * Load and register plugin hooks.
     * Translated from loadPluginHooks() in loadPluginHooks.ts
     */
    public void loadPluginHooks() {
        List<PluginTypes.LoadedPlugin> plugins = pluginLoaderService.loadAllPluginsCacheOnly();

        for (PluginTypes.LoadedPlugin plugin : plugins) {
            if (!Boolean.TRUE.equals(plugin.getEnabled())) continue;

            try {
                registerPluginHooks(plugin);
            } catch (Exception e) {
                log.debug("Could not register hooks for plugin {}: {}", plugin.getName(), e.getMessage());
            }
        }

        log.debug("Loaded hooks for {} plugins", plugins.size());
    }

    private void registerPluginHooks(PluginTypes.LoadedPlugin plugin) {
        // Simplified - full implementation would parse plugin's hooks configuration
        // and register them with the hook service
        log.debug("Registering hooks for plugin: {}", plugin.getName());
    }
}
