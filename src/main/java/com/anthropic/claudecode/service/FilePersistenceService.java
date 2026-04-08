package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * File persistence service for BYOC (Bring Your Own Cloud) mode.
 * Translated from src/utils/filePersistence/filePersistence.ts
 *
 * Orchestrates file persistence at the end of each turn:
 * - BYOC mode: Upload files to Files API and collect file IDs
 * - 1P/Cloud mode: Query Files API listDirectory for file IDs
 */
@Slf4j
@Service
public class FilePersistenceService {

    private static final String OUTPUTS_SUBDIR = "outputs";
    private static final int FILE_COUNT_LIMIT = 100;
    private static final int DEFAULT_UPLOAD_CONCURRENCY = 5;

    private final FilesApiService filesApiService;

    @Autowired
    public FilePersistenceService(FilesApiService filesApiService) {
        this.filesApiService = filesApiService;
    }

    /**
     * Run file persistence for modified files.
     * Translated from runFilePersistence() in filePersistence.ts
     *
     * @return event data, or null if not enabled
     */
    public CompletableFuture<FilesPersistedEventData> runFilePersistence(
            long turnStartTimeMs) {

        return CompletableFuture.supplyAsync(() -> {
            String environmentKind = System.getenv("CLAUDE_CODE_ENVIRONMENT_KIND");
            if (!"byoc".equals(environmentKind)) {
                return null;
            }

            String sessionAccessToken = System.getenv("CLAUDE_CODE_SESSION_ACCESS_TOKEN");
            if (sessionAccessToken == null) {
                log.debug("[file-persistence] No session access token");
                return null;
            }

            String sessionId = System.getenv("CLAUDE_CODE_REMOTE_SESSION_ID");
            if (sessionId == null) {
                log.debug("[file-persistence] No remote session ID");
                return null;
            }

            // Find modified files
            String outputsDir = System.getProperty("user.dir") + "/" + OUTPUTS_SUBDIR;
            List<Path> modifiedFiles = findModifiedFiles(outputsDir, turnStartTimeMs);

            if (modifiedFiles.isEmpty()) {
                return null;
            }

            if (modifiedFiles.size() > FILE_COUNT_LIMIT) {
                log.warn("[file-persistence] Too many modified files: {}", modifiedFiles.size());
                modifiedFiles = modifiedFiles.subList(0, FILE_COUNT_LIMIT);
            }

            // Upload files (simplified - actual implementation would batch upload)
            FilesApiService.FilesApiConfig config = new FilesApiService.FilesApiConfig(
                sessionAccessToken, sessionId
            );

            List<PersistedFile> persistedFiles = new ArrayList<>();
            for (Path file : modifiedFiles) {
                persistedFiles.add(new PersistedFile(
                    file.toString(),
                    file.getFileName().toString(),
                    null // fileId would come from upload
                ));
            }

            return new FilesPersistedEventData(
                persistedFiles.size(),
                persistedFiles.stream()
                    .filter(f -> f.getFileId() != null)
                    .collect(Collectors.toList())
            );
        });
    }

    private List<Path> findModifiedFiles(String dir, long sinceMs) {
        File outputsDir = new File(dir);
        if (!outputsDir.exists()) return List.of();

        List<Path> result = new ArrayList<>();
        try {
            Files.walk(outputsDir.toPath())
                .filter(Files::isRegularFile)
                .filter(p -> {
                    try {
                        return Files.getLastModifiedTime(p).toMillis() > sinceMs;
                    } catch (Exception e) {
                        return false;
                    }
                })
                .forEach(result::add);
        } catch (Exception e) {
            log.debug("Error scanning outputs directory: {}", e.getMessage());
        }
        return result;
    }

    public static class PersistedFile {
        private String path;
        private String name;
        private String fileId;
        public PersistedFile() {}
        public PersistedFile(String path, String name, String fileId) {
            this.path = path; this.name = name; this.fileId = fileId;
        }
        public String getPath() { return path; }
        public void setPath(String v) { this.path = v; }
        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public String getFileId() { return fileId; }
        public void setFileId(String v) { this.fileId = v; }
    }

    public static class FilesPersistedEventData {
        private int totalFiles;
        private List<PersistedFile> uploadedFiles;
        public FilesPersistedEventData() {}
        public FilesPersistedEventData(int totalFiles, List<PersistedFile> uploadedFiles) {
            this.totalFiles = totalFiles; this.uploadedFiles = uploadedFiles;
        }
        public int getTotalFiles() { return totalFiles; }
        public List<PersistedFile> getUploadedFiles() { return uploadedFiles; }
    }
}
