package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Built-in terminal panel management (toggled with Meta+J).
 * Translated from src/utils/terminalPanel.ts
 *
 * <p>Uses tmux for shell persistence: a separate tmux server with a per-instance
 * socket ({@code claude-panel-<8-char-uuid>}) holds the shell session. Each Claude
 * Code instance gets its own isolated terminal panel that persists within the
 * session but is destroyed when the instance exits.
 *
 * <p>When tmux is not available, falls back to a non-persistent shell via
 * {@link ProcessBuilder}.
 */
@Slf4j
public class TerminalPanelUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TerminalPanelUtils.class);


    private static final String TMUX_SESSION = "panel";

    // ── Singleton ─────────────────────────────────────────────────────────────

    private static volatile TerminalPanel instance;

    /**
     * Return the singleton {@link TerminalPanel}, creating it lazily on first use.
     * Translated from {@code getTerminalPanel()} in terminalPanel.ts.
     */
    public static TerminalPanel getTerminalPanel() {
        if (instance == null) {
            synchronized (TerminalPanelUtils.class) {
                if (instance == null) {
                    instance = new TerminalPanel();
                }
            }
        }
        return instance;
    }

    /**
     * Get the tmux socket name for the terminal panel.
     *
     * <p>Uses first 8 characters of the session ID for uniqueness while keeping
     * the socket name short.  Each Claude Code instance gets its own isolated
     * terminal panel.
     * Translated from {@code getTerminalPanelSocket()} in terminalPanel.ts.
     *
     * @param sessionId the full session UUID
     * @return socket name in the form {@code claude-panel-XXXXXXXX}
     */
    public static String getTerminalPanelSocket(String sessionId) {
        return "claude-panel-" + sessionId.substring(0, Math.min(8, sessionId.length()));
    }

    // =========================================================================
    // TerminalPanel class
    // =========================================================================

    /**
     * Manages the lifecycle of a tmux-backed terminal panel.
     * Translated from the {@code TerminalPanel} class in terminalPanel.ts.
     */
    @Slf4j
    public static class TerminalPanel {

        private Boolean hasTmux = null;
        private final AtomicBoolean cleanupRegistered = new AtomicBoolean(false);

        /** Session ID injected or resolved at construction time. */
        private final String sessionId;

        /**
         * Create a panel using the provided session ID.
         * In production this is typically obtained from a bootstrap state bean.
         *
         * @param sessionId 36-character UUID session identifier
         */
        public TerminalPanel(String sessionId) {
            this.sessionId = sessionId;
        }

        /** Create a panel with a randomly generated session ID (for standalone use). */
        public TerminalPanel() {
            this(java.util.UUID.randomUUID().toString());
        }

        // ── Public API ────────────────────────────────────────────────────────

        /**
         * Toggle (show) the terminal panel.
         * Translated from {@code toggle()} in terminalPanel.ts.
         */
        public void toggle() {
            showShell();
        }

        // ── tmux helpers ──────────────────────────────────────────────────────

        /**
         * Check whether tmux is available on this system, memoizing the result.
         * Translated from {@code checkTmux()} in terminalPanel.ts.
         */
        public boolean checkTmux() {
            if (hasTmux != null) return hasTmux;
            try {
                ProcessBuilder pb = new ProcessBuilder("tmux", "-V");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                hasTmux = p.waitFor() == 0;
            } catch (Exception e) {
                hasTmux = false;
            }
            if (!hasTmux) {
                log.debug("Terminal panel: tmux not found, falling back to non-persistent shell");
            }
            return hasTmux;
        }

        /**
         * Check whether a tmux session already exists on the panel socket.
         * Translated from {@code hasSession()} in terminalPanel.ts.
         */
        public boolean hasSession() {
            return execTmux(List.of("-L", socket(), "has-session", "-t", TMUX_SESSION)) == 0;
        }

        /**
         * Create a new tmux session on the panel socket.
         * Translated from {@code createSession()} in terminalPanel.ts.
         *
         * @return true if the session was created successfully
         */
        public boolean createSession() {
            String shell = System.getenv("SHELL");
            if (shell == null) shell = "/bin/bash";
            String cwd = System.getProperty("user.dir");
            String socketName = socket();

            int rc = execTmux(List.of(
                    "-L", socketName, "new-session", "-d", "-s", TMUX_SESSION,
                    "-c", cwd, shell, "-l"
            ));

            if (rc != 0) {
                log.debug("Terminal panel: failed to create tmux session");
                return false;
            }

            // Bind Meta+J to detach-client and configure the status bar
            execTmux(List.of(
                    "-L", socketName,
                    "bind-key", "-n", "M-j", "detach-client", ";",
                    "set-option", "-g", "status-style", "bg=default", ";",
                    "set-option", "-g", "status-left", "", ";",
                    "set-option", "-g", "status-right", " Alt+J to return to Claude ", ";",
                    "set-option", "-g", "status-right-style", "fg=brightblack"
            ));

            if (cleanupRegistered.compareAndSet(false, true)) {
                // Register JVM shutdown hook to kill the tmux server on exit
                final String socketForCleanup = socketName;
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        new ProcessBuilder("tmux", "-L", socketForCleanup, "kill-server")
                                .redirectErrorStream(true)
                                .start();
                        // Fire-and-forget; do not wait
                    } catch (IOException e) {
                        // Swallow — tmux may already be gone
                    }
                }, "terminal-panel-cleanup"));
            }

            return true;
        }

        /**
         * Attach to an existing tmux session on the panel socket.
         * Translated from {@code attachSession()} in terminalPanel.ts.
         */
        public void attachSession() {
            execTmuxInherited(List.of(
                    "-L", socket(), "attach-session", "-t", TMUX_SESSION));
        }

        // ── show shell ────────────────────────────────────────────────────────

        /**
         * Show the terminal panel (tmux-backed or direct shell fallback).
         * Translated from {@code showShell()} in terminalPanel.ts.
         */
        private void showShell() {
            if (checkTmux() && ensureSession()) {
                attachSession();
            } else {
                runShellDirect();
            }
        }

        /**
         * Ensure a tmux session exists, creating one if needed.
         * Translated from {@code ensureSession()} in terminalPanel.ts.
         */
        private boolean ensureSession() {
            if (hasSession()) return true;
            return createSession();
        }

        /**
         * Fallback: run a non-persistent interactive shell directly.
         * Translated from {@code runShellDirect()} in terminalPanel.ts.
         */
        private void runShellDirect() {
            String shell = System.getenv("SHELL");
            if (shell == null) shell = "/bin/bash";
            String cwd = System.getProperty("user.dir");
            try {
                ProcessBuilder pb = new ProcessBuilder(shell, "-i", "-l");
                pb.directory(new java.io.File(cwd));
                pb.inheritIO();
                pb.start().waitFor();
            } catch (Exception e) {
                log.error("Terminal panel: failed to run shell: {}", e.getMessage());
            }
        }

        // ── helpers ───────────────────────────────────────────────────────────

        private String socket() {
            return getTerminalPanelSocket(sessionId);
        }

        /** Execute a tmux subcommand and return the exit code. */
        private int execTmux(List<String> args) {
            List<String> cmd = new ArrayList<>();
            cmd.add("tmux");
            cmd.addAll(args);
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                return p.waitFor();
            } catch (Exception e) {
                return 1;
            }
        }

        /** Execute a tmux subcommand with inherited stdio (for attach). */
        private void execTmuxInherited(List<String> args) {
            List<String> cmd = new ArrayList<>();
            cmd.add("tmux");
            cmd.addAll(args);
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.inheritIO();
                pb.start().waitFor();
            } catch (Exception e) {
                log.debug("Terminal panel: tmux attach failed: {}", e.getMessage());
            }
        }
    }

    private TerminalPanelUtils() {}
}
