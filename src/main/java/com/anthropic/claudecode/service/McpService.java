package com.anthropic.claudecode.service;

import com.anthropic.claudecode.config.ClaudeCodeConfig;
import com.anthropic.claudecode.model.McpTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import lombok.Data;

/**
 * MCP (Model Context Protocol) client service.
 * Translated from src/services/mcp/client.ts
 *
 * Manages connections to MCP servers and provides their tools/resources
 * to the main conversation loop. Handles authentication, session expiry,
 * and reconnection.
 */
@Slf4j
@Service
public class McpService {


    // =========================================================================
    // Constants — translated from client.ts
    // =========================================================================

    /** Default MCP tool call timeout (~27.8 hours, effectively infinite). */
    public static final long DEFAULT_MCP_TOOL_TIMEOUT_MS = 100_000_000L;

    /**
     * Cap on MCP tool descriptions and server instructions sent to the model.
     * OpenAPI-generated MCP servers have been observed dumping 15-60 KB of
     * endpoint docs into tool.description.
     */
    public static final int MAX_MCP_DESCRIPTION_LENGTH = 2048;

    /** Auth cache TTL: 15 minutes. */
    private static final long MCP_AUTH_CACHE_TTL_MS = 15 * 60 * 1000L;

    // =========================================================================
    // Custom exceptions — translated from client.ts
    // =========================================================================

    /**
     * Thrown when an MCP tool call fails due to authentication issues
     * (e.g. expired OAuth token returning 401).
     * Translated from McpAuthError in client.ts
     */
    public static class McpAuthError extends RuntimeException {
        private final String serverName;
        public McpAuthError(String serverName, String message) {
            super(message);
            this.serverName = serverName;
        }
        public String getServerName() { return serverName; }
    }

    /**
     * Thrown when an MCP session has expired (HTTP 404 + JSON-RPC code -32001).
     * Translated from McpSessionExpiredError in client.ts
     */
    public static class McpSessionExpiredError extends RuntimeException {
        public McpSessionExpiredError(String serverName) {
            super("MCP server \"" + serverName + "\" session expired");
        }
    }

    /**
     * Thrown when an MCP tool returns isError:true.
     * Translated from McpToolCallError in client.ts
     */
    @lombok.EqualsAndHashCode(callSuper = false)
    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor(force = true)
    public static class McpToolCallError extends RuntimeException {
        private final Map<String, Object> mcpMeta;
        public Map<String, Object> getMcpMeta() { return mcpMeta; }
    }

    // =========================================================================
    // Dependencies and state
    // =========================================================================

    private final ClaudeCodeConfig config;
    private final ObjectMapper objectMapper;

    /** Live server connections, keyed by server name. */
    private final Map<String, McpServerConnection> connections = new ConcurrentHashMap<>();

    /**
     * Per-server auth cache: serverName → timestamp of last needs-auth event.
     * Entries expire after MCP_AUTH_CACHE_TTL_MS.
     * Translated from McpAuthCacheData in client.ts
     */
    private final Map<String, Long> authCache = new ConcurrentHashMap<>();

