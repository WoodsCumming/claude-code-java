package com.anthropic.claudecode.model;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.*;

/**
 * Memory type taxonomy.
 * Translated from src/memdir/memoryTypes.ts
 */
public enum MemoryTypes {
    USER("user"),
    FEEDBACK("feedback"),
    PROJECT("project"),
    REFERENCE("reference");

    private final String value;

    MemoryTypes(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static final List<String> MEMORY_TYPE_VALUES = List.of("user", "feedback", "project", "reference");

    /**
     * Parse a memory type from a string.
     * Translated from parseMemoryType() in memoryTypes.ts
     */
    public static Optional<MemoryTypes> parseMemoryType(String raw) {
        if (raw == null) return Optional.empty();
        return Arrays.stream(values())
            .filter(t -> t.value.equals(raw))
            .findFirst();
    }

    public static MemoryTypes fromValue(String value) {
        for (MemoryTypes type : values()) {
            if (type.value.equals(value)) return type;
        }
        throw new IllegalArgumentException("Unknown memory type: " + value);
    }
}
