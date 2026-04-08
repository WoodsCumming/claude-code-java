package com.anthropic.claudecode.util;

import com.anthropic.claudecode.tool.Tool;

import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * MCP utility functions.
 * Translated from src/services/mcp/utils.ts
 *
 * Filters, excludes, and queries MCP tools, commands, and resources.
 */
public final class McpUtils {

    // ============================================================================
    // Tool helpers
    // ============================================================================

    /**
     * Filter tools by MCP server name.
     * Translated from filterToolsByServer() in utils.ts
     */
    public static List<Tool<?, ?>> filterToolsByServer(List<Tool<?, ?>> tools, String serverName) {
        String prefix = "mcp__" + McpNormalizationUtils.normalizeNameForMCP(serverName) + "__";
        return tools.stream()
                .filter(t -> t.getName() != null && t.getName().startsWith(prefix))
                .collect(Collectors.toList());
    }

    /**
     * Remove tools belonging to a specific MCP server.
     * Translated from excludeToolsByServer() in utils.ts
     */
    public static List<Tool<?, ?>> excludeToolsByServer(List<Tool<?, ?>> tools, String serverName) {
        String prefix = "mcp__" + McpNormalizationUtils.normalizeNameForMCP(serverName) + "__";
        return tools.stream()
                .filter(t -> t.getName() == null || !t.getName().startsWith(prefix))
                .collect(Collectors.toList());
    }

