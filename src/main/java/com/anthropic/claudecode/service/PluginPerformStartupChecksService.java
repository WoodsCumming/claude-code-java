package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.concurrent.CompletableFuture;

/**
 * Plugin perform startup checks service.
 * Translated from src/utils/plugins/performStartupChecks.tsx
 *
 * Performs plugin startup checks and initiates background installations.
 * SECURITY: Only called after trust dialog is confirmed.
 */
@Slf4j
@Service
public class PluginPerformStartupChecksService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PluginPerformStartupChecksService.class);


    private final PluginInstallationManagerService installationManager;
    private final MarketplaceManagerService marketplaceManager;
    private final PluginLoaderService pluginLoader;

    @Autowired
    public PluginPerformStartupChecksService(PluginInstallationManagerService installationManager,
                                              MarketplaceManagerService marketplaceManager,
                                              PluginLoaderService pluginLoader) {
        this.installationManager = installationManager;
        this.marketplaceManager = marketplaceManager;
        this.pluginLoader = pluginLoader;
    }

    /**
     * Perform plugin startup checks.
     * Translated from performPluginStartupChecks() in performStartupChecks.tsx
     *
     * SECURITY: Only call this after the trust dialog has been confirmed.
     */
    public CompletableFuture<Void> performPluginStartupChecks(boolean trustDialogAccepted) {
        if (!trustDialogAccepted) {
            log.debug("Trust dialog not accepted, skipping plugin startup checks");
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            log.debug("Performing plugin startup checks");

            // Load known marketplaces
            marketplaceManager.loadKnownMarketplacesConfig();

            // Clear caches
            pluginLoader.clearCache();

            // Start background installations
            installationManager.performBackgroundPluginInstallations()
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.debug("Background plugin installations failed: {}", ex.getMessage());
                    }
                });
        });
    }
}
