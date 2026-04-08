package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.PluginTypes.BuiltinPluginDefinition;
import com.anthropic.claudecode.model.PluginTypes.LoadedPlugin;
import com.anthropic.claudecode.model.PluginTypes.PluginManifest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Built-in Plugin Registry.
 * Translated from src/plugins/builtinPlugins.ts
 *
 * Manages built-in plugins that ship with the CLI and can be enabled/disabled
 * by users via the /plugin UI.
 *
 * Built-in plugins differ from bundled skills (src/skills/bundled/) in that:
 *   - They appear in the /plugin UI under a "Built-in" section
 *   - Users can enable/disable them (persisted to user settings)
 *   - They can provide multiple components (skills, hooks, MCP servers)
 *
 * Plugin IDs use the format {@code {name}@builtin}.
 */
@Slf4j
@Service
public class BuiltinPluginsService {



    /** The marketplace identifier for built-in plugins. */
    public static final String BUILTIN_MARKETPLACE_NAME = "builtin";

    /** Module-level registry of built-in plugin definitions, keyed by name. */
    private final Map<String, BuiltinPluginDefinition> builtinPlugins = new LinkedHashMap<>();

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    /**
     * Register a built-in plugin. Call this from initBuiltinPlugins() at startup.
     * Corresponds to TypeScript: function registerBuiltinPlugin(definition)
     */
    public synchronized void registerBuiltinPlugin(BuiltinPluginDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        builtinPlugins.put(definition.getName(), definition);
        log.debug("Registered built-in plugin: {}", definition.getName());
    }

    /**
     * Initialize built-in plugins at application startup.
     * Subclasses or @PostConstruct methods may call registerBuiltinPlugin() here.
     * Corresponds to TypeScript: function initBuiltinPlugins()
     */
    public void initBuiltinPlugins() {
        log.debug("Built-in plugins initialized ({} registered)", builtinPlugins.size());
        // Additional plugins are registered via registerBuiltinPlugin() by
        // individual feature modules at startup.
    }

    // -------------------------------------------------------------------------
    // Lookup helpers
    // -------------------------------------------------------------------------

    /**
     * Check if a plugin ID represents a built-in plugin (ends with {@code @builtin}).
     * Corresponds to TypeScript: function isBuiltinPluginId(pluginId): boolean
     */
    public static boolean isBuiltinPluginId(String pluginId) {
        return pluginId != null && pluginId.endsWith("@" + BUILTIN_MARKETPLACE_NAME);
    }

    /**
     * Get a specific built-in plugin definition by name.
     * Corresponds to TypeScript: function getBuiltinPluginDefinition(name)
     */
    public synchronized Optional<BuiltinPluginDefinition> getBuiltinPluginDefinition(String name) {
        return Optional.ofNullable(builtinPlugins.get(name));
    }

    // -------------------------------------------------------------------------
    // getBuiltinPlugins
    // -------------------------------------------------------------------------

    /**
     * Get all registered built-in plugins as LoadedPlugin objects, split into
     * enabled/disabled based on user settings (with defaultEnabled as fallback).
     * Plugins whose isAvailable() returns false are omitted entirely.
     *
     * Corresponds to TypeScript:
     *   function getBuiltinPlugins(): { enabled: LoadedPlugin[], disabled: LoadedPlugin[] }
     *
     * @param enabledPlugins map of {@code pluginId → true/false} from user settings
     *                       (may be null / empty)
     */
    public synchronized PluginPartition getBuiltinPlugins(Map<String, Boolean> enabledPlugins) {
        List<LoadedPlugin> enabled = new ArrayList<>();
        List<LoadedPlugin> disabled = new ArrayList<>();

        for (Map.Entry<String, BuiltinPluginDefinition> entry : builtinPlugins.entrySet()) {
            String name = entry.getKey();
            BuiltinPluginDefinition definition = entry.getValue();

            // Skip unavailable plugins entirely
            if (definition.getIsAvailable() != null && !definition.getIsAvailable().getAsBoolean()) {
                continue;
            }

            String pluginId = name + "@" + BUILTIN_MARKETPLACE_NAME;

            Boolean userSetting = enabledPlugins != null ? enabledPlugins.get(pluginId) : null;
            // Enabled state: user preference > plugin default > true
            boolean isEnabled = userSetting != null
                    ? userSetting
                    : (definition.getDefaultEnabled() != null ? definition.getDefaultEnabled() : true);

            LoadedPlugin plugin = LoadedPlugin.builder()
                    .name(name)
                    .manifest(PluginManifest.builder()
                            .name(name)
                            .description(definition.getDescription())
                            .version(definition.getVersion())
                            .build())
                    .path(BUILTIN_MARKETPLACE_NAME)  // sentinel — no filesystem path
                    .source(pluginId)
                    .repository(pluginId)
                    .enabled(isEnabled)
                    .isBuiltin(true)
                    .build();

            if (isEnabled) {
                enabled.add(plugin);
            } else {
                disabled.add(plugin);
            }
        }

        return new PluginPartition(
                Collections.unmodifiableList(enabled),
                Collections.unmodifiableList(disabled)
        );
    }

    /**
     * Convenience overload that assumes no user settings (all plugins use their defaults).
     */
    public PluginPartition getBuiltinPlugins() {
        return getBuiltinPlugins(null);
    }

    // -------------------------------------------------------------------------
    // clearBuiltinPlugins
    // -------------------------------------------------------------------------

    /**
     * Clear the built-in plugins registry. For testing only.
     * Corresponds to TypeScript: function clearBuiltinPlugins()
     */
    public synchronized void clearBuiltinPlugins() {
        builtinPlugins.clear();
    }

    // -------------------------------------------------------------------------
    // PluginPartition result type
    // -------------------------------------------------------------------------

    /**
     * Result of getBuiltinPlugins() — mirrors the TypeScript
     * {@code { enabled: LoadedPlugin[], disabled: LoadedPlugin[] }} return type.
     */
    public record PluginPartition(
            List<LoadedPlugin> enabled,
            List<LoadedPlugin> disabled
    ) {}
}
