package com.anthropic.claudecode.util;

import java.nio.file.*;
import java.util.*;

/**
 * Memory file detection utilities.
 * Translated from src/utils/memoryFileDetection.ts
 *
 * Detects if file paths are memory/session-related files.
 */
public class MemoryFileDetection {

    /**
     * Check if a path is an auto-memory file.
     * Translated from isAutoMemFile() in memoryFileDetection.ts
     */
    public static boolean isAutoMemFile(String filePath) {
        if (filePath == null) return false;
        String normalized = normalizePath(filePath);
        String autoMemBase = normalizePath(EnvUtils.getClaudeConfigHomeDir() + "/memory");
        return normalized.startsWith(autoMemBase);
    }

    /**
     * Detect the session file type.
     * Translated from detectSessionFileType() in memoryFileDetection.ts
     */
    public static Optional<String> detectSessionFileType(String filePath) {
        if (filePath == null) return Optional.empty();

        String normalized = normalizePath(filePath);
        String claudeDir = normalizePath(EnvUtils.getClaudeConfigHomeDir());

        if (!normalized.startsWith(claudeDir)) return Optional.empty();

        if (normalized.contains("/sessions/")) return Optional.of("session");
        if (normalized.contains("/memory/")) return Optional.of("memory");
        if (normalized.contains("/projects/")) return Optional.of("project");

        return Optional.of("claude_config");
    }

    private static String normalizePath(String path) {
        if (path == null) return "";
        return Paths.get(path).normalize().toString().replace("\\", "/");
    }

    private MemoryFileDetection() {}
}
