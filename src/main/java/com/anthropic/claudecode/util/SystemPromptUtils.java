package com.anthropic.claudecode.util;

import com.anthropic.claudecode.model.SystemPrompt;
import java.util.*;

/**
 * System prompt building utilities.
 * Translated from src/utils/systemPrompt.ts
 */
public class SystemPromptUtils {

    /**
     * Build the effective system prompt.
     * Translated from buildEffectiveSystemPrompt() in systemPrompt.ts
     *
     * Priority:
     * 0. Override system prompt (replaces all)
     * 1. Custom system prompt (via --system-prompt)
     * 2. Default system prompt
     * + appendSystemPrompt is always added at the end
     */
    public static SystemPrompt buildEffectiveSystemPrompt(
            String customSystemPrompt,
            List<String> defaultSystemPrompt,
            String appendSystemPrompt,
            String overrideSystemPrompt) {

        // Override replaces everything
        if (overrideSystemPrompt != null && !overrideSystemPrompt.isBlank()) {
            return SystemPrompt.of(List.of(overrideSystemPrompt));
        }

        List<String> parts = new ArrayList<>();

        // Custom or default system prompt
        if (customSystemPrompt != null && !customSystemPrompt.isBlank()) {
            parts.add(customSystemPrompt);
        } else if (defaultSystemPrompt != null && !defaultSystemPrompt.isEmpty()) {
            parts.addAll(defaultSystemPrompt);
        }

        // Append system prompt
        if (appendSystemPrompt != null && !appendSystemPrompt.isBlank()) {
            parts.add(appendSystemPrompt);
        }

        return SystemPrompt.of(parts);
    }

    private SystemPromptUtils() {}
}
