package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Data;

/**
 * File history tracking service.
 * Translated from src/utils/fileHistory.ts
 *
 * Tracks file modifications during a session to support undo/redo
 * and diff display.
 */
@Slf4j
@Service
public class FileHistoryService {



    private static final int MAX_SNAPSHOTS = 100;
    private static final String BACKUPS_DIR = "file-backups";

    private final List<FileHistorySnapshot> snapshots = new ArrayList<>();
    private final Set<String> trackedFiles = new HashSet<>();
    private int snapshotSequence = 0;

    /**
     * Check if file history is enabled.
     * Translated from fileHistoryEnabled() in fileHistory.ts
     */
    public boolean isEnabled() {
        return !EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_DISABLE_FILE_HISTORY"));
    }

    /**
     * Track an edit to a file.
     * Translated from fileHistoryTrackEdit() in fileHistory.ts
     */
    public void trackEdit(String filePath, String messageId) {
        if (!isEnabled()) return;

        trackedFiles.add(filePath);

        // Create backup
        try {
            String backupPath = createBackup(filePath);
            FileHistoryBackup backup = new FileHistoryBackup(
                backupPath,
                snapshotSequence++,
                new Date()
            );

            // Add to current snapshot or create new one
            FileHistorySnapshot snapshot = new FileHistorySnapshot(
                messageId,
                Map.of(filePath, backup),
                new Date()
            );

            snapshots.add(snapshot);

            // Trim old snapshots
            while (snapshots.size() > MAX_SNAPSHOTS) {
                snapshots.remove(0);
            }

        } catch (Exception e) {
            log.debug("Could not create file backup: {}", e.getMessage());
        }
    }

    /**
     * Get diff stats for tracked files.
     * Translated from getDiffStats() in fileHistory.ts
     */
    public DiffStats getDiffStats() {
        // Calculate insertions/deletions from tracked files
        int insertions = 0;
        int deletions = 0;
        List<String> filesChanged = new ArrayList<>();

        for (String filePath : trackedFiles) {
            try {
                // Count lines in current vs backup
                File currentFile = new File(filePath);
                if (!currentFile.exists()) continue;

                String currentContent = Files.readString(currentFile.toPath());
                int currentLines = currentContent.split("\n", -1).length;

                // Find the oldest backup for this file
                String backupContent = getOldestBackupContent(filePath);
                if (backupContent != null) {
                    int backupLines = backupContent.split("\n", -1).length;
                    int diff = currentLines - backupLines;
                    if (diff > 0) insertions += diff;
                    else deletions += -diff;
                    filesChanged.add(filePath);
                }
            } catch (Exception e) {
                // Skip
            }
        }

        return new DiffStats(filesChanged, insertions, deletions);
    }

    private String createBackup(String filePath) throws Exception {
        File file = new File(filePath);
        if (!file.exists()) return null;

        String backupsDir = EnvUtils.getClaudeConfigHomeDir() + "/" + BACKUPS_DIR;
        new File(backupsDir).mkdirs();

        String hash = computeFileHash(filePath);
        String backupName = hash + "-" + System.currentTimeMillis();
        String backupPath = backupsDir + "/" + backupName;

        Files.copy(file.toPath(), Paths.get(backupPath));
        return backupPath;
    }

    private String getOldestBackupContent(String filePath) {
        for (FileHistorySnapshot snapshot : snapshots) {
            FileHistoryBackup backup = snapshot.getTrackedFileBackups().get(filePath);
            if (backup != null && backup.getBackupFileName() != null) {
                try {
                    return Files.readString(Paths.get(backup.getBackupFileName()));
                } catch (Exception e) {
                    // Skip
                }
            }
        }
        return null;
    }

    private String computeFileHash(String filePath) throws Exception {
        byte[] bytes = Files.readAllBytes(Paths.get(filePath));
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] hash = md.digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString().substring(0, 8);
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class FileHistoryBackup {
        private String backupFileName;
        private int version;
        private Date backupTime;

        public String getBackupFileName() { return backupFileName; }
        public void setBackupFileName(String v) { backupFileName = v; }
        public int getVersion() { return version; }
        public void setVersion(int v) { version = v; }
        public Date getBackupTime() { return backupTime; }
        public void setBackupTime(Date v) { backupTime = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class FileHistorySnapshot {
        private String messageId;
        private Map<String, FileHistoryBackup> trackedFileBackups;
        private Date timestamp;

        public String getMessageId() { return messageId; }
        public void setMessageId(String v) { messageId = v; }
        public Map<String, FileHistoryBackup> getTrackedFileBackups() { return trackedFileBackups; }
        public void setTrackedFileBackups(Map<String, FileHistoryBackup> v) { trackedFileBackups = v; }
        public Date getTimestamp() { return timestamp; }
        public void setTimestamp(Date v) { timestamp = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DiffStats {
        private List<String> filesChanged;
        private int insertions;
        private int deletions;

        public List<String> getFilesChanged() { return filesChanged; }
        public void setFilesChanged(List<String> v) { filesChanged = v; }
        public int getInsertions() { return insertions; }
        public void setInsertions(int v) { insertions = v; }
        public int getDeletions() { return deletions; }
        public void setDeletions(int v) { deletions = v; }
    }
}
