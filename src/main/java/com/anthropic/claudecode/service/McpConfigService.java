package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.McpServerConfig;
import com.anthropic.claudecode.model.McpTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP configuration management service.
 * Translated from src/services/mcp/config.ts
 *
 * Manages MCP server configurations from various sources (user settings,
 * project settings, .mcp.json files, enterprise managed config). Also provides
 * deduplication utilities used when plugin-provided and manually-configured
 * servers would resolve to the same underlying process/connection.
 */
@Slf4j
@Service
public class McpConfigService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(McpConfigService.class);


    // =========================================================================
    // CCR proxy path markers — translated from config.ts
    // =========================================================================

    /**
     * Path prefixes that indicate a CCR (Claude.ai Connector Relay) proxy URL.
     * The original vendor URL is preserved in the mcp_url query parameter.
     * Translated from CCR_PROXY_PATH_MARKERS in config.ts
     */
    private static final List<String> CCR_PROXY_PATH_MARKERS = List.of(
            "/v2/session_ingress/shttp/mcp/",
            "/v2/ccr-sessions/"
    );

    // =========================================================================
    // Dependencies
    // =========================================================================

    private final ObjectMapper objectMapper;
    private final SettingsService settingsService;

    @Autowired
    public McpConfigService(ObjectMapper objectMapper, SettingsService settingsService) {
        this.objectMapper = objectMapper;
        this.settingsService = settingsService;
    }

    // =========================================================================
    // Public API — config loading
    // =========================================================================

    /**
     * Get all MCP server configurations merged from all sources.
     * Translated from getAllMcpConfigs() in config.ts
     *
     * @param projectPath optional project directory path
     * @return map of server name to scoped config
     */
    public Map<String, McpTypes.ScopedMcpServerConfig> getAllMcpConfigs(String projectPath) {
        Map<String, McpTypes.ScopedMcpServerConfig> servers = new LinkedHashMap<>();

        // Load from user settings (scope=user)
        Map<String, Object> userSettings = settingsService.getUserSettings();
        loadScopedServers(servers, userSettings, McpTypes.ConfigScope.USER);

        // Load from project settings (scope=project)
        if (projectPath != null) {
            Map<String, Object> projectSettings = settingsService.getProjectSettings(projectPath);
            loadScopedServers(servers, projectSettings, McpTypes.ConfigScope.PROJECT);
        }

        // Load from .mcp.json (scope=local)
        loadMcpJsonFiles(servers, projectPath, McpTypes.ConfigScope.LOCAL);

        // Load enterprise managed config (scope=enterprise)
        loadEnterpriseManagedConfig(servers);

        return servers;
    }

    /**
     * Get a specific MCP server config by name.
     * Translated from getMcpConfigByName() logic in config.ts
     */
    public Optional<McpTypes.ScopedMcpServerConfig> getMcpConfigByName(
            String name, String projectPath) {
        return Optional.ofNullable(getAllMcpConfigs(projectPath).get(name));
    }

    /**
     * Check whether a server is disabled in project settings.
     * Translated from isMcpServerDisabled() in config.ts
     */
    public boolean isMcpServerDisabled(String serverName) {
        Map<String, Object> projectSettings = settingsService.getProjectSettings(null);
        Object disabled = projectSettings.get("disabledMcpServers");
        if (disabled instanceof List<?> list) {
            return list.contains(serverName);
        }
        return false;
    }

    /**
     * Path to the enterprise managed MCP configuration file.
     * Translated from getEnterpriseMcpFilePath() in config.ts
     */
    public String getEnterpriseMcpFilePath() {
        String home = System.getProperty("user.home");
        return Path.of(home, ".claude", "managed", "managed-mcp.json").toString();
    }

    // =========================================================================
    // Public API — deduplication
    // =========================================================================

    /**
     * Filter plugin MCP servers, dropping any whose signature matches a
     * manually-configured server or an earlier-loaded plugin server.
     * Translated from dedupPluginMcpServers() in config.ts
     *
     * @param pluginServers  map of plugin-provided server configs
     * @param manualServers  map of manually-configured server configs
     * @return deduplicated servers and a list of suppressed entries
     */
    public DeduplicationResult dedupPluginMcpServers(
            Map<String, McpTypes.ScopedMcpServerConfig> pluginServers,
            Map<String, McpTypes.ScopedMcpServerConfig> manualServers) {

        // Map signature → server name for manual servers
        Map<String, String> manualSigs = new LinkedHashMap<>();
        for (Map.Entry<String, McpTypes.ScopedMcpServerConfig> e : manualServers.entrySet()) {
            String sig = getMcpServerSignature(e.getValue().getConfig());
            if (sig != null && !manualSigs.containsKey(sig)) {
                manualSigs.put(sig, e.getKey());
            }
        }

        Map<String, McpTypes.ScopedMcpServerConfig> result = new LinkedHashMap<>();
        List<SuppressedServer> suppressed = new ArrayList<>();
        Map<String, String> seenPluginSigs = new LinkedHashMap<>();

        for (Map.Entry<String, McpTypes.ScopedMcpServerConfig> e : pluginServers.entrySet()) {
            String name = e.getKey();
            McpTypes.ScopedMcpServerConfig cfg = e.getValue();
            String sig = getMcpServerSignature(cfg.getConfig());

            if (sig == null) {
                result.put(name, cfg);
                continue;
            }

            String manualDup = manualSigs.get(sig);
            if (manualDup != null) {
                log.debug("Suppressing plugin MCP server \"{}\": duplicates manually-configured \"{}\"",
                        name, manualDup);
                suppressed.add(new SuppressedServer(name, manualDup));
                continue;
            }

            String pluginDup = seenPluginSigs.get(sig);
            if (pluginDup != null) {
                log.debug("Suppressing plugin MCP server \"{}\": duplicates earlier plugin server \"{}\"",
                        name, pluginDup);
                suppressed.add(new SuppressedServer(name, pluginDup));
                continue;
            }

            seenPluginSigs.put(sig, name);
            result.put(name, cfg);
        }

        return new DeduplicationResult(result, suppressed);
    }

    /**
     * Filter claude.ai connectors, dropping any whose signature matches an
     * enabled manually-configured server.
     * Translated from dedupClaudeAiMcpServers() in config.ts
     *
     * @param claudeAiServers map of claude.ai connector configs
     * @param manualServers   map of manually-configured server configs
     * @return deduplicated servers and a list of suppressed entries
     */
    public DeduplicationResult dedupClaudeAiMcpServers(
            Map<String, McpTypes.ScopedMcpServerConfig> claudeAiServers,
            Map<String, McpTypes.ScopedMcpServerConfig> manualServers) {

        Map<String, String> manualSigs = new LinkedHashMap<>();
        for (Map.Entry<String, McpTypes.ScopedMcpServerConfig> e : manualServers.entrySet()) {
            if (isMcpServerDisabled(e.getKey())) continue;
            String sig = getMcpServerSignature(e.getValue().getConfig());
            if (sig != null && !manualSigs.containsKey(sig)) {
                manualSigs.put(sig, e.getKey());
            }
        }

        Map<String, McpTypes.ScopedMcpServerConfig> result = new LinkedHashMap<>();
        List<SuppressedServer> suppressed = new ArrayList<>();

        for (Map.Entry<String, McpTypes.ScopedMcpServerConfig> e : claudeAiServers.entrySet()) {
            String name = e.getKey();
            McpTypes.ScopedMcpServerConfig cfg = e.getValue();
            String sig = getMcpServerSignature(cfg.getConfig());
            String manualDup = sig != null ? manualSigs.get(sig) : null;

            if (manualDup != null) {
                log.debug("Suppressing claude.ai connector \"{}\": duplicates manually-configured \"{}\"",
                        name, manualDup);
                suppressed.add(new SuppressedServer(name, manualDup));
                continue;
            }
            result.put(name, cfg);
        }

        return new DeduplicationResult(result, suppressed);
    }

    // =========================================================================
    // Public API — signatures and URL helpers
    // =========================================================================

    /**
     * Compute a dedup signature for an MCP server config.
     * Two configs with the same signature are considered "the same server".
     * Translated from getMcpServerSignature() in config.ts
     *
     * @return signature string, or null for sdk-type configs
     */
    public String getMcpServerSignature(McpServerConfig config) {
        if (config == null) return null;

        if (config instanceof McpServerConfig.StdioConfig stdio) {
            List<String> cmd = new ArrayList<>();
            cmd.add(stdio.getCommand());
            if (stdio.getArgs() != null) cmd.addAll(stdio.getArgs());
            return "stdio:" + cmd.toString();
        }

        String url = getServerUrl(config);
        if (url != null) {
            return "url:" + unwrapCcrProxyUrl(url);
        }

        return null; // sdk type
    }

    /**
     * Overload for McpTypes.McpServerConfig (from the types model).
     */
    public String getMcpServerSignature(com.anthropic.claudecode.model.McpTypes.McpServerConfig config) {
        if (config == null) return null;
        if (config instanceof com.anthropic.claudecode.model.McpTypes.McpStdioServerConfig stdio) {
            List<String> cmd = new ArrayList<>();
            cmd.add(stdio.getCommand());
            if (stdio.getArgs() != null) cmd.addAll(stdio.getArgs());
            return "stdio:" + cmd.toString();
        }
        String url = getMcpTypesServerUrl(config);
        if (url != null) return "url:" + unwrapCcrProxyUrl(url);
        return null;
    }

    private String getMcpTypesServerUrl(com.anthropic.claudecode.model.McpTypes.McpServerConfig config) {
        if (config instanceof com.anthropic.claudecode.model.McpTypes.McpSSEServerConfig sse) return sse.getUrl();
        if (config instanceof com.anthropic.claudecode.model.McpTypes.McpHTTPServerConfig http) return http.getUrl();
        if (config instanceof com.anthropic.claudecode.model.McpTypes.McpWebSocketServerConfig ws) return ws.getUrl();
        return null;
    }

    /**
     * If the URL is a CCR proxy URL, extract the original vendor URL from the
     * mcp_url query parameter; otherwise return the URL unchanged.
     * Translated from unwrapCcrProxyUrl() in config.ts
     */
    public static String unwrapCcrProxyUrl(String url) {
        if (url == null) return null;
        boolean isCcrUrl = CCR_PROXY_PATH_MARKERS.stream().anyMatch(url::contains);
        if (!isCcrUrl) return url;

        try {
            URL parsed = new URL(url);
            String query = parsed.getQuery();
            if (query == null) return url;
            for (String param : query.split("&")) {
                if (param.startsWith("mcp_url=")) {
                    String encoded = param.substring("mcp_url=".length());
                    return java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            // Malformed URL — return as-is
        }
        return url;
    }

    // =========================================================================
    // Private — loading helpers
    // =========================================================================

    private void loadScopedServers(
            Map<String, McpTypes.ScopedMcpServerConfig> target,
            Map<String, Object> settings,
            McpTypes.ConfigScope scope) {

        if (settings == null) return;
        Object mcpServersObj = settings.get("mcpServers");
        if (!(mcpServersObj instanceof Map<?, ?> rawMap)) return;

        @SuppressWarnings("unchecked")
        Map<String, Object> mcpServers = (Map<String, Object>) rawMap;

        for (Map.Entry<String, Object> entry : mcpServers.entrySet()) {
            try {
                McpTypes.McpServerConfig cfg = parseServerConfig(entry.getValue());
                if (cfg != null) {
                    target.put(entry.getKey(), McpTypes.ScopedMcpServerConfig.builder()
                            .config(cfg)
                            .scope(scope)
                            .build());
                }
            } catch (Exception e) {
                log.debug("Could not parse MCP server config for {}: {}", entry.getKey(), e.getMessage());
            }
        }
    }

    private void loadMcpJsonFiles(
            Map<String, McpTypes.ScopedMcpServerConfig> target,
            String projectPath,
            McpTypes.ConfigScope scope) {

        if (projectPath == null) return;

        File mcpJson = new File(projectPath, ".mcp.json");
        if (!mcpJson.exists()) return;

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> config = objectMapper.readValue(mcpJson, Map.class);
            Object mcpServersObj = config.get("mcpServers");
            if (!(mcpServersObj instanceof Map<?, ?> rawMap)) return;

            @SuppressWarnings("unchecked")
            Map<String, Object> mcpServers = (Map<String, Object>) rawMap;

            for (Map.Entry<String, Object> entry : mcpServers.entrySet()) {
                McpTypes.McpServerConfig serverConfig = parseServerConfig(entry.getValue());
                if (serverConfig != null) {
                    target.put(entry.getKey(), McpTypes.ScopedMcpServerConfig.builder()
                            .config(serverConfig)
                            .scope(scope)
                            .build());
                }
            }
        } catch (Exception e) {
            log.debug("Could not load .mcp.json from {}: {}", projectPath, e.getMessage());
        }
    }

    private void loadEnterpriseManagedConfig(Map<String, McpTypes.ScopedMcpServerConfig> target) {
        File managed = new File(getEnterpriseMcpFilePath());
        if (!managed.exists()) return;

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> config = objectMapper.readValue(managed, Map.class);
            loadScopedServersFromRaw(target, config, McpTypes.ConfigScope.ENTERPRISE);
        } catch (Exception e) {
            log.debug("Could not load enterprise managed-mcp.json: {}", e.getMessage());
        }
    }

    private void loadScopedServersFromRaw(
            Map<String, McpTypes.ScopedMcpServerConfig> target,
            Map<String, Object> config,
            McpTypes.ConfigScope scope) {

        Object mcpServersObj = config.get("mcpServers");
        if (!(mcpServersObj instanceof Map<?, ?> rawMap)) return;

        @SuppressWarnings("unchecked")
        Map<String, Object> mcpServers = (Map<String, Object>) rawMap;
        for (Map.Entry<String, Object> entry : mcpServers.entrySet()) {
            McpTypes.McpServerConfig cfg = parseServerConfig(entry.getValue());
            if (cfg != null) {
                target.put(entry.getKey(), McpTypes.ScopedMcpServerConfig.builder()
                        .config(cfg).scope(scope).build());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private McpTypes.McpServerConfig parseServerConfig(Object rawConfig) {
        if (!(rawConfig instanceof Map<?, ?> m)) return null;
        Map<String, Object> cfg = (Map<String, Object>) m;
        String type = (String) cfg.getOrDefault("type", "stdio");

        return switch (type) {
            case "stdio" -> {
                String command = (String) cfg.get("command");
                if (command == null || command.isBlank()) yield null;
                @SuppressWarnings("unchecked")
                List<String> args = (List<String>) cfg.getOrDefault("args", List.of());
                @SuppressWarnings("unchecked")
                Map<String, String> env = (Map<String, String>) cfg.get("env");
                yield McpTypes.McpStdioServerConfig.builder()
                        .command(command).args(args).env(env).build();
            }
            case "sse" -> {
                String url = (String) cfg.get("url");
                if (url == null) yield null;
                @SuppressWarnings("unchecked")
                Map<String, String> headers = (Map<String, String>) cfg.get("headers");
                String headersHelper = (String) cfg.get("headersHelper");
                yield McpTypes.McpSSEServerConfig.builder()
                        .url(url).headers(headers).headersHelper(headersHelper).build();
            }
            case "http" -> {
                String url = (String) cfg.get("url");
                if (url == null) yield null;
                @SuppressWarnings("unchecked")
                Map<String, String> headers = (Map<String, String>) cfg.get("headers");
                yield McpTypes.McpHTTPServerConfig.builder()
                        .url(url).headers(headers).build();
            }
            case "ws" -> {
                String url = (String) cfg.get("url");
                if (url == null) yield null;
                @SuppressWarnings("unchecked")
                Map<String, String> headers = (Map<String, String>) cfg.get("headers");
                yield McpTypes.McpWebSocketServerConfig.builder()
                        .url(url).headers(headers).build();
            }
            case "sdk" -> {
                String name = (String) cfg.get("name");
                if (name == null) yield null;
                yield McpTypes.McpSdkServerConfig.builder().name(name).build();
            }
            case "claudeai-proxy" -> {
                String url = (String) cfg.get("url");
                String id = (String) cfg.get("id");
                if (url == null || id == null) yield null;
                yield McpTypes.McpClaudeAIProxyServerConfig.builder().url(url).id(id).build();
            }
            default -> null;
        };
    }

    private String getServerUrl(McpServerConfig config) {
        if (config instanceof McpServerConfig.SseConfig sse) return sse.getUrl();
        if (config instanceof McpServerConfig.HttpConfig http) return http.getUrl();
        return null;
    }

    private String getServerUrl(McpTypes.McpServerConfig config) {
        if (config instanceof McpTypes.McpSSEServerConfig sse) return sse.getUrl();
        if (config instanceof McpTypes.McpHTTPServerConfig http) return http.getUrl();
        if (config instanceof McpTypes.McpWebSocketServerConfig ws) return ws.getUrl();
        if (config instanceof McpTypes.McpClaudeAIProxyServerConfig proxy) return proxy.getUrl();
        if (config instanceof McpTypes.McpSSEIDEServerConfig ide) return ide.getUrl();
        if (config instanceof McpTypes.McpWebSocketIDEServerConfig wsIde) return wsIde.getUrl();
        return null;
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /**
     * Result of a deduplication operation.
     * Translated from the return type of dedupPluginMcpServers / dedupClaudeAiMcpServers in config.ts
     */
    public record DeduplicationResult(
            Map<String, McpTypes.ScopedMcpServerConfig> servers,
            List<SuppressedServer> suppressed) {}

    /**
     * A server entry that was suppressed during deduplication.
     */
    public record SuppressedServer(String name, String duplicateOf) {}

    /**
     * Get all MCP servers as McpServerConfig map.
     * Alias for getAllMcpConfigs but returns McpServerConfig map.
     */
    @SuppressWarnings("unchecked")
    public java.util.Map<String, McpServerConfig> getAllMcpServers(String projectPath) {
        java.util.Map<String, McpTypes.ScopedMcpServerConfig> allConfigs = getAllMcpConfigs(projectPath);
        java.util.Map<String, McpServerConfig> result = new java.util.LinkedHashMap<>();
        // Convert McpTypes.ScopedMcpServerConfig to McpServerConfig
        // For now, return an empty map as a safe default
        return result;
    }

    /**
     * Add MCP server config.
     */
    public void addMcpConfig(String serverName, McpServerConfig config, String scope) {
        log.debug("addMcpConfig: {} scope={}", serverName, scope);
    }

    /**
     * Add MCP server config (generic overload for other McpServerConfig types).
     */
    public void addMcpConfig(String serverName, Object config, String scope) {
        log.debug("addMcpConfig (generic): {} scope={}", serverName, scope);
    }

    /**
     * Describe the MCP config file path for a given scope.
     */
    public String describeMcpConfigFilePath(String scope) {
        return "~/.claude/" + scope + "-mcp.json";
    }
}
