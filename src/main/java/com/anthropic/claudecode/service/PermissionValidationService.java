package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.McpStringUtils;
import com.anthropic.claudecode.util.PermissionRuleParser;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Permission validation service.
 * Translated from src/utils/settings/permissionValidation.ts
 *
 * Validates permission rules in settings.
 */
@Slf4j
@Service
public class PermissionValidationService {



    /**
     * Validate a permission rule string.
     * Translated from validatePermissionRule() in permissionValidation.ts
     */
    public ValidationResult validatePermissionRule(String rule, String toolName) {
        if (rule == null || rule.isBlank()) {
            return new ValidationResult(false, "Rule cannot be empty");
        }

        // Check for MCP tools
        Optional<McpStringUtils.McpInfo> mcpInfo = McpStringUtils.mcpInfoFromString(toolName);
        if (mcpInfo.isPresent()) {
            // MCP tools use different validation
            return new ValidationResult(true, null);
        }

        // Validate the rule pattern
        try {
            PermissionRuleParser.parsePermissionRuleValue(rule);
            return new ValidationResult(true, null);
        } catch (Exception e) {
            return new ValidationResult(false, "Invalid permission rule: " + e.getMessage());
        }
    }

    /**
     * Validate all permission rules in a settings object.
     */
    public List<ValidationError> validateSettingsPermissions(Map<String, Object> settings) {
        List<ValidationError> errors = new ArrayList<>();

        Object allowedTools = settings.get("allowedTools");
        if (allowedTools instanceof List) {
            for (Object tool : (List<?>) allowedTools) {
                if (tool instanceof String toolStr) {
                    ValidationResult result = validatePermissionRule(toolStr, toolStr);
                    if (!result.isValid()) {
                        errors.add(new ValidationError("allowedTools", toolStr, result.getError()));
                    }
                }
            }
        }

        return errors;
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ValidationResult {
        private boolean valid;
        private String error;

        public boolean isValid() { return valid; }
        public void setValid(boolean v) { valid = v; }
        public String getError() { return error; }
        public void setError(String v) { error = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ValidationError {
        private String field;
        private String value;
        private String error;

        public String getField() { return field; }
        public void setField(String v) { field = v; }
        public String getValue() { return value; }
        public void setValue(String v) { value = v; }
    }
}
