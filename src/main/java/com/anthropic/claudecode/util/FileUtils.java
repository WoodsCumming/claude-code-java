package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * File utility functions.
 * Translated from src/utils/file.ts
 */
@Slf4j
public class FileUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FileUtils.class);


    public static final long MAX_OUTPUT_SIZE = (long) (0.25 * 1024 * 1024); // 0.25MB
    public static final String FILE_NOT_FOUND_CWD_NOTE = "Note: your current working directory is ";

    /**
     * Get file modification time in milliseconds.
     * Translated from getFileModificationTime() in file.ts
     */
    public static long getFileModificationTime(String filePath) {
        try {
            return (long) Math.floor(new File(filePath).lastModified());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Check if a file exists.
     * Translated from pathExists() in file.ts
     */
    public static boolean pathExists(String path) {
        return path != null && new File(path).exists();
    }

    /**
     * Add line numbers to content.
     * Translated from addLineNumbers() in file.ts
     */
    public static String addLineNumbers(String content) {
        if (content == null) return "";
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(String.format("%d\t%s%n", i + 1, lines[i]));
        }
        return sb.toString();
    }

    /**
     * Write text content to a file with proper line endings.
     * Translated from writeTextContent() in file.ts
     */
    public static void writeTextContent(String filePath, String content, String lineEndings) throws Exception {
        String toWrite = content;
        if ("CRLF".equals(lineEndings)) {
            // Normalize CRLF to LF first, then convert back
            toWrite = toWrite.replace("\r\n", "\n").replace("\n", "\r\n");
        }
        Files.writeString(Paths.get(filePath), toWrite, StandardCharsets.UTF_8);
    }

    /**
     * Find a similar file to suggest when a file is not found.
     * Translated from findSimilarFile() in file.ts
     */
    public static Optional<String> findSimilarFile(String filePath) {
        if (filePath == null) return Optional.empty();

        File file = new File(filePath);
        String name = file.getName();
        File parent = file.getParentFile();

        if (parent == null || !parent.exists()) return Optional.empty();

        // Look for files with similar names
        File[] siblings = parent.listFiles();
        if (siblings == null) return Optional.empty();

        String nameLower = name.toLowerCase();
        for (File sibling : siblings) {
            if (sibling.getName().toLowerCase().contains(nameLower)
                || nameLower.contains(sibling.getName().toLowerCase())) {
                return Optional.of(sibling.getAbsolutePath());
            }
        }

        return Optional.empty();
    }

    /**
     * Suggest a path under the current working directory.
     * Translated from suggestPathUnderCwd() in file.ts
     */
    public static Optional<String> suggestPathUnderCwd(String filePath) {
        if (filePath == null) return Optional.empty();

        String cwd = System.getProperty("user.dir");
        File suggested = new File(cwd, new File(filePath).getName());

        if (suggested.exists()) {
            return Optional.of(suggested.getAbsolutePath());
        }

        return Optional.empty();
    }

    /**
     * Check if a file is within the read size limit.
     */
    public static boolean isFileWithinReadSizeLimit(String filePath, long maxBytes) {
        if (filePath == null) return false;
        File file = new File(filePath);
        return file.exists() && file.length() <= maxBytes;
    }

    /**
     * Detect line endings in a string.
     */
    public static String detectLineEndings(String content) {
        if (content == null) return "LF";
        if (content.contains("\r\n")) return "CRLF";
        if (content.contains("\r")) return "CR";
        return "LF";
    }

    private FileUtils() {}
}
