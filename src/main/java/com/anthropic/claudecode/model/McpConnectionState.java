package com.anthropic.claudecode.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * MCP server connection state types.
 * Translated from src/services/mcp/types.ts
 */
public sealed interface McpConnectionState permits
        McpConnectionState.Connected,
        McpConnectionState.Failed,
        McpConnectionState.NeedsAuth,
        McpConnectionState.Pending,
        McpConnectionState.Disabled {

    String getName();
    String getType();

    @Data @NoArgsConstructor
    final class Connected implements McpConnectionState {
        private String name;
        public String getName() { return name; }
        public void setName(String v) { name = v; }
        private final String type = "connected";
        private String instructions;
        private String serverInfoName;
        private String serverInfoVersion;
        @Override public String getType() { return type; }
        public Connected(String name, String instructions, String serverInfoName, String serverInfoVersion) {
            this.name = name; this.instructions = instructions; this.serverInfoName = serverInfoName; this.serverInfoVersion = serverInfoVersion;
        }
        public String getInstructions() { return instructions; }
        public void setInstructions(String v) { instructions = v; }
        public String getServerInfoName() { return serverInfoName; }
        public void setServerInfoName(String v) { serverInfoName = v; }
        public String getServerInfoVersion() { return serverInfoVersion; }
        public void setServerInfoVersion(String v) { serverInfoVersion = v; }
    }

    @Data  @NoArgsConstructor
    final class Failed implements McpConnectionState {
        private String name;
        public String getName() { return name; }
        public void setName(String v) { name = v; }
        private final String type = "failed";
        private String error;
        @Override public String getType() { return type; }
    
    

        public Failed(String name, String error) {
            this.name = name;
            this.error = error;
        }
    }

    @Data  @NoArgsConstructor
@AllArgsConstructor
    final class NeedsAuth implements McpConnectionState {
        private String name;
        public String getName() { return name; }
        public void setName(String v) { name = v; }
        private final String type = "needs-auth";
        @Override public String getType() { return type; }
    }

    final class Pending implements McpConnectionState {
        private String name;
        private Integer reconnectAttempt;
        private Integer maxReconnectAttempts;
        public Pending() {}
        public Pending(String name, Integer reconnectAttempt, Integer maxReconnectAttempts) {
            this.name = name;
            this.reconnectAttempt = reconnectAttempt;
            this.maxReconnectAttempts = maxReconnectAttempts;
        }
        public String getName() { return name; }
        public void setName(String v) { name = v; }
        public Integer getReconnectAttempt() { return reconnectAttempt; }
        public void setReconnectAttempt(Integer v) { reconnectAttempt = v; }
        public Integer getMaxReconnectAttempts() { return maxReconnectAttempts; }
        public void setMaxReconnectAttempts(Integer v) { maxReconnectAttempts = v; }
        @Override public String getType() { return "pending"; }
    }

    final class Disabled implements McpConnectionState {
        private String name;
        public Disabled() {}
        public Disabled(String name) {
            this.name = name;
        }
        public String getName() { return name; }
        public void setName(String v) { name = v; }
        @Override public String getType() { return "disabled"; }
    }
}
