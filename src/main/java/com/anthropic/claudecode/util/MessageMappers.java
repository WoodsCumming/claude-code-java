package com.anthropic.claudecode.util;

import com.anthropic.claudecode.model.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Message mapping utilities for SDK/internal message conversion.
 * Translated from src/utils/messages/mappers.ts
 */
public class MessageMappers {

    /**
     * Convert SDK messages to internal messages.
     * Translated from toInternalMessages() in mappers.ts
     */
    public static List<Message> toInternalMessages(List<Map<String, Object>> sdkMessages) {
        if (sdkMessages == null) return List.of();

        return sdkMessages.stream()
            .flatMap(msg -> {
                String type = (String) msg.get("type");
                if ("assistant".equals(type)) {
                    return List.of(sdkAssistantToInternal(msg)).stream();
                } else if ("user".equals(type)) {
                    return List.of(sdkUserToInternal(msg)).stream();
                }
                return List.<Message>of().stream();
            })
            .collect(Collectors.toList());
    }

    private static Message sdkAssistantToInternal(Map<String, Object> msg) {
        List<Map<String, Object>> content = (List<Map<String, Object>>) msg.get("content");
        List<ContentBlock> blocks = new ArrayList<>();

        if (content != null) {
            for (Map<String, Object> block : content) {
                String blockType = (String) block.get("type");
                if ("text".equals(blockType)) {
                    blocks.add(new ContentBlock.TextBlock((String) block.get("text")));
                } else if ("tool_use".equals(blockType)) {
                    blocks.add(new ContentBlock.ToolUseBlock(
                        "tool_use",
                        (String) block.get("id"),
                        (String) block.get("name"),
                        (Map<String, Object>) block.get("input")
                    ));
                }
            }
        }

        return Message.AssistantMessage.builder()
            .type("assistant")
            .uuid(UUID.randomUUID().toString())
            .content(blocks)
            .build();
    }

    private static Message sdkUserToInternal(Map<String, Object> msg) {
        Object content = msg.get("content");
        List<ContentBlock> blocks = new ArrayList<>();

        if (content instanceof String text) {
            blocks.add(new ContentBlock.TextBlock(text));
        } else if (content instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> block) {
                    String blockType = (String) ((Map<?, ?>) block).get("type");
                    if ("text".equals(blockType)) {
                        blocks.add(new ContentBlock.TextBlock((String) ((Map<?, ?>) block).get("text")));
                    } else if ("tool_result".equals(blockType)) {
                        ContentBlock.ToolResultBlock result = new ContentBlock.ToolResultBlock();
                        result.setToolUseId((String) ((Map<?, ?>) block).get("tool_use_id"));
                        result.setContent(((Map<?, ?>) block).get("content"));
                        blocks.add(result);
                    }
                }
            }
        }

        return Message.UserMessage.builder()
            .type("user")
            .uuid(UUID.randomUUID().toString())
            .content(blocks)
            .build();
    }

    private MessageMappers() {}
}
