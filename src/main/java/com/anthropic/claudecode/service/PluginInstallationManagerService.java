package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Background plugin and marketplace installation manager.
 * Translated from src/services/plugins/PluginInstallationManager.ts
 *
 * Handles automatic installation of plugins and marketplaces from trusted sources
 * (repository and user settings) without blocking startup.
 */
@Slf4j
@Service
public class PluginInstallationManagerService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PluginInstallationManagerService.class);


    /** Status values for a marketplace installation entry. */
    public enum MarketplaceInstallStatus {
        PENDING, INSTALLING, INSTALLED, FAILED
    }

    /** Per-marketplace installation status, mirroring the TypeScript AppState shape. */
    public record MarketplaceStatusEntry(String name, MarketplaceInstallStatus status, String error) {
        public MarketplaceStatusEntry(String name, MarketplaceInstallStatus status) {
            this(name, status, null);
        }
    }

    private final MarketplaceManagerService marketplaceManagerService;
    private final PluginLoaderService pluginLoaderService;
    private final PluginRefreshService pluginRefreshService;
    private final AnalyticsService analyticsService;

    @Autowired
    public PluginInstallationManagerService(
            MarketplaceManagerService marketplaceManagerService,
            PluginLoaderService pluginLoaderService,
            PluginRefreshService pluginRefreshService,
            AnalyticsService analyticsService) {
        this.marketplaceManagerService = marketplaceManagerService;
        this.pluginLoaderService = pluginLoaderService;
        this.pluginRefreshService = pluginRefreshService;
        this.analyticsService = analyticsService;
    }

    /**
     * Perform background plugin startup checks and installations.
     * Translated from performBackgroundPluginInstallations() in PluginInstallationManager.ts
     *
     * This is a thin wrapper around reconcileMarketplaces() that maps onProgress
     * events to AppState updates for the REPL UI. After marketplaces are reconciled:
     * - New installs: auto-refresh plugins (fixes "plugin-not-found" errors from
     *   the initial cache-only load on fresh homespace/cleared cache).
     * - Updates only: set needsRefresh, show notification for /reload-plugins.
     *
     * @param setAppState callback that accepts a state-transformation function
     */
    public CompletableFuture<Void> performBackgroundPluginInstallations() {
        return performBackgroundPluginInstallations(fn -> null);
    }

    public CompletableFuture<Void> performBackgroundPluginInstallations(
            Function<Function<AppState, AppState>, Void> setAppState) {
        log.debug("performBackgroundPluginInstallations called");

        return CompletableFuture.runAsync(() -> {
            try {
                // Compute diff upfront for initial UI status (pending spinners)
                List<String> declared = marketplaceManagerService.getDeclaredMarketplaces();
                Map<String, Object> materialized = marketplaceManagerService.loadKnownMarketplacesConfig();
                MarketplaceManagerService.MarketplaceDiff diff =
                        marketplaceManagerService.diffMarketplaces(declared, materialized);

                List<String> pendingNames = new java.util.ArrayList<>();
                pendingNames.addAll(diff.missing());
                diff.sourceChanged().forEach(c -> pendingNames.add(c.name()));

                // Initialize AppState with pending status for each marketplace to install.
                // No per-plugin pending status — plugin load is fast (cache hit or local copy);
                // marketplace clone is the slow part worth showing progress for.
                if (setAppState != null) {
                    List<MarketplaceStatusEntry> initialStatuses = pendingNames.stream()
                            .map(name -> new MarketplaceStatusEntry(name, MarketplaceInstallStatus.PENDING))
                            .toList();
                    setAppState.apply(prev -> prev.withMarketplaceInstallStatuses(initialStatuses));
                }

                if (pendingNames.isEmpty()) {
                    return;
                }

                log.debug("Installing {} marketplace(s) in background", pendingNames.size());

                // Reconcile marketplaces, emitting progress events mapped to AppState updates
                MarketplaceManagerService.ReconcileResult result =
                        marketplaceManagerService.reconcileMarketplaces(event -> {
                            if (setAppState == null) return;
                            switch (event.type()) {
                                case "installing" ->
                                    setAppState.apply(prev -> prev.withMarketplaceStatus(
                                            event.name(), MarketplaceInstallStatus.INSTALLING, null));
                                case "installed" ->
                                    setAppState.apply(prev -> prev.withMarketplaceStatus(
                                            event.name(), MarketplaceInstallStatus.INSTALLED, null));
                                case "failed" ->
                                    setAppState.apply(prev -> prev.withMarketplaceStatus(
                                            event.name(), MarketplaceInstallStatus.FAILED, event.error()));
                            }
                        });

                Map<String, Object> metrics = Map.of(
                        "installed_count", result.installed().size(),
                        "updated_count", result.updated().size(),
                        "failed_count", result.failed().size(),
                        "up_to_date_count", result.upToDate().size()
                );
                analyticsService.logEvent("tengu_marketplace_background_install", metrics);
                log.info("tengu_marketplace_background_install: {}", metrics);

                if (!result.installed().isEmpty()) {
                    // New marketplaces were installed — auto-refresh plugins. This fixes
                    // "Plugin not found in marketplace" errors from the initial cache-only
                    // load (e.g., fresh homespace where marketplace cache was empty).
                    marketplaceManagerService.clearMarketplacesCache();
                    log.debug("Auto-refreshing plugins after {} new marketplace(s) installed",
                            result.installed().size());
                    try {
                        pluginRefreshService.refreshActivePlugins(setAppState);
                    } catch (Exception refreshError) {
                        // If auto-refresh fails, fall back to needsRefresh notification so
                        // the user can manually run /reload-plugins to recover.
                        log.warn("Auto-refresh failed, falling back to needsRefresh: {}",
                                refreshError.getMessage());
                        pluginLoaderService.clearPluginCache(
                                "performBackgroundPluginInstallations: auto-refresh failed");
                        if (setAppState != null) {
                            setAppState.apply(prev ->
                                    prev.isNeedsRefresh() ? prev : prev.withNeedsRefresh(true));
                        }
                    }
                } else if (!result.updated().isEmpty()) {
                    // Existing marketplaces updated — notify user to run /reload-plugins.
                    // Updates are less urgent and the user should choose when to apply them.
                    marketplaceManagerService.clearMarketplacesCache();
                    pluginLoaderService.clearPluginCache(
                            "performBackgroundPluginInstallations: marketplaces reconciled");
                    if (setAppState != null) {
                        setAppState.apply(prev ->
                                prev.isNeedsRefresh() ? prev : prev.withNeedsRefresh(true));
                    }
                }

            } catch (Exception error) {
                log.error("Background plugin installation failed", error);
            }
        });
    }
}
