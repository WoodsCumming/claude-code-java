package com.anthropic.claudecode.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Result from a tool execution.
 * Translated from src/Tool.ts ToolResult type.
 */
@Data
@lombok.NoArgsConstructor
@lombok.AllArgsConstructor
public class ToolResult<T> {

    /** The data returned by the tool */
    T data;

    /**
     * Optional new messages to append to the conversation.
     * These can be UserMessage, AssistantMessage, AttachmentMessage, or SystemMessage.
     */
    List<Message> newMessages;

    /**
     * Optional context modifier for non-concurrency-safe tools.
     * Allows tools to modify the ToolUseContext after execution.
     */
    UnaryOperator<ToolUseContext> contextModifier;

    /**
     * Optional MCP protocol metadata.
     */
    McpMeta mcpMeta;

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class McpMeta {
        private Map<String, Object> meta;
        private Map<String, Object> structuredContent;

        public Map<String, Object> getMeta() { return meta; }
        public void setMeta(Map<String, Object> v) { meta = v; }
        public Map<String, Object> getStructuredContent() { return structuredContent; }
        public void setStructuredContent(Map<String, Object> v) { structuredContent = v; }
    }

    /**
     * Factory method for a simple result with just data.
     */
    public static <T> ToolResult<T> of(T data) {
        ToolResult<T> r = new ToolResult<>();
        r.data = data;
        return r;
    }

    /**
     * Factory method for a result with data and new messages.
     */
    public static <T> ToolResult<T> of(T data, List<Message> newMessages) {
        ToolResult<T> r = new ToolResult<>();
        r.data = data;
        r.newMessages = newMessages;
        return r;
    }

    /**
     * Factory method for a successful result.
     */
    public static <T> ToolResult<T> success(T data) {
        ToolResult<T> r = new ToolResult<>();
        r.data = data;
        return r;
    }

    /**
     * Factory method for an error result.
     * The error message is wrapped in an ErrorData object.
     */
    @SuppressWarnings("unchecked")
    public static <T> ToolResult<T> error(String message) {
        ToolResult<T> r = new ToolResult<>();
        r.data = (T) message;
        return r;
    }

}
