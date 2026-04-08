package com.anthropic.claudecode.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Memory types for Claude Code's memory system.
 * Translated from src/utils/memory/types.ts
 */
public enum MemoryType {
    USER("User"),
    PROJECT("Project"),
    LOCAL("Local"),
    MANAGED("Managed"),
    AUTO_MEM("AutoMem"),
    TEAM_MEM("TeamMem");

    private final String value;

    MemoryType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static MemoryType fromValue(String value) {
        for (MemoryType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown memory type: " + value);
    }
}
