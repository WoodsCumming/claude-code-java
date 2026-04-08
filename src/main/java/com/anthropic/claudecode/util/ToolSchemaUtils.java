package com.anthropic.claudecode.util;

import com.anthropic.claudecode.tool.Tool;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Tool schema utilities for API submission.
 * Translated from src/utils/api.ts toolToAPISchema
 */
public class ToolSchemaUtils {

    /**
     * Convert a tool to its API schema.
     * Translated from toolToAPISchema() in api.ts
     */
    public static CompletableFuture<Map<String, Object>> toolToAPISchema(
            Tool<?, ?> tool,
            boolean deferLoading,
            Map<String, Object> cacheControl) {

        return tool.description(null, null)
            .thenApply(description -> {
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("name", tool.getName());
                schema.put("description", description != null ? description : "");
                schema.put("input_schema", tool.getInputSchema());

                if (deferLoading) {
                    schema.put("defer_loading", true);
                }

                if (cacheControl != null) {
                    schema.put("cache_control", cacheControl);
                }

                return schema;
            });
    }

    /**
     * Build tool schemas for all tools.
     */
    public static CompletableFuture<List<Map<String, Object>>> buildToolSchemas(
            List<Tool<?, ?>> tools) {

        List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
        for (Tool<?, ?> tool : tools) {
            if (tool.isEnabled()) {
                futures.add(toolToAPISchema(tool, false, null));
            }
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(f -> {
                    try { return f.get(); }
                    catch (Exception e) { return null; }
                })
                .filter(Objects::nonNull)
                .toList()
            );
    }

    private ToolSchemaUtils() {}
}
