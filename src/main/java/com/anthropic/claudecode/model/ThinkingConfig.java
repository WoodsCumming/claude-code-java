package com.anthropic.claudecode.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Configuration for Claude's thinking/reasoning feature.
 * Translated from src/utils/thinking.ts ThinkingConfig type.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ThinkingConfig.Adaptive.class, name = "adaptive"),
})
public sealed interface ThinkingConfig permits
        ThinkingConfig.Adaptive,
        ThinkingConfig.Enabled,
        ThinkingConfig.Disabled {

    String getType();

    /** Adaptive thinking - model decides when to think */
    @Data
    final class Adaptive implements ThinkingConfig {
        private final String type = "adaptive";
        @Override public String getType() { return type; }
    }

    /** Enabled thinking with a token budget */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    final class Enabled implements ThinkingConfig {
        private final String type = "enabled";
        private int budgetTokens;
        @Override public String getType() { return type; }
    }

    /** Disabled thinking */
    @Data
    final class Disabled implements ThinkingConfig {
        private final String type = "disabled";
        @Override public String getType() { return type; }
    }

    /** Default thinking config */
    static ThinkingConfig defaultConfig() {
        return new Disabled();
    }
}
