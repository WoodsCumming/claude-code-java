package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Settings all errors service.
 * Translated from src/utils/settings/allErrors.ts
 *
 * Combines settings validation errors with MCP configuration errors.
 */
@Slf4j
@Service
public class SettingsAllErrorsService {



    private final SettingsService settingsService;
    private final McpConfigService mcpConfigService;

    @Autowired
    public SettingsAllErrorsService(SettingsService settingsService,
                                     McpConfigService mcpConfigService) {
        this.settingsService = settingsService;
        this.mcpConfigService = mcpConfigService;
    }

    /**
     * Get merged settings with all validation errors.
     * Translated from getSettingsWithAllErrors() in allErrors.ts
     */
    public SettingsWithErrors getSettingsWithAllErrors(String projectPath) {
        Map<String, Object> settings = settingsService.getMergedSettings(projectPath);
        List<ValidationError> errors = new ArrayList<>();

        // Add MCP config errors
        List<ValidationError> mcpErrors = getMcpConfigErrors(projectPath);
        errors.addAll(mcpErrors);

        return new SettingsWithErrors(settings, errors);
    }

    private List<ValidationError> getMcpConfigErrors(String projectPath) {
        List<ValidationError> errors = new ArrayList<>();
        // In a full implementation, this would validate MCP configurations
        return errors;
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SettingsWithErrors {
        private Map<String, Object> settings;
        private List<ValidationError> errors;

        public Map<String, Object> getSettings() { return settings; }
        public void setSettings(Map<String, Object> v) { settings = v; }
        public List<ValidationError> getErrors() { return errors; }
        public void setErrors(List<ValidationError> v) { errors = v; }
    
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ValidationError {
        private String field;
        private String message;
        private String source;

        public String getField() { return field; }
        public void setField(String v) { field = v; }
        public String getMessage() { return message; }
        public void setMessage(String v) { message = v; }
        public String getSource() { return source; }
        public void setSource(String v) { source = v; }
    }
}
