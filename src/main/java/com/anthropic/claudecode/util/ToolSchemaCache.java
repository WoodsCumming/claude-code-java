package com.anthropic.claudecode.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Session-scoped cache of rendered tool schemas.
 * Translated from src/utils/toolSchemaCache.ts
 *
 * Tool schemas render at server position 2 (before system prompt), so any
 * byte-level change busts the entire ~11K-token tool block AND everything
 * downstream. Memoizing per-session locks the schema bytes at first render —
 * mid-session GrowthBook refreshes, MCP reconnects, or dynamic content in
 * tool.prompt() no longer bust the cache.
 *
 * Lives in a leaf module (no Spring dependencies) so auth components can
 * clear it without circular import issues.
 */
public final class ToolSchemaCache {

    /**
     * Cached schema type — a BetaTool definition with optional extra fields.
     * Fields correspond to the Anthropic API BetaTool shape plus extensions:
     *   strict, eager_input_streaming.
     */
    public record CachedSchema(
        String name,
        String description,
        Map<String, Object> inputSchema,
        Boolean strict,
        Boolean eagerInputStreaming
    ) {}

    private static final Map<String, CachedSchema> TOOL_SCHEMA_CACHE = new ConcurrentHashMap<>();

    /**
     * Get the underlying tool schema cache map (read-only view for callers).
     * Translated from getToolSchemaCache() in toolSchemaCache.ts
     */
    public static Map<String, CachedSchema> getToolSchemaCache() {
        return TOOL_SCHEMA_CACHE;
    }

    /**
     * Clear the entire tool schema cache.
     * Call this on auth changes (login/logout) so stale schemas are discarded.
     * Translated from clearToolSchemaCache() in toolSchemaCache.ts
     */
    public static void clearToolSchemaCache() {
        TOOL_SCHEMA_CACHE.clear();
    }

    /**
     * Get a cached schema by tool name, computing and caching it if absent.
     *
     * @param toolName The name of the tool (cache key).
     * @param supplier Called once to compute the schema when not cached.
     * @return The cached or freshly computed CachedSchema.
     */
    public static CachedSchema getOrCompute(String toolName, Supplier<CachedSchema> supplier) {
        return TOOL_SCHEMA_CACHE.computeIfAbsent(toolName, k -> supplier.get());
    }

    /**
     * Remove a single tool's cached schema (e.g. on MCP server reconnect).
     */
    public static void invalidate(String toolName) {
        TOOL_SCHEMA_CACHE.remove(toolName);
    }

    private ToolSchemaCache() {}
}
