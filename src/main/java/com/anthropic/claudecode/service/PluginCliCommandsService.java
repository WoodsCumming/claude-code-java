package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * CLI command wrappers for plugin operations.
 * Translated from src/services/plugins/pluginCliCommands.ts
 *
 * Provides thin wrappers around core plugin operations that handle CLI-specific
 * concerns like console output. For core operations without CLI side effects,
 * see PluginOperationsService.
 */
@Slf4j
@Service
public class PluginCliCommandsService {



    /** Valid installable scopes (excludes 'managed'). */
    public static final List<String> VALID_INSTALLABLE_SCOPES = List.of("user", "project", "local");

    /** Valid scopes for update operations (includes 'managed'). */
    public static final List<String> VALID_UPDATE_SCOPES = List.of("user", "project", "local", "managed");

    private final PluginOperationsService pluginOperations;
    private final AnalyticsService analyticsService;

    @Autowired
    public PluginCliCommandsService(PluginOperationsService pluginOperations,
                                     AnalyticsService analyticsService) {
        this.pluginOperations = pluginOperations;
        this.analyticsService = analyticsService;
    }

    /**
     * Generic error handler for plugin CLI commands. Logs the failure event
     * tengu_plugin_command_failed so dashboards can compute a success rate.
     */
    private void handlePluginCommandError(Throwable error, String command, String plugin) {
        log.error("Plugin command '{}' failed for '{}': {}", command, plugin, error.getMessage());
        String operation = (plugin != null)
                ? command + " plugin \"" + plugin + "\""
                : "disable-all".equals(command) ? "disable all plugins" : command + " plugins";
        System.err.println("✗ Failed to " + operation + ": " + error.getMessage());
        analyticsService.logEvent("tengu_plugin_command_failed", Map.of(
                "command", command,
                "error", error.getMessage() != null ? error.getMessage() : "unknown"
        ));
    }

    /**
     * CLI command: Install a plugin non-interactively.
     *
     * @param plugin Plugin identifier (name or plugin@marketplace)
     * @param scope  Installation scope: user, project, or local (defaults to 'user')
     */
    public CompletableFuture<Void> installPlugin(String plugin, String scope) {
        String effectiveScope = (scope != null) ? scope : "user";
        System.out.println("Installing plugin \"" + plugin + "\"...");

        return pluginOperations.installPluginOp(plugin, effectiveScope)
                .thenAccept(result -> {
                    if (!result.isSuccess()) {
                        throw new RuntimeException(result.getMessage());
                    }
                    System.out.println("✓ " + result.getMessage());
                    analyticsService.logEvent("tengu_plugin_installed_cli", Map.of(
                            "plugin", result.getPluginId() != null ? result.getPluginId() : plugin,
                            "scope", result.getScope() != null ? result.getScope() : effectiveScope,
                            "install_source", "cli-explicit"
                    ));
                })
                .exceptionally(ex -> {
                    handlePluginCommandError(ex, "install", plugin);
                    return null;
                });
    }

    /**
     * CLI command: Uninstall a plugin non-interactively.
     *
     * @param plugin   Plugin name or plugin@marketplace identifier
     * @param scope    Uninstall from scope: user, project, or local (defaults to 'user')
     * @param keepData If true, do not delete the plugin's data directory
     */
    public CompletableFuture<Void> uninstallPlugin(String plugin, String scope, boolean keepData) {
        String effectiveScope = (scope != null) ? scope : "user";

        return pluginOperations.uninstallPluginOp(plugin, effectiveScope, !keepData)
                .thenAccept(result -> {
                    if (!result.isSuccess()) {
                        throw new RuntimeException(result.getMessage());
                    }
                    System.out.println("✓ " + result.getMessage());
                    analyticsService.logEvent("tengu_plugin_uninstalled_cli", Map.of(
                            "plugin", result.getPluginId() != null ? result.getPluginId() : plugin,
                            "scope", result.getScope() != null ? result.getScope() : effectiveScope
                    ));
                })
                .exceptionally(ex -> {
                    handlePluginCommandError(ex, "uninstall", plugin);
                    return null;
                });
    }

