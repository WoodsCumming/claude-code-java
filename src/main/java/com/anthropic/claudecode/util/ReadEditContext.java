package com.anthropic.claudecode.util;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Read-edit context utilities for finding context around a match in a file.
 * Translated from src/utils/readEditContext.ts
 *
 * Finds a needle in a file and returns surrounding context lines.
 */
@Slf4j
public class ReadEditContext {



    public static final int CHUNK_SIZE = 8 * 1024;
    public static final int MAX_SCAN_BYTES = 10 * 1024 * 1024;

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class EditContext {
        /** Slice of the file: contextLines before/after the match. */
        private String content;
        /** 1-based line number of content's first line in the original file. */
        private int lineOffset;
        /** True if MAX_SCAN_BYTES was hit without finding the needle. */
        private boolean truncated;

        public String getContent() { return content; }
        public void setContent(String v) { content = v; }
        public int getLineOffset() { return lineOffset; }
        public void setLineOffset(int v) { lineOffset = v; }
        public boolean isTruncated() { return truncated; }
        public void setTruncated(boolean v) { truncated = v; }
    
    }

    /**
     * Find a needle in a file and return surrounding context.
     * Translated from readEditContext() in readEditContext.ts
     *
     * @return EditContext with match and surrounding lines, or null if file not found
     */
    public static EditContext readEditContext(String path, String needle, int contextLines) {
        try {
            String content = Files.readString(Paths.get(path));
            if (content.length() > MAX_SCAN_BYTES) {
                content = content.substring(0, MAX_SCAN_BYTES);
            }

            int matchIndex = content.indexOf(needle);
            if (matchIndex < 0) {
                return new EditContext("", 0, content.length() >= MAX_SCAN_BYTES);
            }

            // Find line boundaries
            String[] lines = content.split("\n", -1);
            int currentPos = 0;
            int matchLine = 0;

            for (int i = 0; i < lines.length; i++) {
                if (currentPos + lines[i].length() >= matchIndex) {
                    matchLine = i;
                    break;
                }
                currentPos += lines[i].length() + 1; // +1 for newline
            }

            // Extract context
            int startLine = Math.max(0, matchLine - contextLines);
            int endLine = Math.min(lines.length - 1, matchLine + contextLines);

            StringBuilder sb = new StringBuilder();
            for (int i = startLine; i <= endLine; i++) {
                sb.append(lines[i]).append("\n");
            }

            return new EditContext(sb.toString(), startLine + 1, false);

        } catch (java.nio.file.NoSuchFileException e) {
            return null;
        } catch (Exception e) {
            log.debug("Could not read edit context: {}", e.getMessage());
            return null;
        }
    }

    private ReadEditContext() {}
}
