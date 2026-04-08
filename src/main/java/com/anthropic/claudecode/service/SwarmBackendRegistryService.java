package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Swarm backend registry — detects and manages pane backends for teammate execution.
 *
 * Merged translation of:
 *  - src/utils/swarm/backends/registry.ts      (backend detection, caching, routing)
 *  - src/utils/swarm/backends/teammateModeSnapshot.ts  (teammate-mode session snapshot)
 *
 * Detection priority:
 *  1. Inside tmux  → use tmux
 *  2. In iTerm2 + it2 CLI available → use iTerm2
 *  3. In iTerm2 + tmux available    → use tmux (it2 setup recommended)
 *  4. Not in iTerm2 + tmux available → use tmux
 *  5. Otherwise → throw / fall back to in-process
 */
@Slf4j
@Service
public class SwarmBackendRegistryService {



    // =========================================================================
    // TeammateMode snapshot (teammateModeSnapshot.ts)
    // =========================================================================

    /** Possible teammate execution modes. Translated from TeammateMode union type. */
    public enum TeammateMode { AUTO, TMUX, IN_PROCESS;
        public static TeammateMode fromString(String s) {
            if (s == null) return AUTO;
            return switch (s.toLowerCase()) {
                case "tmux"       -> TMUX;
                case "in-process" -> IN_PROCESS;
                default           -> AUTO;
            };
        }
    }

    /** Snapshot captured once at session startup. Null until captureTeammateModeSnapshot() is called. */
    private volatile TeammateMode initialTeammateMode = null;

    /** CLI override set before capture (--teammate-mode flag). */
    private volatile TeammateMode cliTeammateModeOverride = null;

    /**
     * Set CLI override before capturing the snapshot.
     * Translated from setCliTeammateModeOverride() in teammateModeSnapshot.ts
     */
    public void setCliTeammateModeOverride(TeammateMode mode) {
        this.cliTeammateModeOverride = mode;
    }

    /**
     * Returns the current CLI override, if any.
     * Translated from getCliTeammateModeOverride() in teammateModeSnapshot.ts
     */
    public TeammateMode getCliTeammateModeOverride() {
        return cliTeammateModeOverride;
    }

    /**
     * Clears the CLI override and updates the snapshot to newMode.
     * Called when the user changes the setting in the UI.
     * Translated from clearCliTeammateModeOverride() in teammateModeSnapshot.ts
     */
    public void clearCliTeammateModeOverride(TeammateMode newMode) {
        this.cliTeammateModeOverride = null;
        this.initialTeammateMode = newMode;
        log.debug("[TeammateModeSnapshot] CLI override cleared, new mode: {}", newMode);
    }

    /**
     * Captures the teammate mode at session startup.
     * CLI override takes precedence over config.
     * Translated from captureTeammateModeSnapshot() in teammateModeSnapshot.ts
     */
    public void captureTeammateModeSnapshot() {
        if (cliTeammateModeOverride != null) {
            initialTeammateMode = cliTeammateModeOverride;
            log.debug("[TeammateModeSnapshot] Captured from CLI override: {}", initialTeammateMode);
        } else {
            String envVal = System.getenv("CLAUDE_CODE_TEAMMATE_MODE");
            initialTeammateMode = (envVal != null) ? TeammateMode.fromString(envVal) : TeammateMode.AUTO;
            log.debug("[TeammateModeSnapshot] Captured from config: {}", initialTeammateMode);
        }
    }

    /**
     * Gets the teammate mode for this session (snapshot taken at startup).
     * Translated from getTeammateModeFromSnapshot() in teammateModeSnapshot.ts
     */
    public TeammateMode getTeammateModeFromSnapshot() {
        if (initialTeammateMode == null) {
            log.warn("[TeammateModeSnapshot] getTeammateModeFromSnapshot called before capture");
            captureTeammateModeSnapshot();
        }
        return initialTeammateMode != null ? initialTeammateMode : TeammateMode.AUTO;
    }

    // =========================================================================
    // Backend detection result (registry.ts types)
    // =========================================================================

    /** Available pane backend types. Translated from PaneBackendType in types.ts */
    public enum PaneBackendType { TMUX, ITERM2 }

    /**
     * Result of backend detection.
     * Translated from BackendDetectionResult in types.ts
     */
    public record BackendDetectionResult(
        PaneBackendType backendType,
        boolean isNative,
        boolean needsIt2Setup
    ) {}

    // =========================================================================
    // Registry state (registry.ts)
    // =========================================================================

    private volatile BackendDetectionResult cachedDetectionResult = null;
    private final AtomicBoolean backendsRegistered   = new AtomicBoolean(false);
    private final AtomicBoolean inProcessFallbackActive = new AtomicBoolean(false);

