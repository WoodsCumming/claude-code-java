package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * File reading utilities.
 * Translated from src/utils/readFileInRange.ts
 *
 * Provides efficient file reading with line range support.
 */
@Slf4j
public class FileReadUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FileReadUtils.class);


    private static final long FAST_PATH_MAX_SIZE = 10 * 1024 * 1024; // 10 MB

    /**
     * Read a range of lines from a file.
     * Translated from readFileInRange() in readFileInRange.ts
     *
     * @param filePath  Path to the file
     * @param offset    Starting line (0-indexed)
     * @param maxLines  Maximum number of lines to read
     * @param maxBytes  Maximum bytes to read (0 = no limit)
     * @return File range result
     */
    public static ReadFileRangeResult readFileInRange(
            String filePath,
            int offset,
            int maxLines,
            long maxBytes) throws Exception {

        File file = new File(filePath);
        long fileSize = file.length();

        if (maxBytes > 0 && fileSize > maxBytes) {
            throw new FileTooLargeError(fileSize, maxBytes);
        }

        // Read the file
        String content;
        if (fileSize <= FAST_PATH_MAX_SIZE) {
            // Fast path: read entire file
            byte[] bytes = Files.readAllBytes(file.toPath());
            content = stripBOM(new String(bytes, StandardCharsets.UTF_8));
        } else {
            // Streaming path: read line by line
            content = readLargeFile(file, offset, maxLines, maxBytes);
        }

        // Normalize line endings
        content = content.replace("\r\n", "\n").replace("\r", "\n");

        String[] allLines = content.split("\n", -1);
        int totalLines = allLines.length;

        // Apply range
        int startLine = Math.min(offset, totalLines);
        int endLine = maxLines > 0
            ? Math.min(startLine + maxLines, totalLines)
            : totalLines;

        StringBuilder sb = new StringBuilder();
        for (int i = startLine; i < endLine; i++) {
            sb.append(allLines[i]).append("\n");
        }

        String rangeContent = sb.toString();
        if (rangeContent.endsWith("\n") && !content.endsWith("\n")) {
            rangeContent = rangeContent.substring(0, rangeContent.length() - 1);
        }

        return new ReadFileRangeResult(
            rangeContent,
            endLine - startLine,
            totalLines,
            fileSize,
            rangeContent.getBytes(StandardCharsets.UTF_8).length,
            file.lastModified(),
            false
        );
    }

    private static String readLargeFile(File file, int offset, int maxLines, long maxBytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        int lineCount = 0;
        int skipped = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (skipped < offset) {
                    skipped++;
                    continue;
                }
                if (maxLines > 0 && lineCount >= maxLines) break;

                sb.append(line).append("\n");
                lineCount++;

                if (maxBytes > 0 && sb.length() > maxBytes) break;
            }
        }

        return sb.toString();
    }

    private static String stripBOM(String s) {
        if (s != null && s.startsWith("\uFEFF")) {
            return s.substring(1);
        }
        return s;
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    public record ReadFileRangeResult(
        String content,
        int lineCount,
        int totalLines,
        long totalBytes,
        int readBytes,
        long mtimeMs,
        boolean truncatedByBytes
    ) {}

    public static class FileTooLargeError extends RuntimeException {
        private final long sizeInBytes;
        private final long maxSizeBytes;

        public FileTooLargeError(long sizeInBytes, long maxSizeBytes) {
            super("File too large: " + FormatUtils.formatFileSize(sizeInBytes)
                + " (max " + FormatUtils.formatFileSize(maxSizeBytes) + ")");
            this.sizeInBytes = sizeInBytes;
            this.maxSizeBytes = maxSizeBytes;
        }

        public long getSizeInBytes() { return sizeInBytes; }
        public long getMaxSizeBytes() { return maxSizeBytes; }
    }

    private FileReadUtils() {}
}
