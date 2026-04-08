package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.PathUtils;
import com.anthropic.claudecode.util.ReadOnlyCommandValidation;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.util.*;

/**
 * Path validation service.
 * Translated from src/utils/permissions/pathValidation.ts
 *
 * Validates file paths for permission checks.
 */
@Slf4j
@Service
public class PathValidationService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PathValidationService.class);


    private final WorkingDirectoryService workingDirectoryService;
    private final FilesystemPermissionService filesystemPermissionService;

    @Autowired
    public PathValidationService(WorkingDirectoryService workingDirectoryService,
                                  FilesystemPermissionService filesystemPermissionService) {
        this.workingDirectoryService = workingDirectoryService;
        this.filesystemPermissionService = filesystemPermissionService;
    }

    /**
     * Validate a path for reading.
     * Translated from validatePathForRead() in pathValidation.ts
     */
    public PathValidationResult validatePathForRead(String path) {
        if (path == null || path.isBlank()) {
            return new PathValidationResult(false, "Path cannot be empty");
        }

        // Check for path traversal
        if (containsPathTraversal(path)) {
            return new PathValidationResult(false, "Path traversal detected");
        }

        // Check for vulnerable UNC paths
        if (ReadOnlyCommandValidation.containsVulnerableUncPath(path)) {
            return new PathValidationResult(false, "Vulnerable UNC path detected");
        }

        // Resolve to absolute path
        String absolutePath = PathUtils.expandPath(path);

        // Check if path is in working directory
        if (!workingDirectoryService.isPathInWorkingDirectory(absolutePath)) {
            // Check if it's an allowed internal path
            if (!filesystemPermissionService.isAllowedInternalPath(absolutePath)) {
                return new PathValidationResult(false, "Path is outside working directory");
            }
        }

        return new PathValidationResult(true, null);
    }

    /**
     * Validate a path for writing.
     * Translated from validatePathForWrite() in pathValidation.ts
     */
    public PathValidationResult validatePathForWrite(String path) {
        PathValidationResult readResult = validatePathForRead(path);
        if (!readResult.isValid()) return readResult;

        // Additional write-specific checks
        if (filesystemPermissionService.isDangerousFile(new File(path).getName())) {
            return new PathValidationResult(false, "Cannot write to dangerous file: " + path);
        }

        return new PathValidationResult(true, null);
    }

    private boolean containsPathTraversal(String path) {
        return path.contains("../") || path.contains("..\\")
            || path.contains("/..")  || path.contains("\\..");
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PathValidationResult {
        private boolean valid;
        private String error;

        public boolean isValid() { return valid; }
        public void setValid(boolean v) { valid = v; }
        public String getError() { return error; }
        public void setError(String v) { error = v; }
    }
}
