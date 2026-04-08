package com.anthropic.claudecode.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Message types for Claude Code conversations.
 * Translated from src/types/message.ts (inferred from usage in messages.ts, Tool.ts, etc.)
 *
 * The TypeScript source uses a discriminated union pattern:
 *   type Message = UserMessage | AssistantMessage | SystemMessage | ...
 */
public sealed interface Message permits
        Message.UserMessage,
        Message.AssistantMessage,
        Message.SystemMessage,
        Message.ProgressMessage,
        Message.AttachmentMessage,
        Message.TombstoneMessage,
        Message.ToolUseSummaryMessage {

    String getType();
    String getUuid();
    String getTimestamp();
    default String getSubtype() { return null; }

    /** Filter whitespace-only assistant messages. */
    static java.util.List<Message> filterWhitespaceOnlyAssistantMessages(java.util.List<Message> messages) {
        return messages;
    }

    /** Filter orphaned thinking-only messages. */
    static java.util.List<Message> filterOrphanedThinkingOnlyMessages(java.util.List<Message> messages) {
        return messages;
    }

    /** Filter messages with unresolved tool uses. */
    static java.util.List<Message> filterUnresolvedToolUses(java.util.List<Message> messages) {
        return messages;
    }

    /**
     * Extract plain-text content from this message for summarisation purposes.
     * Returns null/empty if the message carries no readable text.
     */
    default String extractPlainText() {
        if (this instanceof UserMessage um && um.getContent() != null) {
            java.util.List<String> parts = new java.util.ArrayList<>();
            for (ContentBlock b : um.getContent()) {
                if (b instanceof ContentBlock.TextBlock tb && tb.getText() != null) {
                    parts.add(tb.getText());
                }
            }
            return String.join(" ", parts);
        }
        if (this instanceof AssistantMessage am && am.getContent() != null) {
            java.util.List<String> parts = new java.util.ArrayList<>();
            for (ContentBlock b : am.getContent()) {
                if (b instanceof ContentBlock.TextBlock tb && tb.getText() != null) {
                    parts.add(tb.getText());
                }
            }
            return String.join(" ", parts);
        }
        return null;
    }

    /** Message type constants */
    String TYPE_USER = "user";
    String TYPE_ASSISTANT = "assistant";
    String TYPE_SYSTEM = "system";
    String TYPE_PROGRESS = "progress";
    String TYPE_ATTACHMENT = "attachment";
    String TYPE_TOMBSTONE = "tombstone";
    String TYPE_TOOL_USE_SUMMARY = "tool_use_summary";

    /** Create a user message from a text string. */
    static UserMessage userMessage(String text) {
        // Use all-args constructor: (type, uuid, timestamp, content, userModified, origin, toolUseResult, sourceToolAssistantUUID, meta)
        return new UserMessage("user", java.util.UUID.randomUUID().toString(),
            java.time.Instant.now().toString(),
            List.of(new ContentBlock.TextBlock(text)),
            null, null, null, null, null);
    }

    /** Create a user message from content blocks. */
    static UserMessage userMessageFromBlocks(List<ContentBlock> blocks) {
        return new UserMessage("user", java.util.UUID.randomUUID().toString(),
            java.time.Instant.now().toString(),
            blocks, null, null, null, null, null);
    }

    // =========================================================================
    // UserMessage
    // =========================================================================
    @Data
    @Builder
    final class UserMessage implements Message {
        private String type = "user";
        private String uuid;
        private String timestamp;
        private List<ContentBlock> content;

        /** Whether this message was modified by the user */
        private Boolean userModified;

        /** Message origin - how it was created */
        private MessageOrigin origin;

        /** Tool use result content (for tool_result messages) */
        private String toolUseResult;

        /** UUID of the source assistant message that triggered this tool use */
        private String sourceToolAssistantUUID;

        /** Whether this is a meta message (internal, not shown to user) */
        private Boolean meta;

        public static UserMessageBuilder builder() { return new UserMessageBuilder(); }
        public static class UserMessageBuilder {
            private String type = "user"; private String uuid; private String timestamp;
            private List<ContentBlock> content; private Boolean userModified; private MessageOrigin origin;
            private String toolUseResult; private String sourceToolAssistantUUID; private Boolean meta;
            public UserMessageBuilder type(String v) { this.type = v; return this; }
            public UserMessageBuilder uuid(String v) { this.uuid = v; return this; }
            public UserMessageBuilder timestamp(String v) { this.timestamp = v; return this; }
            public UserMessageBuilder content(List<ContentBlock> v) { this.content = v; return this; }
            public UserMessageBuilder userModified(Boolean v) { this.userModified = v; return this; }
            public UserMessageBuilder origin(MessageOrigin v) { this.origin = v; return this; }
            public UserMessageBuilder toolUseResult(String v) { this.toolUseResult = v; return this; }
            public UserMessageBuilder sourceToolAssistantUUID(String v) { this.sourceToolAssistantUUID = v; return this; }
            public UserMessageBuilder meta(Boolean v) { this.meta = v; return this; }
            public UserMessage build() { return new UserMessage(type, uuid, timestamp, content, userModified, origin, toolUseResult, sourceToolAssistantUUID, meta); }
        }

        /** All-args constructor for static factory methods. */
        UserMessage(String type, String uuid, String timestamp, List<ContentBlock> content,
                    Boolean userModified, MessageOrigin origin, String toolUseResult,
                    String sourceToolAssistantUUID, Boolean meta) {
            this.type = type; this.uuid = uuid; this.timestamp = timestamp;
            this.content = content; this.userModified = userModified; this.origin = origin;
            this.toolUseResult = toolUseResult; this.sourceToolAssistantUUID = sourceToolAssistantUUID;
            this.meta = meta;
        }

        @Override
        public String getType() { return type; }
        public void setType(String v) { type = v; }
        @Override
        public String getUuid() { return uuid; }
        public void setUuid(String v) { uuid = v; }
        @Override
        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String v) { timestamp = v; }
        public List<ContentBlock> getContent() { return content; }
        public void setContent(List<ContentBlock> v) { content = v; }
        public Boolean getUserModified() { return userModified; }
        public void setUserModified(Boolean v) { userModified = v; }
        public MessageOrigin getOrigin() { return origin; }
        public void setOrigin(MessageOrigin v) { origin = v; }
        public String getToolUseResult() { return toolUseResult; }
        public void setToolUseResult(String v) { toolUseResult = v; }
        public String getSourceToolAssistantUUID() { return sourceToolAssistantUUID; }
        public void setSourceToolAssistantUUID(String v) { sourceToolAssistantUUID = v; }
        public Boolean getMeta() { return meta; }
        public void setMeta(Boolean v) { meta = v; }

        /** Convenience method: get content as a list, even if null */
        public List<ContentBlock> getContentAsList() {
            return content != null ? content : List.of();
        }

        /** Return a copy with modified content */
        public UserMessage withContent(List<ContentBlock> newContent) {
            return new UserMessage(this.type, this.uuid, this.timestamp, newContent,
                this.userModified, this.origin, this.toolUseResult,
                this.sourceToolAssistantUUID, this.meta);
        }

        public boolean isMeta() {
            return Boolean.TRUE.equals(meta);
        }
    }

    // =========================================================================
    // AssistantMessage
    // =========================================================================
    final class AssistantMessage implements Message {
        private String type = "assistant";
        private String uuid;
        private String timestamp;
        private List<ContentBlock> content;

        @JsonProperty("isApiErrorMessage")
        private Boolean isApiErrorMessage;

        private String model;

        @JsonProperty("stop_reason")
        private String stopReason;

        private Usage usage;

        private String messageId;

        public AssistantMessage() {}

        // Explicit getters/setters
        @Override public String getType() { return type; }
        public void setType(String v) { type = v; }
        @Override public String getUuid() { return uuid; }
        public void setUuid(String v) { uuid = v; }
        @Override public String getTimestamp() { return timestamp; }
        public void setTimestamp(String v) { timestamp = v; }
        public List<ContentBlock> getContent() { return content; }
        public void setContent(List<ContentBlock> v) { content = v; }
        public Boolean getIsApiErrorMessage() { return isApiErrorMessage; }
        public void setIsApiErrorMessage(Boolean v) { isApiErrorMessage = v; }
        public String getModel() { return model; }
        public void setModel(String v) { model = v; }
        public String getStopReason() { return stopReason; }
        public void setStopReason(String v) { stopReason = v; }
        public Usage getUsage() { return usage; }
        public void setUsage(Usage v) { usage = v; }
        public String getMessageId() { return messageId; }
        public void setMessageId(String v) { messageId = v; }

        public AssistantMessage(String type, String uuid, String timestamp,
                List<ContentBlock> content, Boolean isApiErrorMessage, String model,
                String stopReason, Usage usage, String messageId) {
            this.type = type; this.uuid = uuid; this.timestamp = timestamp;
            this.content = content; this.isApiErrorMessage = isApiErrorMessage;
            this.model = model; this.stopReason = stopReason;
            this.usage = usage; this.messageId = messageId;
        }

        /**
         * Extract TODO items from any TodoWrite tool_use block in this message's content.
         * Returns null or empty list if no TodoWrite block is found.
         */
        public java.util.List<String> extractTodoWriteItems() {
            if (content == null) return java.util.List.of();
            for (ContentBlock block : content) {
                if (block instanceof ContentBlock.ToolUseBlock tu
                        && "TodoWrite".equals(tu.getName())) {
                    Object input = tu.getInput();
                    if (input instanceof java.util.Map<?, ?> map) {
                        Object todos = map.get("todos");
                        if (todos instanceof java.util.List<?> list) {
                            java.util.List<String> result = new java.util.ArrayList<>();
                            for (Object item : list) {
                                if (item instanceof String s) result.add(s);
                                else if (item instanceof java.util.Map<?, ?> m) {
                                    Object content = m.get("content");
                                    if (content instanceof String cs) result.add(cs);
                                }
                            }
                            return result;
                        }
                    }
                }
            }
            return java.util.List.of();
        }

        public static AssistantMessageBuilder builder() { return new AssistantMessageBuilder(); }
        public static class AssistantMessageBuilder {
            private String type = "assistant";
            private String uuid;
            private String timestamp;
            private List<ContentBlock> content;
            private Boolean isApiErrorMessage;
            private String model;
            private String stopReason;
            private Usage usage;
            private String messageId;
            public AssistantMessageBuilder type(String v) { this.type = v; return this; }
            public AssistantMessageBuilder uuid(String v) { this.uuid = v; return this; }
            public AssistantMessageBuilder timestamp(String v) { this.timestamp = v; return this; }
            public AssistantMessageBuilder content(List<ContentBlock> v) { this.content = v; return this; }
            public AssistantMessageBuilder isApiErrorMessage(Boolean v) { this.isApiErrorMessage = v; return this; }
            public AssistantMessageBuilder model(String v) { this.model = v; return this; }
            public AssistantMessageBuilder stopReason(String v) { this.stopReason = v; return this; }
            public AssistantMessageBuilder usage(Usage v) { this.usage = v; return this; }
            public AssistantMessageBuilder messageId(String v) { this.messageId = v; return this; }
            public AssistantMessage build() {
                return new AssistantMessage(type, uuid, timestamp, content, isApiErrorMessage,
                    model, stopReason, usage, messageId);
            }
        }

        /** Return a copy with a new UUID. */
        public AssistantMessage withNewUuid() {
            AssistantMessage copy = new AssistantMessage();
            copy.type = this.type;
            copy.uuid = java.util.UUID.randomUUID().toString();
            copy.timestamp = this.timestamp;
            copy.content = this.content;
            copy.isApiErrorMessage = this.isApiErrorMessage;
            copy.model = this.model;
            copy.stopReason = this.stopReason;
            copy.usage = this.usage;
            copy.messageId = this.messageId;
            return copy;
        }
    }

    // =========================================================================
    // SystemMessage - various system-level messages
    // =========================================================================
    @Data
    @lombok.Builder
    final class SystemMessage implements Message {
        private String type = "system";
        private String uuid;
        private String timestamp;
        private SystemMessageLevel level;
        private String content;
        private SystemMessageSubtype subtype;

        // Optional fields for specific subtypes
        private String sessionId;
        private String agentId;
        private Map<String, Object> metadata;
        private String hookLabel; // For stop_hook_summary subtype

        @Override public String getType() { return type; }
        public void setType(String v) { type = v; }
        @Override public String getUuid() { return uuid; }
        public void setUuid(String v) { uuid = v; }
        @Override public String getTimestamp() { return timestamp; }
        public void setTimestamp(String v) { timestamp = v; }
        public SystemMessageLevel getLevel() { return level; }
        public void setLevel(SystemMessageLevel v) { level = v; }
        public String getContent() { return content; }
        public void setContent(String v) { content = v; }
        @Override public String getSubtype() { return subtype != null ? subtype.name() : null; }
        public SystemMessageSubtype getSubtypeEnum() { return subtype; }
        public void setSubtype(SystemMessageSubtype v) { subtype = v; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String v) { sessionId = v; }
        public String getAgentId() { return agentId; }
        public void setAgentId(String v) { agentId = v; }
        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> v) { metadata = v; }
        public String getHookLabel() { return hookLabel; }
        public void setHookLabel(String v) { hookLabel = v; }

        public static SystemMessageBuilder builder() { return new SystemMessageBuilder(); }
        public static class SystemMessageBuilder {
            private String type = "system"; private String uuid; private String timestamp;
            private SystemMessageLevel level; private String content; private SystemMessageSubtype subtype;
            private String sessionId; private String agentId; private Map<String, Object> metadata; private String hookLabel;
            public SystemMessageBuilder type(String v) { this.type = v; return this; }
            public SystemMessageBuilder uuid(String v) { this.uuid = v; return this; }
            public SystemMessageBuilder timestamp(String v) { this.timestamp = v; return this; }
            public SystemMessageBuilder level(SystemMessageLevel v) { this.level = v; return this; }
            public SystemMessageBuilder content(String v) { this.content = v; return this; }
            public SystemMessageBuilder subtype(SystemMessageSubtype v) { this.subtype = v; return this; }
            public SystemMessageBuilder sessionId(String v) { this.sessionId = v; return this; }
            public SystemMessageBuilder agentId(String v) { this.agentId = v; return this; }
            public SystemMessageBuilder metadata(Map<String, Object> v) { this.metadata = v; return this; }
            public SystemMessageBuilder hookLabel(String v) { this.hookLabel = v; return this; }
            public SystemMessage build() {
                SystemMessage m = new SystemMessage();
                m.setType(type); m.setUuid(uuid); m.setTimestamp(timestamp);
                m.setLevel(level); m.setContent(content); m.setSubtype(subtype);
                m.setSessionId(sessionId); m.setAgentId(agentId); m.setMetadata(metadata); m.setHookLabel(hookLabel);
                return m;
            }
        }
    

        public SystemMessage() {}
    }

    // =========================================================================
    // ProgressMessage - tool execution progress
    // =========================================================================
    @Data
    @lombok.Builder
    final class ProgressMessage implements Message {
        @Builder.Default
        private String type = "progress";
        private String uuid;
        private String timestamp;
        private String toolUseId;
        private Object data; // ToolProgressData | HookProgress

        @Override public String getType() { return type; }
        @Override public String getUuid() { return uuid; }
        @Override public String getTimestamp() { return timestamp; }
    }

    // =========================================================================
    // AttachmentMessage - file/memory attachments
    // =========================================================================
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @lombok.Builder
    final class AttachmentMessage implements Message {
        @Builder.Default
        private String type = "attachment";
        private String uuid;
        private String timestamp;
        private List<ContentBlock> content;
        private String attachmentType;
        private String text;
        private Object attachment;

        @Override public String getType() { return type; }
        @Override public String getUuid() { return uuid; }
        @Override public String getTimestamp() { return timestamp; }
        public List<ContentBlock> getContent() { return content; }
        public void setContent(List<ContentBlock> v) { content = v; }
        public String getAttachmentType() { return attachmentType; }
        public void setAttachmentType(String v) { attachmentType = v; }
        public String getText() { return text; }
        public void setText(String v) { text = v; }
        public Object getAttachment() { return attachment; }
        public void setAttachment(Object v) { attachment = v; }
    }

    // =========================================================================
    // TombstoneMessage - placeholder for removed messages
    // =========================================================================
    @Data
    @lombok.Builder
    final class TombstoneMessage implements Message {
        @Builder.Default
        private String type = "tombstone";
        private String uuid;
        private String timestamp;
        private String originalType;

        @Override public String getType() { return type; }
        @Override public String getUuid() { return uuid; }
        @Override public String getTimestamp() { return timestamp; }
    }

    // =========================================================================
    // ToolUseSummaryMessage - summary of tool use for compact display
    // =========================================================================
    @Data
    @lombok.Builder
    final class ToolUseSummaryMessage implements Message {
        @Builder.Default
        private String type = "tool_use_summary";
        private String uuid;
        private String timestamp;
        private String summary;
        private List<String> toolNames;

        @Override public String getType() { return type; }
        @Override public String getUuid() { return uuid; }
        @Override public String getTimestamp() { return timestamp; }
    }

    // =========================================================================
    // Nested types
    // =========================================================================

    enum MessageOrigin {
        USER,
        SYSTEM,
        INJECTION,
        COMPACT,
        ATTACHMENT
    }

    enum SystemMessageLevel {
        INFO,
        WARNING,
        ERROR,
        DEBUG
    }

    enum SystemMessageSubtype {
        INFORMATIONAL,
        API_ERROR,
        LOCAL_COMMAND,
        COMPACT_BOUNDARY,
        MICROCOMPACT_BOUNDARY,
        MEMORY_SAVED,
        PERMISSION_RETRY,
        AWAY_SUMMARY,
        BRIDGE_STATUS,
        AGENTS_KILLED,
        API_METRICS,
        TURN_DURATION,
        STOP_HOOK_SUMMARY,
        SCHEDULED_TASK_FIRE
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    class Usage {
        @JsonProperty("input_tokens")
        private int inputTokens;

        @JsonProperty("output_tokens")
        private int outputTokens;

        @JsonProperty("cache_read_input_tokens")
        private int cacheReadInputTokens;

        @JsonProperty("cache_creation_input_tokens")
        private int cacheCreationInputTokens;

        public int getInputTokens() { return inputTokens; }
        public void setInputTokens(int v) { inputTokens = v; }
        public int getOutputTokens() { return outputTokens; }
        public void setOutputTokens(int v) { outputTokens = v; }
        public int getCacheReadInputTokens() { return cacheReadInputTokens; }
        public void setCacheReadInputTokens(int v) { cacheReadInputTokens = v; }
        public int getCacheCreationInputTokens() { return cacheCreationInputTokens; }
        public void setCacheCreationInputTokens(int v) { cacheCreationInputTokens = v; }
    }
}
