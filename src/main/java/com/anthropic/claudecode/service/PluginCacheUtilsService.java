package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Plugin cache utilities service.
 * Translated from src/utils/plugins/cacheUtils.ts
 *
 * Manages plugin caches and cleanup.
 */
@Slf4j
@Service
public class PluginCacheUtilsService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PluginCacheUtilsService.class);


    private final PluginLoaderService pluginLoader;
    private final PluginAgentsLoaderService pluginAgentsLoader;
    private final PluginHooksLoaderService pluginHooksLoader;
    private final PluginOutputStylesService pluginOutputStyles;

    @Autowired
    public PluginCacheUtilsService(PluginLoaderService pluginLoader,
                                    PluginAgentsLoaderService pluginAgentsLoader,
                                    PluginHooksLoaderService pluginHooksLoader,
                                    PluginOutputStylesService pluginOutputStyles) {
        this.pluginLoader = pluginLoader;
        this.pluginAgentsLoader = pluginAgentsLoader;
        this.pluginHooksLoader = pluginHooksLoader;
        this.pluginOutputStyles = pluginOutputStyles;
    }

    /**
     * Clear all plugin caches.
     * Translated from clearAllCaches() in cacheUtils.ts
     */
    public void clearAllCaches() {
        pluginLoader.clearCache();
        log.debug("All plugin caches cleared");
    }

    /**
     * Mark a plugin version as orphaned.
     * Translated from markPluginVersionOrphaned() in cacheUtils.ts
     */
    public void markPluginVersionOrphaned(String pluginId, String version) {
        log.debug("Marking plugin {} version {} as orphaned", pluginId, version);
        // In a full implementation, this would mark old plugin versions for cleanup
    }
}
