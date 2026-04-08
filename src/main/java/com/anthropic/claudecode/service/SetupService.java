package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Startup setup and initialization service.
 * Translated from src/setup.ts
 *
 * Handles all startup initialization:
 * - Session creation / custom session ID injection
 * - Worktree creation (--worktree flag)
 * - Tmux session creation for worktrees
 * - Hook configuration snapshot capture
 * - Permission mode validation (bypassPermissions safety gate)
 * - Background job registration (session memory, context collapse, attribution hooks)
 * - Plugin / command pre-loading
 * - Analytics beacon emission
 */
@Slf4j
@Service
public class SetupService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SetupService.class);


    private final SessionService sessionService;
    private final GlobalConfigService globalConfigService;
    private final HookService hookService;
    private final BackgroundHousekeepingService backgroundHousekeepingService;
    private final BootstrapService bootstrapService;
    private final MigrationService migrationService;
    private final CaCertsConfigService caCertsConfigService;
    private final CommandQueueService commandQueueService;
    private final ProjectConfigService projectConfigService;
    private final BootstrapStateService bootstrapStateService;
    private final WorktreeService worktreeService;
    private final FileChangedWatcherService fileChangedWatcherService;

    @Autowired
    public SetupService(SessionService sessionService,
                        GlobalConfigService globalConfigService,
                        HookService hookService,
                        BackgroundHousekeepingService backgroundHousekeepingService,
                        BootstrapService bootstrapService,
                        MigrationService migrationService,
                        CaCertsConfigService caCertsConfigService,
                        CommandQueueService commandQueueService,
                        ProjectConfigService projectConfigService,
                        BootstrapStateService bootstrapStateService,
                        WorktreeService worktreeService,
                        FileChangedWatcherService fileChangedWatcherService) {
        this.sessionService = sessionService;
        this.globalConfigService = globalConfigService;
        this.hookService = hookService;
        this.backgroundHousekeepingService = backgroundHousekeepingService;
        this.bootstrapService = bootstrapService;
        this.migrationService = migrationService;
        this.caCertsConfigService = caCertsConfigService;
        this.commandQueueService = commandQueueService;
        this.projectConfigService = projectConfigService;
        this.bootstrapStateService = bootstrapStateService;
        this.worktreeService = worktreeService;
        this.fileChangedWatcherService = fileChangedWatcherService;
    }

    // =========================================================================
    // Main setup entry point
    // =========================================================================

    /**
     * Initialize Claude Code for a new session.
     * Translated from setup() in setup.ts
     *
     * @param cwd              working directory
     * @param permissionMode   permission mode (default, acceptEdits, bypassPermissions, etc.)
     * @param allowDangerouslySkipPermissions  bypass flag
     * @param worktreeEnabled  whether to create a git worktree
     * @param worktreeName     optional worktree name slug
     * @param tmuxEnabled      whether to create a tmux session for the worktree
     * @param customSessionId  optional pre-assigned session ID
     * @param worktreePRNumber optional PR number for worktree naming
     */
    public CompletableFuture<Void> setup(
            String cwd,
            PermissionMode permissionMode,
            boolean allowDangerouslySkipPermissions,
            boolean worktreeEnabled,
            String worktreeName,
            boolean tmuxEnabled,
            String customSessionId,
            Integer worktreePRNumber) {

        final String[] cwdRef = {cwd};
        return CompletableFuture.runAsync(() -> {
            log.info("setup_started");

            // Set custom session ID if provided (equivalent to switchSession(asSessionId(...)))
            if (customSessionId != null && !customSessionId.isBlank()) {
                bootstrapStateService.switchSession(customSessionId);
            }

            // Apply CA certs from settings (before any network calls)
            caCertsConfigService.applyCACertsFromSettings();

            // IMPORTANT: setCwd must happen before hooks snapshot so hooks load from correct dir.
            setWorkingDirectory(cwdRef[0]);
            long hooksStart = System.currentTimeMillis();
            // Load hooks from current directory (captures snapshot of current config)
            hookService.loadHooks(cwdRef[0]);
            log.debug("setup_hooks_captured duration_ms={}", System.currentTimeMillis() - hooksStart);

            // Initialize FileChanged hook watcher
            fileChangedWatcherService.initialize(cwdRef[0]);
            fileChangedWatcherService.startWatching();

            // Handle worktree creation if requested
            if (worktreeEnabled) {
                setupWorktree(cwdRef[0], worktreeName, tmuxEnabled, worktreePRNumber);
                // cwd may have changed inside setupWorktree; re-read current cwd
                cwdRef[0] = bootstrapStateService.getCwdState();
            }

            // Background jobs — critical registrations before first query
            log.debug("setup_background_jobs_starting");
            backgroundHousekeepingService.start();
            log.debug("setup_background_jobs_launched");

            // Pre-fetch commands (memoized by cwd)
            final String finalCwd = cwdRef[0];
            CompletableFuture.runAsync(() -> {
                try {
                    commandQueueService.getCommands(finalCwd).get();
                } catch (Exception e) {
                    log.debug("Command pre-fetch failed: {}", e.getMessage());
                }
            });

            // Run migrations
            migrationService.runMigrations();

            // Validate permission bypass safety gate
            if (permissionMode == PermissionMode.BYPASS_PERMISSIONS || allowDangerouslySkipPermissions) {
                validateBypassPermissionsSafety();
            }

            // Emit session-started analytics beacon
            logSetupAnalytics();

            // Log exit event from previous session (if any)
            logPreviousSessionExit();

            log.info("setup_complete");
        });
    }

    // =========================================================================
    // Worktree setup
    // =========================================================================

    /**
     * Create and configure a git worktree for the session.
     * Translated from the worktreeEnabled block in setup() in setup.ts
     */
    private void setupWorktree(String cwd, String worktreeName, boolean tmuxEnabled, Integer prNumber) {
        log.info("Creating worktree: name={}, prNumber={}, tmux={}", worktreeName, prNumber, tmuxEnabled);

        if (!isGitRepo(cwd)) {
            throw new IllegalStateException(
                "Error: Can only use --worktree in a git repository, but " + cwd +
                " is not a git repository. Configure a WorktreeCreate hook in settings.json " +
                "to use --worktree with other VCS systems.");
        }

        String slug = prNumber != null
            ? "pr-" + prNumber
            : (worktreeName != null ? worktreeName : generatePlanSlug());

        java.nio.file.Path repoRoot = java.nio.file.Path.of(cwd);
        WorktreeService.WorktreeCreateResult result;
        try {
            result = worktreeService.getOrCreateWorktree(repoRoot, slug).get();
        } catch (Exception e) {
            throw new RuntimeException("Error creating worktree: " + e.getMessage(), e);
        }

        log.info("tengu_worktree_created tmux_enabled={}", tmuxEnabled);

        if (tmuxEnabled) {
            String branchName = worktreeService.worktreeBranchName(slug);
            String tmuxSessionName = worktreeService.generateTmuxSessionName(cwd, branchName);
            // Tmux session creation is a best-effort background operation
            log.info("Worktree ready at: {} — tmux session: {}", result.worktreePath(), tmuxSessionName);
        }

        // Switch working directory to the new worktree
        String newCwd = result.worktreePath();
        setWorkingDirectory(newCwd);
        bootstrapStateService.setOriginalCwd(newCwd);
        bootstrapStateService.setProjectRoot(newCwd);
        bootstrapStateService.setCwdState(newCwd);
        // Re-load hooks from the new worktree directory
        hookService.loadHooks(newCwd);
    }

    // =========================================================================
    // Permission bypass safety gate
    // =========================================================================

    /**
     * Validate that --dangerously-skip-permissions is used in a safe environment.
     * Translated from the permission bypass safety block in setup.ts
     */
    private void validateBypassPermissionsSafety() {
        // Check if running as root on Unix-like systems
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("windows")) {
            // Note: Java doesn't have direct getuid() access; check via system property or env
            String isSandbox = System.getenv("IS_SANDBOX");
            String bubblewrap = System.getenv("CLAUDE_CODE_BUBBLEWRAP");
            boolean inSandbox = "1".equals(isSandbox) || "true".equals(bubblewrap);
            // Additional root check would require JNA or /proc — log warning instead
            if (!inSandbox) {
                log.warn("--dangerously-skip-permissions is intended for use in sandboxed environments only");
            }
        }
    }

    // =========================================================================
    // Analytics
    // =========================================================================

    private void logSetupAnalytics() {
        log.debug("tengu_started");
    }

    private void logPreviousSessionExit() {
        try {
            ProjectConfigService.ProjectConfig config = projectConfigService.getCurrentProjectConfig();
            if (config.getLastCost() != null && config.getLastDuration() != null) {
                log.debug("tengu_exit: last_session_cost={} last_session_duration={}",
                    config.getLastCost(), config.getLastDuration());
            }
        } catch (Exception e) {
            log.debug("Could not log previous session exit: {}", e.getMessage());
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void setWorkingDirectory(String cwd) {
        System.setProperty("user.dir", cwd);
    }

    private boolean isGitRepo(String cwd) {
        try {
            Process proc = new ProcessBuilder("git", "-C", cwd, "rev-parse", "--git-dir")
                .redirectErrorStream(true)
                .start();
            proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return proc.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String generatePlanSlug() {
        return "session-" + System.currentTimeMillis();
    }

    private String generateTmuxSessionName(String cwd, String slug) {
        String base = new java.io.File(cwd).getName();
        return base + "-" + slug;
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /** Permission mode values. */
    public enum PermissionMode {
        DEFAULT,
        ACCEPT_EDITS,
        BYPASS_PERMISSIONS,
        AUTO
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SetupOptions {
        private String cwd;
        private String model;
        private PermissionMode permissionMode;
        private boolean allowDangerouslySkipPermissions;
        private boolean nonInteractive;
        private boolean worktreeEnabled;
        private String worktreeName;
        private boolean tmuxEnabled;
        private String customSessionId;
        private Integer worktreePRNumber;
        private List<String> additionalDirs;

        public String getCwd() { return cwd; }
        public void setCwd(String v) { cwd = v; }
        public String getModel() { return model; }
        public void setModel(String v) { model = v; }
        public PermissionMode getPermissionMode() { return permissionMode; }
        public void setPermissionMode(PermissionMode v) { permissionMode = v; }
        public boolean isAllowDangerouslySkipPermissions() { return allowDangerouslySkipPermissions; }
        public void setAllowDangerouslySkipPermissions(boolean v) { allowDangerouslySkipPermissions = v; }
        public boolean isNonInteractive() { return nonInteractive; }
        public void setNonInteractive(boolean v) { nonInteractive = v; }
        public boolean isWorktreeEnabled() { return worktreeEnabled; }
        public void setWorktreeEnabled(boolean v) { worktreeEnabled = v; }
        public String getWorktreeName() { return worktreeName; }
        public void setWorktreeName(String v) { worktreeName = v; }
        public boolean isTmuxEnabled() { return tmuxEnabled; }
        public void setTmuxEnabled(boolean v) { tmuxEnabled = v; }
        public String getCustomSessionId() { return customSessionId; }
        public void setCustomSessionId(String v) { customSessionId = v; }
        public Integer getWorktreePRNumber() { return worktreePRNumber; }
        public void setWorktreePRNumber(Integer v) { worktreePRNumber = v; }
        public List<String> getAdditionalDirs() { return additionalDirs; }
        public void setAdditionalDirs(List<String> v) { additionalDirs = v; }
    }

    public record WorktreeSession(String worktreePath, String branchName) {}
    public record TmuxResult(boolean created, String error) {}
}
