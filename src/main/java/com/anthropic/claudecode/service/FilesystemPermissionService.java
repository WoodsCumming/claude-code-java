package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.File;
import java.nio.file.*;
import java.util.*;

/**
 * Filesystem permission checking service.
 * Translated from src/utils/permissions/filesystem.ts
 *
 * Checks if file operations are allowed based on permission rules
 * and dangerous file/directory patterns.
 */
@Slf4j
@Service
public class FilesystemPermissionService {



    /**
     * Dangerous files that should be protected from auto-editing.
     * Translated from DANGEROUS_FILES in filesystem.ts
     */
    public static final Set<String> DANGEROUS_FILES = Set.of(
        ".gitconfig",
        ".gitmodules",
        ".bashrc",
        ".bash_profile",
        ".zshrc",
        ".zprofile",
        ".profile",
        ".ripgreprc",
        ".mcp.json",
        ".claude.json"
    );

    /**
     * Dangerous directories that should be protected from auto-editing.
     * Translated from DANGEROUS_DIRECTORIES in filesystem.ts
     */
    public static final Set<String> DANGEROUS_DIRECTORIES = Set.of(
        ".git",
        ".vscode",
        ".idea",
        ".claude"
    );

    /**
     * Check if a file path is safe to read.
     * Translated from checkReadPermissionForTool() in filesystem.ts
     */
    public PermissionResult checkReadPermission(
            String filePath,
            ToolPermissionContext context) {

        if (filePath == null) {
            return PermissionResult.DenyDecision.builder()
                .message("File path is required")
                .build();
        }

        // Expand and normalize path
        String normalizedPath = normalizePath(filePath);

        // Check for path traversal
        if (containsPathTraversal(normalizedPath)) {
            return PermissionResult.DenyDecision.builder()
                .message("Path traversal detected: " + filePath)
                .build();
        }

        // In default mode, reads are generally allowed
        return PermissionResult.AllowDecision.builder().build();
    }

    /**
     * Check if a file path is safe to write.
     * Translated from checkWritePermissionForTool() in filesystem.ts
     */
    public PermissionResult checkWritePermission(
            String filePath,
            ToolPermissionContext context) {

        if (filePath == null) {
            return PermissionResult.DenyDecision.builder()
                .message("File path is required")
                .build();
        }

        String normalizedPath = normalizePath(filePath);

        // Check for path traversal
        if (containsPathTraversal(normalizedPath)) {
            return PermissionResult.DenyDecision.builder()
                .message("Path traversal detected: " + filePath)
                .build();
        }

        // Check if file is in dangerous list
        String filename = new File(normalizedPath).getName();
        if (DANGEROUS_FILES.contains(filename)) {
            return PermissionResult.AskDecision.builder()
                .message("This file (" + filename + ") is sensitive. Allow editing?")
                .build();
        }

        // Check if path is in dangerous directory
        for (String dangerousDir : DANGEROUS_DIRECTORIES) {
            if (normalizedPath.contains("/" + dangerousDir + "/")
                || normalizedPath.contains(File.separator + dangerousDir + File.separator)) {
                return PermissionResult.AskDecision.builder()
                    .message("This path is in a sensitive directory (" + dangerousDir + "). Allow editing?")
                    .build();
            }
        }

        // Check permission mode
        PermissionMode mode = context.getMode();
        if (mode == PermissionMode.BYPASS_PERMISSIONS) {
            return PermissionResult.AllowDecision.builder().build();
        }

        // Default: allow writes
        return PermissionResult.AllowDecision.builder().build();
    }

    /**
     * Check if a path contains path traversal sequences.
     */
    public static boolean containsPathTraversal(String path) {
        if (path == null) return false;
        String normalized = path.replace("\\", "/");
        return normalized.contains("/../") || normalized.endsWith("/..")
            || normalized.startsWith("../") || normalized.equals("..");
    }

    /**
     * Check if a file name is dangerous.
     */
    public boolean isDangerousFile(String filename) {
        return DANGEROUS_FILES.contains(filename);
    }

    /**
     * Check if a path is an allowed internal path.
     */
    public boolean isAllowedInternalPath(String path) {
        if (path == null) return false;
        String home = System.getProperty("user.home");
        // Allow ~/.claude and system directories
        return path.startsWith(home + "/.claude")
            || path.startsWith("/tmp/")
            || path.startsWith(System.getProperty("java.io.tmpdir"));
    }

    /**
     * Get the session memory directory.
     * Translated from getSessionMemoryDir() in filesystem.ts
     */
    public String getSessionMemoryDir() {
        return com.anthropic.claudecode.util.EnvUtils.getClaudeConfigHomeDir() + "/session-memory";
    }

    /**
     * Get the Claude temp directory.
     * Translated from getClaudeTempDir() in filesystem.ts
     */
    public String getClaudeTempDir() {
        return System.getProperty("java.io.tmpdir") + "/claude-code";
    }

    /**
     * Get the project temp directory.
     * Translated from getProjectTempDir() in filesystem.ts
     */
    public String getProjectTempDir() {
        return getClaudeTempDir() + "/" + sanitizeProjectPath(System.getProperty("user.dir"));
    }

    /**
     * Check if Claude settings path.
     * Translated from isClaudeSettingsPath() in filesystem.ts
     */
    public boolean isClaudeSettingsPath(String filePath) {
        if (filePath == null) return false;
        return filePath.contains("/.claude/") && filePath.endsWith("settings.json");
    }

    private String sanitizeProjectPath(String path) {
        if (path == null) return "default";
        return path.replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    /**
     * Generate permission rule suggestions for a given path and operation.
     * Returns a list of suggested PermissionUpdate objects.
     */
    public java.util.List<Object> generateSuggestions(String path, String operationType, Object toolPermissionContext) {
        // In a full implementation this would analyze the path and operation to suggest
        // appropriate alwaysAllow rules.
        return java.util.List.of();
    }

    private String normalizePath(String path) {
        if (path == null) return "";
        // Expand ~ and resolve
        if (path.startsWith("~/")) {
            path = System.getProperty("user.home") + path.substring(1);
        }
        return Paths.get(path).normalize().toString();
    }
}
