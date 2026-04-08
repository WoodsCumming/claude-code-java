package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Team memory paths service.
 * Translated from src/memdir/teamMemPaths.ts
 *
 * Manages paths for team-shared memory files.
 */
@Slf4j
@Service
public class TeamMemPathsService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TeamMemPathsService.class);

    /**
     * Check if a path is a team memory file.
     * Translated from isTeamMemFile() in teamMemPaths.ts
     */
    public boolean isTeamMemFile(String filePath) {
        if (filePath == null) return false;
        String teamsDir = getTeamsMemDir();
        return filePath.startsWith(teamsDir);
    }

    /**
     * Get the team memory directory.
     */
    public String getTeamsMemDir() {
        String teamsDir = System.getenv("CLAUDE_TEAMS_MEMORY_DIR");
        if (teamsDir != null) return teamsDir;
        return EnvUtils.getClaudeConfigHomeDir() + "/team-memory";
    }

    /**
     * Sanitize a path key to prevent traversal attacks.
     * Translated from sanitizePathKey() in teamMemPaths.ts
     */
    public String sanitizePathKey(String key) {
        if (key == null) throw new IllegalArgumentException("Path key cannot be null");
        if (key.contains("\0")) throw new SecurityException("Null byte in path key");

        // Check for URL-encoded traversals
        try {
            String decoded = java.net.URLDecoder.decode(key, "UTF-8");
            if (decoded.contains("..") || decoded.contains("/")) {
                throw new SecurityException("Path traversal detected in key: " + key);
            }
        } catch (java.io.UnsupportedEncodingException e) {
            // UTF-8 is always supported
        }

        if (key.contains("..") || key.contains("/") || key.contains("\\")) {
            throw new SecurityException("Path traversal detected in key: " + key);
        }

        return key;
    }

    /**
     * Get the team memory path as a java.nio.file.Path.
     * Returns null if the directory is not configured.
     */
    public java.nio.file.Path getTeamMemPath() {
        String dir = getTeamsMemDir();
        if (dir == null || dir.isBlank()) return null;
        return java.nio.file.Paths.get(dir);
    }

    /**
     * Validate a key and return its resolved path within the team memory directory.
     * Returns null if validation fails.
     */
    public java.nio.file.Path validateTeamMemKey(String key) {
        try {
            String sanitized = sanitizePathKey(key);
            java.nio.file.Path base = getTeamMemPath();
            if (base == null) return null;
            return base.resolve(sanitized);
        } catch (SecurityException e) {
            log.warn("team-memory-paths: rejected key \"{}\": {}", key, e.getMessage());
            return null;
        }
    }
}
