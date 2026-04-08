package com.anthropic.claudecode.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Shared utility functions for tool message handling.
 * Translated from src/tools/utils.ts
 *
 * <p>Tags user messages with a sourceToolUseID so they stay transient until
 * the tool resolves, preventing "is running" message duplication in the UI.</p>
 */
public class ToolsUtils {

    /**
     * A minimal representation of a message that carries a type discriminant.
     * In the full Java model these would be concrete implementations of a sealed interface.
     */
    public interface Message {
        String getType();
    }

    /**
     * A user message that can carry an optional sourceToolUseID.
     */
    public interface UserMessage extends Message {
        Optional<String> getSourceToolUseId();

        UserMessage withSourceToolUseId(String toolUseId);

        @Override
        default String getType() {
            return "user";
        }
    }

    /**
     * Tags each user-type message in the list with the given tool-use ID.
     * Non-user messages are returned unchanged.
     * Translated from tagMessagesWithToolUseID() in utils.ts
     *
     * @param messages   list of messages (user, attachment, system, etc.)
     * @param toolUseId  the tool-use ID to stamp onto user messages; if null/empty, the list is returned as-is
     */
    public static List<Message> tagMessagesWithToolUseId(
            List<Message> messages,
            String toolUseId
    ) {
        if (toolUseId == null || toolUseId.isBlank()) {
            return messages;
        }
        List<Message> result = new ArrayList<>(messages.size());
        for (Message m : messages) {
            if (m instanceof UserMessage userMsg) {
                result.add(userMsg.withSourceToolUseId(toolUseId));
            } else {
                result.add(m);
            }
        }
        return result;
    }

    /**
     * Extracts the tool-use ID for a given tool name from an assistant message's content blocks.
     * Translated from getToolUseIDFromParentMessage() in utils.ts
     *
     * @param contentBlocks  content blocks of the parent assistant message
     * @param toolName       name of the tool whose tool-use ID is sought
     * @return the tool-use ID if found, otherwise empty
     */
    public static Optional<String> getToolUseIdFromParentMessage(
            List<ContentBlock> contentBlocks,
            String toolName
    ) {
        if (contentBlocks == null || toolName == null) {
            return Optional.empty();
        }
        return contentBlocks.stream()
                .filter(b -> "tool_use".equals(b.type()) && toolName.equals(b.name()))
                .map(ContentBlock::id)
                .findFirst();
    }

    /**
     * Minimal representation of a content block in an assistant message.
     */
    public record ContentBlock(String type, String id, String name) {}

    private ToolsUtils() {}
}
