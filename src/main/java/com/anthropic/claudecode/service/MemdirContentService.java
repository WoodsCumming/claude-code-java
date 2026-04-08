package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Memory directory content service.
 * Translated from src/memdir/memdir.ts
 *
 * Manages memory file content and prompts.
 */
@Slf4j
@Service
public class MemdirContentService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MemdirContentService.class);


    public static final String ENTRYPOINT_NAME = "MEMORY.md";
    public static final int MAX_ENTRYPOINT_LINES = 200;
    public static final int MAX_ENTRYPOINT_BYTES = 25_000;

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class EntrypointTruncation {
        private String content;
        private int lineCount;
        private int byteCount;
        private boolean wasLineTruncated;
        private boolean wasByteTruncated;

        public String getContent() { return content; }
        public void setContent(String v) { content = v; }
        public int getLineCount() { return lineCount; }
        public void setLineCount(int v) { lineCount = v; }
        public int getByteCount() { return byteCount; }
        public void setByteCount(int v) { byteCount = v; }
        public boolean isWasLineTruncated() { return wasLineTruncated; }
        public void setWasLineTruncated(boolean v) { wasLineTruncated = v; }
        public boolean isWasByteTruncated() { return wasByteTruncated; }
        public void setWasByteTruncated(boolean v) { wasByteTruncated = v; }
    

    }

    /**
     * Truncate MEMORY.md content to line and byte caps.
     * Translated from truncateEntrypointContent() in memdir.ts
     */
    public EntrypointTruncation truncateEntrypointContent(String raw) {
        if (raw == null) {
            EntrypointTruncation result = new EntrypointTruncation();
            result.setContent("");
            return result;
        }

        String trimmed = raw.trim();
        String[] lines = trimmed.split("\n", -1);

        boolean wasLineTruncated = false;
        boolean wasByteTruncated = false;
        String content = trimmed;

        // Line truncation
        if (lines.length > MAX_ENTRYPOINT_LINES) {
            String[] truncated = Arrays.copyOf(lines, MAX_ENTRYPOINT_LINES);
            content = String.join("\n", truncated);
            wasLineTruncated = true;
        }

        // Byte truncation
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_ENTRYPOINT_BYTES) {
            // Find last newline before the byte cap
            String truncated = new String(bytes, 0, MAX_ENTRYPOINT_BYTES, StandardCharsets.UTF_8);
            int lastNewline = truncated.lastIndexOf('\n');
            if (lastNewline > 0) {
                content = truncated.substring(0, lastNewline);
            } else {
                content = truncated;
            }
            wasByteTruncated = true;
        }

        // Add truncation warning
        if (wasLineTruncated || wasByteTruncated) {
            String warning = wasLineTruncated
                ? "\n[Memory truncated at " + MAX_ENTRYPOINT_LINES + " lines]"
                : "\n[Memory truncated at " + MAX_ENTRYPOINT_BYTES + " bytes]";
            content += warning;
        }

        EntrypointTruncation result = new EntrypointTruncation();
        result.setContent(content);
        result.setLineCount(content.split("\n", -1).length);
        result.setByteCount(content.getBytes(StandardCharsets.UTF_8).length);
        result.setWasLineTruncated(wasLineTruncated);
        result.setWasByteTruncated(wasByteTruncated);
        return result;
    }

    /**
     * Build the memory prompt.
     * Translated from buildMemoryPrompt() in memdir.ts
     */
    public String buildMemoryPrompt(String memoryContent) {
        if (memoryContent == null || memoryContent.isBlank()) return "";

        EntrypointTruncation truncated = truncateEntrypointContent(memoryContent);
        return "<memory>\n" + truncated.getContent() + "\n</memory>";
    }
}