    // Registered backend implementation classes
    private final AtomicReference<Class<?>> tmuxBackendClass  = new AtomicReference<>(null);
    private final AtomicReference<Class<?>> itermBackendClass = new AtomicReference<>(null);

    private final SwarmBackendDetectionService detectionService;
    private final BootstrapStateService bootstrapStateService;

    @Autowired
    public SwarmBackendRegistryService(
            SwarmBackendDetectionService detectionService,
            BootstrapStateService bootstrapStateService) {
        this.detectionService     = detectionService;
        this.bootstrapStateService = bootstrapStateService;
    }

    // =========================================================================
    // Backend registration (registry.ts)
    // =========================================================================

    /**
     * Ensures backend classes are registered (lazy).
     * Translated from ensureBackendsRegistered() in registry.ts
     */
    public CompletableFuture<Void> ensureBackendsRegistered() {
        if (backendsRegistered.get()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> backendsRegistered.set(true));
    }

    /**
     * Registers the Tmux backend class.
     * Translated from registerTmuxBackend() in registry.ts
     */
    public void registerTmuxBackend(Class<?> backendClass) {
        tmuxBackendClass.set(backendClass);
    }

    /**
     * Registers the iTerm2 backend class.
     * Translated from registerITermBackend() in registry.ts
     */
    public void registerITermBackend(Class<?> backendClass) {
        log.debug("[registry] registerITermBackend called, class={}", backendClass != null ? backendClass.getSimpleName() : "null");
        itermBackendClass.set(backendClass);
    }

    // =========================================================================
    // Detection (registry.ts)
    // =========================================================================

    /**
     * Detects and returns the best available backend.
     * Translated from detectAndGetBackend() in registry.ts
     *
     * Priority:
     *  1. Inside tmux → tmux
     *  2. In iTerm2 + it2 CLI → iTerm2
     *  3. In iTerm2 + tmux → tmux (it2 setup flagged)
     *  4. tmux available → tmux
     *  5. throw
     */
    public CompletableFuture<BackendDetectionResult> detectAndGetBackend() {
        return ensureBackendsRegistered().thenCompose(__ -> {
            if (cachedDetectionResult != null) {
                log.debug("[BackendRegistry] Using cached backend: {}", cachedDetectionResult.backendType());
                return CompletableFuture.completedFuture(cachedDetectionResult);
            }

            log.debug("[BackendRegistry] Starting backend detection...");

            return detectionService.isInsideTmuxAsync().thenCompose(insideTmux -> {
                boolean inITerm2 = detectionService.isInITerm2();
                log.debug("[BackendRegistry] Environment: insideTmux={}, inITerm2={}", insideTmux, inITerm2);

                // Priority 1: inside tmux → always use tmux
                if (insideTmux) {
                    log.debug("[BackendRegistry] Selected: tmux (running inside tmux session)");
                    cachedDetectionResult = new BackendDetectionResult(PaneBackendType.TMUX, true, false);
                    return CompletableFuture.completedFuture(cachedDetectionResult);
                }

                // Priority 2 & 3: in iTerm2
                if (inITerm2) {
                    boolean preferTmux = getPreferTmuxOverIterm2();
                    if (preferTmux) {
                        log.debug("[BackendRegistry] User prefers tmux over iTerm2");
                    } else {
                        return detectionService.isIt2CliAvailableAsync().thenCompose(it2Available -> {
                            log.debug("[BackendRegistry] iTerm2 detected, it2 CLI available: {}", it2Available);
                            if (it2Available) {
                                log.debug("[BackendRegistry] Selected: iterm2 (native iTerm2 with it2 CLI)");
                                cachedDetectionResult = new BackendDetectionResult(PaneBackendType.ITERM2, true, false);
                                return CompletableFuture.completedFuture(cachedDetectionResult);
                            }
                            return detectionService.isTmuxAvailableAsync().thenApply(tmuxAvail -> {
                                log.debug("[BackendRegistry] it2 not available, tmux available: {}", tmuxAvail);
                                if (tmuxAvail) {
                                    log.debug("[BackendRegistry] Selected: tmux (fallback in iTerm2)");
                                    cachedDetectionResult = new BackendDetectionResult(
                                            PaneBackendType.TMUX, false, !preferTmux);
                                    return cachedDetectionResult;
                                }
                                throw new RuntimeException(
                                    "iTerm2 detected but it2 CLI not installed. Install it2 with: pip install it2");
                            });
                        });
                    }
                }

                // Priority 4: fall back to tmux external session
                return detectionService.isTmuxAvailableAsync().thenApply(tmuxAvail -> {
                    log.debug("[BackendRegistry] Not in tmux or iTerm2, tmux available: {}", tmuxAvail);
                    if (tmuxAvail) {
                        log.debug("[BackendRegistry] Selected: tmux (external session mode)");
                        cachedDetectionResult = new BackendDetectionResult(PaneBackendType.TMUX, false, false);
                        return cachedDetectionResult;
                    }
                    throw new RuntimeException(getTmuxInstallInstructions());
                });
            });
        });
    }

