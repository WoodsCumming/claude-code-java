package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import lombok.Data;

/**
 * Plugin refresh service.
 * Translated from src/utils/plugins/refresh.ts
 *
 * Swaps active plugin components in the running session.
 */
@Slf4j
@Service
public class PluginRefreshService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PluginRefreshService.class);


    private final PluginLoaderService pluginLoader;
    private final PluginAgentsLoaderService pluginAgentsLoader;
    private final PluginHooksLoaderService pluginHooksLoader;

    @Autowired
    public PluginRefreshService(PluginLoaderService pluginLoader,
                                 PluginAgentsLoaderService pluginAgentsLoader,
                                 PluginHooksLoaderService pluginHooksLoader) {
        this.pluginLoader = pluginLoader;
        this.pluginAgentsLoader = pluginAgentsLoader;
        this.pluginHooksLoader = pluginHooksLoader;
    }

    /**
     * Refresh plugin state in the running session.
     * Translated from refreshPluginState() in refresh.ts
     */
    public CompletableFuture<RefreshResult> refreshPluginState(String cwd) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Refreshing plugin state");

            // Reload plugins
            pluginLoader.clearCache();
            pluginLoader.loadAllInstalledPlugins();

            log.info("Plugin state refreshed");
            return new RefreshResult(true, null);
        });
    }

    public static class RefreshResult {
        private boolean success;
        private String error;

        public RefreshResult() {}
        public RefreshResult(boolean success, String error) { this.success = success; this.error = error; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean v) { success = v; }
        public String getError() { return error; }
        public void setError(String v) { error = v; }
    }

    /**
     * Refresh active plugins with an AppState updater.
     */
    public void refreshActivePlugins(
            java.util.function.Function<java.util.function.Function<AppState, AppState>, Void> setAppState) {
        log.debug("refreshActivePlugins called");
        try {
            refreshPluginState(System.getProperty("user.dir", "")).join();
        } catch (Exception e) {
            log.warn("refreshActivePlugins failed: {}", e.getMessage());
        }
    }
}
