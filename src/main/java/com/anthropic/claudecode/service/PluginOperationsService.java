package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Core plugin operations (install, uninstall, enable, disable, update).
 * Translated from src/services/plugins/pluginOperations.ts
 *
 * Provides pure library functions usable by both CLI commands and interactive UI.
 * Functions in this service:
 * - Do NOT call System.exit()
 * - Do NOT write to console directly
 * - Return PluginOperationResult objects indicating success/failure with messages
 * - May throw for unexpected failures
 */
@Slf4j
@Service
public class PluginOperationsService {



    /** Valid installable scopes (excludes 'managed' which is installed from managed-settings.json). */
    public static final List<String> VALID_INSTALLABLE_SCOPES = List.of("user", "project", "local");

    /** Valid scopes for update operations (includes 'managed' since managed plugins can be updated). */
    public static final List<String> VALID_UPDATE_SCOPES = List.of("user", "project", "local", "managed");

    // ============================================================================
    // Result Types
    // ============================================================================

    /**
     * Result of a plugin operation (install/uninstall/enable/disable).
     */
    @Data
    public static class PluginOperationResult {
        private final boolean success;
        private final String message;
        private final String pluginId;
        private final String pluginName;
        private final String scope;
        /** Plugins that declare this plugin as a dependency (warning on uninstall/disable). */
        private final List<String> reverseDependents;

        public PluginOperationResult(boolean success, String message) {
            this(success, message, null, null, null, null);
        }

        public PluginOperationResult(boolean success, String message,
                                     String pluginId, String pluginName,
                                     String scope, List<String> reverseDependents) {
            this.success = success;
            this.message = message;
            this.pluginId = pluginId;
            this.pluginName = pluginName;
            this.scope = scope;
            this.reverseDependents = reverseDependents;
        }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public String getPluginId() { return pluginId; }
        public String getPluginName() { return pluginName; }
        public String getScope() { return scope; }
        public List<String> getReverseDependents() { return reverseDependents; }
    }

    /**
     * Result of a plugin update operation.
     */
    @Data
    public static class PluginUpdateResult {
        private final boolean success;
        private final String message;
        private final String pluginId;
        private final String newVersion;
        private final String oldVersion;
        private final boolean alreadyUpToDate;
        private final String scope;

        public PluginUpdateResult(boolean success, String message) {
            this(success, message, null, null, null, false, null);
        }

        public PluginUpdateResult(boolean success, String message,
                                  String pluginId, String newVersion, String oldVersion,
                                  boolean alreadyUpToDate, String scope) {
            this.success = success;
            this.message = message;
            this.pluginId = pluginId;
            this.newVersion = newVersion;
            this.oldVersion = oldVersion;
            this.alreadyUpToDate = alreadyUpToDate;
            this.scope = scope;
        }

        public String getNewVersion() { return newVersion; }
        public String getOldVersion() { return oldVersion; }
        public boolean isAlreadyUpToDate() { return alreadyUpToDate; }
    }

    // ============================================================================
    // Dependencies
    // ============================================================================

    private final InstalledPluginsManagerService installedPluginsManager;
    private final MarketplaceManagerService marketplaceManager;
    private final SettingsService settingsService;

    @Autowired
    public PluginOperationsService(InstalledPluginsManagerService installedPluginsManager,
                                    MarketplaceManagerService marketplaceManager,
                                    SettingsService settingsService) {
        this.installedPluginsManager = installedPluginsManager;
        this.marketplaceManager = marketplaceManager;
        this.settingsService = settingsService;
    }

    // ============================================================================
    // Scope validation helpers
    // ============================================================================

    /**
     * Assert that a scope is a valid installable scope at runtime.
     *
     * @throws IllegalArgumentException if scope is not a valid installable scope
     */
    public static void assertInstallableScope(String scope) {
        if (!VALID_INSTALLABLE_SCOPES.contains(scope)) {
            throw new IllegalArgumentException(
                    "Invalid scope \"" + scope + "\". Must be one of: " +
                    String.join(", ", VALID_INSTALLABLE_SCOPES));
        }
    }

    /**
     * Type guard to check if a scope is an installable scope (not 'managed').
     */
    public static boolean isInstallableScope(String scope) {
        return VALID_INSTALLABLE_SCOPES.contains(scope);
    }

    // ============================================================================
    // Core Operations
    // ============================================================================

