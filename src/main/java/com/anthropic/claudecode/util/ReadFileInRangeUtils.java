package com.anthropic.claudecode.util;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Line-oriented file reader with fast-path and streaming-path implementations.
 * Translated from src/utils/readFileInRange.ts
 *
 * Returns lines [offset, offset + maxLines) from a file.
 *
 * Fast path (regular files smaller than FAST_PATH_MAX_SIZE):
 *   Reads the whole file into memory and splits lines. Faster for typical sources.
 *
 * Streaming path (large files, or when fast-path size limit is exceeded):
 *   Reads line-by-line, accumulating only the requested range, so reading line 1
 *   of a huge file does not balloon heap usage.
 *
 * Both paths:
 *   - Strip UTF-8 BOM.
 *   - Strip trailing {@code \r} (CRLF → LF).
 *   - Respect {@code maxBytes} in either throw-mode (legacy) or truncate-mode.
 */
@Slf4j
public class ReadFileInRangeUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ReadFileInRangeUtils.class);


    /** Maximum file size to read into memory in one shot (10 MB). */
    private static final long FAST_PATH_MAX_SIZE = 10L * 1024 * 1024;

    // -------------------------------------------------------------------------
    // Public result type
    // -------------------------------------------------------------------------

    /**
     * Result returned by {@link #readFileInRange}.
     * Translated from ReadFileRangeResult in readFileInRange.ts
     */
    @Data
    @Builder
    public static class ReadFileRangeResult {
        /** The selected lines joined with {@code \n}. */
        private String content;
        /** Number of lines in {@code content}. */
        private int lineCount;
        /** Total number of lines in the file. */
        private int totalLines;
        /** Total file size in bytes. */
        private long totalBytes;
        /** Byte length of {@code content} in UTF-8. */
        private long readBytes;
        /** Last-modified time of the file in epoch milliseconds. */
        private long mtimeMs;
        /** {@code true} when output was clipped to maxBytes under truncate mode. */
        private boolean truncatedByBytes;
    }

    // -------------------------------------------------------------------------
    // Exception
    // -------------------------------------------------------------------------

    /**
     * Thrown when the file size exceeds {@code maxBytes} and truncate-mode is off.
     * Translated from FileTooLargeError in readFileInRange.ts
     */
    public static class FileTooLargeException extends RuntimeException {
        private final long sizeInBytes;
        private final long maxSizeBytes;

        public FileTooLargeException(long sizeInBytes, long maxSizeBytes) {
            super(String.format(
                    "File content (%d bytes) exceeds maximum allowed size (%d bytes). "
                    + "Use offset and limit parameters to read specific portions of the file, "
                    + "or search for specific content instead of reading the whole file.",
                    sizeInBytes, maxSizeBytes));
            this.sizeInBytes = sizeInBytes;
            this.maxSizeBytes = maxSizeBytes;
        }

        public long getSizeInBytes() { return sizeInBytes; }
        public long getMaxSizeBytes() { return maxSizeBytes; }
    }

    // -------------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------------

    /**
     * Read lines [offset, offset + maxLines) from the file at {@code filePath}.
     *
     * @param filePath           absolute path to the file
     * @param offset             zero-based first line to include
     * @param maxLines           maximum number of lines to return ({@code null} = unlimited)
     * @param maxBytes           byte limit ({@code null} = unlimited)
     * @param truncateOnByteLimit if {@code true}, cap output at maxBytes instead of throwing
     * @return the selected content and metadata
     * @throws FileTooLargeException when the file exceeds maxBytes and truncate-mode is off
     * @throws IOException           on I/O errors
     * Translated from readFileInRange() in readFileInRange.ts
     */
    public static ReadFileRangeResult readFileInRange(
            Path filePath,
            int offset,
            Integer maxLines,
            Long maxBytes,
            boolean truncateOnByteLimit
    ) throws IOException {

        BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);

        if (attrs.isDirectory()) {
            throw new IOException("EISDIR: illegal operation on a directory, read '" + filePath + "'");
        }

        long fileSize = attrs.size();
        long mtimeMs = attrs.lastModifiedTime().toMillis();

        if (attrs.isRegularFile() && fileSize < FAST_PATH_MAX_SIZE) {
            // Guard byte limit before reading the whole file
            if (!truncateOnByteLimit && maxBytes != null && fileSize > maxBytes) {
                throw new FileTooLargeException(fileSize, maxBytes);
            }
            String text = Files.readString(filePath, StandardCharsets.UTF_8);
            return fastPath(text, fileSize, mtimeMs, offset, maxLines,
                    truncateOnByteLimit ? maxBytes : null);
        }

        return streamingPath(filePath, fileSize, mtimeMs, offset, maxLines, maxBytes,
                truncateOnByteLimit);
    }

    /** Convenience overload without truncate-mode. */
    public static ReadFileRangeResult readFileInRange(
            Path filePath,
            int offset,
            Integer maxLines,
            Long maxBytes
    ) throws IOException {
        return readFileInRange(filePath, offset, maxLines, maxBytes, false);
    }

    // -------------------------------------------------------------------------
    // Fast path — whole file in memory
    // -------------------------------------------------------------------------

    private static ReadFileRangeResult fastPath(
            String raw,
            long totalBytes,
            long mtimeMs,
            int offset,
            Integer maxLines,
            Long truncateAtBytes
    ) {
        // Strip BOM
        String text = (raw.length() > 0 && raw.charAt(0) == '\uFEFF') ? raw.substring(1) : raw;

        int endLine = (maxLines != null) ? offset + maxLines : Integer.MAX_VALUE;

        List<String> selectedLines = new ArrayList<>();
        int lineIndex = 0;
        int startPos = 0;
        long selectedBytes = 0;
        boolean truncatedByBytes = false;
        int newlinePos;

        while ((newlinePos = text.indexOf('\n', startPos)) != -1) {
            if (lineIndex >= offset && lineIndex < endLine && !truncatedByBytes) {
                String line = stripCr(text.substring(startPos, newlinePos));
                if (truncateAtBytes != null) {
                    long sep = selectedLines.isEmpty() ? 0 : 1;
                    long next = selectedBytes + sep + line.getBytes(StandardCharsets.UTF_8).length;
                    if (next > truncateAtBytes) {
                        truncatedByBytes = true;
                    } else {
                        selectedBytes = next;
                        selectedLines.add(line);
                    }
                } else {
                    selectedLines.add(line);
                }
            }
            lineIndex++;
            startPos = newlinePos + 1;
        }

        // Final fragment (no trailing newline)
        if (lineIndex >= offset && lineIndex < endLine && !truncatedByBytes) {
            String line = stripCr(text.substring(startPos));
            if (truncateAtBytes != null) {
                long sep = selectedLines.isEmpty() ? 0 : 1;
                long next = selectedBytes + sep + line.getBytes(StandardCharsets.UTF_8).length;
                if (next > truncateAtBytes) {
                    truncatedByBytes = true;
                } else {
                    selectedLines.add(line);
                }
            } else {
                selectedLines.add(line);
            }
        }
        lineIndex++;

        String content = String.join("\n", selectedLines);
        return ReadFileRangeResult.builder()
                .content(content)
                .lineCount(selectedLines.size())
                .totalLines(lineIndex)
                .totalBytes(totalBytes)
                .readBytes(content.getBytes(StandardCharsets.UTF_8).length)
                .mtimeMs(mtimeMs)
                .truncatedByBytes(truncatedByBytes)
                .build();
    }

    // -------------------------------------------------------------------------
    // Streaming path — line-by-line via BufferedReader
    // -------------------------------------------------------------------------

    private static ReadFileRangeResult streamingPath(
            Path filePath,
            long fileSize,
            long mtimeMs,
            int offset,
            Integer maxLines,
            Long maxBytes,
            boolean truncateOnByteLimit
    ) throws IOException {

        int endLine = (maxLines != null) ? offset + maxLines : Integer.MAX_VALUE;

        List<String> selectedLines = new ArrayList<>();
        int currentLineIndex = 0;
        long totalBytesRead = 0;
        long selectedBytes = 0;
        boolean truncatedByBytes = false;
        boolean isFirstLine = true;

        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String rawLine;
            while ((rawLine = reader.readLine()) != null) {
                // Strip BOM from the very first line
                if (isFirstLine) {
                    isFirstLine = false;
                    if (!rawLine.isEmpty() && rawLine.charAt(0) == '\uFEFF') {
                        rawLine = rawLine.substring(1);
                    }
                }

                // readLine() already strips the line terminator; strip \r just in case
                String line = stripCr(rawLine);
                long lineBytes = (line + "\n").getBytes(StandardCharsets.UTF_8).length;
                totalBytesRead += lineBytes;

                // Legacy throw-mode byte guard
                if (!truncateOnByteLimit && maxBytes != null && totalBytesRead > maxBytes) {
                    throw new FileTooLargeException(totalBytesRead, maxBytes);
                }

                if (currentLineIndex >= offset && currentLineIndex < endLine && !truncatedByBytes) {
                    if (truncateOnByteLimit && maxBytes != null) {
                        long sep = selectedLines.isEmpty() ? 0 : 1;
                        long next = selectedBytes + sep + line.getBytes(StandardCharsets.UTF_8).length;
                        if (next > maxBytes) {
                            truncatedByBytes = true;
                            // Collapse endLine so we stop accumulating but keep counting total lines
                            endLine = currentLineIndex;
                        } else {
                            selectedBytes = next;
                            selectedLines.add(line);
                        }
                    } else {
                        selectedLines.add(line);
                    }
                }
                currentLineIndex++;
            }
        }

        String content = String.join("\n", selectedLines);
        return ReadFileRangeResult.builder()
                .content(content)
                .lineCount(selectedLines.size())
                .totalLines(currentLineIndex)
                .totalBytes(fileSize)
                .readBytes(content.getBytes(StandardCharsets.UTF_8).length)
                .mtimeMs(mtimeMs)
                .truncatedByBytes(truncatedByBytes)
                .build();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String stripCr(String line) {
        if (line != null && !line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
            return line.substring(0, line.length() - 1);
        }
        return line != null ? line : "";
    }

    private ReadFileInRangeUtils() {}
}
