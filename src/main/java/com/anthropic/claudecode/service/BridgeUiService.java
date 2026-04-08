package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.BridgeStatusUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Bridge terminal UI service — manages the status display for the bridge CLI.
 * Translated from src/bridge/bridgeUI.ts
 *
 * Provides a BridgeLogger-like API for printing status lines, banners,
 * session events, and the QR code + shimmer animation to the terminal.
 * Manages a state machine (idle → attached → titled → reconnecting/failed)
 * and tracks visual line counts to erase and redraw the status area.
 */
@Slf4j
@Service
public class BridgeUiService {



    /** Spawn mode options. */
    public enum SpawnMode {
        SINGLE_SESSION("single-session"),
        WORKTREE("worktree"),
        SAME_DIR("same-dir");

        private final String value;
        SpawnMode(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    /** Session activity types. */
    public enum ActivityType {
        TOOL_START, RESULT, ERROR, OTHER
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SessionActivity {
        private ActivityType type;
        private String summary;

        public ActivityType getType() { return type; }
        public void setType(ActivityType v) { type = v; }
        public String getSummary() { return summary; }
        public void setSummary(String v) { summary = v; }
    

    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class BridgeConfig {
        private String sessionIngressUrl;
        private SpawnMode spawnMode;
        private int maxSessions;
        private boolean sandbox;
        private boolean verbose;
        private String debugFile;

        public String getSessionIngressUrl() { return sessionIngressUrl; }
        public void setSessionIngressUrl(String v) { sessionIngressUrl = v; }
        public SpawnMode getSpawnMode() { return spawnMode; }
        public void setSpawnMode(SpawnMode v) { spawnMode = v; }
        public int getMaxSessions() { return maxSessions; }
        public void setMaxSessions(int v) { maxSessions = v; }
        public boolean isSandbox() { return sandbox; }
        public void setSandbox(boolean v) { sandbox = v; }
        public boolean isVerbose() { return verbose; }
        public void setVerbose(boolean v) { verbose = v; }
        public String getDebugFile() { return debugFile; }
        public void setDebugFile(String v) { debugFile = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    private static class SessionDisplayInfo {
        private String title;
        private String url;
        private SessionActivity activity;
    
        public String getTitle() { return title; }
    
        public void setActivity(SessionActivity v) { activity = v; }
    
        public void setTitle(String v) { title = v; }
    
        public void setUrl(String v) { url = v; }
    
        public SessionActivity getActivity() { return activity; }
    
        public String getUrl() { return url; }
    

    }

    /** The terminal writer (defaults to System.out). */
    private final Consumer<String> writer;

    // State machine fields
    private BridgeStatusUtils.StatusState currentState = BridgeStatusUtils.StatusState.IDLE;
    private String currentStateText = "Ready";
    private String repoName = "";
    private String branch = "";
    private String debugLogPath = "";

    // URL tracking
    private String connectUrl = "";
    private String cachedIngressUrl = "";
    private String cachedEnvironmentId = "";
    private String activeSessionUrl = null;

    // QR code state
    private List<String> qrLines = Collections.emptyList();
    private boolean qrVisible = false;

    // Tool activity
    private String lastToolSummary = null;
    private long lastToolTime = 0;

    // Session count + spawn mode
    private int sessionActive = 0;
    private int sessionMax = 1;
    private SpawnMode spawnModeDisplay = null;
    private SpawnMode spawnMode = SpawnMode.SINGLE_SESSION;

    // Per-session display info (keyed by compat sessionId)
    private final Map<String, SessionDisplayInfo> sessionDisplayInfo = new LinkedHashMap<>();

    // Connecting spinner state
    private ScheduledFuture<?> connectingTimer = null;
    private int connectingTick = 0;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // Status line tracking
    private int statusLineCount = 0;

    public BridgeUiService() {
        this.writer = s -> System.out.print(s);
    }

    public BridgeUiService(Consumer<String> writer) {
        this.writer = writer;
    }

    // --- Public API ---

    /**
     * Print the banner and start the connecting spinner.
     * Called once at startup.
     */
    public void printBanner(BridgeConfig config, String environmentId) {
        cachedIngressUrl = config.getSessionIngressUrl() != null ? config.getSessionIngressUrl() : "";
        cachedEnvironmentId = environmentId;
        connectUrl = BridgeStatusUtils.buildBridgeConnectUrl(environmentId, cachedIngressUrl);

        writer.accept("\n");
        startConnecting();
    }

    /** Log session start event. */
    public void logSessionStart(String sessionId, String prompt) {
        String short_ = BridgeStatusUtils.truncateToWidth(prompt, 80);
        printLog("[" + BridgeStatusUtils.timestamp() + "] Session started: \"" + short_
                + "\" (" + sessionId + ")\n");
    }

    /** Log session complete event. */
    public void logSessionComplete(String sessionId, long durationMs) {
        printLog("[" + BridgeStatusUtils.timestamp() + "] Session completed ("
                + BridgeStatusUtils.formatDuration(durationMs) + ") " + sessionId + "\n");
    }

    /** Log session failed event. */
    public void logSessionFailed(String sessionId, String error) {
        printLog("[" + BridgeStatusUtils.timestamp() + "] Session failed: " + error
                + " " + sessionId + "\n");
    }

    /** Log a general status message. */
    public void logStatus(String message) {
        printLog("[" + BridgeStatusUtils.timestamp() + "] " + message + "\n");
    }

    /** Log a verbose message (only shown in verbose mode). */
    public void logVerbose(String message) {
        log.debug("[bridge:verbose] {}", message);
    }

    /** Log an error message. */
    public void logError(String message) {
        printLog("[" + BridgeStatusUtils.timestamp() + "] Error: " + message + "\n");
    }

    /** Log a reconnection event after a disconnect. */
    public void logReconnected(long disconnectedMs) {
        printLog("[" + BridgeStatusUtils.timestamp() + "] Reconnected after "
                + BridgeStatusUtils.formatDuration(disconnectedMs) + "\n");
    }

    /** Set repository info for display in the status line. */
    public void setRepoInfo(String repo, String branchName) {
        this.repoName = repo;
        this.branch = branchName;
    }

    /** Set the debug log path for ant users. */
    public void setDebugLogPath(String path) {
        this.debugLogPath = path;
    }

    /** Transition to idle state (Ready). */
    public void updateIdleStatus() {
        stopConnecting();
        currentState = BridgeStatusUtils.StatusState.IDLE;
        currentStateText = "Ready";
        lastToolSummary = null;
        lastToolTime = 0;
        activeSessionUrl = null;
        renderStatusLine();
    }

    /** Transition to attached state (Connected). */
    public void setAttached(String sessionId) {
        stopConnecting();
        currentState = BridgeStatusUtils.StatusState.ATTACHED;
        currentStateText = "Connected";
        lastToolSummary = null;
        lastToolTime = 0;
        if (sessionMax <= 1) {
            activeSessionUrl = BridgeStatusUtils.buildBridgeSessionUrl(
                    sessionId, cachedEnvironmentId, cachedIngressUrl);
        }
        renderStatusLine();
    }

    /** Transition to reconnecting state. */
    public void updateReconnectingStatus(String delayStr, String elapsedStr) {
        stopConnecting();
        clearStatusLines();
        currentState = BridgeStatusUtils.StatusState.RECONNECTING;
        writeStatus("Reconnecting · retrying in " + delayStr
                + " · disconnected " + elapsedStr + "\n");
    }

    /** Transition to failed state. */
    public void updateFailedStatus(String error) {
        stopConnecting();
        clearStatusLines();
        currentState = BridgeStatusUtils.StatusState.FAILED;
        String suffix = buildSuffix();
        writeStatus("Remote Control Failed" + suffix + "\n");
        writeStatus(BridgeStatusUtils.FAILED_FOOTER_TEXT + "\n");
        if (error != null && !error.isEmpty()) {
            writeStatus(error + "\n");
        }
    }

    /** Update session status with activity info. */
    public void updateSessionStatus(String sessionId, String elapsed,
                                    SessionActivity activity, List<String> trail) {
        if (activity.getType() == ActivityType.TOOL_START) {
            lastToolSummary = activity.getSummary();
            lastToolTime = System.currentTimeMillis();
        }
        renderStatusLine();
    }

    /** Clear the status display. */
    public void clearStatus() {
        stopConnecting();
        clearStatusLines();
    }

    /** Toggle the QR code visibility. */
    public void toggleQr() {
        qrVisible = !qrVisible;
        renderStatusLine();
    }

    /** Update the session count and spawn mode. */
    public void updateSessionCount(int active, int max, SpawnMode mode) {
        if (sessionActive == active && sessionMax == max && spawnMode == mode) return;
        sessionActive = active;
        sessionMax = max;
        spawnMode = mode;
        // Don't re-render here — the status ticker calls renderStatusLine
    }

    /** Set the spawn mode for display. */
    public void setSpawnModeDisplay(SpawnMode mode) {
        if (spawnModeDisplay == mode) return;
        spawnModeDisplay = mode;
        if (mode != null) spawnMode = mode;
    }

    /** Add a session to the display list. */
    public void addSession(String sessionId, String url) {
        SessionDisplayInfo info = new SessionDisplayInfo();
        info.setUrl(url);
        sessionDisplayInfo.put(sessionId, info);
    }

    /** Update a session's activity. */
    public void updateSessionActivity(String sessionId, SessionActivity activity) {
        SessionDisplayInfo info = sessionDisplayInfo.get(sessionId);
        if (info == null) return;
        info.setActivity(activity);
    }

    /** Set the title for a session. */
    public void setSessionTitle(String sessionId, String title) {
        SessionDisplayInfo info = sessionDisplayInfo.get(sessionId);
        if (info == null) return;
        info.setTitle(title);
        if (currentState == BridgeStatusUtils.StatusState.RECONNECTING
                || currentState == BridgeStatusUtils.StatusState.FAILED) return;
        if (sessionMax == 1) {
            currentState = BridgeStatusUtils.StatusState.TITLED;
            currentStateText = BridgeStatusUtils.truncateToWidth(title, 40);
        }
        renderStatusLine();
    }

    /** Remove a session from the display list. */
    public void removeSession(String sessionId) {
        sessionDisplayInfo.remove(sessionId);
    }

    /** Refresh the status display (re-render). */
    public void refreshDisplay() {
        if (currentState == BridgeStatusUtils.StatusState.RECONNECTING
                || currentState == BridgeStatusUtils.StatusState.FAILED) return;
        renderStatusLine();
    }

    // --- Private rendering helpers ---

    private void write(String text) {
        writer.accept(text);
    }

    private void writeStatus(String text) {
        write(text);
        statusLineCount += countVisualLines(text);
    }

    private void clearStatusLines() {
        if (statusLineCount <= 0) return;
        log.debug("[bridge:ui] clearStatusLines count={}", statusLineCount);
        write("\u001b[" + statusLineCount + "A"); // cursor up N lines
        write("\u001b[J");                         // erase from cursor to end of screen
        statusLineCount = 0;
    }

    private void printLog(String line) {
        clearStatusLines();
        write(line);
    }

    private int countVisualLines(String text) {
        int cols = 80; // fallback terminal width
        int count = 0;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String logical = lines[i];
            if (logical.isEmpty()) {
                count++;
                continue;
            }
            count += Math.max(1, (int) Math.ceil((double) logical.length() / cols));
        }
        if (text.endsWith("\n")) {
            count--;
        }
        return count;
    }

    private void renderConnectingLine() {
        clearStatusLines();
        String[] frames = {"|", "/", "-", "\\"};
        String frame = frames[connectingTick % frames.length];
        String suffix = buildSuffix();
        writeStatus(frame + " Connecting" + suffix + "\n");
    }

    private void startConnecting() {
        stopConnecting();
        renderConnectingLine();
        connectingTimer = scheduler.scheduleAtFixedRate(() -> {
            connectingTick++;
            renderConnectingLine();
        }, 150, 150, TimeUnit.MILLISECONDS);
    }

    private void stopConnecting() {
        if (connectingTimer != null) {
            connectingTimer.cancel(false);
            connectingTimer = null;
        }
    }

    private void renderStatusLine() {
        if (currentState == BridgeStatusUtils.StatusState.RECONNECTING
                || currentState == BridgeStatusUtils.StatusState.FAILED) {
            return;
        }

        clearStatusLines();

        boolean isIdle = currentState == BridgeStatusUtils.StatusState.IDLE;
        String suffix = buildSuffix();

        writeStatus((isIdle ? "* " : "> ") + currentStateText + suffix + "\n");

        // Session count / multi-session list
        if (sessionMax > 1) {
            String modeHint = spawnMode == SpawnMode.WORKTREE
                    ? "New sessions will be created in an isolated worktree"
                    : "New sessions will be created in the current directory";
            writeStatus("    Capacity: " + sessionActive + "/" + sessionMax
                    + " · " + modeHint + "\n");
            for (Map.Entry<String, SessionDisplayInfo> entry : sessionDisplayInfo.entrySet()) {
                SessionDisplayInfo info = entry.getValue();
                String titleText = info.getTitle() != null
                        ? BridgeStatusUtils.truncateToWidth(info.getTitle(), 35)
                        : "Attached";
                SessionActivity act = info.getActivity();
                boolean showAct = act != null
                        && act.getType() != ActivityType.RESULT
                        && act.getType() != ActivityType.ERROR;
                String actText = showAct
                        ? " " + BridgeStatusUtils.truncateToWidth(act.getSummary(), 40)
                        : "";
                writeStatus("    " + BridgeStatusUtils.wrapWithOsc8Link(titleText, info.getUrl())
                        + actText + "\n");
            }
        }

        // Mode line for single-slot or single-session
        if (sessionMax == 1) {
            String modeText = switch (spawnMode) {
                case SINGLE_SESSION -> "Single session · exits when complete";
                case WORKTREE -> "Capacity: " + sessionActive + "/1 · New sessions will be created in an isolated worktree";
                case SAME_DIR -> "Capacity: " + sessionActive + "/1 · New sessions will be created in the current directory";
            };
            writeStatus("    " + modeText + "\n");
        }

        // Tool activity line
        if (sessionMax == 1 && !isIdle && lastToolSummary != null
                && (System.currentTimeMillis() - lastToolTime) < BridgeStatusUtils.TOOL_DISPLAY_EXPIRY_MS) {
            writeStatus("  " + BridgeStatusUtils.truncateToWidth(lastToolSummary, 60) + "\n");
        }

        // Footer
        String url = activeSessionUrl != null ? activeSessionUrl : connectUrl;
        if (url != null && !url.isEmpty()) {
            writeStatus("\n");
            String footerText = isIdle
                    ? BridgeStatusUtils.buildIdleFooterText(url)
                    : BridgeStatusUtils.buildActiveFooterText(url);
            String qrHint = qrVisible ? "space to hide QR code" : "space to show QR code";
            String toggleHint = spawnModeDisplay != null ? " · w to toggle spawn mode" : "";
            writeStatus(footerText + "\n");
            writeStatus(qrHint + toggleHint + "\n");
        }
    }

    private String buildSuffix() {
        StringBuilder sb = new StringBuilder();
        if (repoName != null && !repoName.isEmpty()) {
            sb.append(" · ").append(repoName);
        }
        if (branch != null && !branch.isEmpty() && spawnMode != SpawnMode.WORKTREE) {
            sb.append(" · ").append(branch);
        }
        return sb.toString();
    }
}
