package com.anthropic.claudecode.util;

import java.util.regex.Pattern;

/**
 * Pure utility functions for MCP name normalization.
 * Translated from src/services/mcp/normalization.ts
 *
 * This class has no Spring dependencies to avoid circular imports.
 */
public final class McpNormalizationUtils {

    // Claude.ai server names are prefixed with this string
    private static final String CLAUDEAI_SERVER_PREFIX = "claude.ai ";

    /** Matches any character not in the API-compatible set ^[a-zA-Z0-9_-]{1,64}$. */
    private static final Pattern INVALID_CHARS = Pattern.compile("[^a-zA-Z0-9_-]");

    /** Collapses consecutive underscores. */
    private static final Pattern CONSECUTIVE_UNDERSCORES = Pattern.compile("_+");

    /** Strips leading or trailing underscores. */
    private static final Pattern LEADING_TRAILING_UNDERSCORES = Pattern.compile("^_|_$");

    /**
     * Normalize server names to be compatible with the API pattern {@code ^[a-zA-Z0-9_-]{1,64}$}.
     * Replaces any invalid characters (including dots and spaces) with underscores.
     *
     * For claude.ai servers (names starting with "claude.ai "), also collapses
     * consecutive underscores and strips leading/trailing underscores to prevent
     * interference with the {@code __} delimiter used in MCP tool names.
     * Translated from normalizeNameForMCP() in normalization.ts
     *
     * @param name the raw server name
     * @return the normalized name safe for use as an MCP server identifier
     */
    public static String normalizeNameForMCP(String name) {
        if (name == null) return "";
        String normalized = INVALID_CHARS.matcher(name).replaceAll("_");
        if (name.startsWith(CLAUDEAI_SERVER_PREFIX)) {
            normalized = CONSECUTIVE_UNDERSCORES.matcher(normalized).replaceAll("_");
            normalized = LEADING_TRAILING_UNDERSCORES.matcher(normalized).replaceAll("");
        }
        return normalized;
    }

    private McpNormalizationUtils() {}
}
