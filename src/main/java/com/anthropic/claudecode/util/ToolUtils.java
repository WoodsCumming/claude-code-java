package com.anthropic.claudecode.util;

import com.anthropic.claudecode.model.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Tool utility functions.
 * Translated from src/tools/utils.ts
 */
public class ToolUtils {

    /**
     * Get tool use ID from a parent assistant message.
     * Translated from getToolUseIDFromParentMessage() in tools/utils.ts
     */
    public static Optional<String> getToolUseIDFromParentMessage(
            Message.AssistantMessage parentMessage,
            String toolName) {

        if (parentMessage == null || parentMessage.getContent() == null) {
            return Optional.empty();
        }

        return parentMessage.getContent().stream()
            .filter(b -> b instanceof ContentBlock.ToolUseBlock)
            .map(b -> (ContentBlock.ToolUseBlock) b)
            .filter(b -> toolName.equals(b.getName()))
            .map(ContentBlock.ToolUseBlock::getId)
            .findFirst();
    }

    /**
     * Check if a tool name matches (including aliases).
     * Translated from toolMatchesName() in Tool.ts
     */
    public static boolean toolMatchesName(
            com.anthropic.claudecode.tool.Tool<?, ?> tool,
            String name) {
        return tool.getName().equals(name)
            || tool.getAliases().contains(name);
    }

    /**
     * Find a tool by name from a list.
     * Translated from findToolByName() in Tool.ts
     */
    public static Optional<com.anthropic.claudecode.tool.Tool<?, ?>> findToolByName(
            List<com.anthropic.claudecode.tool.Tool<?, ?>> tools,
            String name) {
        return tools.stream()
            .filter(t -> toolMatchesName(t, name))
            .findFirst();
    }

    private ToolUtils() {}
}
