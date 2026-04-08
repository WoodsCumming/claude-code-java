package com.anthropic.claudecode.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * YAML parsing utilities.
 * Translated from src/utils/yaml.ts
 *
 * Wraps Jackson YAML parsing so callers don't need to depend on the mapper directly.
 * The TypeScript source uses Bun.YAML when running under Bun, otherwise falls back
 * to the {@code yaml} npm package; here we always use Jackson's YAMLMapper.
 */
@Slf4j
public final class YamlUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(YamlUtils.class);


    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    // =========================================================================
    // Parsing
    // =========================================================================

    /**
     * Parse a YAML string into a plain Java object tree
     * ({@code Map}, {@code List}, scalars, or {@code null}).
     * Returns {@code null} for blank or invalid input.
     *
     * Translated from parseYaml() in yaml.ts
     */
    public static Object parseYaml(String input) {
        if (input == null || input.isBlank()) return null;
        try {
            return YAML_MAPPER.readValue(input, Object.class);
        } catch (Exception e) {
            log.debug("YAML parse error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parse a YAML string as a {@code Map<String, Object>}.
     * Returns an empty map for blank, invalid, or non-map input.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseYamlAsMap(String input) {
        Object parsed = parseYaml(input);
        if (parsed instanceof Map) return (Map<String, Object>) parsed;
        return Map.of();
    }

    // =========================================================================
    // Serialisation
    // =========================================================================

    /**
     * Serialize an object to a YAML string.
     * Returns {@code null} if serialisation fails.
     */
    public static String toYaml(Object value) {
        if (value == null) return "null\n";
        try {
            return YAML_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.debug("YAML serialisation error: {}", e.getMessage());
            return null;
        }
    }

    private YamlUtils() {}
}
