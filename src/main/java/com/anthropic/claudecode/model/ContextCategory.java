package com.anthropic.claudecode.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Builder;

/**
 * Context window category for visualization.
 * Translated from ContextCategory interface in src/utils/analyzeContext.ts
 */
@Data
@lombok.Builder

public class ContextCategory {

    public static final int TOOL_TOKEN_COUNT_OVERHEAD = 500;

    private String name;
    private int tokens;
    private String color;
    private boolean isDeferred;

    public ContextCategory() {}

    public static ContextCategoryBuilder builder() { return new ContextCategoryBuilder(); }
    public static class ContextCategoryBuilder {
        private String name; private int tokens; private String color; private boolean isDeferred;
        public ContextCategoryBuilder name(String v) { this.name = v; return this; }
        public ContextCategoryBuilder tokens(int v) { this.tokens = v; return this; }
        public ContextCategoryBuilder color(String v) { this.color = v; return this; }
        public ContextCategoryBuilder isDeferred(boolean v) { this.isDeferred = v; return this; }
        public ContextCategory build() {
            ContextCategory o = new ContextCategory();
            o.name = name; o.tokens = tokens; o.color = color; o.isDeferred = isDeferred;
            return o;
        }
    }

    public static ContextCategory of(String name, int tokens) {
        return ContextCategory.builder()
            .name(name)
            .tokens(tokens)
            .build();
    }
}
