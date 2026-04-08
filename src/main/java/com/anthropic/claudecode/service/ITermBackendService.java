package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * iTerm2 pane management backend, including it2 CLI setup utilities.
 *
 * Merged translation of:
 *  - src/utils/swarm/backends/ITermBackend.ts   (pane create/send/kill)
 *  - src/utils/swarm/backends/it2Setup.ts        (install/verify it2 CLI)
 *
 * Layout strategy:
 *   - First teammate: vertical split (-v) from leader's session
 *   - Subsequent teammates: horizontal split from the last teammate's session
 *
 * At-fault recovery: if a targeted teammate session is dead (user closed the pane),
 * it is pruned and the split is retried with the next-to-last session — O(N+1).
 */
@Slf4j
@Service
public class ITermBackendService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ITermBackendService.class);


    private static final Pattern SPLIT_OUTPUT_PATTERN =
            Pattern.compile("Created new pane:\\s*(.+)");

    /** Session IDs for all tracked teammate panes, in creation order. */
    private final List<String> teammateSessionIds = new CopyOnWriteArrayList<>();

    /** Whether the first pane slot has already been consumed. */
    private volatile boolean firstPaneUsed = false;

    /** Serializes pane creation to prevent race conditions. */
    private final ReentrantLock paneCreationLock = new ReentrantLock(true);

    private final SwarmBackendDetectionService detectionService;

    @Autowired
    public ITermBackendService(SwarmBackendDetectionService detectionService) {
        this.detectionService = detectionService;
    }

    // =========================================================================
    // PaneBackend public API  (ITermBackend.ts)
    // =========================================================================

    public String getType()            { return "iterm2"; }
    public String getDisplayName()     { return "iTerm2"; }
    public boolean isSupportsHideShow(){ return false; }

    /**
     * Checks if the iTerm2 backend is available.
     * Requires: running inside iTerm2 AND it2 CLI installed and Python API enabled.
     * Translated from isAvailable() in ITermBackend.ts
     */
    public CompletableFuture<Boolean> isAvailable() {
        return CompletableFuture.supplyAsync(() -> {
            boolean inITerm2 = detectionService.isInITerm2();
            log.debug("[ITermBackend] isAvailable check: inITerm2={}", inITerm2);
            if (!inITerm2) {
                log.debug("[ITermBackend] isAvailable: false (not in iTerm2)");
                return false;
            }
            boolean it2Available = detectionService.isIt2CliAvailable();
            log.debug("[ITermBackend] isAvailable: {} (it2 CLI {})",
                    it2Available, it2Available ? "found" : "not found");
            return it2Available;
        });
    }

    /**
     * Checks if we're currently running inside iTerm2.
     * Translated from isRunningInside() in ITermBackend.ts
     */
    public CompletableFuture<Boolean> isRunningInside() {
        return CompletableFuture.supplyAsync(() -> {
            boolean result = detectionService.isInITerm2();
            log.debug("[ITermBackend] isRunningInside: {}", result);
            return result;
        });
    }

    /**
     * Creates a new teammate pane via it2 session split.
     * Translated from createTeammatePaneInSwarmView() in ITermBackend.ts
     */
    public CompletableFuture<CreatePaneResult> createTeammatePaneInSwarmView(
            String name, String color) {
        return CompletableFuture.supplyAsync(() -> {
            log.debug("[ITermBackend] createTeammatePaneInSwarmView called for {} with color {}", name, color);
            paneCreationLock.lock();
            try {
                while (true) {
                    boolean isFirstTeammate = !firstPaneUsed;
                    log.debug("[ITermBackend] Creating pane: isFirstTeammate={}, existingPanes={}",
                            isFirstTeammate, teammateSessionIds.size());

                    List<String> splitArgs;
                    String targetedTeammateId = null;

                    if (isFirstTeammate) {
                        String leaderSessionId = getLeaderSessionId();
                        if (leaderSessionId != null) {
                            splitArgs = List.of("session", "split", "-v", "-s", leaderSessionId);
                            log.debug("[ITermBackend] First split from leader session: {}", leaderSessionId);
                        } else {
                            splitArgs = List.of("session", "split", "-v");
                            log.debug("[ITermBackend] First split from active session (no leader ID)");
                        }
                    } else {
                        if (!teammateSessionIds.isEmpty()) {
                            targetedTeammateId = teammateSessionIds.get(teammateSessionIds.size() - 1);
                            splitArgs = List.of("session", "split", "-s", targetedTeammateId);
                            log.debug("[ITermBackend] Subsequent split from teammate session: {}", targetedTeammateId);
                        } else {
                            splitArgs = List.of("session", "split");
                            log.debug("[ITermBackend] Subsequent split from active session (no teammate ID)");
                        }
                    }

                    It2Result splitResult = runIt2(splitArgs);

                    if (splitResult.code() != 0) {
                        if (targetedTeammateId != null) {
                            It2Result listResult = runIt2(List.of("session", "list"));
                            final String deadId = targetedTeammateId;
                            if (listResult.code() == 0 && !listResult.stdout().contains(deadId)) {
                                log.debug("[ITermBackend] Split failed targeting dead session {}, pruning and retrying: {}",
                                        deadId, splitResult.stderr());
                                teammateSessionIds.remove(deadId);
                                if (teammateSessionIds.isEmpty()) firstPaneUsed = false;
                                continue;
                            }
                        }
                        throw new RuntimeException("Failed to create iTerm2 split pane: " + splitResult.stderr());
                    }

                    if (isFirstTeammate) firstPaneUsed = true;

                    String paneId = parseSplitOutput(splitResult.stdout());
                    if (paneId.isBlank()) {
                        throw new RuntimeException(
                                "Failed to parse session ID from split output: " + splitResult.stdout());
                    }

                    log.debug("[ITermBackend] Created teammate pane for {}: {}", name, paneId);
                    teammateSessionIds.add(paneId);

                    return new CreatePaneResult(paneId, isFirstTeammate);
                }
            } finally {
                paneCreationLock.unlock();
            }
        });
    }

    /**
     * Sends a command to a specific pane.
     * Translated from sendCommandToPane() in ITermBackend.ts
     */
    public CompletableFuture<Void> sendCommandToPane(
            String paneId, String command, boolean useExternalSession) {
        return CompletableFuture.runAsync(() -> {
            List<String> args = (paneId != null && !paneId.isBlank())
                    ? List.of("session", "run", "-s", paneId, command)
                    : List.of("session", "run", command);
            It2Result result = runIt2(args);
            if (result.code() != 0) {
                throw new RuntimeException(
                        "Failed to send command to iTerm2 pane " + paneId + ": " + result.stderr());
            }
        });
    }

    /** No-op for iTerm2: border color styling skipped for performance. */
    public CompletableFuture<Void> setPaneBorderColor(
            String paneId, String color, boolean useExternalSession) {
        return CompletableFuture.completedFuture(null);
    }

    /** No-op for iTerm2: pane title styling skipped for performance. */
    public CompletableFuture<Void> setPaneTitle(
            String paneId, String name, String color, boolean useExternalSession) {
        return CompletableFuture.completedFuture(null);
    }

    /** No-op for iTerm2: titles shown in tabs automatically. */
    public CompletableFuture<Void> enablePaneBorderStatus(
            String windowTarget, boolean useExternalSession) {
        return CompletableFuture.completedFuture(null);
    }

    /** No-op for iTerm2: pane balancing is handled automatically. */
    public CompletableFuture<Void> rebalancePanes(String windowTarget, boolean hasLeader) {
        log.debug("[ITermBackend] Pane rebalancing not implemented for iTerm2");
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Kills/closes a pane using it2 session close -f.
     * Also removes from tracked session IDs.
     * Translated from killPane() in ITermBackend.ts
     */
    public CompletableFuture<Boolean> killPane(String paneId, boolean useExternalSession) {
        return CompletableFuture.supplyAsync(() -> {
            It2Result result = runIt2(List.of("session", "close", "-f", "-s", paneId));
            teammateSessionIds.remove(paneId);
            if (teammateSessionIds.isEmpty()) firstPaneUsed = false;
            return result.code() == 0;
        });
    }

    /** Stub: hiding panes not supported in iTerm2 (no break-pane equivalent). */
    public CompletableFuture<Boolean> hidePane(String paneId, boolean useExternalSession) {
        log.debug("[ITermBackend] hidePane not supported in iTerm2");
        return CompletableFuture.completedFuture(false);
    }

    /** Stub: showing hidden panes not supported in iTerm2 (no join-pane equivalent). */
    public CompletableFuture<Boolean> showPane(
            String paneId, String targetWindowOrPane, boolean useExternalSession) {
        log.debug("[ITermBackend] showPane not supported in iTerm2");
        return CompletableFuture.completedFuture(false);
    }

    // =========================================================================
    // it2 Setup utilities  (it2Setup.ts)
    // =========================================================================

    /** Python package manager types. Translated from PythonPackageManager in it2Setup.ts */
    public enum PythonPackageManager { UVX, PIPX, PIP }

    /**
     * Result of attempting to install it2.
     * Translated from It2InstallResult in it2Setup.ts
     */
    public record It2InstallResult(
        boolean success,
        String error,
        PythonPackageManager packageManager
    ) {}

    /**
     * Result of verifying it2 setup.
     * Translated from It2VerifyResult in it2Setup.ts
     */
    public record It2VerifyResult(
        boolean success,
        String error,
        boolean needsPythonApiEnabled
    ) {}

    /**
     * Detects which Python package manager is available.
     * Checks in order: uv (uvx), pipx, pip/pip3.
     * Translated from detectPythonPackageManager() in it2Setup.ts
     */
    public CompletableFuture<PythonPackageManager> detectPythonPackageManager() {
        return CompletableFuture.supplyAsync(() -> {
            if (which("uv")) {
                log.debug("[it2Setup] Found uv (will use uv tool install)");
                return PythonPackageManager.UVX;
            }
            if (which("pipx")) {
                log.debug("[it2Setup] Found pipx package manager");
                return PythonPackageManager.PIPX;
            }
            if (which("pip")) {
                log.debug("[it2Setup] Found pip package manager");
                return PythonPackageManager.PIP;
            }
            if (which("pip3")) {
                log.debug("[it2Setup] Found pip3 package manager");
                return PythonPackageManager.PIP;
            }
            log.debug("[it2Setup] No Python package manager found");
            return null;
        });
    }

    /**
     * Checks if the it2 CLI tool is installed.
     * Translated from isIt2CliAvailable() in it2Setup.ts
     */
    public CompletableFuture<Boolean> isIt2CliAvailableAsync() {
        return CompletableFuture.supplyAsync(() -> which("it2"));
    }

    /**
     * Installs the it2 CLI using the detected package manager.
     * Translated from installIt2() in it2Setup.ts
     */
    public CompletableFuture<It2InstallResult> installIt2(PythonPackageManager packageManager) {
        return CompletableFuture.supplyAsync(() -> {
            log.debug("[it2Setup] Installing it2 using {}", packageManager);
            String homeDir = System.getProperty("user.home");

            List<String> cmd = switch (packageManager) {
                case UVX  -> List.of("uv", "tool", "install", "it2");
                case PIPX -> List.of("pipx", "install", "it2");
                case PIP  -> List.of("pip", "install", "--user", "it2");
            };

            It2Result result = runCommand(cmd, homeDir);

            // Try pip3 if pip fails
            if (result.code() != 0 && packageManager == PythonPackageManager.PIP) {
                result = runCommand(List.of("pip3", "install", "--user", "it2"), homeDir);
            }

            if (result.code() != 0) {
                String error = !result.stderr().isEmpty() ? result.stderr() : "Unknown installation error";
                log.error("[it2Setup] Failed to install it2: {}", error);
                return new It2InstallResult(false, error, packageManager);
            }

            log.debug("[it2Setup] it2 installed successfully");
            return new It2InstallResult(true, null, packageManager);
        });
    }

    /**
     * Verifies that it2 is properly configured and can communicate with iTerm2.
     * Translated from verifyIt2Setup() in it2Setup.ts
     */
    public CompletableFuture<It2VerifyResult> verifyIt2Setup() {
        return CompletableFuture.supplyAsync(() -> {
            log.debug("[it2Setup] Verifying it2 setup...");

            if (!which("it2")) {
                return new It2VerifyResult(false, "it2 CLI is not installed or not in PATH", false);
            }

            It2Result result = runIt2(List.of("session", "list"));

            if (result.code() != 0) {
                String stderr = result.stderr().toLowerCase();
                if (stderr.contains("api") || stderr.contains("python")
                        || stderr.contains("connection refused") || stderr.contains("not enabled")) {
                    log.debug("[it2Setup] Python API not enabled in iTerm2");
                    return new It2VerifyResult(false, "Python API not enabled in iTerm2 preferences", true);
                }
                String errorMsg = !result.stderr().isEmpty() ? result.stderr() : "Failed to communicate with iTerm2";
                return new It2VerifyResult(false, errorMsg, false);
            }

            log.debug("[it2Setup] it2 setup verified successfully");
            return new It2VerifyResult(true, null, false);
        });
    }

    /**
     * Returns instructions for enabling the Python API in iTerm2.
     * Translated from getPythonApiInstructions() in it2Setup.ts
     */
    public List<String> getPythonApiInstructions() {
        return List.of(
            "Almost done! Enable the Python API in iTerm2:",
            "",
            "  iTerm2 → Settings → General → Magic → Enable Python API",
            "",
            "After enabling, you may need to restart iTerm2."
        );
    }

    /**
     * Marks that it2 setup is complete (persists to config).
     * Translated from markIt2SetupComplete() in it2Setup.ts
     */
    public void markIt2SetupComplete() {
        log.debug("[it2Setup] Marked it2 setup as complete");
        // Persistence handled by GlobalConfigService in a full implementation
    }

    /**
     * Marks whether the user prefers tmux over iTerm2 split panes.
     * Translated from setPreferTmuxOverIterm2() in it2Setup.ts
     */
    public void setPreferTmuxOverIterm2(boolean prefer) {
        log.debug("[it2Setup] Set preferTmuxOverIterm2 = {}", prefer);
        // Persistence handled by GlobalConfigService in a full implementation
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Parses the session ID from `it2 session split` output.
     * Format: "Created new pane: <session-id>"
     * Translated from parseSplitOutput() in ITermBackend.ts
     */
    private String parseSplitOutput(String output) {
        if (output == null) return "";
        Matcher matcher = SPLIT_OUTPUT_PATTERN.matcher(output);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    /**
     * Gets the leader's session ID from ITERM_SESSION_ID env var.
     * Format: "wXtYpZ:UUID" — extracts UUID after the first colon.
     * Translated from getLeaderSessionId() in ITermBackend.ts
     */
    private String getLeaderSessionId() {
        String itermSessionId = System.getenv("ITERM_SESSION_ID");
        if (itermSessionId == null || itermSessionId.isBlank()) return null;
        int colonIndex = itermSessionId.indexOf(':');
        return colonIndex == -1 ? null : itermSessionId.substring(colonIndex + 1);
    }

    /** Runs an it2 CLI command and returns stdout/stderr/code. */
    private It2Result runIt2(List<String> args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(SwarmBackendDetectionService.IT2_COMMAND);
        cmd.addAll(args);
        return runCommand(cmd, null);
    }

    /** Runs an arbitrary shell command optionally from a working directory. */
    private It2Result runCommand(List<String> cmd, String cwd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(false);
            if (cwd != null) pb.directory(new File(cwd));
            Process process = pb.start();

            String stdout;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                stdout = r.lines().collect(Collectors.joining("\n"));
            }
            String stderr;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                stderr = r.lines().collect(Collectors.joining("\n"));
            }

            int code = process.waitFor();
            return new It2Result(stdout, stderr, code);
        } catch (IOException | InterruptedException e) {
            log.debug("[ITermBackend] command failed {}: {}", cmd, e.getMessage());
            return new It2Result("", e.getMessage() != null ? e.getMessage() : "", 1);
        }
    }

    /** Checks if a binary is accessible on PATH via `which`. */
    private boolean which(String binary) {
        It2Result r = runCommand(List.of("which", binary), null);
        return r.code() == 0;
    }

    // =========================================================================
    // Value types
    // =========================================================================

    /** Result from creating a pane. */
    public record CreatePaneResult(String paneId, boolean isFirstTeammate) {}

    /** Result of executing an it2 CLI command. */
    public record It2Result(String stdout, String stderr, int code) {}
}
