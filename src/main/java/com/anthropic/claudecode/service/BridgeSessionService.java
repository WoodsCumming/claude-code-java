package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.BridgeFlushGate;
import com.anthropic.claudecode.util.BridgeStatusUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bridge poll-loop orchestration service.
 * Translated from src/bridge/bridgeMain.ts
 *
 * Manages the main bridge work-poll loop: fetches work items, spawns sessions,
 * tracks active sessions and their heartbeats, handles backoff and reconnection,
 * and orchestrates clean shutdown.
 */
@Slf4j
@Service
public class BridgeSessionService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BridgeSessionService.class);


    // --- Backoff configuration ---

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class BackoffConfig {
        private long connInitialMs = 2_000;
        private long connCapMs = 120_000;    // 2 minutes
        private long connGiveUpMs = 600_000; // 10 minutes
        private long generalInitialMs = 500;
        private long generalCapMs = 30_000;
        private long generalGiveUpMs = 600_000; // 10 minutes
        /** SIGTERM→SIGKILL grace period on shutdown. Default 30s. */
        private long shutdownGraceMs = 30_000;
        /** stopWorkWithRetry base delay (1s/2s/4s backoff). Default 1000ms. */
        private long stopWorkBaseDelayMs = 1_000;

        public long getConnInitialMs() { return connInitialMs; }
        public void setConnInitialMs(long v) { connInitialMs = v; }
        public long getConnCapMs() { return connCapMs; }
        public void setConnCapMs(long v) { connCapMs = v; }
        public long getConnGiveUpMs() { return connGiveUpMs; }
        public void setConnGiveUpMs(long v) { connGiveUpMs = v; }
        public long getGeneralInitialMs() { return generalInitialMs; }
        public void setGeneralInitialMs(long v) { generalInitialMs = v; }
        public long getGeneralCapMs() { return generalCapMs; }
        public void setGeneralCapMs(long v) { generalCapMs = v; }
        public long getGeneralGiveUpMs() { return generalGiveUpMs; }
        public void setGeneralGiveUpMs(long v) { generalGiveUpMs = v; }
        public long getShutdownGraceMs() { return shutdownGraceMs; }
        public void setShutdownGraceMs(long v) { shutdownGraceMs = v; }
        public long getStopWorkBaseDelayMs() { return stopWorkBaseDelayMs; }
        public void setStopWorkBaseDelayMs(long v) { stopWorkBaseDelayMs = v; }
    }

    // --- Session state ---

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SessionHandle {
        private String sessionId;
        private String workId;
        private String ingressToken;
        private long startTime;
        private String currentActivitySummary;
        private ActivityType currentActivityType;
        private final List<SessionActivity> activities = new ArrayList<>();
        private final List<String> lastStderr = new ArrayList<>();
        private final AtomicBoolean stopped = new AtomicBoolean(false);

        public void updateAccessToken(String token) {
            this.ingressToken = token;
        }

        public String getSessionId() { return sessionId; }
        public void setSessionId(String v) { sessionId = v; }
        public String getWorkId() { return workId; }
        public void setWorkId(String v) { workId = v; }
        public String getIngressToken() { return ingressToken; }
        public void setIngressToken(String v) { ingressToken = v; }
        public long getStartTime() { return startTime; }
        public void setStartTime(long v) { startTime = v; }
        public String getCurrentActivitySummary() { return currentActivitySummary; }
        public void setCurrentActivitySummary(String v) { currentActivitySummary = v; }
        public ActivityType getCurrentActivityType() { return currentActivityType; }
        public void setCurrentActivityType(ActivityType v) { currentActivityType = v; }
        public List<SessionActivity> getActivities() { return activities; }
        public List<String> getLastStderr() { return lastStderr; }
        public AtomicBoolean getStopped() { return stopped; }
    

    }

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

    public enum SessionDoneStatus {
        COMPLETED, FAILED, INTERRUPTED
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SpawnOpts {
        private String sessionId;
        private String workId;
        private String ingressToken;
        private String environmentId;
        private String sdkUrl;
        private String permissionMode;
        private boolean isV2;

        public String getEnvironmentId() { return environmentId; }
        public void setEnvironmentId(String v) { environmentId = v; }
        public String getSdkUrl() { return sdkUrl; }
        public void setSdkUrl(String v) { sdkUrl = v; }
        public String getPermissionMode() { return permissionMode; }
        public void setPermissionMode(String v) { permissionMode = v; }
        public boolean isIsV2() { return isV2; }
        public void setIsV2(boolean v) { isV2 = v; }
    }

    // --- Bridge loop state ---

    private final Map<String, SessionHandle> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionStartTimes = new ConcurrentHashMap<>();
    private final Map<String, String> sessionWorkIds = new ConcurrentHashMap<>();
    private final Map<String, String> sessionCompatIds = new ConcurrentHashMap<>();
    private final Map<String, String> sessionIngressTokens = new ConcurrentHashMap<>();
    private final Set<String> completedWorkIds = ConcurrentHashMap.newKeySet();
    private final Set<String> timedOutSessions = ConcurrentHashMap.newKeySet();
    private final Set<String> titledSessions = ConcurrentHashMap.newKeySet();
    private final Set<String> v2Sessions = ConcurrentHashMap.newKeySet();

    private static final long STATUS_UPDATE_INTERVAL_MS = 1_000;
    private static final int SPAWN_SESSIONS_DEFAULT = 32;

    private final BridgeUiService bridgeUiService;
    private final CapacityWakeService capacityWakeService;

    private ScheduledFuture<?> statusUpdateTimer = null;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private boolean fatalExit = false;

    @Autowired
    public BridgeSessionService(BridgeUiService bridgeUiService) {
        this.bridgeUiService = bridgeUiService;
        this.capacityWakeService = CapacityWakeService.create();
    }

    // --- Session lifecycle ---

    /**
     * Register a newly spawned session handle.
     */
    public void registerSession(String sessionId, String workId, String ingressToken,
                                 String compatId, boolean isV2) {
        SessionHandle handle = new SessionHandle();
        handle.setSessionId(sessionId);
        handle.setWorkId(workId);
        handle.setIngressToken(ingressToken);
        handle.setStartTime(System.currentTimeMillis());

        activeSessions.put(sessionId, handle);
        sessionStartTimes.put(sessionId, System.currentTimeMillis());
        sessionWorkIds.put(sessionId, workId);
        sessionCompatIds.put(sessionId, compatId);
        sessionIngressTokens.put(sessionId, ingressToken);
        if (isV2) v2Sessions.add(sessionId);
    }

    /**
     * Handle session completion. Cleans up all state and decides lifecycle.
     */
    public void onSessionDone(String sessionId, SessionDoneStatus rawStatus) {
        String workId = sessionWorkIds.get(sessionId);
        activeSessions.remove(sessionId);
        sessionStartTimes.remove(sessionId);
        sessionWorkIds.remove(sessionId);
        sessionIngressTokens.remove(sessionId);

        String compatId = sessionCompatIds.getOrDefault(sessionId, sessionId);
        sessionCompatIds.remove(sessionId);
        bridgeUiService.removeSession(compatId);
        titledSessions.remove(compatId);
        v2Sessions.remove(sessionId);

        // Wake the at-capacity sleep so the bridge can accept new work
        capacityWakeService.wake();

        boolean wasTimedOut = timedOutSessions.remove(sessionId);
        SessionDoneStatus status =
                (wasTimedOut && rawStatus == SessionDoneStatus.INTERRUPTED)
                        ? SessionDoneStatus.FAILED : rawStatus;

        long startTime = sessionStartTimes.getOrDefault(sessionId, System.currentTimeMillis());
        long durationMs = System.currentTimeMillis() - startTime;

        log.debug("[bridge:session] sessionId={} workId={} exited status={} duration={}",
                sessionId, workId != null ? workId : "unknown", status,
                BridgeStatusUtils.formatDuration(durationMs));

        bridgeUiService.clearStatus();
        stopStatusUpdates();

        switch (status) {
            case COMPLETED -> bridgeUiService.logSessionComplete(sessionId, durationMs);
            case FAILED -> {
                if (!wasTimedOut && !capacityWakeService.isOuterAborted()) {
                    String msg = "Process exited with error";
                    bridgeUiService.logSessionFailed(sessionId, msg);
                }
            }
            case INTERRUPTED -> bridgeUiService.logVerbose("Session " + sessionId + " interrupted");
        }

        if (!capacityWakeService.isOuterAborted()) {
            startStatusUpdates();
        }
    }

    /**
     * Mark a session as timed out (for watchdog use).
     */
    public void markTimedOut(String sessionId) {
        timedOutSessions.add(sessionId);
    }

    /**
     * Update the access token for an existing session (re-dispatch/refresh).
     */
    public boolean updateSessionToken(String sessionId, String newToken, String newWorkId) {
        SessionHandle handle = activeSessions.get(sessionId);
        if (handle == null) return false;
        handle.updateAccessToken(newToken);
        sessionIngressTokens.put(sessionId, newToken);
        sessionWorkIds.put(sessionId, newWorkId);
        return true;
    }

    /**
     * Record a work ID as completed so re-deliveries are skipped.
     */
    public void markWorkCompleted(String workId) {
        completedWorkIds.add(workId);
    }

    /**
     * Returns true if this work item has already been completed.
     */
    public boolean isWorkCompleted(String workId) {
        return completedWorkIds.contains(workId);
    }

    /**
     * Returns the number of currently active sessions.
     */
    public int getActiveSessionCount() {
        return activeSessions.size();
    }

    /**
     * Returns true if the bridge is at capacity.
     */
    public boolean isAtCapacity(int maxSessions) {
        return activeSessions.size() >= maxSessions;
    }

    // --- Status display ---

    /**
     * Update the status display with current session activity.
     */
    public void updateStatusDisplay(int maxSessions, BridgeUiService.SpawnMode spawnMode) {
        bridgeUiService.updateSessionCount(
                activeSessions.size(), maxSessions, spawnMode);

        for (Map.Entry<String, SessionHandle> entry : activeSessions.entrySet()) {
            String sid = entry.getKey();
            SessionHandle handle = entry.getValue();
            if (handle.getCurrentActivitySummary() != null) {
                BridgeUiService.SessionActivity act = new BridgeUiService.SessionActivity();
                act.setType(mapActivityType(handle.getCurrentActivityType()));
                act.setSummary(handle.getCurrentActivitySummary());
                bridgeUiService.updateSessionActivity(
                        sessionCompatIds.getOrDefault(sid, sid), act);
            }
        }

        if (activeSessions.isEmpty()) {
            bridgeUiService.updateIdleStatus();
            return;
        }

        // Show the most recently started session
        Optional<Map.Entry<String, SessionHandle>> last = activeSessions.entrySet()
                .stream().reduce((a, b) -> b);
        if (last.isEmpty()) return;

        String sessionId = last.get().getKey();
        SessionHandle handle = last.get().getValue();
        Long startTime = sessionStartTimes.get(sessionId);
        if (startTime == null) return;

        ActivityType actType = handle.getCurrentActivityType();
        if (actType == null || actType == ActivityType.RESULT || actType == ActivityType.ERROR) {
            if (maxSessions > 1) bridgeUiService.refreshDisplay();
            return;
        }

        String elapsed = BridgeStatusUtils.formatDuration(System.currentTimeMillis() - startTime);
        List<String> trail = handle.getActivities().stream()
                .filter(a -> a.getType() == ActivityType.TOOL_START)
                .map(SessionActivity::getSummary)
                .skip(Math.max(0, handle.getActivities().size() - 5))
                .toList();

        BridgeUiService.SessionActivity activity = new BridgeUiService.SessionActivity();
        activity.setType(mapActivityType(actType));
        activity.setSummary(handle.getCurrentActivitySummary());
        bridgeUiService.updateSessionStatus(sessionId, elapsed, activity, trail);
    }

    /**
     * Start the status display update ticker.
     */
    public void startStatusUpdates() {
        stopStatusUpdates();
        statusUpdateTimer = scheduler.scheduleAtFixedRate(
                () -> updateStatusDisplay(SPAWN_SESSIONS_DEFAULT,
                        BridgeUiService.SpawnMode.SINGLE_SESSION),
                0, STATUS_UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Stop the status display update ticker.
     */
    public void stopStatusUpdates() {
        if (statusUpdateTimer != null) {
            statusUpdateTimer.cancel(false);
            statusUpdateTimer = null;
        }
    }

    /**
     * Returns the capacity wake service for coordinating at-capacity sleeps.
     */
    public CapacityWakeService getCapacityWake() {
        return capacityWakeService;
    }

    /**
     * Returns true if this session was spawned as a CCR v2 session.
     */
    public boolean isV2Session(String sessionId) {
        return v2Sessions.contains(sessionId);
    }

    /**
     * Returns the ingress token for a session, or null if not found.
     */
    public String getIngressToken(String sessionId) {
        return sessionIngressTokens.get(sessionId);
    }

    /**
     * Set the title for a session in the UI.
     */
    public void setSessionTitle(String sessionId, String title) {
        String compatId = sessionCompatIds.getOrDefault(sessionId, sessionId);
        if (!titledSessions.contains(compatId)) {
            titledSessions.add(compatId);
            bridgeUiService.setSessionTitle(compatId, title);
        }
    }

    // --- Cleanup ---

    /**
     * Shut down the service executor. Call on application shutdown.
     */
    public void shutdown() {
        stopStatusUpdates();
        scheduler.shutdownNow();
    }

    // --- Private helpers ---

    private BridgeUiService.ActivityType mapActivityType(ActivityType type) {
        if (type == null) return BridgeUiService.ActivityType.OTHER;
        return switch (type) {
            case TOOL_START -> BridgeUiService.ActivityType.TOOL_START;
            case RESULT -> BridgeUiService.ActivityType.RESULT;
            case ERROR -> BridgeUiService.ActivityType.ERROR;
            default -> BridgeUiService.ActivityType.OTHER;
        };
    }

    /**
     * Sleep poll detection threshold — must exceed max backoff cap to avoid
     * false sleep detection during normal backoff delays.
     */
    public static long pollSleepDetectionThresholdMs(BackoffConfig backoff) {
        return backoff.getConnCapMs() * 2;
    }
}
