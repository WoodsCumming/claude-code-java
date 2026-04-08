package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.McpConnectionState;
import com.anthropic.claudecode.model.McpServerConfig;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP connection manager service.
 * Translated from src/services/mcp/useManageMCPConnections.ts
 *
 * Manages MCP (Model Context Protocol) server connections and their lifecycle:
 * - Initializes MCP client connections from config
 * - Handles connection lifecycle events
 * - Manages automatic reconnection for SSE/HTTP connections with exponential backoff
 * - Deduplicates errors added to state
 */
@Slf4j
@Service
public class McpConnectionManagerService {



    // Constants for reconnection with exponential backoff
    // Translated from MAX_RECONNECT_ATTEMPTS, INITIAL_BACKOFF_MS, MAX_BACKOFF_MS
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long INITIAL_BACKOFF_MS = 1_000L;
    private static final long MAX_BACKOFF_MS = 30_000L;

    private final McpService mcpService;
    private final McpConfigService mcpConfigService;

    /** Active connection states keyed by server name. */
    private final Map<String, McpConnectionState> connectionStates = new ConcurrentHashMap<>();

    /** Active reconnect timers keyed by server name — allows cancellation. */
    private final Map<String, ScheduledFuture<?>> reconnectTimers = new ConcurrentHashMap<>();

