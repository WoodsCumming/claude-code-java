package com.anthropic.claudecode.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Permission update destination types.
 * Translated from PermissionUpdateDestination in src/types/permissions.ts
 */
public enum PermissionUpdateDestination {
    USER_SETTINGS("userSettings"),
    PROJECT_SETTINGS("projectSettings"),
    LOCAL_SETTINGS("localSettings"),
    SESSION("session"),
    CLI_ARG("cliArg");

    private final String value;

    PermissionUpdateDestination(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static PermissionUpdateDestination fromValue(String value) {
        for (PermissionUpdateDestination dest : values()) {
            if (dest.value.equals(value)) return dest;
        }
        throw new IllegalArgumentException("Unknown destination: " + value);
    }
}
