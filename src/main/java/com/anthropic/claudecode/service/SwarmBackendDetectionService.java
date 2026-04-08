package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Swarm backend detection service.
 * Translated from src/utils/swarm/backends/detection.ts
 *
 * Detects tmux and iTerm2 availability for teammate pane management.
 *
 * TMUX and TMUX_PANE env vars are captured at class-load time to preserve
 * the user's original session context, since those vars may be overridden
 * later (e.g. when Claude's own tmux socket is initialized).
 */
@Slf4j
@Service
public class SwarmBackendDetectionService {

    public boolean isAgentSwarmsEnabled() { return false; }


    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SwarmBackendDetectionService.class);


    /**
     * Captured at class-load time to detect if the user started Claude from within tmux.
     * Shell service may override TMUX env var later, so we capture the original value.
     */
    private static final String ORIGINAL_USER_TMUX = System.getenv("TMUX");

    /**
     * Captured at class-load time to get the leader's tmux pane ID.
     * TMUX_PANE is set by tmux to the pane ID (e.g., %0, %1) when a process runs inside tmux.
     * Captured at startup so we always know the leader's original pane, even if the user
     * switches to a different pane later.
     */
    private static final String ORIGINAL_TMUX_PANE = System.getenv("TMUX_PANE");

    /** The it2 CLI command name. */
    public static final String IT2_COMMAND = "it2";

    /** The tmux CLI command name. */
    public static final String TMUX_COMMAND = "tmux";

    private volatile Boolean isInsideTmuxCached = null;
    private volatile Boolean isInITerm2Cached = null;

    /**
     * Synchronous check: are we running inside a tmux session?
     *
     * IMPORTANT: We ONLY check the TMUX env var. We do NOT run `tmux display-message`
     * as a fallback because that command will succeed if ANY tmux server is running
     * on the system, not just if THIS process is inside tmux.
     *
     * Translated from isInsideTmuxSync() in detection.ts
     */
    public boolean isInsideTmuxSync() {
        return ORIGINAL_USER_TMUX != null && !ORIGINAL_USER_TMUX.isBlank();
    }

    /**
     * Async check: are we running inside a tmux session?
     * Result is cached since it cannot change during the process lifetime.
     *
     * Translated from isInsideTmux() in detection.ts
     */
    public CompletableFuture<Boolean> isInsideTmuxAsync() {
        if (isInsideTmuxCached != null) {
            return CompletableFuture.completedFuture(isInsideTmuxCached);
        }
        isInsideTmuxCached = isInsideTmuxSync();
        return CompletableFuture.completedFuture(isInsideTmuxCached);
    }

    /**
     * Convenience synchronous variant (same logic, separate cached field).
     * Translated from isInsideTmux() — used by callers that don't need async.
     */
    public boolean isInsideTmux() {
        if (isInsideTmuxCached != null) return isInsideTmuxCached;
        isInsideTmuxCached = isInsideTmuxSync();
        return isInsideTmuxCached;
    }

    /**
     * Gets the leader's tmux pane ID captured at class-load time.
     * Returns null if not running inside tmux.
     *
     * Translated from getLeaderPaneId() in detection.ts
     */
    public String getLeaderPaneId() {
        String pane = ORIGINAL_TMUX_PANE;
        return (pane != null && !pane.isBlank()) ? pane : null;
    }

    /**
     * Checks if tmux is available on the system (installed and in PATH).
     *
     * Translated from isTmuxAvailable() in detection.ts
     */
    public CompletableFuture<Boolean> isTmuxAvailableAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Process process = new ProcessBuilder(TMUX_COMMAND, "-V")
                        .redirectErrorStream(true)
                        .start();
                int code = process.waitFor();
                return code == 0;
            } catch (IOException | InterruptedException e) {
                log.debug("[Detection] tmux not found: {}", e.getMessage());
                return false;
            }
        });
    }

    /**
     * Synchronous variant of isTmuxAvailable.
     * Translated from isTmuxAvailable() in detection.ts
     */
    public boolean isTmuxAvailable() {
        try {
            Process process = new ProcessBuilder(TMUX_COMMAND, "-V")
                    .redirectErrorStream(true)
                    .start();
            int code = process.waitFor();
            return code == 0;
        } catch (IOException | InterruptedException e) {
            log.debug("[Detection] tmux not found: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Checks if we're currently running inside iTerm2.
     * Uses multiple detection methods:
     *   1. TERM_PROGRAM env var set to "iTerm.app"
     *   2. ITERM_SESSION_ID env var is present
     *
     * Result is cached since it cannot change during the process lifetime.
     *
     * Note: iTerm2 backend uses the it2 CLI tool (Python-based).
     * No AppleScript / osascript required.
     *
     * Translated from isInITerm2() in detection.ts
     */
    public boolean isInITerm2() {
        if (isInITerm2Cached != null) return isInITerm2Cached;

        String termProgram = System.getenv("TERM_PROGRAM");
        boolean hasItermSessionId = System.getenv("ITERM_SESSION_ID") != null
                && !System.getenv("ITERM_SESSION_ID").isBlank();
        boolean termProgramIsITerm = "iTerm.app".equals(termProgram);

        isInITerm2Cached = termProgramIsITerm || hasItermSessionId;
        return isInITerm2Cached;
    }

    /**
     * Checks if the it2 CLI tool is available AND can reach the iTerm2 Python API.
     *
     * Uses 'session list' (not '--version') because --version succeeds even when
     * the Python API is disabled in iTerm2 preferences — which would cause
     * 'session split' to fail later with no fallback.
     *
     * Translated from isIt2CliAvailable() in detection.ts
     */
    public CompletableFuture<Boolean> isIt2CliAvailableAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Process process = new ProcessBuilder(IT2_COMMAND, "session", "list")
                        .redirectErrorStream(true)
                        .start();
                int code = process.waitFor();
                return code == 0;
            } catch (IOException | InterruptedException e) {
                log.debug("[Detection] it2 CLI not found: {}", e.getMessage());
                return false;
            }
        });
    }

    /**
     * Synchronous variant of isIt2CliAvailable.
     */
    public boolean isIt2CliAvailable() {
        try {
            Process process = new ProcessBuilder(IT2_COMMAND, "session", "list")
                    .redirectErrorStream(true)
                    .start();
            int code = process.waitFor();
            return code == 0;
        } catch (IOException | InterruptedException e) {
            log.debug("[Detection] it2 CLI not found: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Resets all cached detection results. Used for testing.
     *
     * Translated from resetDetectionCache() in detection.ts
     */
    public void resetDetectionCache() {
        isInsideTmuxCached = null;
        isInITerm2Cached = null;
    }
}
