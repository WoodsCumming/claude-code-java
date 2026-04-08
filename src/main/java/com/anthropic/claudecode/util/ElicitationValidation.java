package com.anthropic.claudecode.util;

import lombok.Data;
import java.util.*;
import java.util.regex.*;

/**
 * MCP elicitation validation utilities.
 * Translated from src/utils/mcp/elicitationValidation.ts
 *
 * Validates user input against MCP elicitation schemas.
 */
public class ElicitationValidation {

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ValidationResult {
        private Object value;
        private boolean valid;
        private String error;

        public static ValidationResult success(Object value) {
            return new ValidationResult(value, true, null);
        }

        public static ValidationResult failure(String error) {
            return new ValidationResult(null, false, error);
        }

        public Object getValue() { return value; }
        public void setValue(Object v) { value = v; }
        public boolean isValid() { return valid; }
        public void setValid(boolean v) { valid = v; }
        public String getError() { return error; }
        public void setError(String v) { error = v; }
    
    }

    /**
     * Validate a string value against a schema.
     * Translated from validateStringInput() in elicitationValidation.ts
     */
    public static ValidationResult validateStringInput(String input, Map<String, Object> schema) {
        if (input == null) {
            return ValidationResult.failure("Value is required");
        }

        String type = (String) schema.get("type");
        if (!"string".equals(type)) {
            return ValidationResult.failure("Expected string type");
        }

        // Check minLength
        Number minLength = (Number) schema.get("minLength");
        if (minLength != null && input.length() < minLength.intValue()) {
            return ValidationResult.failure("Value must be at least " + minLength + " characters");
        }

        // Check maxLength
        Number maxLength = (Number) schema.get("maxLength");
        if (maxLength != null && input.length() > maxLength.intValue()) {
            return ValidationResult.failure("Value must be at most " + maxLength + " characters");
        }

        // Check pattern
        String pattern = (String) schema.get("pattern");
        if (pattern != null && !Pattern.matches(pattern, input)) {
            return ValidationResult.failure("Value does not match required pattern");
        }

        // Check format
        String format = (String) schema.get("format");
        if (format != null) {
            ValidationResult formatResult = validateFormat(input, format);
            if (!formatResult.isValid()) {
                return formatResult;
            }
        }

        return ValidationResult.success(input);
    }

    /**
     * Validate enum input.
     */
    public static ValidationResult validateEnumInput(String input, List<String> enumValues) {
        if (input == null) {
            return ValidationResult.failure("Value is required");
        }
        if (!enumValues.contains(input)) {
            return ValidationResult.failure("Value must be one of: " + String.join(", ", enumValues));
        }
        return ValidationResult.success(input);
    }

    /**
     * Validate a number input.
     */
    public static ValidationResult validateNumberInput(String input, Map<String, Object> schema) {
        try {
            double value = Double.parseDouble(input);

            Number minimum = (Number) schema.get("minimum");
            if (minimum != null && value < minimum.doubleValue()) {
                return ValidationResult.failure("Value must be at least " + minimum);
            }

            Number maximum = (Number) schema.get("maximum");
            if (maximum != null && value > maximum.doubleValue()) {
                return ValidationResult.failure("Value must be at most " + maximum);
            }

            return ValidationResult.success(value);
        } catch (NumberFormatException e) {
            return ValidationResult.failure("Value must be a number");
        }
    }

    private static ValidationResult validateFormat(String value, String format) {
        switch (format) {
            case "email":
                if (!value.contains("@") || !value.contains(".")) {
                    return ValidationResult.failure("Value must be a valid email address (e.g., user@example.com)");
                }
                break;
            case "uri":
                try {
                    new java.net.URI(value);
                } catch (Exception e) {
                    return ValidationResult.failure("Value must be a valid URI (e.g., https://example.com)");
                }
                break;
            case "date":
                if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    return ValidationResult.failure("Value must be a valid date (e.g., 2024-03-15)");
                }
                break;
            case "date-time":
                try {
                    java.time.Instant.parse(value);
                } catch (Exception e) {
                    return ValidationResult.failure("Value must be a valid date-time (e.g., 2024-03-15T14:30:00Z)");
                }
                break;
        }
        return ValidationResult.success(value);
    }

    private ElicitationValidation() {}
}