    /**
     * Remove resources belonging to a specific MCP server.
     * Translated from excludeResourcesByServer() in utils.ts
     */
    public static <T> Map<String, T> excludeResourcesByServer(
            Map<String, T> resources, String serverName) {
        return resources.entrySet().stream()
                .filter(e -> !e.getKey().equals(serverName))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Checks if a tool name belongs to a specific MCP server.
     * Translated from isToolFromMcpServer() in utils.ts
     */
    public static boolean isToolFromMcpServer(String toolName, String serverName) {
        Optional<McpStringUtils.McpInfo> info = McpStringUtils.mcpInfoFromString(toolName);
        return info.map(i -> i.serverName().equals(serverName)).orElse(false);
    }

    /**
     * Checks if a tool belongs to any MCP server.
     * Translated from isMcpTool() in utils.ts
     */
    public static boolean isMcpTool(Tool<?, ?> tool) {
        return (tool.getName() != null && tool.getName().startsWith("mcp__"))
                || Boolean.TRUE.equals(tool.isMcp());
    }

    // ============================================================================
    // Config hash / stale-client detection
    // ============================================================================

    /**
     * Stable hash of an MCP server config for change detection on /reload-plugins.
     * Excludes {@code scope} (provenance, not content — moving a server between
     * settings files shouldn't reconnect it). Keys sorted so reordered objects hash
     * the same.
     * Translated from hashMcpConfig() in utils.ts
     */
    public static String hashMcpConfig(Map<String, Object> config) {
        try {
            // Remove scope from comparison
            Map<String, Object> rest = config.entrySet().stream()
                    .filter(e -> !"scope".equals(e.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            String stable = toSortedJson(rest);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(stable.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) { // 16 hex chars
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Recursive JSON serialization with sorted keys. */
    @SuppressWarnings("unchecked")
    private static String toSortedJson(Object value) {
        if (value == null) return "null";
        if (value instanceof Map<?, ?> map) {
            List<String> keys = new ArrayList<>(((Map<String, Object>) map).keySet());
            java.util.Collections.sort(keys);
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(keys.get(i)).append("\":");
                sb.append(toSortedJson(((Map<?, ?>) map).get(keys.get(i))));
            }
            sb.append("}");
            return sb.toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(toSortedJson(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        if (value instanceof String s) return "\"" + s.replace("\"", "\\\"") + "\"";
        return value.toString();
    }

    // ============================================================================
    // Config scope helpers
    // ============================================================================

    /**
     * Describe the file path or label for a given MCP config scope.
     * Translated from describeMcpConfigFilePath() in utils.ts
     */
    public static String describeMcpConfigFilePath(String scope) {
        return switch (scope) {
            case "user"       -> System.getProperty("user.home") + "/.claude/claude.json";
            case "project"    -> ".mcp.json";
            case "local"      -> System.getProperty("user.home") + "/.claude/claude.json [project: " +
                                 System.getProperty("user.dir") + "]";
            case "dynamic"    -> "Dynamically configured";
            case "enterprise" -> "Enterprise MCP config";
            case "claudeai"   -> "claude.ai";
            default           -> scope;
        };
    }

    /**
     * Returns a human-readable label for a config scope.
     * Translated from getScopeLabel() in utils.ts
     */
    public static String getScopeLabel(String scope) {
        return switch (scope) {
            case "local"      -> "Local config (private to you in this project)";
            case "project"    -> "Project config (shared via .mcp.json)";
            case "user"       -> "User config (available in all your projects)";
            case "dynamic"    -> "Dynamic config (from command line)";
            case "enterprise" -> "Enterprise config (managed by your organization)";
            case "claudeai"   -> "claude.ai config";
            default           -> scope;
        };
    }

    /**
     * Validate and return a config scope, defaulting to "local" when null.
     * Throws {@link IllegalArgumentException} for unrecognized scopes.
     * Translated from ensureConfigScope() in utils.ts
     */
    public static String ensureConfigScope(String scope) {
        if (scope == null) return "local";
        List<String> valid = List.of("user", "project", "local", "dynamic", "enterprise", "claudeai");
        if (!valid.contains(scope)) {
            throw new IllegalArgumentException(
                    "Invalid scope: " + scope + ". Must be one of: " + String.join(", ", valid));
        }
        return scope;
    }

    /**
     * Validate and return a transport type, defaulting to "stdio" when null.
     * Translated from ensureTransport() in utils.ts
     */
    public static String ensureTransport(String type) {
        if (type == null) return "stdio";
        if (!List.of("stdio", "sse", "http").contains(type)) {
            throw new IllegalArgumentException(
                    "Invalid transport type: " + type + ". Must be one of: stdio, sse, http");
        }
        return type;
    }

    /**
     * Parse a list of {@code "Header-Name: value"} strings into a map.
     * Translated from parseHeaders() in utils.ts
     */
    public static Map<String, String> parseHeaders(List<String> headerArray) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        for (String header : headerArray) {
            int colonIndex = header.indexOf(':');
            if (colonIndex == -1) {
                throw new IllegalArgumentException(
                        "Invalid header format: \"" + header +
                        "\". Expected format: \"Header-Name: value\"");
            }
            String key = header.substring(0, colonIndex).trim();
            String value = header.substring(colonIndex + 1).trim();
            if (key.isEmpty()) {
                throw new IllegalArgumentException(
                        "Invalid header: \"" + header + "\". Header name cannot be empty.");
            }
            headers.put(key, value);
        }
        return headers;
    }

    /**
     * Extracts the MCP server base URL (without query string) for analytics logging.
     * Query strings are stripped because they can contain access tokens.
     * Trailing slashes are removed for normalization.
     * Returns null for stdio/sdk servers or if URL parsing fails.
     * Translated from getLoggingSafeMcpBaseUrl() in utils.ts
     */
    public static Optional<String> getLoggingSafeMcpBaseUrl(Map<String, Object> config) {
        Object urlObj = config.get("url");
        if (!(urlObj instanceof String urlStr)) return Optional.empty();
        try {
            URL parsed = new URL(urlStr);
            String base = parsed.getProtocol() + "://" + parsed.getHost()
                    + (parsed.getPort() != -1 ? ":" + parsed.getPort() : "")
                    + parsed.getPath();
            // Strip trailing slash
            return Optional.of(base.replaceAll("/$", ""));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private McpUtils() {}
}
