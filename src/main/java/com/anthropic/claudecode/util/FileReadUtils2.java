package com.anthropic.claudecode.util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * File read utilities for encoding and line ending detection.
 * Translated from src/utils/fileRead.ts
 */
public class FileReadUtils2 {

    /**
     * Detect the file encoding.
     * Translated from detectEncodingForResolvedPath() in fileRead.ts
     */
    public static String detectEncoding(String filePath) {
        try {
            byte[] header = new byte[4];
            int bytesRead;

            try (FileInputStream fis = new FileInputStream(filePath)) {
                bytesRead = fis.read(header);
            }

            if (bytesRead == 0) return "UTF-8"; // Empty file

            // Check for UTF-16 LE BOM (FF FE)
            if (bytesRead >= 2 && (header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xFE) {
                return "UTF-16LE";
            }

            // Check for UTF-8 BOM (EF BB BF)
            if (bytesRead >= 3 && (header[0] & 0xFF) == 0xEF
                && (header[1] & 0xFF) == 0xBB && (header[2] & 0xFF) == 0xBF) {
                return "UTF-8";
            }

            return "UTF-8"; // Default
        } catch (Exception e) {
            return "UTF-8";
        }
    }

    /**
     * Detect line endings in a string.
     * Translated from detectLineEndingsForString() in fileRead.ts
     */
    public static String detectLineEndings(String content) {
        if (content == null) return "LF";

        int crlfCount = 0;
        int lfCount = 0;

        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                if (i > 0 && content.charAt(i - 1) == '\r') {
                    crlfCount++;
                } else {
                    lfCount++;
                }
            }
        }

        return crlfCount > lfCount ? "CRLF" : "LF";
    }

    /**
     * Read file with metadata (content, line endings, encoding).
     */
    public static FileReadResult readFileSyncWithMetadata(String filePath) {
        try {
            String encoding = detectEncoding(filePath);
            byte[] bytes = Files.readAllBytes(Paths.get(filePath));
            String content = new String(bytes, encoding);
            String lineEndings = detectLineEndings(content);
            long mtime = new File(filePath).lastModified();

            return new FileReadResult(content, encoding, lineEndings, mtime);
        } catch (Exception e) {
            throw new RuntimeException("Could not read file: " + filePath, e);
        }
    }

    public record FileReadResult(
        String content,
        String encoding,
        String lineEndings,
        long mtime
    ) {}

    private FileReadUtils2() {}
}
