package com.anthropic.claudecode.util;

import com.anthropic.claudecode.model.ContentBlock;
import com.anthropic.claudecode.model.Message;
import com.anthropic.claudecode.tool.Tool;
import java.util.*;
import java.util.stream.Collectors;

/**
 * API utility functions.
 * Translated from src/utils/api.ts
 *
 * Utilities for building API requests and processing responses.
 */
public class ApiUtils {

    /**
     * Convert a tool to its API schema representation.
     * Translated from toolToAPISchema() in api.ts
     */
    public static Map<String, Object> toolToAPISchema(Tool<?, ?> tool) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", tool.getName());

        // Get description synchronously (simplified)
        schema.put("description", "Tool: " + tool.getName());
        schema.put("input_schema", tool.getInputSchema());

        return schema;
    }

    /**
     * Normalize tool input for API submission.
     * Translated from normalizeToolInput() in api.ts
     */
    public static Map<String, Object> normalizeToolInput(Map<String, Object> input) {
        if (input == null) return Map.of();
        return input;
    }

    /**
     * Prepend user context to messages.
     * Translated from prependUserContext() in api.ts
     */
    public static List<Message> prependUserContext(
            List<Message> messages,
            String userContext) {
        if (userContext == null || userContext.isBlank()) return messages;

        List<Message> result = new ArrayList<>();
        Message.UserMessage contextMsg = MessageUtils.createUserMessage(userContext);
        result.add(contextMsg);
        result.addAll(messages);
        return result;
    }

    /**
     * Append system context to system prompt.
     * Translated from appendSystemContext() in api.ts
     */
    public static String appendSystemContext(String systemPrompt, String additionalContext) {
        if (additionalContext == null || additionalContext.isBlank()) return systemPrompt;
        if (systemPrompt == null) return additionalContext;
        return systemPrompt + "\n\n" + additionalContext;
    }

    /**
     * Split system prompt prefix from the main prompt.
     * Translated from splitSysPromptPrefix() in api.ts
     */
    public static String[] splitSysPromptPrefix(String systemPrompt) {
        if (systemPrompt == null) return new String[]{"", ""};
        // Look for the CLI prefix boundary
        String boundary = "---";
        int idx = systemPrompt.indexOf(boundary);
        if (idx >= 0) {
            return new String[]{
                systemPrompt.substring(0, idx),
                systemPrompt.substring(idx + boundary.length())
            };
        }
        return new String[]{"", systemPrompt};
    }

    /**
     * Log the API prefix for debugging.
     * Translated from logAPIPrefix() in api.ts
     */
    public static void logAPIPrefix(String prefix) {
        // In production, this would log to the debug logger
        System.err.println("[API PREFIX] " + prefix);
    }

    public enum CacheScope {
        GLOBAL("global"),
        ORG("org");

        private final String value;
        CacheScope(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    /**
     * Add cache breakpoints to system blocks.
     * Translated from addCacheBreakpoints() in api.ts
     */
    public static List<Map<String, Object>> addCacheBreakpoints(
            List<Map<String, Object>> systemBlocks,
            boolean useGlobalCacheScope) {
        if (systemBlocks == null || systemBlocks.isEmpty()) return systemBlocks;
        List<Map<String, Object>> result = new ArrayList<>(systemBlocks);
        Map<String, Object> lastBlock = new LinkedHashMap<>(result.get(result.size() - 1));
        lastBlock.put("cache_control", Map.of("type", "ephemeral"));
        result.set(result.size() - 1, lastBlock);
        return result;
    }

    private ApiUtils() {}
}
