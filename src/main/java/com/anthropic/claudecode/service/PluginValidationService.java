package com.anthropic.claudecode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.util.*;

/**
 * Plugin validation service.
 * Translated from src/utils/plugins/validatePlugin.ts
 *
 * Validates plugin manifests and structure.
 */
@Slf4j
@Service
public class PluginValidationService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PluginValidationService.class);


    private final ObjectMapper objectMapper;

    @Autowired
    public PluginValidationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Validate a plugin directory.
     * Translated from validatePlugin() in validatePlugin.ts
     */
    public ValidationResult validatePlugin(String pluginPath) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Check plugin.json exists
        File manifestFile = new File(pluginPath + "/plugin.json");
        if (!manifestFile.exists()) {
            errors.add("Missing plugin.json");
            return new ValidationResult(false, errors, warnings);
        }

        // Parse and validate manifest
        try {
            Map<String, Object> manifest = objectMapper.readValue(manifestFile, Map.class);

            if (!manifest.containsKey("name")) {
                errors.add("plugin.json missing 'name' field");
            }
            if (!manifest.containsKey("version")) {
                errors.add("plugin.json missing 'version' field");
            }
            if (!manifest.containsKey("description")) {
                warnings.add("plugin.json missing 'description' field");
            }

        } catch (Exception e) {
            errors.add("Invalid JSON in plugin.json: " + e.getMessage());
        }

        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ValidationResult {
        private boolean valid;
        private List<String> errors;
        private List<String> warnings;

        public boolean isValid() { return valid; }
        public void setValid(boolean v) { valid = v; }
        public List<String> getErrors() { return errors; }
        public void setErrors(List<String> v) { errors = v; }
        public List<String> getWarnings() { return warnings; }
        public void setWarnings(List<String> v) { warnings = v; }
    }
}
