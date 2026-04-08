package com.anthropic.claudecode.model;

import lombok.Builder;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * MCP (Model Context Protocol) type definitions.
 * Translated from src/services/mcp/types.ts
 *
 * Contains all configuration schemas, server connection types, resource types,
 * and CLI state types for MCP server integration.
 */
public final class McpTypes {

    private McpTypes() {}

    // =========================================================================
    // Config scopes
    // =========================================================================

    /**
     * The scope from which an MCP server configuration originates.
     * Translated from ConfigScope in types.ts
     */
    public enum ConfigScope {
        LOCAL("local"),
        USER("user"),
        PROJECT("project"),
        DYNAMIC("dynamic"),
        ENTERPRISE("enterprise"),
        CLAUDE_AI("claudeai"),
        MANAGED("managed");

        private final String value;
        ConfigScope(String value) { this.value = value; }
        public String getValue() { return value; }

        public static ConfigScope fromValue(String v) {
            for (ConfigScope s : values()) {
                if (s.value.equals(v)) return s;
            }
            throw new IllegalArgumentException("Unknown ConfigScope: " + v);
        }
    }

    // =========================================================================
    // Transport types
    // =========================================================================

    /**
     * Transport type for MCP server connections.
     * Translated from Transport in types.ts
     */
    public enum Transport {
        STDIO("stdio"),
        SSE("sse"),
        SSE_IDE("sse-ide"),
        HTTP("http"),
        WS("ws"),
        SDK("sdk"),
        CLAUDEAI_PROXY("claudeai-proxy");

        private final String value;
        Transport(String value) { this.value = value; }

        public static Transport fromValue(String v) {
            for (Transport t : values()) {
                if (t.value.equals(v)) return t;
            }
            throw new IllegalArgumentException("Unknown Transport: " + v);
        }
    }

    // =========================================================================
    // Server configs — sealed hierarchy
    // =========================================================================

    /**
     * Base sealed interface for all MCP server configuration variants.
     * Translated from McpServerConfig union type in types.ts
     */
    public sealed interface McpServerConfig permits
            McpTypes.McpStdioServerConfig,
            McpTypes.McpSSEServerConfig,
            McpTypes.McpSSEIDEServerConfig,
            McpTypes.McpWebSocketIDEServerConfig,
            McpTypes.McpHTTPServerConfig,
            McpTypes.McpWebSocketServerConfig,
            McpTypes.McpSdkServerConfig,
            McpTypes.McpClaudeAIProxyServerConfig {

        /** Returns the transport type tag for this config. */
        String getType();
    }

    /**
     * Standard I/O server configuration.
     * Translated from McpStdioServerConfig in types.ts
     */
    @Data
    @lombok.Builder
    
    public static final class McpStdioServerConfig implements McpServerConfig {
        /** type is optional for backward compatibility; defaults to "stdio" */
        @Builder.Default
        private String type = "stdio";
        private String command;
        @Builder.Default
        private List<String> args = List.of();
        private Map<String, String> env;
    
        @Override public String getType() { return type; }
    
        public String getCommand() { return command; }
        public List<String> getArgs() { return args; }
    
        public McpStdioServerConfig() {}

        public static McpStdioServerConfigBuilder builder() { return new McpStdioServerConfigBuilder(); }
        public static class McpStdioServerConfigBuilder {
            private String type;
            private String command;
            private List<String> args;
            private Map<String, String> env;
            public McpStdioServerConfigBuilder type(String v) { this.type = v; return this; }
            public McpStdioServerConfigBuilder command(String v) { this.command = v; return this; }
            public McpStdioServerConfigBuilder args(List<String> v) { this.args = v; return this; }
            public McpStdioServerConfigBuilder env(Map<String, String> v) { this.env = v; return this; }
            public McpStdioServerConfig build() {
                McpStdioServerConfig o = new McpStdioServerConfig();
                o.type = type;
                o.command = command;
                o.args = args;
                o.env = env;
                return o;
            }
        }
    }

    /**
     * OAuth config embedded in SSE and HTTP server configs.
     * Translated from McpOAuthConfig in types.ts
     */
    @Data
    @lombok.Builder
    
    public static final class McpOAuthConfig {
        private String clientId;
        private Integer callbackPort;
        private String authServerMetadataUrl;
        /** Cross-App Access (XAA / SEP-990) flag. */
        private Boolean xaa;
    }

    /**
     * SSE (Server-Sent Events) server configuration.
     * Translated from McpSSEServerConfig in types.ts
     */
    @Data
    @lombok.Builder
    
    public static final class McpSSEServerConfig implements McpServerConfig {
        @Builder.Default
        private String type = "sse";
        private String url;
        private Map<String, String> headers;
        private String headersHelper;
        private McpOAuthConfig oauth;
    