    /**
     * Gets a backend by explicit type.
     * Translated from getBackendByType() in registry.ts
     */
    public PaneBackendType getBackendByType(String type) {
        return switch (type != null ? type.toLowerCase() : "") {
            case "iterm2" -> PaneBackendType.ITERM2;
            default       -> PaneBackendType.TMUX;
        };
    }

    /**
     * Returns the cached backend detection result, if any.
     * Translated from getCachedDetectionResult() in registry.ts
     */
    public BackendDetectionResult getCachedDetectionResult() {
        return cachedDetectionResult;
    }

    /**
     * Records that spawn fell back to in-process mode.
     * Translated from markInProcessFallback() in registry.ts
     */
    public void markInProcessFallback() {
        log.debug("[BackendRegistry] Marking in-process fallback as active");
        inProcessFallbackActive.set(true);
    }

    // =========================================================================
    // In-process mode detection (registry.ts)
    // =========================================================================

    /**
     * Checks whether in-process teammate execution is enabled.
     * Translated from isInProcessEnabled() in registry.ts
     */
    public boolean isInProcessEnabled() {
        // Non-interactive sessions always use in-process
        if (bootstrapStateService.isNonInteractiveSession()) {
            log.debug("[BackendRegistry] isInProcessEnabled: true (non-interactive session)");
            return true;
        }

        TeammateMode mode = getTeammateMode();

        boolean enabled;
        if (mode == TeammateMode.IN_PROCESS) {
            enabled = true;
        } else if (mode == TeammateMode.TMUX) {
            enabled = false;
        } else {
            // AUTO mode
            if (inProcessFallbackActive.get()) {
                log.debug("[BackendRegistry] isInProcessEnabled: true (fallback after pane backend unavailable)");
                return true;
            }
            boolean insideTmux = detectionService.isInsideTmuxSync();
            boolean inITerm2   = detectionService.isInITerm2();
            enabled = !insideTmux && !inITerm2;
        }

        log.debug("[BackendRegistry] isInProcessEnabled: {} (mode={}, insideTmux={}, inITerm2={})",
                enabled, mode, detectionService.isInsideTmuxSync(), detectionService.isInITerm2());
        return enabled;
    }

    /**
     * Returns the resolved teammate mode ('in-process' or 'tmux'), resolving 'auto'.
     * Translated from getResolvedTeammateMode() in registry.ts
     */
    public TeammateMode getResolvedTeammateMode() {
        return isInProcessEnabled() ? TeammateMode.IN_PROCESS : TeammateMode.TMUX;
    }

    /**
     * Gets the teammate mode for this session (snapshot, not runtime config).
     * Translated from private getTeammateMode() in registry.ts
     */
    private TeammateMode getTeammateMode() {
        return getTeammateModeFromSnapshot();
    }

    // =========================================================================
    // iTerm2 preferences (it2Setup.ts — preference accessors used by registry)
    // =========================================================================

    /** Returns true if the user chose to prefer tmux over iTerm2. */
    public boolean getPreferTmuxOverIterm2() {
        String val = System.getenv("CLAUDE_CODE_PREFER_TMUX");
        return "true".equalsIgnoreCase(val) || "1".equals(val);
    }

    // =========================================================================
    // State reset (registry.ts)
    // =========================================================================

    /**
     * Resets all cached detection state.
     * Translated from resetBackendDetection() in registry.ts
     */
    public void resetBackendDetection() {
        cachedDetectionResult = null;
        initialTeammateMode   = null;
        backendsRegistered.set(false);
        inProcessFallbackActive.set(false);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Returns platform-specific tmux installation instructions.
     * Translated from getTmuxInstallInstructions() in registry.ts
     */
    private String getTmuxInstallInstructions() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return "To use agent swarms, install tmux:\n  brew install tmux\nThen start a tmux session with: tmux new-session -s claude";
        }
        if (os.contains("win")) {
            return "To use agent swarms, you need tmux which requires WSL (Windows Subsystem for Linux).\n"
                 + "Install WSL first, then inside WSL run:\n  sudo apt install tmux\n"
                 + "Then start a tmux session with: tmux new-session -s claude";
        }
        return "To use agent swarms, install tmux:\n  sudo apt install tmux    # Ubuntu/Debian\n"
             + "  sudo dnf install tmux    # Fedora/RHEL\n"
             + "Then start a tmux session with: tmux new-session -s claude";
    }
}
