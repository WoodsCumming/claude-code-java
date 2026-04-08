package com.anthropic.claudecode.util;

/**
 * Environment variable validation utilities.
 * Translated from src/utils/envValidation.ts
 */
public class EnvValidation {

    public enum ValidationStatus {
        VALID, CAPPED, INVALID
    }

    public record EnvVarValidationResult(int effective, ValidationStatus status, String message) {}

    /**
     * Validate a bounded integer environment variable.
     * Translated from validateBoundedIntEnvVar() in envValidation.ts
     */
    public static EnvVarValidationResult validateBoundedIntEnvVar(
            String name,
            String value,
            int defaultValue,
            int upperLimit) {

        if (value == null || value.isBlank()) {
            return new EnvVarValidationResult(defaultValue, ValidationStatus.VALID, null);
        }

        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                return new EnvVarValidationResult(
                    defaultValue,
                    ValidationStatus.INVALID,
                    "Invalid value \"" + value + "\" (using default: " + defaultValue + ")"
                );
            }

            if (parsed > upperLimit) {
                return new EnvVarValidationResult(
                    upperLimit,
                    ValidationStatus.CAPPED,
                    "Capped from " + parsed + " to " + upperLimit
                );
            }

            return new EnvVarValidationResult(parsed, ValidationStatus.VALID, null);

        } catch (NumberFormatException e) {
            return new EnvVarValidationResult(
                defaultValue,
                ValidationStatus.INVALID,
                "Invalid value \"" + value + "\" (using default: " + defaultValue + ")"
            );
        }
    }

    private EnvValidation() {}
}