        @Override public String getType() { return type; }
    
        public String getUrl() { return url; }
    
        public McpSSEServerConfig() {}

        public static McpSSEServerConfigBuilder builder() { return new McpSSEServerConfigBuilder(); }
        public static class McpSSEServerConfigBuilder {
            private String type;
            private String url;
            private Map<String, String> headers;
            private String headersHelper;
            private McpOAuthConfig oauth;
            public McpSSEServerConfigBuilder type(String v) { this.type = v; return this; }
            public McpSSEServerConfigBuilder url(String v) { this.url = v; return this; }
            public McpSSEServerConfigBuilder headers(Map<String, String> v) { this.headers = v; return this; }
            public McpSSEServerConfigBuilder headersHelper(String v) { this.headersHelper = v; return this; }
            public McpSSEServerConfigBuilder oauth(McpOAuthConfig v) { this.oauth = v; return this; }
            public McpSSEServerConfig build() {
                McpSSEServerConfig o = new McpSSEServerConfig();
                o.type = type;
                o.url = url;
                o.headers = headers;
                o.headersHelper = headersHelper;
                o.oauth = oauth;
                return o;
            }
        }
    }

    /**
     * Internal IDE SSE server configuration.
     * Translated from McpSSEIDEServerConfig in types.ts
     */
    @Data
    @lombok.Builder
    
    public static final class McpSSEIDEServerConfig implements McpServerConfig {
        @Builder.Default
        private String type = "sse-ide";
        private String url;
        private String ideName;
        private Boolean ideRunningInWindows;
    
        @Override public String getType() { return type; }
    
        public String getUrl() { return url; }
    }

    /**
     * Internal IDE WebSocket server configuration.
     * Translated from McpWebSocketIDEServerConfig in types.ts
     */
    @Data
    @lombok.Builder
    
    public static final class McpWebSocketIDEServerConfig implements McpServerConfig {
        @Builder.Default
        private String type = "ws-ide";
        private String url;
        private String ideName;
        private String authToken;
        private Boolean ideRunningInWindows;
    
        @Override public String getType() { return type; }
    
        public String getUrl() { return url; }
    }

    /**
     * HTTP server configuration (Streamable HTTP / MCP HTTP transport).
     * Translated from McpHTTPServerConfig in types.ts
     */
    @Data
    @lombok.Builder
    
    public static final class McpHTTPServerConfig implements McpServerConfig {
        @Builder.Default
        private String type = "http";
        private String url;
        private Map<String, String> headers;
        private String headersHelper;
        private McpOAuthConfig oauth;
    
        @Override public String getType() { return type; }
    
        public String getUrl() { return url; }
    
        public McpHTTPServerConfig() {}

        public static McpHTTPServerConfigBuilder builder() { return new McpHTTPServerConfigBuilder(); }
        public static class McpHTTPServerConfigBuilder {
            private String type;
            private String url;
            private Map<String, String> headers;
            private String headersHelper;
            private McpOAuthConfig oauth;
            public McpHTTPServerConfigBuilder type(String v) { this.type = v; return this; }
            public McpHTTPServerConfigBuilder url(String v) { this.url = v; return this; }
            public McpHTTPServerConfigBuilder headers(Map<String, String> v) { this.headers = v; return this; }
            public McpHTTPServerConfigBuilder headersHelper(String v) { this.headersHelper = v; return this; }
            public McpHTTPServerConfigBuilder oauth(McpOAuthConfig v) { this.oauth = v; return this; }
            public McpHTTPServerConfig build() {
                McpHTTPServerConfig o = new McpHTTPServerConfig();
                o.type = type;
                o.url = url;
                o.headers = headers;
                o.headersHelper = headersHelper;
                o.oauth = oauth;
                return o;
            }
        }
    }

    /**
     * WebSocket server configuration.
     * Translated from McpWebSocketServerConfig in types.ts
     */
    @Data
    @lombok.Builder
    
    public static final class McpWebSocketServerConfig implements McpServerConfig {
        @Builder.Default
        private String type = "ws";
        private String url;
        private Map<String, String> headers;
        private String headersHelper;
    
        @Override public String getType() { return type; }
    
        public String getUrl() { return url; }
    
        public McpWebSocketServerConfig() {}

        public static McpWebSocketServerConfigBuilder builder() { return new McpWebSocketServerConfigBuilder(); }
        public static class McpWebSocketServerConfigBuilder {
            private String type;
            private String url;
            private Map<String, String> headers;
            private String headersHelper;
            public McpWebSocketServerConfigBuilder type(String v) { this.type = v; return this; }
            public McpWebSocketServerConfigBuilder url(String v) { this.url = v; return this; }
            public McpWebSocketServerConfigBuilder headers(Map<String, String> v) { this.headers = v; return this; }
            public McpWebSocketServerConfigBuilder headersHelper(String v) { this.headersHelper = v; return this; }
            public McpWebSocketServerConfig build() {
                McpWebSocketServerConfig o = new McpWebSocketServerConfig();
                o.type = type;
                o.url = url;
                o.headers = headers;
                o.headersHelper = headersHelper;
                return o;
            }
        }
    }