    /** Accumulated plugin errors, deduplicated by error key. */
    private final List<PluginError> pluginErrors = new CopyOnWriteArrayList<>();
    private final Set<String> knownErrorKeys = ConcurrentHashMap.newKeySet();

    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mcp-reconnect");
            t.setDaemon(true);
            return t;
        });

    @Autowired
    public McpConnectionManagerService(McpService mcpService, McpConfigService mcpConfigService) {
        this.mcpService = mcpService;
        this.mcpConfigService = mcpConfigService;
    }

    // =========================================================================
    // Plugin error types
    // =========================================================================

    /**
     * A plugin error entry (analogous to PluginError in src/types/plugin.ts).
     */
    @Data
    @lombok.NoArgsConstructor(force = true)
    @lombok.AllArgsConstructor
    public static class PluginError {
        private final String type;
        private final String source;
        private final String plugin;
        private final String message;

        public String getType() { return type; }
        public String getSource() { return source; }
        public String getPlugin() { return plugin; }
        public String getMessage() { return message; }
    }

    /**
     * Create a unique dedup key for a plugin error.
     * Translated from getErrorKey() in useManageMCPConnections.ts
     */
    private static String getErrorKey(PluginError error) {
        String plugin = error.getPlugin() != null ? error.getPlugin() : "no-plugin";
        return error.getType() + ":" + error.getSource() + ":" + plugin;
    }

    /**
     * Add errors to the plugin error list, deduplicating to avoid showing the same error twice.
     * Translated from addErrorsToAppState() in useManageMCPConnections.ts
     */
    public void addErrors(List<PluginError> newErrors) {
        if (newErrors == null || newErrors.isEmpty()) return;
        for (PluginError error : newErrors) {
            String key = getErrorKey(error);
            if (knownErrorKeys.add(key)) {
                pluginErrors.add(error);
            }
        }
    }

    /** Get the accumulated plugin errors (unmodifiable). */
    public List<PluginError> getPluginErrors() {
        return Collections.unmodifiableList(pluginErrors);
    }

    // =========================================================================
    // Connection initialization
    // =========================================================================

    /**
     * Connect to all configured MCP servers for the given project path.
     * Translated from the initialization logic in useManageMCPConnections.ts
     */
    public CompletableFuture<Void> initializeMcpConnections(String projectPath) {
        Map<String, McpServerConfig> servers = mcpConfigService.getAllMcpServers(projectPath);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Map.Entry<String, McpServerConfig> entry : servers.entrySet()) {
            futures.add(connectServer(entry.getKey(), entry.getValue()));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    /**
     * Connect to a specific MCP server, tracking state transitions.
     * Marks server as pending → connected (or failed).
     */
    public CompletableFuture<Void> connectServer(String serverName, McpServerConfig config) {
        updateConnectionState(serverName, new McpConnectionState.Pending(serverName, null, null));

        return mcpService.connect(buildMcpServerConfig(serverName, config))
            .thenAccept(connection -> {
                updateConnectionState(serverName,
                    new McpConnectionState.Connected(serverName, null, null, null));
                log.info("Connected to MCP server: {}", serverName);
                // Note: In a full implementation, we'd register a close handler on
                // the connection object to trigger handleServerClose() for SSE/HTTP
                // transport reconnection with exponential backoff.
            })
            .exceptionally(e -> {
                updateConnectionState(serverName,
                    new McpConnectionState.Failed(serverName, e.getMessage()));
                log.warn("Failed to connect to MCP server {}: {}", serverName, e.getMessage());
                return null;
            });
    }

    // =========================================================================
    // Reconnection with exponential backoff
    // =========================================================================

    /**
     * Register an on-close handler that attempts exponential-backoff reconnection
     * for SSE and HTTP transports.
     * Translated from the client.onclose handler in useManageMCPConnections.ts
     *
     * In a full implementation, this registers a close callback with the MCP client
     * connection object. Here we expose it as a callable hook for the connection
     * lifecycle manager.
     */
    public void handleServerClose(String serverName, McpServerConfig config, String configType) {
        if (mcpConfigService.isMcpServerDisabled(serverName)) {
            log.debug("[MCP {}] Server is disabled, skipping automatic reconnection", serverName);
            return;
        }

        log.debug("[MCP {}] {} transport closed, attempting automatic reconnection", serverName, configType);

        // Cancel any existing reconnection attempt
        ScheduledFuture<?> existing = reconnectTimers.remove(serverName);
        if (existing != null) existing.cancel(false);

        reconnectWithBackoff(serverName, config, 1);
    }

    /**
     * Attempt reconnection with exponential backoff.
     * Translated from reconnectWithBackoff() in useManageMCPConnections.ts
     */
    private void reconnectWithBackoff(String serverName, McpServerConfig config, int attempt) {
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            log.warn("[MCP {}] Exceeded max reconnect attempts ({})", serverName, MAX_RECONNECT_ATTEMPTS);
            updateConnectionState(serverName,
                new McpConnectionState.Failed(serverName,
                    "Failed to reconnect after " + MAX_RECONNECT_ATTEMPTS + " attempts"));
            reconnectTimers.remove(serverName);
            return;
        }

        if (mcpConfigService.isMcpServerDisabled(serverName)) {
            log.debug("[MCP {}] Server disabled during reconnection, stopping retry", serverName);
            reconnectTimers.remove(serverName);
            return;
        }

        // Pending state with attempt info
        updateConnectionState(serverName,
            new McpConnectionState.Pending(serverName, attempt, MAX_RECONNECT_ATTEMPTS));

        long backoffMs = Math.min(INITIAL_BACKOFF_MS * (1L << (attempt - 1)), MAX_BACKOFF_MS);
        log.debug("[MCP {}] Reconnect attempt {}/{} in {}ms", serverName, attempt, MAX_RECONNECT_ATTEMPTS, backoffMs);

        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            reconnectTimers.remove(serverName);
            if (mcpConfigService.isMcpServerDisabled(serverName)) return;

            // Re-use connect() for reconnection (same as initial connection)
            mcpService.connect(buildMcpServerConfig(serverName, config))
                .thenAccept(connection -> {
                    updateConnectionState(serverName,
                        new McpConnectionState.Connected(serverName, null, null, null));
                    log.info("[MCP {}] Reconnected successfully", serverName);
                })
                .exceptionally(e -> {
                    log.warn("[MCP {}] Reconnect attempt {} failed: {}", serverName, attempt, e.getMessage());
                    reconnectWithBackoff(serverName, config, attempt + 1);
                    return null;
                });
        }, backoffMs, TimeUnit.MILLISECONDS);

        reconnectTimers.put(serverName, timer);
    }

    // =========================================================================
    // State management
    // =========================================================================

    /**
     * Update the connection state for a server (thread-safe).
     */
    private void updateConnectionState(String serverName, McpConnectionState newState) {
        connectionStates.put(serverName, newState);
    }

    /**
     * Disconnect from a specific MCP server (disables it).
     */
    public void disconnectServer(String serverName) {
        ScheduledFuture<?> timer = reconnectTimers.remove(serverName);
        if (timer != null) timer.cancel(false);
        updateConnectionState(serverName, new McpConnectionState.Disabled(serverName));
        log.info("Disconnected from MCP server: {}", serverName);
    }

    /**
     * Get the connection state for a server.
     */
    public Optional<McpConnectionState> getConnectionState(String serverName) {
        return Optional.ofNullable(connectionStates.get(serverName));
    }

    /**
     * Get all connection states.
     */
    public Map<String, McpConnectionState> getAllConnectionStates() {
        return Collections.unmodifiableMap(connectionStates);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private McpService.McpServerConfig buildMcpServerConfig(String serverName, McpServerConfig config) {
        return new McpService.McpServerConfig(
            serverName,
            config.getType(),
            config instanceof McpServerConfig.StdioConfig s ? s.getCommand() : null,
            config instanceof McpServerConfig.StdioConfig s ? s.getArgs() : null,
            config instanceof McpServerConfig.StdioConfig s ? s.getEnv() : null,
            config instanceof McpServerConfig.SseConfig s ? s.getUrl() : null,
            config instanceof McpServerConfig.SseConfig s ? s.getHeaders() : null
        );
    }
}
