package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Working directory management service.
 * Translated from src/utils/permissions/filesystem.ts (allWorkingDirectories, pathInWorkingPath)
 *
 * Manages the set of directories that Claude Code is allowed to access.
 */
@Slf4j
@Service
public class WorkingDirectoryService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorkingDirectoryService.class);


    // The initial working directory (set at startup)
    private final String initialWorkingDirectory;

    // Additional working directories added via /add-dir
    private final List<String> additionalWorkingDirectories = new ArrayList<>();

    public WorkingDirectoryService() {
        this.initialWorkingDirectory = System.getProperty("user.dir");
    }

    /**
     * Get all working directories.
     * Translated from allWorkingDirectories() in filesystem.ts
     */
    public List<String> getAllWorkingDirectories() {
        List<String> all = new ArrayList<>();
        all.add(initialWorkingDirectory);
        all.addAll(additionalWorkingDirectories);
        return Collections.unmodifiableList(all);
    }

    /**
     * Add a new working directory.
     */
    public void addWorkingDirectory(String absolutePath) {
        if (!additionalWorkingDirectories.contains(absolutePath)) {
            additionalWorkingDirectories.add(absolutePath);
            log.info("Added working directory: {}", absolutePath);
        }
    }

    /**
     * Check if a path is within any of the working directories.
     * Translated from pathInWorkingPath() in filesystem.ts
     */
    public boolean isPathInWorkingDirectory(String path) {
        for (String workingDir : getAllWorkingDirectories()) {
            if (pathInWorkingPath(path, workingDir)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the working directory that contains the given path.
     */
    public String getContainingWorkingDirectory(String path) {
        for (String workingDir : getAllWorkingDirectories()) {
            if (pathInWorkingPath(path, workingDir)) {
                return workingDir;
            }
        }
        return null;
    }

    /**
     * Check if a path is within a working directory.
     * Translated from pathInWorkingPath() in filesystem.ts
     */
    public boolean pathInWorkingPath(String path, String workingDir) {
        if (path == null || workingDir == null) return false;
        // Normalize paths for comparison
        String normalizedPath = path.endsWith("/") ? path : path + "/";
        String normalizedWorkingDir = workingDir.endsWith("/") ? workingDir : workingDir + "/";
        return normalizedPath.startsWith(normalizedWorkingDir) || path.equals(workingDir);
    }

    /**
     * Get the initial working directory.
     */
    public String getInitialWorkingDirectory() {
        return initialWorkingDirectory;
    }

    /**
     * Get the current working directory.
     */
    public String getCwd() {
        return System.getProperty("user.dir", initialWorkingDirectory);
    }
}
