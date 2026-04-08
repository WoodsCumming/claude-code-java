package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.PluginTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
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
 * LSP plugin integration service.
 * Translated from src/utils/plugins/lspPluginIntegration.ts
 *
 * Loads LSP server configurations from plugins, resolves environment variables,
 * and scopes server names to avoid conflicts between plugins.
 */
@Slf4j
@Service
public class LspPluginIntegrationService {



    private final ObjectMapper objectMapper;

    @Autowired
    public LspPluginIntegrationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // DTOs
    // -------------------------------------------------------------------------

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class LspServerConfig {
        private String command;
        private List<String> args;
        private Map<String, String> env;
        private String workspaceFolder;
        private Map<String, String> extensionToLanguage;

        public String getCommand() { return command; }
        public void setCommand(String v) { command = v; }
        public List<String> getArgs() { return args; }
        public void setArgs(List<String> v) { args = v; }
        public Map<String, String> getEnv() { return env; }
        public void setEnv(Map<String, String> v) { env = v; }
        public String getWorkspaceFolder() { return workspaceFolder; }
        public void setWorkspaceFolder(String v) { workspaceFolder = v; }
        public Map<String, String> getExtensionToLanguage() { return extensionToLanguage; }
        public void setExtensionToLanguage(Map<String, String> v) { extensionToLanguage = v; }
    
    

    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @lombok.EqualsAndHashCode(callSuper = false)
    public static class ScopedLspServerConfig extends LspServerConfig {
        private String scope;   // "dynamic" for plugin servers
        private String source;  // plugin name

        public String getScope() { return scope; }
        public void setScope(String v) { scope = v; }
        public String getSource() { return source; }
        public void setSource(String v) { source = v; }
    

    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Load LSP server configurations from a plugin.
     * Checks for:
     *  1. .lsp.json file in plugin directory
     *  2. manifest.lspServers field
     * Translated from loadPluginLspServers() in lspPluginIntegration.ts
     */
    public CompletableFuture<Map<String, LspServerConfig>> loadPluginLspServers(
            PluginTypes.LoadedPlugin plugin) {

        return CompletableFuture.supplyAsync(() -> {
            Map<String, LspServerConfig> servers = new LinkedHashMap<>();

            // 1. Check for .lsp.json file in plugin directory
            if (plugin.getPath() != null) {
                Path lspJsonPath = Paths.get(plugin.getPath(), ".lsp.json");
                if (Files.exists(lspJsonPath)) {
                    try {
                        String content = Files.readString(lspJsonPath);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> parsed = objectMapper.readValue(content, Map.class);
                        Map<String, LspServerConfig> fromFile = parseLspServerConfigMap(parsed, plugin.getName());
                        servers.putAll(fromFile);
                    } catch (IOException e) {
                        log.error("Failed to read/parse .lsp.json in plugin {}: {}",
                            plugin.getName(), e.getMessage());
                    }
                }
            }

            // 2. Check manifest.lspServers field (inline configs only)
            Object lspServers = plugin.getManifest() != null ? plugin.getManifest().getLspServers() : null;
            if (lspServers != null) {
                Map<String, LspServerConfig> fromManifest = loadLspServersFromManifest(
                    lspServers, plugin.getPath(), plugin.getName());
                servers.putAll(fromManifest);
            }

            return servers.isEmpty() ? null : servers;
        });
    }

    /**
     * Get LSP servers from a specific plugin with environment variable resolution and scoping.
     * Translated from getPluginLspServers() in lspPluginIntegration.ts
     */
    public CompletableFuture<Map<String, ScopedLspServerConfig>> getPluginLspServers(
            PluginTypes.LoadedPlugin plugin) {

        if (!plugin.isEnabled()) {
            return CompletableFuture.completedFuture(null);
        }

        return loadPluginLspServers(plugin).thenApply(servers -> {
            if (servers == null) return null;

            // Resolve environment variables
            Map<String, LspServerConfig> resolvedServers = new LinkedHashMap<>();
            for (Map.Entry<String, LspServerConfig> entry : servers.entrySet()) {
                resolvedServers.put(entry.getKey(),
                    resolvePluginLspEnvironment(entry.getValue(), plugin));
            }

            // Add plugin scope
            return addPluginScopeToLspServers(resolvedServers, plugin.getName());
        });
    }

    /**
     * Extract all LSP servers from all loaded plugins.
     * Translated from extractLspServersFromPlugins() in lspPluginIntegration.ts
     */
    public CompletableFuture<Map<String, ScopedLspServerConfig>> extractLspServersFromPlugins(
            List<PluginTypes.LoadedPlugin> plugins) {

        Map<String, ScopedLspServerConfig> allServers = new LinkedHashMap<>();

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (PluginTypes.LoadedPlugin plugin : plugins) {
            if (!plugin.isEnabled()) continue;

            CompletableFuture<Void> f = loadPluginLspServers(plugin).thenAccept(servers -> {
                if (servers != null) {
                    Map<String, ScopedLspServerConfig> scopedServers =
                        addPluginScopeToLspServers(servers, plugin.getName());
                    synchronized (allServers) {
                        allServers.putAll(scopedServers);
                    }
                    log.debug("Loaded {} LSP servers from plugin {}",
                        servers.size(), plugin.getName());
                }
            });
            futures.add(f);
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> allServers);
    }

    /**
     * Resolve environment variables for plugin LSP servers.
     * Handles CLAUDE_PLUGIN_ROOT, CLAUDE_PLUGIN_DATA and general ${VAR} substitution.
     * Translated from resolvePluginLspEnvironment() in lspPluginIntegration.ts
     */
    public LspServerConfig resolvePluginLspEnvironment(
            LspServerConfig config, PluginTypes.LoadedPlugin plugin) {

        LspServerConfig resolved = new LspServerConfig(
            config.getCommand(),
            config.getArgs() != null ? new ArrayList<>(config.getArgs()) : null,
            config.getEnv() != null ? new LinkedHashMap<>(config.getEnv()) : new LinkedHashMap<>(),
            config.getWorkspaceFolder(),
            config.getExtensionToLanguage()
        );

        String pluginRoot = plugin.getPath() != null ? plugin.getPath() : "";
        String pluginData = plugin.getSource() != null ? plugin.getSource() : "";

        // Inject plugin-specific environment variables
        resolved.getEnv().put("CLAUDE_PLUGIN_ROOT", pluginRoot);
        resolved.getEnv().put("CLAUDE_PLUGIN_DATA", pluginData);

        // Resolve environment variable references in command, args, and env values
        if (resolved.getCommand() != null) {
            resolved.setCommand(expandVars(resolved.getCommand(), pluginRoot, plugin));
        }
        if (resolved.getArgs() != null) {
            resolved.setArgs(resolved.getArgs().stream()
                .map(arg -> expandVars(arg, pluginRoot, plugin))
                .toList());
        }
        Map<String, String> resolvedEnv = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : resolved.getEnv().entrySet()) {
            if ("CLAUDE_PLUGIN_ROOT".equals(entry.getKey()) || "CLAUDE_PLUGIN_DATA".equals(entry.getKey())) {
                resolvedEnv.put(entry.getKey(), entry.getValue());
            } else {
                resolvedEnv.put(entry.getKey(), expandVars(entry.getValue(), pluginRoot, plugin));
            }
        }
        resolved.setEnv(resolvedEnv);
        if (resolved.getWorkspaceFolder() != null) {
            resolved.setWorkspaceFolder(expandVars(resolved.getWorkspaceFolder(), pluginRoot, plugin));
        }

        return resolved;
    }

    /**
     * Add plugin scope to LSP server configs.
     * Adds a prefix to server names to avoid conflicts between plugins.
     * Translated from addPluginScopeToLspServers() in lspPluginIntegration.ts
     */
    public Map<String, ScopedLspServerConfig> addPluginScopeToLspServers(
            Map<String, LspServerConfig> servers, String pluginName) {

        Map<String, ScopedLspServerConfig> scopedServers = new LinkedHashMap<>();
        for (Map.Entry<String, LspServerConfig> entry : servers.entrySet()) {
            String scopedName = "plugin:" + pluginName + ":" + entry.getKey();
            LspServerConfig cfg = entry.getValue();
            ScopedLspServerConfig scoped = new ScopedLspServerConfig();
            scoped.setCommand(cfg.getCommand());
            scoped.setArgs(cfg.getArgs());
            scoped.setEnv(cfg.getEnv());
            scoped.setWorkspaceFolder(cfg.getWorkspaceFolder());
            scoped.setExtensionToLanguage(cfg.getExtensionToLanguage());
            scoped.setScope("dynamic");
            scoped.setSource(pluginName);
            scopedServers.put(scopedName, scoped);
        }
        return scopedServers;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Validate that a resolved path stays within the plugin directory.
     * Prevents path traversal via ".." or absolute paths.
     * Translated from validatePathWithinPlugin() in lspPluginIntegration.ts
     */
    private String validatePathWithinPlugin(String pluginPath, String relativePath) {
        try {
            Path resolved = Paths.get(pluginPath).resolve(relativePath).normalize();
            Path pluginBase = Paths.get(pluginPath).normalize();
            if (!resolved.startsWith(pluginBase)) {
                return null; // Outside plugin directory
            }
            return resolved.toString();
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, LspServerConfig> loadLspServersFromManifest(
            Object declaration, String pluginPath, String pluginName) {

        Map<String, LspServerConfig> servers = new LinkedHashMap<>();
        List<Object> declarations;
        if (declaration instanceof List<?> list) {
            declarations = (List<Object>) list;
        } else {
            declarations = List.of(declaration);
        }

        for (Object decl : declarations) {
            if (decl instanceof String filePath) {
                // File reference: validate and load
                if (pluginPath == null) continue;
                String validatedPath = validatePathWithinPlugin(pluginPath, filePath);
                if (validatedPath == null) {
                    log.error("Security: Path traversal attempt blocked in plugin {}: {}",
                        pluginName, filePath);
                    continue;
                }
                try {
                    String content = Files.readString(Path.of(validatedPath));
                    Map<String, Object> parsed = objectMapper.readValue(content, Map.class);
                    servers.putAll(parseLspServerConfigMap(parsed, pluginName));
                } catch (Exception e) {
                    log.error("Failed to read/parse LSP config from {} in plugin {}: {}",
                        filePath, pluginName, e.getMessage());
                }
            } else if (decl instanceof Map<?, ?> inlineMap) {
                servers.putAll(parseLspServerConfigMap((Map<String, Object>) inlineMap, pluginName));
            }
        }
        return servers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, LspServerConfig> parseLspServerConfigMap(
            Map<String, Object> raw, String pluginName) {

        Map<String, LspServerConfig> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String serverName = entry.getKey();
            if (!(entry.getValue() instanceof Map<?, ?> cfgMap)) continue;
            Map<String, Object> cfg = (Map<String, Object>) cfgMap;

            String command = cfg.get("command") instanceof String s ? s : null;
            List<String> args = cfg.get("args") instanceof List<?> l
                ? (List<String>) l : null;
            Map<String, String> env = cfg.get("env") instanceof Map<?, ?> m
                ? (Map<String, String>) m : null;
            String wsFolder = cfg.get("workspaceFolder") instanceof String s ? s : null;
            Map<String, String> extToLang = cfg.get("extensionToLanguage") instanceof Map<?, ?> m
                ? (Map<String, String>) m : null;

            result.put(serverName, new LspServerConfig(command, args, env, wsFolder, extToLang));
        }
        return result;
    }

    private String expandVars(String value, String pluginRoot, PluginTypes.LoadedPlugin plugin) {
        if (value == null) return null;
        String expanded = value
            .replace("${CLAUDE_PLUGIN_ROOT}", pluginRoot)
            .replace("${CLAUDE_PLUGIN_DATA}", plugin.getSource() != null ? plugin.getSource() : "");
        // Expand general environment variables ${VAR_NAME}
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < expanded.length()) {
            int start = expanded.indexOf("${", i);
            if (start == -1) { sb.append(expanded, i, expanded.length()); break; }
            sb.append(expanded, i, start);
            int end = expanded.indexOf("}", start);
            if (end == -1) { sb.append(expanded, start, expanded.length()); break; }
            String varName = expanded.substring(start + 2, end);
            String envVal = System.getenv(varName);
            sb.append(envVal != null ? envVal : expanded, start, end + 1);
            i = end + 1;
        }
        return sb.toString();
    }
}
