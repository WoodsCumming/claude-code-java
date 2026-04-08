package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.*;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.*;

/**
 * Message stream handler service.
 * Translated from src/utils/messages.ts handleMessageFromStream()
 *
 * Handles messages from the streaming API response.
 */
@Slf4j
@Service
public class MessageStreamHandlerService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MessageStreamHandlerService.class);


    /**
     * Handle a message from the stream.
     * Translated from handleMessageFromStream() in messages.ts
     */
    public void handleMessageFromStream(
            Object message,
            Consumer<Message> onMessage,
            Consumer<String> onUpdateLength,
            Consumer<String> onSetStreamMode) {

        if (message instanceof Message msg) {
            onMessage.accept(msg);
        }
        // In a full implementation, this would handle StreamEvent, ToolUseSummaryMessage, etc.
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class StreamingToolUse {
        private String id;
        private String name;
        private String partialInput;

        public String getId() { return id; }
        public void setId(String v) { id = v; }
        public String getName() { return name; }
        public void setName(String v) { name = v; }
        public String getPartialInput() { return partialInput; }
        public void setPartialInput(String v) { partialInput = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class StreamingThinking {
        private String id;
        private String text;

        public String getText() { return text; }
        public void setText(String v) { text = v; }
    }
}
