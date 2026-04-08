package com.anthropic.claudecode.model;

import java.util.Arrays;
import java.util.List;

/**
 * System prompt type.
 * Translated from src/utils/systemPromptType.ts
 *
 * A system prompt is a list of strings that form the complete system prompt.
 */
public class SystemPrompt {

    private final List<String> parts;

    private SystemPrompt(List<String> parts) {
        this.parts = List.copyOf(parts);
    }

    /**
     * Create a system prompt from a list of strings.
     * Translated from asSystemPrompt() in systemPromptType.ts
     */
    public static SystemPrompt of(List<String> parts) {
        return new SystemPrompt(parts);
    }

    public static SystemPrompt of(String... parts) {
        return new SystemPrompt(Arrays.asList(parts));
    }

    public static SystemPrompt single(String content) {
        return new SystemPrompt(List.of(content));
    }

    public List<String> getParts() {
        return parts;
    }

    /**
     * Get the full system prompt as a single string.
     */
    public String getFullPrompt() {
        return String.join("\n\n", parts);
    }

    public boolean isEmpty() {
        return parts.isEmpty();
    }

    @Override
    public String toString() {
        return getFullPrompt();
    }
}
