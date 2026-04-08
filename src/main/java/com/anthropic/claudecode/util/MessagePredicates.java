package com.anthropic.claudecode.util;

import com.anthropic.claudecode.model.*;
import java.util.List;

/**
 * Message predicate utilities.
 * Translated from src/utils/messagePredicates.ts
 */
public class MessagePredicates {

    /**
     * Check if a message is a human turn (not a tool result).
     * Translated from isHumanTurn() in messagePredicates.ts
     */
    public static boolean isHumanTurn(Message msg) {
        if (!(msg instanceof Message.UserMessage userMsg)) return false;

        // Tool result messages share type='user' with human turns
        // The discriminant is whether the content contains tool results
        if (userMsg.getContent() == null) return true;

        boolean hasToolResults = userMsg.getContent().stream()
            .anyMatch(b -> b instanceof ContentBlock.ToolResultBlock);

        return !hasToolResults;
    }

    /**
     * Check if a message is a tool result message.
     */
    public static boolean isToolResultMessage(Message msg) {
        if (!(msg instanceof Message.UserMessage userMsg)) return false;
        if (userMsg.getContent() == null) return false;

        return userMsg.getContent().stream()
            .anyMatch(b -> b instanceof ContentBlock.ToolResultBlock);
    }

    /**
     * Check if a message is an assistant message.
     */
    public static boolean isAssistantMessage(Message msg) {
        return msg instanceof Message.AssistantMessage;
    }

    /**
     * Check if a message has tool use.
     */
    public static boolean hasToolUse(Message msg) {
        if (!(msg instanceof Message.AssistantMessage assistantMsg)) return false;
        if (assistantMsg.getContent() == null) return false;

        return assistantMsg.getContent().stream()
            .anyMatch(b -> b instanceof ContentBlock.ToolUseBlock);
    }

    private MessagePredicates() {}
}