    /**
     * In-process SDK server configuration (name-only, no subprocess).
     * Translated from McpSdkServerConfig in types.ts
     */
    @Data
    @lombok.Builder
    
    public static final class McpSdkServerConfig implements McpServerConfig {
        @Builder.Default
        private String type = "sdk";
        private String name;
    
        @Override public String getType() { return type; }
    
        public McpSdkServerConfig() {}

        public static McpSdkServerConfigBuilder builder() { return new McpSdkServerConfigBuilder(); }
        public static class McpSdkServerConfigBuilder {
            private String type;
            private String name;
            public McpSdkServerConfigBuilder type(String v) { this.type = v; return this; }
            public McpSdkServerConfigBuilder name(String v) { this.name = v; return this; }
            public McpSdkServerConfig build() {
                McpSdkServerConfig o = new McpSdkServerConfig();
                o.type = type;
                o.name = name;
                return o;
            }
        }
    }

    /**
     * Claude.ai proxy server configuration.
     * Translated from McpClaudeAIProxyServerConfig in types.ts
     */
    @Data
    @lombok.Builder
    
    public static final class McpClaudeAIProxyServerConfig implements McpServerConfig {
        @Builder.Default
        private String type = "claudeai-proxy";
        private String url;
        private String id;
    
        @Override public String getType() { return type; }
    
        public String getUrl() { return url; }
    
        public McpClaudeAIProxyServerConfig() {}

        public static McpClaudeAIProxyServerConfigBuilder builder() { return new McpClaudeAIProxyServerConfigBuilder(); }
        public static class McpClaudeAIProxyServerConfigBuilder {
            private String type;
            private String url;
            private String id;
            public McpClaudeAIProxyServerConfigBuilder type(String v) { this.type = v; return this; }
            public McpClaudeAIProxyServerConfigBuilder url(String v) { this.url = v; return this; }
            public McpClaudeAIProxyServerConfigBuilder id(String v) { this.id = v; return this; }
            public McpClaudeAIProxyServerConfig build() {
                McpClaudeAIProxyServerConfig o = new McpClaudeAIProxyServerConfig();
                o.type = type;
                o.url = url;
                o.id = id;
                return o;
            }
        }
    }

    // =========================================================================
    // Scoped config
    // =========================================================================

    /**
     * An MCP server config decorated with its configuration scope and optional
     * plugin source. Used throughout the connection layer.
     * Translated from ScopedMcpServerConfig in types.ts
     */
    @Data
    @lombok.Builder
    
    public static final class ScopedMcpServerConfig {
        private McpServerConfig config;
        private ConfigScope scope;
        /**
         * For plugin-provided servers: the providing plugin's source identifier
         * (e.g. 'slack@anthropic'). Stashed at config-build time.
         */
        private String pluginSource;
    
        public McpServerConfig getConfig() { return config; }
    
        public ScopedMcpServerConfig() {}

        public static ScopedMcpServerConfigBuilder builder() { return new ScopedMcpServerConfigBuilder(); }
        public static class ScopedMcpServerConfigBuilder {
            private McpServerConfig config;
            private ConfigScope scope;
            private String pluginSource;
            public ScopedMcpServerConfigBuilder config(McpServerConfig v) { this.config = v; return this; }
            public ScopedMcpServerConfigBuilder scope(ConfigScope v) { this.scope = v; return this; }
            public ScopedMcpServerConfigBuilder pluginSource(String v) { this.pluginSource = v; return this; }
            public ScopedMcpServerConfig build() {
                ScopedMcpServerConfig o = new ScopedMcpServerConfig();
                o.config = config;
                o.scope = scope;
                o.pluginSource = pluginSource;
                return o;
            }
        }
    }

    // =========================================================================
    // JSON config wrapper
    // =========================================================================

    /**
     * Top-level structure of a .mcp.json file.
     * Translated from McpJsonConfig in types.ts
     */
    @Data
    @lombok.Builder
    
    public static final class McpJsonConfig {
        private Map<String, McpServerConfig> mcpServers;
    }

    // =========================================================================
    // Server connection states — sealed hierarchy
    // =========================================================================

    /**
     * Server info reported by a connected MCP server.
     */
    public record McpServerInfo(String name, String version) {}

