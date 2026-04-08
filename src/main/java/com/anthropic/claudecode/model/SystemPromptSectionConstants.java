package com.anthropic.claudecode.model;

/**
 * Types and helpers for composing system prompt sections.
 * Translated from src/constants/systemPromptSections.ts
 *
 * In the TypeScript source, system prompt sections are runtime objects built
 * and resolved asynchronously (including cache-break support). This Java
 * translation provides the data model for the same concept.
 */
public class SystemPromptSectionConstants {

    /**
     * A named section of the system prompt.
     *
     * @param name       The unique name used as the cache key for this section.
     * @param content    The resolved string content of the section, or null if
     *                   this section should be omitted from the prompt.
     * @param cacheBreak Whether this section is volatile and should bypass the
     *                   memoization cache, recomputing on every turn.
     *                   A {@code true} value WILL break the prompt cache when the
     *                   value changes between turns.
     */
    public record SystemPromptSection(
        String name,
        String content,
        boolean cacheBreak
    ) {

        /**
         * Create a memoized (cached) system prompt section.
         * Computed once, cached until /clear or /compact.
         */
        public static SystemPromptSection cached(String name, String content) {
            return new SystemPromptSection(name, content, false);
        }

        /**
         * Create a volatile system prompt section that is not cached.
         * This WILL break the prompt cache when the value changes.
         *
         * @param name   Unique section name.
         * @param content The computed content, or null to omit the section.
         * @param reason  Reason explaining why cache-breaking is necessary
         *                (documentation only, not persisted).
         */
        public static SystemPromptSection uncached(
            String name,
            String content,
            @SuppressWarnings("unused") String reason
        ) {
            return new SystemPromptSection(name, content, true);
        }
    }

    private SystemPromptSectionConstants() {}
}
