package com.anthropic.claudecode.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Message role types.
 * Translated from Anthropic SDK types.
 */
public enum MessageRole {
    USER("user"),
    ASSISTANT("assistant");

    private final String value;

    MessageRole(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static MessageRole fromValue(String value) {
        for (MessageRole role : values()) {
            if (role.value.equals(value)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unknown role: " + value);
    }
}
