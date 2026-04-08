package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Tmux pane management backend.
 * Translated from src/utils/swarm/backends/TmuxBackend.ts
 *
 * Implements pane creation, styling, layout rebalancing, and hide/show
 * operations using the tmux CLI via ProcessBuilder.
 *
 * When running INSIDE tmux (leader is in tmux):
 *   - Splits the current window to add teammates alongside the leader
 *   - Leader stays on left (30%), teammates on right (70%)
 *
 * When running OUTSIDE tmux (leader is in a regular terminal):
 *   - Creates a claude-swarm session with a swarm-view window
 *   - All teammates are equally distributed (no leader pane)
 */
@Slf4j
@Service
public class TmuxBackendService {



    public static final String TMUX_COMMAND = "tmux";
    public static final String SWARM_SESSION_NAME = "claude-swarm";
    public static final String SWARM_VIEW_WINDOW_NAME = "swarm-view";
    public static final String HIDDEN_SESSION_NAME = "claude-swarm-hidden";

    private static final int PANE_SHELL_INIT_DELAY_MS = 200;

    /** Whether the first pane has already been reused for an external swarm session. */
    private volatile boolean firstPaneUsedForExternal = false;

    /** Cached leader window target (session:window format). */
    private volatile String cachedLeaderWindowTarget = null;

    /** Serializes pane creation to prevent race conditions. */
    private final ReentrantLock paneCreationLock = new ReentrantLock(true);

    private final SwarmBackendDetectionService detectionService;

