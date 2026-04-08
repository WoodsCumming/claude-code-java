package com.anthropic.claudecode.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * Zod-to-JSON-Schema conversion utilities.
 * Translated from src/utils/zodToJsonSchema.ts
 *
 * In the TypeScript source, Zod v4 schemas are converted to JSON Schema using
 * {@code toJSONSchema(schema)}. In Java we don't have Zod — schemas are expressed
 * directly as {@code Map<String, Object>} or Jackson {@link JsonNode} trees.
 *
 * This utility provides:
 * <ul>
 *   <li>A per-session WeakMap cache (identity-keyed) analogous to the TypeScript cache.</li>
 *   <li>Helpers for validating and normalising JSON Schema maps.</li>
 * </ul>
 *
 * The {@code toolToAPISchema()} call in the TypeScript code runs this for every tool
 * on every API request (~60–250 times/turn). Tool schemas are wrapped with
 * {@code lazySchema()} which guarantees the same object reference per session, so
 * the cache avoids redundant work on every turn.
 */
@Slf4j
public final class ZodToJsonSchemaUtils {



    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Identity-keyed cache: same schema object → same JSON Schema map.
     * Translated from the WeakMap cache in zodToJsonSchema.ts
     */
    private static final WeakHashMap<Object, Map<String, Object>> CACHE = new WeakHashMap<>();

    // =========================================================================
    // Core conversion
    // =========================================================================

    /**
     * Retrieve a cached JSON Schema for a schema descriptor object, computing it
     * from the supplier on the first call and caching the result by identity.
     *
     * Analogous to {@code zodToJsonSchema(schema)} in zodToJsonSchema.ts:
     * the supplier plays the role of {@code toJSONSchema(schema)}.
     *
     * @param schemaKey Object whose identity is used as the cache key
     *                  (equivalent to the ZodTypeAny reference in TypeScript).
     * @param supplier  Called once to produce the JSON Schema {@code Map<String, Object>}.
     * @return Cached or freshly computed JSON Schema map.
     */
    public static synchronized Map<String, Object> getOrConvert(
            Object schemaKey,
            Supplier<Map<String, Object>> supplier) {
        return CACHE.computeIfAbsent(schemaKey, k -> supplier.get());
    }

    /**
     * Normalise a raw JSON Schema map by ensuring required top-level fields are present.
     * Adds {@code "type": "object"} and an empty {@code "properties": {}} when absent.
     *
     * @param schema Possibly incomplete JSON Schema map.
     * @return The same map, mutated to include defaults.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> normalise(Map<String, Object> schema) {
        if (schema == null) return Map.of("type", "object", "properties", Map.of());
        if (!schema.containsKey("type")) schema.put("type", "object");
        if (!schema.containsKey("properties")) schema.put("properties", new java.util.LinkedHashMap<>());
        return schema;
    }

    // =========================================================================
    // Jackson-based helpers
    // =========================================================================

    /**
     * Convert a Jackson {@link JsonNode} (e.g. parsed from an OpenAPI spec) to a
     * plain {@code Map<String, Object>} for use as a JSON Schema.
     *
     * @param node A JSON object node representing a JSON Schema.
     * @return Map representation, or an empty object schema on failure.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> fromJsonNode(JsonNode node) {
        if (node == null || node.isNull()) return Map.of("type", "object");
        try {
            return MAPPER.convertValue(node, Map.class);
        } catch (Exception e) {
            log.debug("Failed to convert JsonNode to JSON Schema map: {}", e.getMessage());
            return Map.of("type", "object");
        }
    }

    /**
     * Convert a plain {@code Map<String, Object>} JSON Schema to a Jackson
     * {@link ObjectNode} for use in JSON serialisation.
     *
     * @param schema The JSON Schema map.
     * @return An {@link ObjectNode}, or an empty object node on failure.
     */
    public static ObjectNode toJsonNode(Map<String, Object> schema) {
        if (schema == null) return MAPPER.createObjectNode();
        try {
            return MAPPER.convertValue(schema, ObjectNode.class);
        } catch (Exception e) {
            log.debug("Failed to convert JSON Schema map to JsonNode: {}", e.getMessage());
            return MAPPER.createObjectNode();
        }
    }

    /**
     * Type alias for a JSON Schema 7 map — mirrors {@code JsonSchema7Type} in zodToJsonSchema.ts.
     */
    public static Map<String, Object> emptyObjectSchema() {
        java.util.Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new java.util.LinkedHashMap<>());
        return schema;
    }

    private ZodToJsonSchemaUtils() {}
}
