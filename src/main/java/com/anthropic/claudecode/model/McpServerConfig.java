package com.anthropic.claudecode.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Builder;
import java.util.*;

/**
 * MCP server configuration types.
 * Translated from src/services/mcp/types.ts
 */
public sealed interface McpServerConfig permits
        McpServerConfig.StdioConfig,
        McpServerConfig.SseConfig,
        McpServerConfig.HttpConfig {

    String getType();

    /** Return this (scope tracking not implemented in base config). */
    default McpServerConfig withScope(String scope) { return this; }
    /** Return this (plugin source tracking not implemented in base config). */
    default McpServerConfig withPluginSource(String pluginSource) { return this; }

    /**
     * Resolve environment variable references in this config.
     * @param resolver function to apply to each string value
     * @param pluginPath base path of the plugin
     * @return a new config with resolved values
     */
    default McpServerConfig resolveValues(java.util.function.UnaryOperator<String> resolver, String pluginPath) {
        return this;
    }

    /**
     * Create a McpServerConfig from a raw config map.
     */
    static McpServerConfig fromMap(Map<String, Object> map) {
        if (map == null) return null;
        String type = (String) map.getOrDefault("type", "stdio");
        switch (type) {
            case "sse": {
                SseConfig c = new SseConfig();
                c.setUrl((String) map.get("url"));
                return c;
            }
            case "http": {
                HttpConfig c = new HttpConfig();
                c.setUrl((String) map.get("url"));
                return c;
            }
            default: {
                StdioConfig c = new StdioConfig();
                c.setCommand((String) map.get("command"));
                Object argsObj = map.get("args");
                if (argsObj instanceof List) {
                    c.setArgs((List<String>) argsObj);
                }
                return c;
            }
        }
    }

    @Data @Builder @NoArgsConstructor
@AllArgsConstructor
    final class StdioConfig implements McpServerConfig {
        @Builder.Default
        private String type = "stdio";
        private String command;
        private List<String> args;
        private Map<String, String> env;
        @Override public String getType() { return type; }
        public void setType(String v) { type = v; }
        public List<String> getArgs() { return args; }
        public void setArgs(List<String> v) { args = v; }
        public String getCommand() { return command; }
        public void setCommand(String v) { command = v; }
        public Map<String, String> getEnv() { return env; }
        public void setEnv(Map<String, String> v) { env = v; }
    }

    @Data @Builder @NoArgsConstructor
@AllArgsConstructor
    final class SseConfig implements McpServerConfig {
        @Builder.Default
        private String type = "sse";
        private String url;
        private Map<String, String> headers;
        private String headersHelper;
        @Override public String getType() { return type; }
        public void setType(String v) { type = v; }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> v) { headers = v; }
        public String getUrl() { return url; }
        public void setUrl(String v) { url = v; }
        public String getHeadersHelper() { return headersHelper; }
        public void setHeadersHelper(String v) { headersHelper = v; }
    }

    @Data @Builder @NoArgsConstructor
@AllArgsConstructor
    final class HttpConfig implements McpServerConfig {
        @Builder.Default
        private String type = "http";
        private String url;
        private Map<String, String> headers;
        @Override public String getType() { return type; }
        public void setType(String v) { type = v; }
        public String getUrl() { return url; }
        public void setUrl(String v) { url = v; }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> v) { headers = v; }
    }

    // Config scopes
    enum ConfigScope {
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
    }

    // Transport types
    enum Transport {
        STDIO("stdio"),
        SSE("sse"),
        HTTP("http"),
        WS("ws"),
        SDK("sdk");

        private final String value;
        Transport(String value) { this.value = value; }
    }
}