    @Autowired
    public TmuxBackendService(SwarmBackendDetectionService detectionService) {
        this.detectionService = detectionService;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public String getType() { return "tmux"; }
    public String getDisplayName() { return "tmux"; }
    public boolean isSupportsHideShow() { return true; }

    /**
     * Checks if tmux is installed and available.
     * Translated from isAvailable() in TmuxBackend.ts
     */
    public CompletableFuture<Boolean> isAvailable() {
        return detectionService.isTmuxAvailableAsync();
    }

    /**
     * Checks if we're currently running inside a tmux session.
     * Translated from isRunningInside() in TmuxBackend.ts
     */
    public CompletableFuture<Boolean> isRunningInside() {
        return detectionService.isInsideTmuxAsync();
    }

    /**
     * Creates a new teammate pane in the swarm view.
     * Uses a lock to prevent race conditions when multiple teammates are spawned in parallel.
     * Translated from createTeammatePaneInSwarmView() in TmuxBackend.ts
     */
    public CompletableFuture<CreatePaneResult> createTeammatePaneInSwarmView(
            String name, String color) {
        return CompletableFuture.supplyAsync(() -> {
            paneCreationLock.lock();
            try {
                boolean insideTmux = detectionService.isInsideTmux();
                if (insideTmux) {
                    return createTeammatePaneWithLeader(name, color);
                }
                return createTeammatePaneExternal(name, color);
            } finally {
                paneCreationLock.unlock();
            }
        });
    }

    /**
     * Sends a command to a specific pane.
     * Translated from sendCommandToPane() in TmuxBackend.ts
     */
    public CompletableFuture<Void> sendCommandToPane(
            String paneId, String command, boolean useExternalSession) {
        return CompletableFuture.runAsync(() -> {
            TmuxResult result = runTmux(useExternalSession,
                    "send-keys", "-t", paneId, command, "Enter");
            if (result.code() != 0) {
                throw new RuntimeException(
                        "Failed to send command to pane " + paneId + ": " + result.stderr());
            }
        });
    }

    /**
     * Sends a command using the user session (non-external).
     */
    public CompletableFuture<Void> sendCommandToPane(String paneId, String command) {
        return sendCommandToPane(paneId, command, false);
    }

    /**
     * Sets the border color for a specific pane (requires tmux 3.2+).
     * Translated from setPaneBorderColor() in TmuxBackend.ts
     */
    public CompletableFuture<Void> setPaneBorderColor(
            String paneId, String color, boolean useExternalSession) {
        return CompletableFuture.runAsync(() -> {
            String tmuxColor = getTmuxColorName(color);

            runTmux(useExternalSession,
                    "select-pane", "-t", paneId, "-P", "bg=default,fg=" + tmuxColor);
            runTmux(useExternalSession,
                    "set-option", "-p", "-t", paneId, "pane-border-style", "fg=" + tmuxColor);
            runTmux(useExternalSession,
                    "set-option", "-p", "-t", paneId, "pane-active-border-style", "fg=" + tmuxColor);
        });
    }

    /**
     * Sets the title for a pane (shown in pane border if pane-border-status is set).
     * Translated from setPaneTitle() in TmuxBackend.ts
     */
    public CompletableFuture<Void> setPaneTitle(
            String paneId, String name, String color, boolean useExternalSession) {
        return CompletableFuture.runAsync(() -> {
            String tmuxColor = getTmuxColorName(color);

            runTmux(useExternalSession, "select-pane", "-t", paneId, "-T", name);
            runTmux(useExternalSession,
                    "set-option", "-p", "-t", paneId,
                    "pane-border-format",
                    "#[fg=" + tmuxColor + ",bold] #{pane_title} #[default]");
        });
    }

    /**
     * Enables pane border status for a window (shows pane titles).
     * Translated from enablePaneBorderStatus() in TmuxBackend.ts
     */
    public CompletableFuture<Void> enablePaneBorderStatus(
            String windowTarget, boolean useExternalSession) {
        return CompletableFuture.runAsync(() -> {
            String target = windowTarget;
            if (target == null || target.isBlank()) {
                target = getCurrentWindowTarget();
            }
            if (target == null) return;

            runTmux(useExternalSession,
                    "set-option", "-w", "-t", target, "pane-border-status", "top");
        });
    }

    /**
     * Enables pane border status using the current window.
     */
    public CompletableFuture<Void> enablePaneBorderStatus() {
        return enablePaneBorderStatus(null, false);
    }

    /**
     * Rebalances panes to achieve the desired layout.
     * Translated from rebalancePanes() in TmuxBackend.ts
     */
    public CompletableFuture<Void> rebalancePanes(String windowTarget, boolean hasLeader) {
        return CompletableFuture.runAsync(() -> {
            if (hasLeader) {
                rebalancePanesWithLeader(windowTarget);
            } else {
                rebalancePanesTiled(windowTarget);
            }
        });
    }

    /**
     * Kills/closes a specific pane.
     * Translated from killPane() in TmuxBackend.ts
     */
    public CompletableFuture<Boolean> killPane(String paneId, boolean useExternalSession) {
        return CompletableFuture.supplyAsync(() -> {
            TmuxResult result = runTmux(useExternalSession, "kill-pane", "-t", paneId);
            return result.code() == 0;
        });
    }

    /**
     * Hides a pane by moving it to a detached hidden session.
     * Creates the hidden session if it doesn't exist, then uses break-pane to move the pane there.
     * Translated from hidePane() in TmuxBackend.ts
     */
    public CompletableFuture<Boolean> hidePane(String paneId, boolean useExternalSession) {
        return CompletableFuture.supplyAsync(() -> {
            // Create hidden session if it doesn't exist
            runTmux(useExternalSession, "new-session", "-d", "-s", HIDDEN_SESSION_NAME);

            TmuxResult result = runTmux(useExternalSession,
                    "break-pane", "-d", "-s", paneId, "-t", HIDDEN_SESSION_NAME + ":");

            if (result.code() == 0) {
                log.debug("[TmuxBackend] Hidden pane {}", paneId);
            } else {
                log.debug("[TmuxBackend] Failed to hide pane {}: {}", paneId, result.stderr());
            }
            return result.code() == 0;
        });
    }

    /**
     * Shows a previously hidden pane by joining it back into the target window.
     * Reapplies main-vertical layout with leader at 30%.
     * Translated from showPane() in TmuxBackend.ts
     */
    public CompletableFuture<Boolean> showPane(
            String paneId, String targetWindowOrPane, boolean useExternalSession) {
        return CompletableFuture.supplyAsync(() -> {
            TmuxResult result = runTmux(useExternalSession,
                    "join-pane", "-h", "-s", paneId, "-t", targetWindowOrPane);

            if (result.code() != 0) {
                log.debug("[TmuxBackend] Failed to show pane {}: {}", paneId, result.stderr());
                return false;
            }

            log.debug("[TmuxBackend] Showed pane {} in {}", paneId, targetWindowOrPane);

            // Reapply main-vertical layout with leader at 30%
            runTmux(useExternalSession, "select-layout", "-t", targetWindowOrPane, "main-vertical");

            TmuxResult panesResult = runTmux(useExternalSession,
                    "list-panes", "-t", targetWindowOrPane, "-F", "#{pane_id}");
            List<String> panes = splitLines(panesResult.stdout());
            if (!panes.isEmpty()) {
                runTmux(useExternalSession, "resize-pane", "-t", panes.get(0), "-x", "30%");
            }

            return true;
        });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Gets the leader's pane ID from the TMUX_PANE env var captured at startup.
     * Falls back to a dynamic `tmux display-message` query.
     * Translated from getCurrentPaneId() in TmuxBackend.ts
     */
    private String getCurrentPaneId() {
        String leaderPane = detectionService.getLeaderPaneId();
        if (leaderPane != null) return leaderPane;

        // Fallback: dynamic query (shouldn't happen if we're inside tmux)
        TmuxResult result = runUserSession("display-message", "-p", "#{pane_id}");
        if (result.code() != 0) {
            log.debug("[TmuxBackend] Failed to get current pane ID (exit {}): {}",
                    result.code(), result.stderr());
            return null;
        }
        return result.stdout().trim();
    }

    /**
     * Gets the leader's window target (session:window format).
     * Result is cached since the leader's window won't change.
     * Translated from getCurrentWindowTarget() in TmuxBackend.ts
     */
    private String getCurrentWindowTarget() {
        if (cachedLeaderWindowTarget != null) return cachedLeaderWindowTarget;

        String leaderPane = detectionService.getLeaderPaneId();
        List<String> args = new ArrayList<>(List.of("display-message"));
        if (leaderPane != null) {
            args.add("-t");
            args.add(leaderPane);
        }
        args.add("-p");
        args.add("#{session_name}:#{window_index}");

        TmuxResult result = runUserSessionArgs(args);
        if (result.code() != 0) {
            log.debug("[TmuxBackend] Failed to get current window target (exit {}): {}",
                    result.code(), result.stderr());
            return null;
        }

        cachedLeaderWindowTarget = result.stdout().trim();
        return cachedLeaderWindowTarget;
    }

    /**
     * Gets the number of panes in a window.
     * Translated from getCurrentWindowPaneCount() in TmuxBackend.ts
     */
    private Integer getCurrentWindowPaneCount(String windowTarget, boolean useSwarmSocket) {
        String target = (windowTarget != null) ? windowTarget : getCurrentWindowTarget();
        if (target == null) return null;

        TmuxResult result = runTmux(useSwarmSocket, "list-panes", "-t", target, "-F", "#{pane_id}");
        if (result.code() != 0) {
            log.error("[TmuxBackend] Failed to get pane count for {} (exit {}): {}",
                    target, result.code(), result.stderr());
            return null;
        }
        return (int) splitLines(result.stdout()).stream().filter(s -> !s.isBlank()).count();
    }

    /**
     * Checks if a tmux session exists in the swarm socket.
     * Translated from hasSessionInSwarm() in TmuxBackend.ts
     */
    private boolean hasSessionInSwarm(String sessionName) {
        TmuxResult result = runSwarmSession("has-session", "-t", sessionName);
        return result.code() == 0;
    }

    /**
     * Creates the swarm session with a single window for teammates when running outside tmux.
     * Translated from createExternalSwarmSession() in TmuxBackend.ts
     */
    private ExternalSwarmSession createExternalSwarmSession() {
        boolean sessionExists = hasSessionInSwarm(SWARM_SESSION_NAME);

        if (!sessionExists) {
            TmuxResult result = runSwarmSession(
                    "new-session", "-d", "-s", SWARM_SESSION_NAME,
                    "-n", SWARM_VIEW_WINDOW_NAME, "-P", "-F", "#{pane_id}");

            if (result.code() != 0) {
                throw new RuntimeException(
                        "Failed to create swarm session: " +
                                (result.stderr().isBlank() ? "Unknown error" : result.stderr()));
            }

            String paneId = result.stdout().trim();
            String windowTarget = SWARM_SESSION_NAME + ":" + SWARM_VIEW_WINDOW_NAME;
            log.debug("[TmuxBackend] Created external swarm session with window {}, pane {}",
                    windowTarget, paneId);
            return new ExternalSwarmSession(windowTarget, paneId);
        }

        // Session exists — check if swarm-view window exists
        TmuxResult listResult = runSwarmSession(
                "list-windows", "-t", SWARM_SESSION_NAME, "-F", "#{window_name}");
        List<String> windows = splitLines(listResult.stdout());
        String windowTarget = SWARM_SESSION_NAME + ":" + SWARM_VIEW_WINDOW_NAME;

        if (windows.contains(SWARM_VIEW_WINDOW_NAME)) {
            TmuxResult paneResult = runSwarmSession(
                    "list-panes", "-t", windowTarget, "-F", "#{pane_id}");
            List<String> panes = splitLines(paneResult.stdout());
            return new ExternalSwarmSession(windowTarget, panes.isEmpty() ? "" : panes.get(0));
        }

        // Create the swarm-view window
        TmuxResult createResult = runSwarmSession(
                "new-window", "-t", SWARM_SESSION_NAME,
                "-n", SWARM_VIEW_WINDOW_NAME, "-P", "-F", "#{pane_id}");

        if (createResult.code() != 0) {
            throw new RuntimeException(
                    "Failed to create swarm-view window: " +
                            (createResult.stderr().isBlank() ? "Unknown error" : createResult.stderr()));
        }
        return new ExternalSwarmSession(windowTarget, createResult.stdout().trim());
    }

    /**
     * Creates a teammate pane when running inside tmux (with leader).
     * Translated from createTeammatePaneWithLeader() in TmuxBackend.ts
     */
    private CreatePaneResult createTeammatePaneWithLeader(String teammateName, String teammateColor) {
        String currentPaneId = getCurrentPaneId();
        String windowTarget = getCurrentWindowTarget();

        if (currentPaneId == null || windowTarget == null) {
            throw new RuntimeException("Could not determine current tmux pane/window");
        }

        Integer paneCount = getCurrentWindowPaneCount(windowTarget, false);
        if (paneCount == null) {
            throw new RuntimeException("Could not determine pane count for current window");
        }

        boolean isFirstTeammate = paneCount == 1;
        TmuxResult splitResult;

        if (isFirstTeammate) {
            // First teammate: split horizontally from the leader pane at 70%
            splitResult = runUserSession(
                    "split-window", "-t", currentPaneId, "-h", "-l", "70%", "-P", "-F", "#{pane_id}");
        } else {
            // Additional teammates: split from an existing teammate pane
            TmuxResult listResult = runUserSession(
                    "list-panes", "-t", windowTarget, "-F", "#{pane_id}");
            List<String> panes = splitLines(listResult.stdout());
            List<String> teammatePanes = panes.subList(1, panes.size()); // skip leader
            int teammateCount = teammatePanes.size();

            boolean splitVertically = teammateCount % 2 == 1;
            int targetPaneIndex = Math.max(0, (teammateCount - 1) / 2);
            String targetPane = teammatePanes.isEmpty()
                    ? panes.get(panes.size() - 1)
                    : teammatePanes.get(Math.min(targetPaneIndex, teammatePanes.size() - 1));

            splitResult = runUserSession(
                    "split-window", "-t", targetPane,
                    splitVertically ? "-v" : "-h", "-P", "-F", "#{pane_id}");
        }

        if (splitResult.code() != 0) {
            throw new RuntimeException("Failed to create teammate pane: " + splitResult.stderr());
        }

        String paneId = splitResult.stdout().trim();
        log.debug("[TmuxBackend] Created teammate pane for {}: {}", teammateName, paneId);

        setPaneBorderColor(paneId, teammateColor, false).join();
        setPaneTitle(paneId, teammateName, teammateColor, false).join();
        rebalancePanesWithLeader(windowTarget);

        sleepPaneShellInit();

        return new CreatePaneResult(paneId, isFirstTeammate);
    }

    /**
     * Creates a teammate pane when running outside tmux (no leader in tmux).
     * Translated from createTeammatePaneExternal() in TmuxBackend.ts
     */
    private CreatePaneResult createTeammatePaneExternal(String teammateName, String teammateColor) {
        ExternalSwarmSession session = createExternalSwarmSession();
        String windowTarget = session.windowTarget();
        String firstPaneId = session.paneId();

        Integer paneCount = getCurrentWindowPaneCount(windowTarget, true);
        if (paneCount == null) {
            throw new RuntimeException("Could not determine pane count for swarm window");
        }

        boolean isFirstTeammate = !firstPaneUsedForExternal && paneCount == 1;
        String paneId;

        if (isFirstTeammate) {
            paneId = firstPaneId;
            firstPaneUsedForExternal = true;
            log.debug("[TmuxBackend] Using initial pane for first teammate {}: {}", teammateName, paneId);
            enablePaneBorderStatus(windowTarget, true).join();
        } else {
            TmuxResult listResult = runSwarmSession(
                    "list-panes", "-t", windowTarget, "-F", "#{pane_id}");
            List<String> panes = splitLines(listResult.stdout());
            int teammateCount = panes.size();

            boolean splitVertically = teammateCount % 2 == 1;
            int targetPaneIndex = Math.max(0, (teammateCount - 1) / 2);
            String targetPane = panes.isEmpty()
                    ? firstPaneId
                    : panes.get(Math.min(targetPaneIndex, panes.size() - 1));

            TmuxResult splitResult = runSwarmSession(
                    "split-window", "-t", targetPane,
                    splitVertically ? "-v" : "-h", "-P", "-F", "#{pane_id}");

            if (splitResult.code() != 0) {
                throw new RuntimeException("Failed to create teammate pane: " + splitResult.stderr());
            }

            paneId = splitResult.stdout().trim();
            log.debug("[TmuxBackend] Created teammate pane for {}: {}", teammateName, paneId);
        }

        setPaneBorderColor(paneId, teammateColor, true).join();
        setPaneTitle(paneId, teammateName, teammateColor, true).join();
        rebalancePanesTiled(windowTarget);

        sleepPaneShellInit();

        return new CreatePaneResult(paneId, isFirstTeammate);
    }

    /**
     * Rebalances panes with leader at 30% (main-vertical layout).
     * Translated from rebalancePanesWithLeader() in TmuxBackend.ts
     */
    private void rebalancePanesWithLeader(String windowTarget) {
        TmuxResult listResult = runUserSession(
                "list-panes", "-t", windowTarget, "-F", "#{pane_id}");
        List<String> panes = splitLines(listResult.stdout());
        if (panes.size() <= 2) return;

        runUserSession("select-layout", "-t", windowTarget, "main-vertical");
        runUserSession("resize-pane", "-t", panes.get(0), "-x", "30%");

        log.debug("[TmuxBackend] Rebalanced {} teammate panes with leader", panes.size() - 1);
    }

    /**
     * Rebalances panes in a window without a leader (tiled layout).
     * Translated from rebalancePanesTiled() in TmuxBackend.ts
     */
    private void rebalancePanesTiled(String windowTarget) {
        TmuxResult listResult = runSwarmSession(
                "list-panes", "-t", windowTarget, "-F", "#{pane_id}");
        List<String> panes = splitLines(listResult.stdout());
        if (panes.size() <= 1) return;

        runSwarmSession("select-layout", "-t", windowTarget, "tiled");

        log.debug("[TmuxBackend] Rebalanced {} teammate panes with tiled layout", panes.size());
    }

    // -------------------------------------------------------------------------
    // tmux CLI execution helpers
    // -------------------------------------------------------------------------

    /**
     * Runs a tmux command in the user's original tmux session (no socket override).
     */
    private TmuxResult runUserSession(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(TMUX_COMMAND);
        cmd.addAll(Arrays.asList(args));
        return execTmux(cmd);
    }

    private TmuxResult runUserSessionArgs(List<String> args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(TMUX_COMMAND);
        cmd.addAll(args);
        return execTmux(cmd);
    }

    /**
     * Runs a tmux command in the external swarm socket.
     */
    private TmuxResult runSwarmSession(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(TMUX_COMMAND);
        cmd.add("-L");
        cmd.add(getSwarmSocketName());
        cmd.addAll(Arrays.asList(args));
        return execTmux(cmd);
    }

    /**
     * Dispatches to user session or swarm socket based on the flag.
     */
    private TmuxResult runTmux(boolean useExternalSession, String... args) {
        if (useExternalSession) {
            return runSwarmSession(args);
        }
        return runUserSession(args);
    }

    private TmuxResult execTmux(List<String> cmd) {
        try {
            Process process = new ProcessBuilder(cmd)
                    .redirectErrorStream(false)
                    .start();

            String stdout = readStream(process);
            String stderr;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                stderr = reader.lines().collect(Collectors.joining("\n"));
            }

            int code = process.waitFor();
            return new TmuxResult(stdout, stderr, code);
        } catch (IOException | InterruptedException e) {
            log.debug("[TmuxBackend] Command failed {}: {}", cmd, e.getMessage());
            return new TmuxResult("", e.getMessage(), 1);
        }
    }

    private String readStream(Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    /**
     * Returns the socket name for the swarm tmux server.
     * Mirrors getSwarmSocketName() from constants.ts.
     */
    private String getSwarmSocketName() {
        return "claude-swarm";
    }

    /**
     * Maps an AgentColorName to a tmux color string.
     * Translated from getTmuxColorName() in TmuxBackend.ts
     */
    private String getTmuxColorName(String color) {
        if (color == null) return "white";
        return switch (color.toLowerCase()) {
            case "red"    -> "red";
            case "blue"   -> "blue";
            case "green"  -> "green";
            case "yellow" -> "yellow";
            case "purple" -> "magenta";
            case "orange" -> "colour208";
            case "pink"   -> "colour205";
            case "cyan"   -> "cyan";
            default       -> "white";
        };
    }

    private List<String> splitLines(String text) {
        if (text == null || text.isBlank()) return new ArrayList<>();
        return Arrays.stream(text.trim().split("\n"))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());
    }

    private void sleepPaneShellInit() {
        try {
            Thread.sleep(PANE_SHELL_INIT_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Value types
    // -------------------------------------------------------------------------

    /**
     * Result from creating a pane.
     * Translated from CreatePaneResult in types.ts
     */
    public record CreatePaneResult(String paneId, boolean isFirstTeammate) {}

    /**
     * Result of executing a tmux CLI command.
     */
    public record TmuxResult(String stdout, String stderr, int code) {}

    /**
     * Window + initial pane of an external swarm session.
     */
    private record ExternalSwarmSession(String windowTarget, String paneId) {}
}
