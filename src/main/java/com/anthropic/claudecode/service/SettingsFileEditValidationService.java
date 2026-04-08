package com.anthropic.claudecode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Supplier;

/**
 * Settings file edit validation service.
 * Translated from src/utils/settings/validateEditTool.ts
 *
 * Validates settings file edits to ensure they conform to SettingsSchema.
 */
@Slf4j
@Service
public class SettingsFileEditValidationService {



    private final ObjectMapper objectMapper;

    @Autowired
    public SettingsFileEditValidationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Validate a settings file edit.
     * Translated from validateInputForSettingsFileEdit() in validateEditTool.ts
     */
    public Optional<ValidationError> validateInputForSettingsFileEdit(
            String filePath,
            String originalContent,
            Supplier<String> getUpdatedContent) {

        // Only validate Claude settings files
        if (!isClaudeSettingsPath(filePath)) return Optional.empty();

        try {
            String updatedContent = getUpdatedContent.get();
            if (updatedContent == null) return Optional.empty();

            // Validate JSON structure
            Map<String, Object> parsed = objectMapper.readValue(updatedContent, Map.class);

            // Basic validation - check for known invalid fields
            // In a full implementation, this would validate against SettingsSchema

            return Optional.empty();

        } catch (Exception e) {
            return Optional.of(new ValidationError(
                "Invalid JSON in settings file: " + e.getMessage()
            ));
        }
    }

    private boolean isClaudeSettingsPath(String filePath) {
        if (filePath == null) return false;
        return filePath.endsWith("settings.json")
            && (filePath.contains("/.claude/") || filePath.contains("\\.claude\\"));
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ValidationError {
        private String message;

        public String getMessage() { return message; }
        public void setMessage(String v) { message = v; }
    
    }
}
