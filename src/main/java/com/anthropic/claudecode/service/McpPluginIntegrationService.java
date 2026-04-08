package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.McpServerConfig;
import com.anthropic.claudecode.model.PluginTypes;
import com.anthropic.claudecode.util.EnvExpansion;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * MCP plugin integration service.
 * Translated from src/utils/plugins/mcpPluginIntegration.ts
 *
 * Loads MCP server configurations from installed plugins, including:
 *  - .mcp.json files in the plugin directory (lowest priority)
 *  - manifest.mcpServers (higher priority; string path, array of paths/configs, or inline map)
 *  - .mcpb (DXT) binary bundles
 *
 * Environment variables are resolved in the following order:
 *  1. Plugin-specific variables (${CLAUDE_PLUGIN_ROOT}, ${CLAUDE_PLUGIN_DATA})
 *  2. User config variables (${user_config.X})
 *  3. General process environment variables
 *
 * Servers are scoped by prefixing their names with "plugin:<pluginName>:<name>" to
 * avoid conflicts between plugins.
 */
@Slf4j
@Service
public class McpPluginIntegrationService {



    private final PluginLoaderService pluginLoaderService;
    private final PluginOptionsStorageService pluginOptionsStorageService;
    private final McpbHandlerService mcpbHandlerService;
    private final ObjectMapper objectMapper;

