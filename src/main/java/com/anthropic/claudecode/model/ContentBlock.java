package com.anthropic.claudecode.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;
import java.util.Map;

/**
 * Content block types for messages.
 * Translated from @anthropic-ai/sdk ContentBlock types.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ContentBlock.TextBlock.class, name = "text"),
})
public abstract sealed class ContentBlock permits
        ContentBlock.TextBlock,
        ContentBlock.ThinkingBlock,
        ContentBlock.RedactedThinkingBlock,
        ContentBlock.ToolUseBlock,
        ContentBlock.ToolResultBlock,
        ContentBlock.ImageBlock,
        ContentBlock.DocumentBlock {

    public abstract String getType();

    /** Create a TextBlock. */
    public static TextBlock text(String text) {
        return new TextBlock(text);
    }

    // =========================================================================
    // TextBlock
    // =========================================================================
    public static final class TextBlock extends ContentBlock {
        private String type = "text";
        private String text;

        @JsonProperty("cache_control")
        private CacheControl cacheControl;

        public TextBlock() {}
        public TextBlock(String text) { this.text = text; }
        public TextBlock(String type, String text, CacheControl cacheControl) {
            this.type = type; this.text = text; this.cacheControl = cacheControl;
        }

        @Override public String getType() { return type; }
        public void setType(String v) { type = v; }
        public String getText() { return text; }
        public void setText(String v) { text = v; }
        public CacheControl getCacheControl() { return cacheControl; }
        public void setCacheControl(CacheControl v) { cacheControl = v; }
    }

    // =========================================================================
    // ThinkingBlock
    // =========================================================================
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static final class ThinkingBlock extends ContentBlock {
        private String type = "thinking";
        private String thinking;

        @JsonProperty("signature")
        private String signature;

        @Override
        public String getType() { return type; }
        public String getThinking() { return thinking; }
        public void setThinking(String v) { thinking = v; }
        public String getSignature() { return signature; }
        public void setSignature(String v) { signature = v; }
    }

    // =========================================================================
    // RedactedThinkingBlock
    // =========================================================================
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static final class RedactedThinkingBlock extends ContentBlock {
        private String type = "redacted_thinking";
        private String data;

        @Override
        public String getType() { return type; }
        public String getData() { return data; }
        public void setData(String v) { data = v; }
    }

    // =========================================================================
    // ToolUseBlock
    // =========================================================================
    public static final class ToolUseBlock extends ContentBlock {
        private String type = "tool_use";
        private String id;
        private String name;
        private Map<String, Object> input;

        public ToolUseBlock() {}
        public ToolUseBlock(String type, String id, String name, Map<String, Object> input) {
            this.type = type; this.id = id; this.name = name; this.input = input;
        }
        @Override public String getType() { return type; }
        public void setType(String v) { type = v; }
        public String getId() { return id; }
        public void setId(String v) { id = v; }
        public String getName() { return name; }
        public void setName(String v) { name = v; }
        public Map<String, Object> getInput() { return input; }
        public void setInput(Map<String, Object> v) { input = v; }

        /**
         * Extract file path from the tool input (used for Read tool).
         */
        public String getFilePath() {
            if (input == null) return null;
            Object path = input.get("file_path");
            if (path instanceof String s) return s;
            path = input.get("path");
            if (path instanceof String s) return s;
            return null;
        }
    }

    // =========================================================================
    // ToolResultBlock
    // =========================================================================
    public static final class ToolResultBlock extends ContentBlock {
        private String type = "tool_result";

        @JsonProperty("tool_use_id")
        private String toolUseId;

        private Object content; // String or List<ContentBlock>

        @JsonProperty("is_error")
        private Boolean isError;

        public ToolResultBlock() {}

        public ToolResultBlock(String type, String toolUseId, Object content, Boolean isError) {
            this.type = type; this.toolUseId = toolUseId; this.content = content; this.isError = isError;
        }

        public String getToolUseId() { return toolUseId; }
        public void setToolUseId(String v) { this.toolUseId = v; }
        public Object getContent() { return content; }
        public void setContent(Object v) { this.content = v; }
        @Override public String getType() { return type; }
        public void setType(String v) { this.type = v; }
        public Boolean getIsError() { return isError; }
        public void setIsError(Boolean v) { this.isError = v; }

        /** Get content as a string if it is one. */
        public String getContentAsString() {
            if (content instanceof String s) return s;
            return content != null ? content.toString() : null;
        }

        /** Return a copy with different string content. */
        public ToolResultBlock withContent(String newContent) {
            ToolResultBlock copy = new ToolResultBlock();
            copy.type = this.type;
            copy.toolUseId = this.toolUseId;
            copy.content = newContent;
            copy.isError = this.isError;
            return copy;
        }

        /** Return a copy with different list content. */
        public ToolResultBlock withContent(List<?> newContent) {
            ToolResultBlock copy = new ToolResultBlock();
            copy.type = this.type;
            copy.toolUseId = this.toolUseId;
            copy.content = newContent;
            copy.isError = this.isError;
            return copy;
        }
    }

    // =========================================================================
    // ImageBlock
    // =========================================================================
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static final class ImageBlock extends ContentBlock {
        private String type = "image";
        private ImageSource source;

        @JsonProperty("cache_control")
        private CacheControl cacheControl;

        @Override public String getType() { return type; }

        @Data
        @lombok.NoArgsConstructor
        @lombok.AllArgsConstructor
        public static class ImageSource {
            private String type; // "base64" | "url"

            @JsonProperty("media_type")
            private String mediaType;

            private String data;
            private String url;

        public String getMediaType() { return mediaType; }
        public void setMediaType(String v) { mediaType = v; }
        public String getData() { return data; }
        public void setData(String v) { data = v; }
        public String getUrl() { return url; }
        public void setUrl(String v) { url = v; }
        }
    }

    // =========================================================================
    // DocumentBlock
    // =========================================================================
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static final class DocumentBlock extends ContentBlock {
        private String type = "document";
        private DocumentSource source;
        private String title;
        private String context;

        @JsonProperty("cache_control")
        private CacheControl cacheControl;

        @Override public String getType() { return type; }

        @Data
        @lombok.NoArgsConstructor
        @lombok.AllArgsConstructor
        public static class DocumentSource {
            private String type; // "base64" | "url" | "text"

            @JsonProperty("media_type")
            private String mediaType;

            private String data;
            private String url;
            private String text;

        }
    }

    // =========================================================================
    // CacheControl
    // =========================================================================
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class CacheControl {
        private String type; // "ephemeral"

    }

    // =========================================================================
    // Static factory methods (defined after inner classes to ensure Lombok has processed them)
    // =========================================================================

    /** Create a ToolResultBlock with list content. */
    public static ToolResultBlock toolResult(String toolUseId, java.util.List<?> contentBlocks) {
        ToolResultBlock block = new ToolResultBlock("tool_result", toolUseId, contentBlocks, null);
        return block;
    }

    /** Create a ToolResultBlock with string content. */
    public static ToolResultBlock toolResult(String toolUseId, String content) {
        ToolResultBlock block = new ToolResultBlock("tool_result", toolUseId, content, null);
        return block;
    }
}
