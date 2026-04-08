package com.anthropic.claudecode.util;

import java.util.Optional;

/**
 * MCP string utility functions.
 * Translated from src/services/mcp/mcpStringUtils.ts
 */
public class McpStringUtils {

    /**
     * Parse MCP info from a tool string.
     * Translated from mcpInfoFromString() in mcpStringUtils.ts
     *
     * Expected format: "mcp__serverName__toolName"
     */
    public static Optional<McpInfo> mcpInfoFromString(String toolString) {
        if (toolString == null) return Optional.empty();

        String[] parts = toolString.split("__");
        if (parts.length < 2 || !"mcp".equals(parts[0])) {
            return Optional.empty();
        }

        String serverName = parts[1];
        String toolName = parts.length > 2
            ? String.join("__", java.util.Arrays.copyOfRange(parts, 2, parts.length))
            : null;

        return Optional.of(new McpInfo(serverName, toolName));
    }

    /**
     * Get the MCP prefix for a server.
     * Translated from getMcpPrefix() in mcpStringUtils.ts
     */
    public static String getMcpPrefix(String serverName) {
        return "mcp__" + normalizeNameForMCP(serverName) + "__";
    }

    /**
     * Build a fully qualified MCP tool name.
     * Translated from buildMcpToolName() in mcpStringUtils.ts
     */
    public static String buildMcpToolName(String serverName, String toolName) {
        return getMcpPrefix(serverName) + normalizeNameForMCP(toolName);
    }

    /**
     * Normalize a name for MCP.
     * Translated from normalizeNameForMCP() in normalization.ts
     */
    public static String normalizeNameForMCP(String name) {
        if (name == null) return "";
        // Replace non-alphanumeric chars with underscores
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    public record McpInfo(String serverName, String toolName) {}

    private McpStringUtils() {}
}