    @Autowired
    public McpPluginIntegrationService(
            PluginLoaderService pluginLoaderService,
            PluginOptionsStorageService pluginOptionsStorageService,
            McpbHandlerService mcpbHandlerService,
            ObjectMapper objectMapper) {
        this.pluginLoaderService = pluginLoaderService;
        this.pluginOptionsStorageService = pluginOptionsStorageService;
        this.mcpbHandlerService = mcpbHandlerService;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------------------
    // Types
    // ---------------------------------------------------------------------------

    /**
     * Represents a channel entry whose required userConfig has not yet been saved.
     * Translated from UnconfiguredChannel in mcpPluginIntegration.ts
     */
    @Data
    @lombok.NoArgsConstructor(force = true)
    @lombok.AllArgsConstructor
    public static class UnconfiguredChannel {
        private final String server;
        private final String displayName;
        private final Map<String, Object> configSchema;

        public String getServer() { return server; }
        public String getDisplayName() { return displayName; }
        public Map<String, Object> getConfigSchema() { return configSchema; }
    
    }

    // ---------------------------------------------------------------------------
    // loadPluginMcpServers
    // ---------------------------------------------------------------------------

    /**
     * Load all MCP server configs declared by a single plugin.
     * Handles .mcp.json, manifest.mcpServers (string/array/map), and .mcpb files.
     * Translated from loadPluginMcpServers() in mcpPluginIntegration.ts
     *
     * @param plugin Plugin to load servers from
     * @param errors Mutable list to accumulate plugin errors
     * @return Map of serverName → config, or null if none found
     */
    public Map<String, McpServerConfig> loadPluginMcpServers(
            PluginTypes.LoadedPlugin plugin,
            List<PluginError> errors) {

        Map<String, McpServerConfig> servers = new LinkedHashMap<>();

        // 1. .mcp.json in plugin directory (lowest priority)
        Map<String, McpServerConfig> defaultServers = loadMcpServersFromFile(plugin.getPath(), ".mcp.json");
        if (defaultServers != null) {
            servers.putAll(defaultServers);
        }

        // 2. manifest.mcpServers (higher priority)
        Object mcpServersSpec = plugin.getManifest() != null
            ? plugin.getManifest().getMcpServers()
            : null;

        if (mcpServersSpec instanceof String specStr) {
            if (mcpbHandlerService.isMcpbSource(specStr)) {
                Map<String, McpServerConfig> mcpbServers =
                    loadMcpServersFromMcpb(plugin, specStr, errors);
                if (mcpbServers != null) servers.putAll(mcpbServers);
            } else {
                Map<String, McpServerConfig> jsonServers =
                    loadMcpServersFromFile(plugin.getPath(), specStr);
                if (jsonServers != null) servers.putAll(jsonServers);
            }
        } else if (mcpServersSpec instanceof List<?> specList) {
            // Array of paths or inline configs — load in parallel, merge in order
            List<CompletableFuture<Map<String, McpServerConfig>>> futures = new ArrayList<>();
            for (Object spec : specList) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        if (spec instanceof String specStr2) {
                            if (mcpbHandlerService.isMcpbSource(specStr2)) {
                                return loadMcpServersFromMcpb(plugin, specStr2, errors);
                            }
                            return loadMcpServersFromFile(plugin.getPath(), specStr2);
                        }
                        // Inline config map
                        if (spec instanceof Map<?, ?> inlineMap) {
                            return parseInlineServerConfigs(inlineMap);
                        }
                    } catch (Exception e) {
                        log.error("[McpPlugin] Failed to load spec for plugin {}: {}",
                            plugin.getName(), e.getMessage());
                    }
                    return null;
                }));
            }
            // Merge results in original order
            for (CompletableFuture<Map<String, McpServerConfig>> f : futures) {
                Map<String, McpServerConfig> result = f.join();
                if (result != null) servers.putAll(result);
            }
        } else if (mcpServersSpec instanceof Map<?, ?> specMap) {
            // Direct inline server config map
            Map<String, McpServerConfig> inlineServers = parseInlineServerConfigs(specMap);
            if (inlineServers != null) servers.putAll(inlineServers);
        }

        return servers.isEmpty() ? null : servers;
    }

    // ---------------------------------------------------------------------------
    // getUnconfiguredChannels
    // ---------------------------------------------------------------------------

    /**
     * Find channel entries in a plugin's manifest whose required userConfig
     * fields have not yet been saved.
     * Translated from getUnconfiguredChannels() in mcpPluginIntegration.ts
     */
    public List<UnconfiguredChannel> getUnconfiguredChannels(PluginTypes.LoadedPlugin plugin) {
        List<?> channels = plugin.getManifest() != null
            ? plugin.getManifest().getChannels()
            : null;

        if (channels == null || channels.isEmpty()) return List.of();

        String pluginId = plugin.getRepository();
        List<UnconfiguredChannel> unconfigured = new ArrayList<>();

        for (Object channelObj : channels) {
            if (!(channelObj instanceof Map<?, ?> channel)) continue;
            Object userConfigObj = channel.get("userConfig");
            if (!(userConfigObj instanceof Map<?, ?> userConfigSchema)
                    || userConfigSchema.isEmpty()) continue;

            String server = (String) channel.get("server");
            String displayName = channel.containsKey("displayName")
                ? (String) channel.get("displayName")
                : server;

            Map<String, Object> saved = mcpbHandlerService.loadMcpServerUserConfig(pluginId, server);
            if (!mcpbHandlerService.validateUserConfig(saved, userConfigSchema)) {
                unconfigured.add(new UnconfiguredChannel(
                    server,
                    displayName != null ? displayName : server,
                    new LinkedHashMap<String, Object>((Map<String, Object>) userConfigSchema)
                ));
            }
        }
        return unconfigured;
    }

    // ---------------------------------------------------------------------------
    // addPluginScopeToServers
    // ---------------------------------------------------------------------------

    /**
     * Add the "plugin:<pluginName>:<name>" prefix to each server name.
     * Sets scope = "dynamic" and pluginSource on every entry.
     * Translated from addPluginScopeToServers() in mcpPluginIntegration.ts
     */
    public Map<String, McpServerConfig> addPluginScopeToServers(
            Map<String, McpServerConfig> servers,
            String pluginName,
            String pluginSource) {

        Map<String, McpServerConfig> scoped = new LinkedHashMap<>();
        for (Map.Entry<String, McpServerConfig> entry : servers.entrySet()) {
            String scopedName = "plugin:" + pluginName + ":" + entry.getKey();
            McpServerConfig config = entry.getValue().withScope("dynamic")
                .withPluginSource(pluginSource);
            scoped.put(scopedName, config);
        }
        return scoped;
    }

    // ---------------------------------------------------------------------------
    // extractMcpServersFromPlugins
    // ---------------------------------------------------------------------------

    /**
     * Extract, resolve, and scope MCP servers from all loaded plugins in parallel.
     * Translated from extractMcpServersFromPlugins() in mcpPluginIntegration.ts
     */
    public CompletableFuture<Map<String, McpServerConfig>> extractMcpServersFromPlugins(
            List<PluginTypes.LoadedPlugin> plugins,
            List<PluginError> errors) {

        List<CompletableFuture<Map<String, McpServerConfig>>> futures = new ArrayList<>();

        for (PluginTypes.LoadedPlugin plugin : plugins) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                if (!Boolean.TRUE.equals(plugin.getEnabled())) return null;

                Map<String, McpServerConfig> servers = loadPluginMcpServers(plugin, errors);
                if (servers == null) return null;

                Map<String, McpServerConfig> resolved = new LinkedHashMap<>();
                for (Map.Entry<String, McpServerConfig> entry : servers.entrySet()) {
                    Map<String, Object> userConfig = buildMcpUserConfig(plugin, entry.getKey());
                    try {
                        resolved.put(entry.getKey(),
                            resolvePluginMcpEnvironment(entry.getValue(), plugin, userConfig,
                                errors, plugin.getName(), entry.getKey()));
                    } catch (Exception err) {
                        errors.add(new PluginError("generic-error", entry.getKey(),
                            plugin.getName(), err.getMessage()));
                    }
                }

                // Cache unresolved servers on the plugin object (cast to Map<String, Object>)
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> serversAsObj = (java.util.Map<String, Object>)(java.util.Map<?, ?>) servers;
                plugin.setMcpServers(serversAsObj);

                log.debug("[McpPlugin] Loaded {} MCP servers from plugin {}",
                    servers.size(), plugin.getName());

                return addPluginScopeToServers(resolved, plugin.getName(), plugin.getSource());
            }));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(_v -> {
                Map<String, McpServerConfig> all = new LinkedHashMap<>();
                for (CompletableFuture<Map<String, McpServerConfig>> f : futures) {
                    Map<String, McpServerConfig> result = f.join();
                    if (result != null) all.putAll(result);
                }
                return all;
            });
    }

    // ---------------------------------------------------------------------------
    // getPluginMcpServers
    // ---------------------------------------------------------------------------

    /**
     * Get MCP servers from a specific plugin with env resolution and scoping.
     * Uses plugin.mcpServers cache when available.
     * Translated from getPluginMcpServers() in mcpPluginIntegration.ts
     */
    public CompletableFuture<Map<String, McpServerConfig>> getPluginMcpServers(
            PluginTypes.LoadedPlugin plugin,
            List<PluginError> errors) {

        return CompletableFuture.supplyAsync(() -> {
            if (!Boolean.TRUE.equals(plugin.getEnabled())) return null;

            @SuppressWarnings("unchecked")
            Map<String, McpServerConfig> cachedServers = plugin.getMcpServers() != null
                ? (Map<String, McpServerConfig>)(Map<?, ?>) plugin.getMcpServers()
                : null;
            Map<String, McpServerConfig> servers =
                cachedServers != null
                    ? cachedServers
                    : loadPluginMcpServers(plugin, errors);
            if (servers == null) return null;

            Map<String, McpServerConfig> resolved = new LinkedHashMap<>();
            for (Map.Entry<String, McpServerConfig> entry : servers.entrySet()) {
                Map<String, Object> userConfig = buildMcpUserConfig(plugin, entry.getKey());
                try {
                    resolved.put(entry.getKey(),
                        resolvePluginMcpEnvironment(entry.getValue(), plugin, userConfig,
                            errors, plugin.getName(), entry.getKey()));
                } catch (Exception err) {
                    errors.add(new PluginError("generic-error", entry.getKey(),
                        plugin.getName(), err.getMessage()));
                }
            }

            return addPluginScopeToServers(resolved, plugin.getName(), plugin.getSource());
        });
    }

    // ---------------------------------------------------------------------------
    // resolvePluginMcpEnvironment
    // ---------------------------------------------------------------------------

    /**
     * Resolve ${CLAUDE_PLUGIN_ROOT}, ${user_config.X}, and general ${VAR} references
     * in a plugin MCP server configuration.
     * Translated from resolvePluginMcpEnvironment() in mcpPluginIntegration.ts
     */
    public McpServerConfig resolvePluginMcpEnvironment(
            McpServerConfig config,
            PluginTypes.LoadedPlugin plugin,
            Map<String, Object> userConfig,
            List<PluginError> errors,
            String pluginName,
            String serverName) {

        List<String> allMissing = new ArrayList<>();

        java.util.function.UnaryOperator<String> resolveValue = value -> {
            if (value == null) return null;
            // 1. Plugin-specific variables
            String r = pluginOptionsStorageService.substitutePluginVariables(value, plugin.getSource());
            // 2. User config variables
            if (userConfig != null && !userConfig.isEmpty()) {
                r = substituteUserConfigVariables(r, userConfig);
            }
            // 3. General env vars
            EnvExpansion.ExpandResult expanded = EnvExpansion.expandEnvVarsInString(r);
            allMissing.addAll(expanded.getMissingVars());
            return expanded.getExpanded();
        };

        McpServerConfig resolved = config.resolveValues(resolveValue, plugin.getPath());

        if (!allMissing.isEmpty() && errors != null) {
            Set<String> unique = new LinkedHashSet<>(allMissing);
            String varList = String.join(", ", unique);
            log.warn("[McpPlugin] Missing env vars in plugin MCP config: {}", varList);
            if (pluginName != null && serverName != null) {
                errors.add(new PluginError("mcp-config-invalid",
                    "plugin:" + pluginName, pluginName, serverName,
                    "Missing environment variables: " + varList));
            }
        }

        return resolved;
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    /**
     * Build the merged userConfig for a single server by combining top-level
     * manifest.userConfig with channel-specific per-server config.
     * Channel-specific wins on collision.
     * Translated from buildMcpUserConfig() in mcpPluginIntegration.ts
     */
    private Map<String, Object> buildMcpUserConfig(PluginTypes.LoadedPlugin plugin, String serverName) {
        // Only load options when the manifest declares userConfig (avoids 50-100ms keychain read)
        Map<String, Object> topLevel = null;
        if (plugin.getManifest() != null && plugin.getManifest().getUserConfig() != null) {
            String storageId = pluginOptionsStorageService.getPluginStorageId(plugin);
            topLevel = pluginOptionsStorageService.loadPluginOptions(storageId);
        }
        Map<String, Object> channelSpecific = loadChannelUserConfig(plugin, serverName);

        if (topLevel == null && channelSpecific == null) return null;
        Map<String, Object> merged = new LinkedHashMap<>();
        if (topLevel != null) merged.putAll(topLevel);
        if (channelSpecific != null) merged.putAll(channelSpecific);
        return merged;
    }

    private Map<String, Object> loadChannelUserConfig(PluginTypes.LoadedPlugin plugin, String serverName) {
        if (plugin.getManifest() == null || plugin.getManifest().getChannels() == null) return null;
        for (Object channelObj : plugin.getManifest().getChannels()) {
            if (!(channelObj instanceof Map<?, ?> channel)) continue;
            if (serverName.equals(channel.get("server")) && channel.get("userConfig") != null) {
                return mcpbHandlerService.loadMcpServerUserConfig(plugin.getRepository(), serverName);
            }
        }
        return null;
    }

    private Map<String, McpServerConfig> loadMcpServersFromMcpb(
            PluginTypes.LoadedPlugin plugin,
            String mcpbPath,
            List<PluginError> errors) {

        try {
            log.debug("[McpPlugin] Loading MCP servers from MCPB: {}", mcpbPath);
            String pluginId = plugin.getRepository();
            McpbHandlerService.McpbLoadResult result =
                mcpbHandlerService.loadMcpbFile(mcpbPath, plugin.getPath(), pluginId,
                    status -> log.debug("[McpPlugin] MCPB [{}]: {}", plugin.getName(), status));

            if (result == null) {
                log.debug("[McpPlugin] MCPB {} requires user configuration", mcpbPath);
                return null;
            }

            String serverName = result.getManifestName();
            log.debug("[McpPlugin] Loaded server \"{}\" from MCPB (extracted to {})",
                serverName, result.getExtractedPath());
            McpServerConfig mcpConfig = McpServerConfig.fromMap(result.getMcpConfig());
            if (mcpConfig == null) return null;
            return Map.of(serverName, mcpConfig);

        } catch (Exception error) {
            String errorMsg = error.getMessage() != null ? error.getMessage() : error.toString();
            log.error("[McpPlugin] Failed to load MCPB {}: {}", mcpbPath, errorMsg);
            String source = plugin.getName() + "@" + plugin.getRepository();
            boolean isUrl = mcpbPath.startsWith("http");
            if (isUrl && (errorMsg.contains("download") || errorMsg.contains("network"))) {
                errors.add(new PluginError("mcpb-download-failed", source, plugin.getName(),
                    mcpbPath, errorMsg));
            } else if (errorMsg.contains("manifest") || errorMsg.contains("user configuration")) {
                errors.add(new PluginError("mcpb-invalid-manifest", source, plugin.getName(),
                    mcpbPath, errorMsg));
            } else {
                errors.add(new PluginError("mcpb-extract-failed", source, plugin.getName(),
                    mcpbPath, errorMsg));
            }
            return null;
        }
    }

    private Map<String, McpServerConfig> loadMcpServersFromFile(String pluginPath, String relativePath) {
        Path filePath = Paths.get(pluginPath, relativePath);
        if (!Files.exists(filePath)) return null;
        try {
            String content = Files.readString(filePath);
            Map<String, Object> parsed = objectMapper.readValue(content,
                new TypeReference<Map<String, Object>>() {});

            // Support both { mcpServers: {...} } and flat { serverName: config }
            Object mcpServersObj = parsed.containsKey("mcpServers")
                ? parsed.get("mcpServers")
                : parsed;

            if (!(mcpServersObj instanceof Map<?, ?> mcpServers)) return null;
            return parseInlineServerConfigs(mcpServers);
        } catch (IOException e) {
            log.debug("[McpPlugin] Failed to load MCP servers from {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, McpServerConfig> parseInlineServerConfigs(Map<?, ?> rawMap) {
        Map<String, McpServerConfig> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String name)) continue;
            if (!(entry.getValue() instanceof Map<?, ?> configMap)) continue;
            try {
                McpServerConfig config = McpServerConfig.fromMap((Map<String, Object>) configMap);
                if (config != null) result.put(name, config);
            } catch (Exception e) {
                log.debug("[McpPlugin] Invalid MCP server config for {}: {}", name, e.getMessage());
            }
        }
        return result.isEmpty() ? null : result;
    }

    private String substituteUserConfigVariables(String value, Map<String, Object> userConfig) {
        if (value == null || userConfig == null) return value;
        String result = value;
        for (Map.Entry<String, Object> entry : userConfig.entrySet()) {
            result = result.replace("${user_config." + entry.getKey() + "}",
                entry.getValue() != null ? entry.getValue().toString() : "");
        }
        return result;
    }

    // ---------------------------------------------------------------------------
    // PluginError record (mirrors PluginError union in types/plugin.ts)
    // ---------------------------------------------------------------------------

    public record PluginError(
        String type,
        String source,
        String plugin,
        String detail,
        String reason
    ) {
        public PluginError(String type, String source, String plugin, String reason) {
            this(type, source, plugin, null, reason);
        }
    }
}