    /**
     * Base sealed interface for all server connection states.
     * Translated from MCPServerConnection union type in types.ts
     */
    public sealed interface MCPServerConnection permits
            McpTypes.ConnectedMCPServer,
            McpTypes.FailedMCPServer,
            McpTypes.NeedsAuthMCPServer,
            McpTypes.PendingMCPServer,
            McpTypes.DisabledMCPServer {

        String getName();
        String getType();
        ScopedMcpServerConfig getConfig();
    }

    /**
     * A successfully connected MCP server.
     * Translated from ConnectedMCPServer in types.ts
     */
    @Data
    @lombok.Builder
    
    public static final class ConnectedMCPServer implements MCPServerConnection {
        private String name;
        @Builder.Default
        private String type = "connected";
        private Map<String, Object> capabilities;
        private McpServerInfo serverInfo;
        private String instructions;
        private ScopedMcpServerConfig config;
        /** Cleanup callback to disconnect/release resources. */
        private Runnable cleanup;
    
        @Override public ScopedMcpServerConfig getConfig() { return config; }
    
        @Override public String getType() { return type; }
    
        @Override public String getName() { return name; }
    }

    /**
     * An MCP server that failed to connect.
     * Translated from FailedMCPServer in types.ts
     */
    @Data
    @lombok.Builder
    
    public static final class FailedMCPServer implements MCPServerConnection {
        private String name;
        @Builder.Default
        private String type = "failed";
        private ScopedMcpServerConfig config;
        private String error;
    
        @Override public ScopedMcpServerConfig getConfig() { return config; }
    
        @Override public String getType() { return type; }
    
        @Override public String getName() { return name; }
    }

    /**
     * An MCP server that requires authentication before it can connect.
     * Translated from NeedsAuthMCPServer in types.ts
     */
    @Data
    @lombok.Builder
    
    public static final class NeedsAuthMCPServer implements MCPServerConnection {
        private String name;
        @Builder.Default
        private String type = "needs-auth";
        private ScopedMcpServerConfig config;
    
        @Override public ScopedMcpServerConfig getConfig() { return config; }
    
        @Override public String getType() { return type; }
    
        @Override public String getName() { return name; }
    }

    /**
     * An MCP server that is pending connection (including reconnect attempts).
     * Translated from PendingMCPServer in types.ts
     */
    @Data
    @lombok.Builder
    
    public static final class PendingMCPServer implements MCPServerConnection {
        private String name;
        @Builder.Default
        private String type = "pending";
        private ScopedMcpServerConfig config;
        private Integer reconnectAttempt;
        private Integer maxReconnectAttempts;
    
        @Override public ScopedMcpServerConfig getConfig() { return config; }
    
        @Override public String getType() { return type; }
    
        @Override public String getName() { return name; }
    }

    /**
     * An MCP server that has been explicitly disabled.
     * Translated from DisabledMCPServer in types.ts
     */
    @Data
    @lombok.Builder
    
    public static final class DisabledMCPServer implements MCPServerConnection {
        private String name;
        @Builder.Default
        private String type = "disabled";
        private ScopedMcpServerConfig config;
    
        @Override public ScopedMcpServerConfig getConfig() { return config; }
    
        @Override public String getType() { return type; }
    
        @Override public String getName() { return name; }
    }

    // =========================================================================
    // Resource types
    // =========================================================================

    /**
     * An MCP resource augmented with the name of the server that provides it.
     * Translated from ServerResource in types.ts
     */
    @Data
    @lombok.Builder
    
    public static final class ServerResource {
        private String uri;
        private String name;
        private String description;
        private String mimeType;
        /** Name of the MCP server that provides this resource. */
        private String server;
    }

    // =========================================================================
    // MCP CLI state types
    // =========================================================================

    /**
     * Serialized representation of an MCP tool for the CLI state dump.
     * Translated from SerializedTool in types.ts
     */
    @Data
    @lombok.Builder
    
    public static final class SerializedTool {
        private String name;
        private String description;
        private Map<String, Object> inputJsonSchema;
        private Boolean isMcp;
        /** Original unnormalized tool name from the MCP server. */
        private String originalToolName;
    }

    /**
     * Serialized representation of an MCP client/server for the CLI state dump.
     * Translated from SerializedClient in types.ts
     */
    @Data
    @lombok.Builder
    
    public static final class SerializedClient {
        private String name;
        /** One of: connected, failed, needs-auth, pending, disabled */
        private String type;
        private Map<String, Object> capabilities;
    }

    /**
     * Full MCP CLI state snapshot.
     * Translated from MCPCliState in types.ts
     */
    @Data
    @lombok.Builder
    
    public static final class MCPCliState {
        private List<SerializedClient> clients;
        private Map<String, ScopedMcpServerConfig> configs;
        private List<SerializedTool> tools;
        private Map<String, List<ServerResource>> resources;
        /** Maps normalized tool names back to original MCP server tool names. */
        private Map<String, String> normalizedNames;
    }
}
