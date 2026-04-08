package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.security.MessageDigest;

/**
 * File operation analytics utilities.
 * Translated from src/utils/fileOperationAnalytics.ts
 */
@Slf4j
public class FileOperationAnalytics {



    private static final int MAX_CONTENT_HASH_SIZE = 100 * 1024; // 100KB

    /**
     * Create a truncated SHA256 hash of a file path.
     * Translated from hashFilePath() in fileOperationAnalytics.ts
     */
    public static String hashFilePath(String filePath) {
        if (filePath == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(filePath.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString().substring(0, 16);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Create a SHA256 hash of file content.
     * Translated from hashFileContent() in fileOperationAnalytics.ts
     */
    public static String hashFileContent(String content) {
        if (content == null) return "";
        try {
            String toHash = content.length() > MAX_CONTENT_HASH_SIZE
                ? content.substring(0, MAX_CONTENT_HASH_SIZE)
                : content;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(toHash.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Log a file operation.
     * Translated from logFileOperation() in fileOperationAnalytics.ts
     */
    public static void logFileOperation(String operation, String tool, String filePath) {
        log.debug("File operation: {} {} {}", operation, tool, hashFilePath(filePath));
    }

    private FileOperationAnalytics() {}
}