    @Autowired
    public McpService(ClaudeCodeConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    // Auth cache helpers — translated from client.ts
    // =========================================================================

    /**
     * Return true if the server's auth-needs entry is still within TTL.
     * Translated from isMcpAuthCached() in client.ts
     */
    public boolean isMcpAuthCached(String serverName) {
        Long ts = authCache.get(serverName);
        if (ts == null) return false;
        return (System.currentTimeMillis() - ts) < MCP_AUTH_CACHE_TTL_MS;
    }

    /**
     * Record that this server requires authentication.
     * Translated from setMcpAuthCacheEntry() in client.ts
     */
    public void setMcpAuthCacheEntry(String serverName) {
        authCache.put(serverName, System.currentTimeMillis());
    }

    /**
     * Clear the in-memory auth cache (and best-effort delete the on-disk cache).
     * Translated from clearMcpAuthCache() in client.ts
     */
    public void clearMcpAuthCache() {
        authCache.clear();
        try {
            Path cachePath = getMcpAuthCachePath();
            Files.deleteIfExists(cachePath);
        } catch (Exception e) {
            log.debug("Could not delete MCP auth cache file: {}", e.getMessage());
        }
    }

    private Path getMcpAuthCachePath() {
        String home = System.getProperty("user.home");
        return Path.of(home, ".claude", "mcp-needs-auth-cache.json");
    }

    // =========================================================================
    // Session-expiry detection — translated from client.ts
    // =========================================================================

    /**
     * Detect whether an error is an MCP "Session not found" error
     * (HTTP 404 + JSON-RPC code -32001).
     * Translated from isMcpSessionExpiredError() in client.ts
     */
    public static boolean isMcpSessionExpiredError(Throwable error) {
        if (error == null) return false;
        String msg = error.getMessage();
        if (msg == null) return false;
        // Check for HTTP 404 via error message convention
        boolean has404 = msg.contains("404") || msg.contains("status 404");
        boolean hasCode = msg.contains("\"code\":-32001") || msg.contains("\"code\": -32001");
        return has404 && hasCode;
    }

    // =========================================================================
    // Connect
    // =========================================================================

    /**
     * Connect to an MCP server.
     * Translated from connectMcpServer() in client.ts
     */
    public CompletableFuture<McpServerConnection> connect(McpServerConfig serverConfig) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("Connecting to MCP server: {}", serverConfig.getName());

                // Return needs-auth immediately if we know auth is required
                if (isMcpAuthCached(serverConfig.getName())) {
                    log.debug("MCP server {} needs auth (cached)", serverConfig.getName());
                    return new NeedsAuthMcpServerConnection(serverConfig.getName());
                }

                McpServerConnection connection = switch (serverConfig.getTransport()) {
                    case "stdio" -> connectStdio(serverConfig);
                    case "sse" -> connectSse(serverConfig);
                    case "http" -> connectHttp(serverConfig);
                    default -> throw new IllegalArgumentException(
                        "Unknown transport: " + serverConfig.getTransport()
                    );
                };

                connections.put(serverConfig.getName(), connection);
                log.info("Connected to MCP server: {}", serverConfig.getName());
                return connection;

            } catch (McpAuthError e) {
                log.warn("MCP server {} requires authentication", serverConfig.getName());
                setMcpAuthCacheEntry(serverConfig.getName());
                return new NeedsAuthMcpServerConnection(serverConfig.getName());
            } catch (Exception e) {
                log.error("Failed to connect to MCP server {}: {}",
                    serverConfig.getName(), e.getMessage(), e);
                throw new RuntimeException("MCP connection failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Get all tools from connected MCP servers.
     */
    public List<McpTool> getAllTools() {
        List<McpTool> tools = new ArrayList<>();
        for (McpServerConnection conn : connections.values()) {
            tools.addAll(conn.getTools());
        }
        return tools;
    }

    /**
     * Get the number of connected MCP servers.
     */
    public int getConnectedServerCount() {
        return connections.size();
    }

    /**
     * Call a tool on an MCP server.
     */
    public CompletableFuture<Object> callTool(
            String serverName,
            String toolName,
            Map<String, Object> input) {

        McpServerConnection conn = connections.get(serverName);
        if (conn == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("MCP server not connected: " + serverName)
            );
        }

        return conn.callTool(toolName, input);
    }

    /**
     * Disconnect all MCP servers.
     */
    public void disconnectAll() {
        for (Map.Entry<String, McpServerConnection> entry : connections.entrySet()) {
            try {
                entry.getValue().close();
                log.info("Disconnected from MCP server: {}", entry.getKey());
            } catch (Exception e) {
                log.warn("Error disconnecting from {}: {}", entry.getKey(), e.getMessage());
            }
        }
        connections.clear();
    }

    private McpServerConnection connectStdio(McpServerConfig config) throws Exception {
        // Launch the MCP server as a subprocess
        List<String> cmd = new ArrayList<>();
        cmd.add(config.getCommand());
        if (config.getArgs() != null) {
            cmd.addAll(config.getArgs());
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (config.getEnv() != null) {
            pb.environment().putAll(config.getEnv());
        }

        Process process = pb.start();

        return new StdioMcpServerConnection(config.getName(), process, objectMapper);
    }

    private McpServerConnection connectSse(McpServerConfig config) {
        return new SseMcpServerConnection(config.getName(), config.getUrl(), objectMapper);
    }

    private McpServerConnection connectHttp(McpServerConfig config) {
        return new HttpMcpServerConnection(config.getName(), config.getUrl(), objectMapper);
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    public static class McpServerConfig {
        private String name;
        private String transport; // "stdio" | "sse" | "http"
        private String command; // for stdio
        private List<String> args; // for stdio
        private Map<String, String> env; // for stdio
        private String url; // for sse/http
        private Map<String, String> headers; // for sse/http

        public McpServerConfig() {}
        public McpServerConfig(String name, String transport, String command, List<String> args,
                                Map<String, String> env, String url, Map<String, String> headers) {
            this.name = name; this.transport = transport; this.command = command;
            this.args = args; this.env = env; this.url = url; this.headers = headers;
        }
        public String getName() { return name; }
        public void setName(String v) { name = v; }
        public String getTransport() { return transport; }
        public void setTransport(String v) { transport = v; }
        public String getCommand() { return command; }
        public void setCommand(String v) { command = v; }
        public List<String> getArgs() { return args; }
        public void setArgs(List<String> v) { args = v; }
        public Map<String, String> getEnv() { return env; }
        public void setEnv(Map<String, String> v) { env = v; }
        public String getUrl() { return url; }
        public void setUrl(String v) { url = v; }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> v) { headers = v; }

        public static McpServerConfigBuilder builder() { return new McpServerConfigBuilder(); }
        public static class McpServerConfigBuilder {
            private final McpServerConfig c = new McpServerConfig();
            public McpServerConfigBuilder name(String v) { c.name = v; return this; }
            public McpServerConfigBuilder transport(String v) { c.transport = v; return this; }
            public McpServerConfigBuilder command(String v) { c.command = v; return this; }
            public McpServerConfigBuilder args(List<String> v) { c.args = v; return this; }
            public McpServerConfigBuilder env(Map<String, String> v) { c.env = v; return this; }
            public McpServerConfigBuilder url(String v) { c.url = v; return this; }
            public McpServerConfigBuilder headers(Map<String, String> v) { c.headers = v; return this; }
            public McpServerConfig build() { return c; }
        }
    }

    public static class McpTool {
        private String serverName;
        private String name;
        private String description;
        private Map<String, Object> inputSchema;

        public McpTool() {}
        public McpTool(String serverName, String name, String description, Map<String, Object> inputSchema) {
            this.serverName = serverName; this.name = name; this.description = description; this.inputSchema = inputSchema;
        }
        public String getServerName() { return serverName; }
        public void setServerName(String v) { this.serverName = v; }
        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { this.description = v; }
        public Map<String, Object> getInputSchema() { return inputSchema; }
        public void setInputSchema(Map<String, Object> v) { this.inputSchema = v; }
    }

    /**
     * Abstract MCP server connection.
     */
    public interface McpServerConnection {
        String getName();
        List<McpTool> getTools();
        CompletableFuture<Object> callTool(String toolName, Map<String, Object> input);
        void close() throws Exception;
    }

    /**
     * Stdio-based MCP server connection.
     */
    private static class StdioMcpServerConnection implements McpServerConnection {
        private final String name;
        private final Process process;
        private final ObjectMapper objectMapper;
        private final List<McpTool> tools = new ArrayList<>();
        private int requestId = 0;

        StdioMcpServerConnection(String name, Process process, ObjectMapper objectMapper) throws Exception {
            this.name = name;
            this.process = process;
            this.objectMapper = objectMapper;
            initialize();
        }

        private void initialize() throws Exception {
            // Send initialize request
            Map<String, Object> initRequest = Map.of(
                "jsonrpc", "2.0",
                "id", ++requestId,
                "method", "initialize",
                "params", Map.of(
                    "protocolVersion", "2024-11-05",
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "claude-code-java", "version", "2.1.88")
                )
            );
            sendRequest(initRequest);

            // List tools
            Map<String, Object> listToolsRequest = Map.of(
                "jsonrpc", "2.0",
                "id", ++requestId,
                "method", "tools/list",
                "params", Map.of()
            );
            Map<String, Object> response = sendRequest(listToolsRequest);

            // Parse tools
            if (response != null && response.containsKey("result")) {
                Map<String, Object> result = (Map<String, Object>) response.get("result");
                List<Map<String, Object>> toolsList = (List<Map<String, Object>>) result.get("tools");
                if (toolsList != null) {
                    for (Map<String, Object> tool : toolsList) {
                        tools.add(new McpTool(
                            name,
                            (String) tool.get("name"),
                            (String) tool.get("description"),
                            (Map<String, Object>) tool.get("inputSchema")
                        ));
                    }
                }
            }
        }

        private Map<String, Object> sendRequest(Map<String, Object> request) throws Exception {
            String json = objectMapper.writeValueAsString(request) + "\n";
            process.getOutputStream().write(json.getBytes());
            process.getOutputStream().flush();

            // Read response
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            );
            String line = reader.readLine();
            if (line == null) return null;
            return objectMapper.readValue(line, Map.class);
        }

        @Override public String getName() { return name; }
        @Override public List<McpTool> getTools() { return tools; }

        @Override
        public CompletableFuture<Object> callTool(String toolName, Map<String, Object> input) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Map<String, Object> request = Map.of(
                        "jsonrpc", "2.0",
                        "id", ++requestId,
                        "method", "tools/call",
                        "params", Map.of("name", toolName, "arguments", input)
                    );
                    Map<String, Object> response = sendRequest(request);
                    if (response != null && response.containsKey("result")) {
                        return response.get("result");
                    }
                    return null;
                } catch (Exception e) {
                    throw new RuntimeException("Tool call failed: " + e.getMessage(), e);
                }
            });
        }

        @Override
        public void close() {
            process.destroyForcibly();
        }
    }

    /**
     * SSE-based MCP server connection (stub).
     */
    private static class SseMcpServerConnection implements McpServerConnection {
        private final String name;
        private final String url;
        private final ObjectMapper objectMapper;

        SseMcpServerConnection(String name, String url, ObjectMapper objectMapper) {
            this.name = name;
            this.url = url;
            this.objectMapper = objectMapper;
        }

        @Override public String getName() { return name; }
        @Override public List<McpTool> getTools() { return List.of(); }

        @Override
        public CompletableFuture<Object> callTool(String toolName, Map<String, Object> input) {
            return CompletableFuture.failedFuture(
                new UnsupportedOperationException("SSE transport not fully implemented")
            );
        }

        @Override public void close() {}
    }

    /**
     * HTTP-based MCP server connection (stub).
     */
    private static class HttpMcpServerConnection implements McpServerConnection {
        private final String name;
        private final String url;
        private final ObjectMapper objectMapper;

        HttpMcpServerConnection(String name, String url, ObjectMapper objectMapper) {
            this.name = name;
            this.url = url;
            this.objectMapper = objectMapper;
        }

        @Override public String getName() { return name; }
        @Override public List<McpTool> getTools() { return List.of(); }

        @Override
        public CompletableFuture<Object> callTool(String toolName, Map<String, Object> input) {
            return CompletableFuture.failedFuture(
                new UnsupportedOperationException("HTTP transport not fully implemented")
            );
        }

        @Override public void close() {}
    }

    /**
     * Placeholder connection for servers that need authentication.
     * Translated from NeedsAuthMCPServer state in types.ts
     */
    private static class NeedsAuthMcpServerConnection implements McpServerConnection {
        private final String name;

        NeedsAuthMcpServerConnection(String name) { this.name = name; }

        @Override public String getName() { return name; }
        @Override public List<McpTool> getTools() { return List.of(); }

        @Override
        public CompletableFuture<Object> callTool(String toolName, Map<String, Object> input) {
            return CompletableFuture.failedFuture(
                new McpAuthError(name, "MCP server \"" + name + "\" requires authentication")
            );
        }

        @Override public void close() {}
    }

    /** Lightweight view of an MCP tool for doctor/context-warning purposes. */
    public record McpToolInfo(String name, String description) {}
}
