package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.GlobalConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;

/**
 * iTerm2 settings backup and restore service.
 * Translated from src/utils/iTermBackup.ts
 *
 * <p>The TS source backs up and restores the iTerm2 preference plist file
 * ({@code ~/Library/Preferences/com.googlecode.iterm2.plist}) when Claude Code
 * modifies iTerm2 settings. This service mirrors that behaviour using Java NIO.</p>
 */
@Slf4j
@Service
public class AppleTerminalBackupService {

    private static final String ITERM2_PLIST_PATH =
            System.getProperty("user.home")
            + "/Library/Preferences/com.googlecode.iterm2.plist";

    private final GlobalConfigService globalConfigService;

    @Autowired
    public AppleTerminalBackupService(GlobalConfigService globalConfigService) {
        this.globalConfigService = globalConfigService;
    }

    // -------------------------------------------------------------------------
    // RestoreResult sealed hierarchy
    // -------------------------------------------------------------------------

    /**
     * Sealed result type for {@link #checkAndRestoreITerm2Backup()}.
     * Translated from the {@code RestoreResult} union type in iTermBackup.ts.
     */
    public sealed interface RestoreResult permits
            AppleTerminalBackupService.Restored,
            AppleTerminalBackupService.NoBackup,
            AppleTerminalBackupService.Failed {}

    /** Backup was found and successfully restored. */
    public record Restored() implements RestoreResult {}

    /** No backup was in progress (or no backup path was recorded). */
    public record NoBackup() implements RestoreResult {}

    /** Backup was found but copying it back failed. */
    public record Failed(String backupPath) implements RestoreResult {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Marks the iTerm2 setup as complete by clearing the in-progress flag.
     * Translated from {@code markITerm2SetupComplete()} in iTermBackup.ts.
     */
    public void markITerm2SetupComplete() {
        globalConfigService.updateGlobalConfig(config -> {
            config.setIterm2SetupInProgress(false);
            return config;
        });
    }

    /**
     * Checks whether an iTerm2 backup is pending and, if so, copies it back to
     * the original plist location.
     * Translated from {@code checkAndRestoreITerm2Backup()} in iTermBackup.ts.
     *
     * @return a {@link CompletableFuture} that resolves to:
     *   <ul>
     *     <li>{@link Restored} — backup was found and restored successfully</li>
     *     <li>{@link NoBackup} — no backup was in progress</li>
     *     <li>{@link Failed}   — backup was found but could not be restored</li>
     *   </ul>
     */
    public CompletableFuture<RestoreResult> checkAndRestoreITerm2Backup() {
        return CompletableFuture.supplyAsync(() -> {
            RecoveryInfo info = getIterm2RecoveryInfo();

            if (!info.inProgress()) {
                return (RestoreResult) new NoBackup();
            }

            if (info.backupPath() == null) {
                markITerm2SetupComplete();
                return new NoBackup();
            }

            Path backupPath = Path.of(info.backupPath());
            if (!Files.exists(backupPath)) {
                markITerm2SetupComplete();
                return new NoBackup();
            }

            try {
                Files.copy(backupPath, Path.of(ITERM2_PLIST_PATH),
                        StandardCopyOption.REPLACE_EXISTING);
                markITerm2SetupComplete();
                return new Restored();
            } catch (IOException restoreError) {
                log.error("Failed to restore iTerm2 settings with: {}", restoreError.getMessage());
                markITerm2SetupComplete();
                return new Failed(info.backupPath());
            }
        });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private record RecoveryInfo(boolean inProgress, String backupPath) {}

    /**
     * Reads the iTerm2 recovery state from global config.
     * Translated from {@code getIterm2RecoveryInfo()} in iTermBackup.ts.
     */
    private RecoveryInfo getIterm2RecoveryInfo() {
        GlobalConfig config = globalConfigService.getGlobalConfig();
        boolean inProgress = Boolean.TRUE.equals(config.getIterm2SetupInProgress());
        String  backupPath = config.getIterm2BackupPath();
        return new RecoveryInfo(inProgress, backupPath);
    }
}
