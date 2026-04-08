package com.anthropic.claudecode.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/**
 * Settings schema output utilities.
 * Translated from src/utils/settings/schemaOutput.ts
 *
 * Generates JSON schema for settings.
 */
public class SettingsSchemaOutput {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Generate the settings JSON schema.
     * Translated from generateSettingsJSONSchema() in schemaOutput.ts
     */
    public static String generateSettingsJSONSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$schema", "http://json-schema.org/draft-07/schema#");
        schema.put("type", "object");
        schema.put("title", "Claude Code Settings");
        schema.put("description", "Configuration settings for Claude Code");

        Map<String, Object> properties = new LinkedHashMap<>();

        // Add known settings properties
        properties.put("model", Map.of("type", "string", "description", "Default model to use"));
        properties.put("permissionMode", Map.of("type", "string", "description", "Permission mode"));
        properties.put("autoCompactEnabled", Map.of("type", "boolean", "description", "Enable auto-compact"));
        properties.put("includeGitInstructions", Map.of("type", "boolean", "description", "Include git instructions"));
        properties.put("allowedTools", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("disabledTools", Map.of("type", "array", "items", Map.of("type", "string")));

        schema.put("properties", properties);
        schema.put("additionalProperties", false);

        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(schema);
        } catch (Exception e) {
            return "{}";
        }
    }

    private SettingsSchemaOutput() {}
}