    /**
     * CLI command: Enable a plugin non-interactively.
     *
     * @param plugin Plugin name or plugin@marketplace identifier
     * @param scope  Optional scope. If null, finds the most specific scope for the current project.
     */
    public CompletableFuture<Void> enablePlugin(String plugin, String scope) {
        return pluginOperations.enablePluginOp(plugin, scope)
                .thenAccept(result -> {
                    if (!result.isSuccess()) {
                        throw new RuntimeException(result.getMessage());
                    }
                    System.out.println("✓ " + result.getMessage());
                    analyticsService.logEvent("tengu_plugin_enabled_cli", Map.of(
                            "plugin", result.getPluginId() != null ? result.getPluginId() : plugin,
                            "scope", result.getScope() != null ? result.getScope() : ""
                    ));
                })
                .exceptionally(ex -> {
                    handlePluginCommandError(ex, "enable", plugin);
                    return null;
                });
    }

    /**
     * CLI command: Disable a plugin non-interactively.
     *
     * @param plugin Plugin name or plugin@marketplace identifier
     * @param scope  Optional scope. If null, finds the most specific scope for the current project.
     */
    public CompletableFuture<Void> disablePlugin(String plugin, String scope) {
        return pluginOperations.disablePluginOp(plugin, scope)
                .thenAccept(result -> {
                    if (!result.isSuccess()) {
                        throw new RuntimeException(result.getMessage());
                    }
                    System.out.println("✓ " + result.getMessage());
                    analyticsService.logEvent("tengu_plugin_disabled_cli", Map.of(
                            "plugin", result.getPluginId() != null ? result.getPluginId() : plugin,
                            "scope", result.getScope() != null ? result.getScope() : ""
                    ));
                })
                .exceptionally(ex -> {
                    handlePluginCommandError(ex, "disable", plugin);
                    return null;
                });
    }

    /**
     * CLI command: Disable all enabled plugins non-interactively.
     */
    public CompletableFuture<Void> disableAllPlugins() {
        return pluginOperations.disableAllPluginsOp()
                .thenAccept(result -> {
                    if (!result.isSuccess()) {
                        throw new RuntimeException(result.getMessage());
                    }
                    System.out.println("✓ " + result.getMessage());
                    analyticsService.logEvent("tengu_plugin_disabled_all_cli", Map.of());
                })
                .exceptionally(ex -> {
                    handlePluginCommandError(ex, "disable-all", null);
                    return null;
                });
    }

    /**
     * CLI command: Update a plugin non-interactively.
     *
     * @param plugin Plugin name or plugin@marketplace identifier
     * @param scope  Scope to update (user, project, local, or managed)
     */
    public CompletableFuture<Void> updatePluginCli(String plugin, String scope) {
        System.out.println("Checking for updates for plugin \"" + plugin + "\" at " + scope + " scope...");

        return pluginOperations.updatePluginOp(plugin, scope)
                .thenAccept(result -> {
                    if (!result.isSuccess()) {
                        throw new RuntimeException(result.getMessage());
                    }
                    System.out.println("✓ " + result.getMessage());
                    if (!Boolean.TRUE.equals(result.isAlreadyUpToDate())) {
                        analyticsService.logEvent("tengu_plugin_updated_cli", Map.of(
                                "plugin", result.getPluginId() != null ? result.getPluginId() : plugin,
                                "old_version", result.getOldVersion() != null ? result.getOldVersion() : "unknown",
                                "new_version", result.getNewVersion() != null ? result.getNewVersion() : "unknown"
                        ));
                    }
                })
                .exceptionally(ex -> {
                    handlePluginCommandError(ex, "update", plugin);
                    return null;
                });
    }
}