    /**
     * Install a plugin (settings-first).
     *
     * Order of operations:
     *   1. Search materialized marketplaces for the plugin
     *   2. Write settings (THE ACTION — declares intent)
     *   3. Cache plugin + record version hint (materialization)
     *
     * @param plugin Plugin identifier (name or plugin@marketplace)
     * @param scope  Installation scope: user, project, or local (defaults to 'user')
     */
    public CompletableFuture<PluginOperationResult> installPluginOp(String plugin, String scope) {
        String effectiveScope = (scope != null) ? scope : "user";
        return CompletableFuture.supplyAsync(() -> {
            try {
                assertInstallableScope(effectiveScope);
                log.info("Installing plugin: {} (scope: {})", plugin, effectiveScope);

                // Search materialized marketplaces for the plugin
                MarketplaceManagerService.PluginSearchResult found =
                        marketplaceManager.findPluginInMarketplaces(plugin);

                if (found == null) {
                    String loc = plugin.contains("@")
                            ? "marketplace \"" + plugin.substring(plugin.indexOf('@') + 1) + "\""
                            : "any configured marketplace";
                    return new PluginOperationResult(false,
                            "Plugin \"" + plugin + "\" not found in " + loc);
                }

                String pluginId = found.name() + "@" + found.marketplace();

                // Write settings to declare intent
                settingsService.enablePlugin(pluginId, effectiveScope);

                return new PluginOperationResult(
                        true,
                        "Successfully installed plugin: " + pluginId + " (scope: " + effectiveScope + ")",
                        pluginId, found.name(), effectiveScope, null);

            } catch (IllegalArgumentException e) {
                return new PluginOperationResult(false, e.getMessage());
            } catch (Exception e) {
                log.error("Failed to install plugin {}: {}", plugin, e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Uninstall a plugin.
     *
     * @param plugin        Plugin name or plugin@marketplace identifier
     * @param scope         Uninstall from scope: user, project, or local (defaults to 'user')
     * @param deleteDataDir Whether to delete the plugin's data directory
     */
    public CompletableFuture<PluginOperationResult> uninstallPluginOp(
            String plugin, String scope, boolean deleteDataDir) {
        String effectiveScope = (scope != null) ? scope : "user";
        return CompletableFuture.supplyAsync(() -> {
            try {
                assertInstallableScope(effectiveScope);
                log.info("Uninstalling plugin: {} (scope: {})", plugin, effectiveScope);

                // Check if installed
                InstalledPluginsManagerService.PluginInstallation installation =
                        installedPluginsManager.findInstallation(plugin, effectiveScope);
                if (installation == null) {
                    return new PluginOperationResult(false,
                            "Plugin \"" + plugin + "\" is not installed in " + effectiveScope +
                            " scope. Use --scope to specify the correct scope.");
                }

                // Remove from settings and installed_plugins
                settingsService.disablePlugin(installation.getPluginId(), effectiveScope);
                installedPluginsManager.removePluginInstallation(
                        installation.getPluginId(), effectiveScope, null);

                return new PluginOperationResult(
                        true,
                        "Successfully uninstalled plugin: " + installation.getPluginId() +
                        " (scope: " + effectiveScope + ")",
                        installation.getPluginId(), installation.getPluginId(), effectiveScope, null);

            } catch (IllegalArgumentException e) {
                return new PluginOperationResult(false, e.getMessage());
            } catch (Exception e) {
                log.error("Failed to uninstall plugin {}: {}", plugin, e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Set plugin enabled/disabled status (settings-first).
     *
     * Resolves the plugin ID and scope from settings — does NOT pre-gate on
     * installed_plugins.json. Settings declares intent; if the plugin isn't
     * cached yet, the next load will cache it.
     *
     * @param plugin   Plugin name or plugin@marketplace identifier
     * @param enabled  true to enable, false to disable
     * @param scope    Optional scope. If null, auto-detects the most specific scope
     *                 where the plugin is mentioned in settings.
     */
    public CompletableFuture<PluginOperationResult> setPluginEnabledOp(
            String plugin, boolean enabled, String scope) {
        String operation = enabled ? "enable" : "disable";
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (scope != null) {
                    assertInstallableScope(scope);
                }

                // Resolve pluginId and scope from settings
                SettingsService.PluginScopeMatch found = settingsService.findPluginInSettings(plugin);

                String pluginId;
                String resolvedScope;

                if (scope != null) {
                    resolvedScope = scope;
                    if (found != null) {
                        pluginId = found.pluginId();
                    } else if (plugin.contains("@")) {
                        pluginId = plugin;
                    } else {
                        return new PluginOperationResult(false,
                                "Plugin \"" + plugin + "\" not found in settings. " +
                                "Use plugin@marketplace format.");
                    }
                } else if (found != null) {
                    pluginId = found.pluginId();
                    resolvedScope = found.scope();
                } else if (plugin.contains("@")) {
                    pluginId = plugin;
                    resolvedScope = "user";
                } else {
                    return new PluginOperationResult(false,
                            "Plugin \"" + plugin + "\" not found in any editable settings scope. " +
                            "Use plugin@marketplace format.");
                }

                // Write settings
                if (enabled) {
                    settingsService.enablePlugin(pluginId, resolvedScope);
                } else {
                    settingsService.disablePlugin(pluginId, resolvedScope);
                }

                String pluginName = pluginId.contains("@")
                        ? pluginId.substring(0, pluginId.indexOf('@'))
                        : pluginId;

                return new PluginOperationResult(
                        true,
                        "Successfully " + operation + "d plugin: " + pluginName +
                        " (scope: " + resolvedScope + ")",
                        pluginId, pluginName, resolvedScope, null);

            } catch (IllegalArgumentException e) {
                return new PluginOperationResult(false, e.getMessage());
            } catch (Exception e) {
                log.error("Failed to {} plugin {}: {}", operation, plugin, e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Enable a plugin.
     *
     * @param plugin Plugin name or plugin@marketplace identifier
     * @param scope  Optional scope. If null, finds the most specific scope.
     */
    public CompletableFuture<PluginOperationResult> enablePluginOp(String plugin, String scope) {
        return setPluginEnabledOp(plugin, true, scope);
    }

    /**
     * Disable a plugin.
     *
     * @param plugin Plugin name or plugin@marketplace identifier
     * @param scope  Optional scope. If null, finds the most specific scope.
     */
    public CompletableFuture<PluginOperationResult> disablePluginOp(String plugin, String scope) {
        return setPluginEnabledOp(plugin, false, scope);
    }

    /**
     * Disable all enabled plugins.
     */
    public CompletableFuture<PluginOperationResult> disableAllPluginsOp() {
        return CompletableFuture.supplyAsync(() -> {
            java.util.List<String> editableScopes = settingsService.getEditablePluginScopes();
            Map<String, String> enabledPlugins = new java.util.LinkedHashMap<>();
            for (String scope : editableScopes) {
                enabledPlugins.put(scope, scope);
            }

            if (enabledPlugins.isEmpty()) {
                return new PluginOperationResult(true, "No enabled plugins to disable");
            }

            List<String> disabled = new ArrayList<>();
            List<String> errors = new ArrayList<>();

            for (Map.Entry<String, String> entry : enabledPlugins.entrySet()) {
                String pluginId = entry.getKey();
                try {
                    PluginOperationResult result =
                            setPluginEnabledOp(pluginId, false, null).join();
                    if (result.isSuccess()) {
                        disabled.add(pluginId);
                    } else {
                        errors.add(pluginId + ": " + result.getMessage());
                    }
                } catch (Exception e) {
                    errors.add(pluginId + ": " + e.getMessage());
                }
            }

            String pluginWord = disabled.size() == 1 ? "plugin" : "plugins";
            if (!errors.isEmpty()) {
                return new PluginOperationResult(false,
                        "Disabled " + disabled.size() + " " + pluginWord + ", " +
                        errors.size() + " failed:\n" + String.join("\n", errors));
            }

            return new PluginOperationResult(true,
                    "Disabled " + disabled.size() + " " + pluginWord);
        });
    }

    /**
     * Update a plugin to the latest version (non-inplace).
     *
     * Unlike install/uninstall/enable/disable, managed scope IS allowed here.
     *
     * @param plugin Plugin name or plugin@marketplace identifier
     * @param scope  Scope to update (user, project, local, or managed)
     */
    public CompletableFuture<PluginUpdateResult> updatePluginOp(String plugin, String scope) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("Updating plugin: {} (scope: {})", plugin, scope);

                String pluginName = plugin.contains("@")
                        ? plugin.substring(0, plugin.indexOf('@'))
                        : plugin;
                String pluginId = plugin.contains("@") ? plugin : plugin;

                // Get plugin info from marketplace
                MarketplaceManagerService.PluginSearchResult found =
                        marketplaceManager.findPluginInMarketplaces(plugin);
                if (found == null) {
                    return new PluginUpdateResult(false,
                            "Plugin \"" + pluginName + "\" not found",
                            pluginId, null, null, false, scope);
                }

                // Check it is installed at the given scope
                InstalledPluginsManagerService.PluginInstallation installation =
                        installedPluginsManager.findInstallation(plugin, scope);
                if (installation == null) {
                    return new PluginUpdateResult(false,
                            "Plugin \"" + pluginName + "\" is not installed at scope " + scope,
                            pluginId, null, null, false, scope);
                }

                String oldVersion = installation.getVersion();
                String newVersion = marketplaceManager.resolveLatestVersion(found);

                if (oldVersion != null && oldVersion.equals(newVersion)) {
                    return new PluginUpdateResult(true,
                            pluginName + " is already at the latest version (" + newVersion + ").",
                            pluginId, newVersion, oldVersion, true, scope);
                }

                // Perform the update
                installedPluginsManager.updateInstallationVersion(
                        pluginId, scope, null, newVersion);

                return new PluginUpdateResult(true,
                        "Plugin \"" + pluginName + "\" updated from " +
                        (oldVersion != null ? oldVersion : "unknown") + " to " + newVersion +
                        " for scope " + scope + ". Restart to apply changes.",
                        pluginId, newVersion, oldVersion, false, scope);

            } catch (Exception e) {
                log.error("Failed to update plugin {}: {}", plugin, e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }
}
