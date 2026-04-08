package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.PlatformUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages an isolated tmux socket for Claude's operations.
 * Translated from src/utils/tmuxSocket.ts
 *
 * <p>Without isolation Claude could accidentally affect the user's tmux sessions.
 * For example, running {@code tmux kill-session} via the Bash tool would kill the
 * user's current session if they started Claude from within tmux.
 *
 * <p>Claude creates its own tmux socket ({@code claude-<PID>}) and ALL tmux commands
 * use this socket via the {@code -L} flag.  ALL Bash tool commands inherit a
 * {@code TMUX} env var pointing to this socket so that any {@code tmux} command
 * run through Claude operates on Claude's isolated socket, NOT the user's session.
 */
@Slf4j
@Service
public class TmuxSocketService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TmuxSocketService.class);


    private static final String TMUX_COMMAND = "tmux";
    private static final String CLAUDE_SOCKET_PREFIX = "claude";

    // ── Socket state (initialized lazily on first use) ────────────────────────

    private final AtomicReference<String>  socketName    = new AtomicReference<>(null);
    private final AtomicReference<String>  socketPath    = new AtomicReference<>(null);
    private final AtomicReference<Integer> serverPid     = new AtomicReference<>(null);

    private final AtomicBoolean isInitializing       = new AtomicBoolean(false);
    private volatile CompletableFuture<Void> initFuture = null;

    // ── tmux availability (checked once) ──────────────────────────────────────

    private final AtomicBoolean tmuxAvailabilityChecked = new AtomicBoolean(false);
    private final AtomicBoolean tmuxAvailable           = new AtomicBoolean(false);

    // ── Tmux-tool-used flag ───────────────────────────────────────────────────

    private final AtomicBoolean tmuxToolUsed = new AtomicBoolean(false);

    // =========================================================================
    // Socket name / path / env
    // =========================================================================

    /**
     * Gets the socket name for Claude's isolated tmux session ({@code claude-<PID>}).
     * Translated from {@code getClaudeSocketName()} in tmuxSocket.ts.
     */
    public String getClaudeSocketName() {
        return socketName.updateAndGet(existing ->
                existing != null ? existing
                        : CLAUDE_SOCKET_PREFIX + "-" + ProcessHandle.current().pid());
    }

    /**
     * Gets the socket path if the socket has been initialized; null otherwise.
     * Translated from {@code getClaudeSocketPath()} in tmuxSocket.ts.
     */
    public String getClaudeSocketPath() {
        return socketPath.get();
    }

    /**
     * Sets socket info after initialization.
     * Translated from {@code setClaudeSocketInfo()} in tmuxSocket.ts.
     */
    public void setClaudeSocketInfo(String path, int pid) {
        socketPath.set(path);
        serverPid.set(pid);
    }

    /**
     * Returns whether the socket has been initialized.
     * Translated from {@code isSocketInitialized()} in tmuxSocket.ts.
     */
    public boolean isSocketInitialized() {
        return socketPath.get() != null && serverPid.get() != null;
    }

    /**
     * Gets the TMUX environment variable value for Claude's isolated socket.
     *
     * <p>Format: {@code "socket_path,server_pid,pane_index"} — matches tmux's own TMUX env var.
     * Returns null if the socket is not yet initialized.
     *
     * Translated from {@code getClaudeTmuxEnv()} in tmuxSocket.ts.
     */
    public String getClaudeTmuxEnv() {
        String path = socketPath.get();
        Integer pid  = serverPid.get();
        if (path == null || pid == null) {
            return null;
        }
        return path + "," + pid + ",0";
    }

    // =========================================================================
    // Availability check
    // =========================================================================

    /**
     * Checks if tmux is available on this system (result is cached).
     * Translated from {@code checkTmuxAvailable()} in tmuxSocket.ts.
     */
    public CompletableFuture<Boolean> checkTmuxAvailable() {
        if (tmuxAvailabilityChecked.get()) {
            return CompletableFuture.completedFuture(tmuxAvailable.get());
        }

        return CompletableFuture.supplyAsync(() -> {
            if (tmuxAvailabilityChecked.get()) {
                return tmuxAvailable.get();
            }

            boolean available;
            if (PlatformUtils.isWindows()) {
                available = execRaw(List.of("wsl", "-e", TMUX_COMMAND, "-V")).code() == 0;
            } else {
                available = execRaw(List.of("which", TMUX_COMMAND)).code() == 0;
            }

            tmuxAvailable.set(available);
            tmuxAvailabilityChecked.set(true);

            if (!available) {
                log.debug("[Socket] tmux is not installed. The Tmux tool and Teammate tool will not be available.");
            }
            return available;
        });
    }

    /**
     * Returns cached tmux availability (false if not yet checked).
     * Translated from {@code isTmuxAvailable()} in tmuxSocket.ts.
     */
    public boolean isTmuxAvailable() {
        return tmuxAvailabilityChecked.get() && tmuxAvailable.get();
    }

    // =========================================================================
    // Tmux-tool-used flag
    // =========================================================================

    /**
     * Marks that the Tmux tool has been used at least once.
     * Translated from {@code markTmuxToolUsed()} in tmuxSocket.ts.
     */
    public void markTmuxToolUsed() {
        tmuxToolUsed.set(true);
    }

    /**
     * Returns whether the Tmux tool has been used at least once.
     * Translated from {@code hasTmuxToolBeenUsed()} in tmuxSocket.ts.
     */
    public boolean hasTmuxToolBeenUsed() {
        return tmuxToolUsed.get();
    }

    // =========================================================================
    // Socket initialization
    // =========================================================================

    /**
     * Ensures the socket is initialized with a tmux session.
     * Safe to call multiple times; initializes only once.
     * Translated from {@code ensureSocketInitialized()} in tmuxSocket.ts.
     */
    public CompletableFuture<Void> ensureSocketInitialized() {
        if (isSocketInitialized()) {
            return CompletableFuture.completedFuture(null);
        }

        return checkTmuxAvailable().thenCompose(available -> {
            if (!available) {
                return CompletableFuture.completedFuture(null);
            }

            // If already initializing, join the existing future
            if (isInitializing.get() && initFuture != null) {
                return initFuture.exceptionally(e -> null); // swallow errors for waiters
            }

            if (!isInitializing.compareAndSet(false, true)) {
                // Lost the race — another thread is initializing
                CompletableFuture<Void> existing = initFuture;
                return existing != null ? existing.exceptionally(e -> null)
                        : CompletableFuture.completedFuture(null);
            }

            initFuture = doInitialize().whenComplete((v, err) -> {
                if (err != null) {
                    log.error("[Socket] Failed to initialize tmux socket: {}. Tmux isolation will be disabled.",
                            err.getMessage());
                }
                isInitializing.set(false);
            });

            return initFuture;
        });
    }

    // =========================================================================
    // execTmux helper
    // =========================================================================

    /**
     * Executes a tmux command, routing through WSL on Windows.
     * Translated from {@code execTmux()} in tmuxSocket.ts.
     */
    public CompletableFuture<ExecResult> execTmux(List<String> args) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> cmd = new ArrayList<>();
            if (PlatformUtils.isWindows()) {
                cmd.add("wsl");
                cmd.add("-e");
            }
            cmd.add(TMUX_COMMAND);
            cmd.addAll(args);
            return execRaw(cmd);
        });
    }

    // =========================================================================
    // Reset (for testing)
    // =========================================================================

    /**
     * Resets all socket state. For testing only.
     * Translated from {@code resetSocketState()} in tmuxSocket.ts.
     */
    public void resetSocketState() {
        socketName.set(null);
        socketPath.set(null);
        serverPid.set(null);
        isInitializing.set(false);
        initFuture = null;
        tmuxAvailabilityChecked.set(false);
        tmuxAvailable.set(false);
        tmuxToolUsed.set(false);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Result of a raw process execution.
     */
    public record ExecResult(String stdout, String stderr, int code) {}

    private ExecResult execRaw(List<String> command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();
            String stdout = new String(process.getInputStream().readAllBytes());
            String stderr = new String(process.getErrorStream().readAllBytes());
            int code = process.waitFor();
            return new ExecResult(stdout.trim(), stderr.trim(), code);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ExecResult("", e.getMessage(), 1);
        }
    }

    private CompletableFuture<Void> doInitialize() {
        return CompletableFuture.runAsync(() -> {
            String socket = getClaudeSocketName();

            // Build new-session command
            List<String> newSessionArgs = new ArrayList<>(List.of(
                    "-L", socket, "new-session", "-d", "-s", "base",
                    "-e", "CLAUDE_CODE_SKIP_PROMPT_HISTORY=true"
            ));
            if (PlatformUtils.isWindows()) {
                newSessionArgs.addAll(List.of("-e", "WSL_INTEROP=/run/WSL/1_interop"));
            }

            ExecResult result = execTmux(newSessionArgs).join();

            if (result.code() != 0) {
                // Session might already exist
                ExecResult checkResult = execTmux(
                        List.of("-L", socket, "has-session", "-t", "base")).join();
                if (checkResult.code() != 0) {
                    throw new RuntimeException(
                            "Failed to create tmux session on socket " + socket + ": " + result.stderr());
                }
            }

            // Set global environment for CLAUDE_CODE_SKIP_PROMPT_HISTORY
            execTmux(List.of("-L", socket, "set-environment", "-g",
                    "CLAUDE_CODE_SKIP_PROMPT_HISTORY", "true")).join();

            if (PlatformUtils.isWindows()) {
                execTmux(List.of("-L", socket, "set-environment", "-g",
                        "WSL_INTEROP", "/run/WSL/1_interop")).join();
            }

            // Get socket path and server PID
            ExecResult infoResult = execTmux(List.of(
                    "-L", socket, "display-message", "-p", "#{socket_path},#{pid}")).join();

            if (infoResult.code() == 0) {
                String[] parts = infoResult.stdout().split(",", 2);
                if (parts.length == 2) {
                    try {
                        int pid = Integer.parseInt(parts[1].trim());
                        setClaudeSocketInfo(parts[0].trim(), pid);
                        return;
                    } catch (NumberFormatException e) {
                        log.debug("[Socket] Failed to parse socket info: \"{}\"", infoResult.stdout());
                    }
                }
            } else {
                log.debug("[Socket] Failed to get socket info via display-message (exit {}): {}",
                        infoResult.code(), infoResult.stderr());
            }

            // Fallback: construct path from standard tmux location
            String tmpDir = System.getenv("TMPDIR");
            if (tmpDir == null) tmpDir = "/tmp";
            long uid;
            try {
                uid = (Long) java.lang.management.ManagementFactory
                        .getOperatingSystemMXBean().getClass()
                        .getMethod("getUid").invoke(
                                java.lang.management.ManagementFactory.getOperatingSystemMXBean());
            } catch (Exception e) {
                uid = 0;
            }
            String fallbackPath = tmpDir + "/tmux-" + uid + "/" + socket;

            ExecResult pidResult = execTmux(List.of(
                    "-L", socket, "display-message", "-p", "#{pid}")).join();

            if (pidResult.code() == 0) {
                try {
                    int pid = Integer.parseInt(pidResult.stdout().trim());
                    log.debug("[Socket] Using fallback socket path: {} (server PID: {})", fallbackPath, pid);
                    setClaudeSocketInfo(fallbackPath, pid);
                    return;
                } catch (NumberFormatException e) {
                    log.debug("[Socket] Failed to parse server PID: \"{}\"", pidResult.stdout());
                }
            } else {
                log.debug("[Socket] Failed to get server PID (exit {}): {}", pidResult.code(), pidResult.stderr());
            }

            throw new RuntimeException(
                    "Failed to get socket info for " + socket
                            + ": primary=\"" + infoResult.stderr()
                            + "\", fallback=\"" + pidResult.stderr() + "\"");
        });
    }
}
