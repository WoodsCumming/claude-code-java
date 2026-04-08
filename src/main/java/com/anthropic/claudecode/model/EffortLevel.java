package com.anthropic.claudecode.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Effort level configuration for Claude's thinking.
 * Translated from src/utils/effort.ts
 */
public enum EffortLevel {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    MAX("max");

    private final String value;

    EffortLevel(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static EffortLevel fromValue(String value) {
        for (EffortLevel level : values()) {
            if (level.value.equals(value)) return level;
        }
        throw new IllegalArgumentException("Unknown effort level: " + value);
    }

    /**
     * Check if a model supports the effort parameter.
     * Translated from modelSupportsEffort() in effort.ts
     */
    public static boolean modelSupportsEffort(String model) {
        if (model == null) return false;
        String m = model.toLowerCase();
        return m.contains("opus-4-6") || m.contains("sonnet-4-6");
    }

    /**
     * Check if a model supports max effort.
     * Translated from modelSupportsMaxEffort() in effort.ts
     */
    public static boolean modelSupportsMaxEffort(String model) {
        if (model == null) return false;
        return model.toLowerCase().contains("opus-4-6");
    }
}
