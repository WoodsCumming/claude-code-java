package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Session activity tracking with refcount-based heartbeat timer.
 * Translated from src/utils/sessionActivity.ts
 *
 * The transport registers its keep-alive sender via registerActivityCallback().
 * Callers (API streaming, tool execution) bracket their work with
 * startActivity() / stopActivity(). When the refcount is > 0 a periodic timer
 * fires the registered callback every 30 seconds to keep the container alive.
 *
 * Sending keep-alives is gated behind CLAUDE_CODE_REMOTE_SEND_KEEPALIVES.
 * Diagnostic logging always fires to help diagnose idle gaps.
 */
@Slf4j
@Service
public class SessionActivityService {



    /**
     * Reason for starting/stopping session activity.
     * Translated from SessionActivityReason in sessionActivity.ts
     */
    public enum SessionActivityReason {
        API_CALL, TOOL_EXEC
    }

    private static final long SESSION_ACTIVITY_INTERVAL_MS = 30_000;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "session-activity-heartbeat");
                t.setDaemon(true);
                return t;
            });

    private volatile Runnable activityCallback;
    private final AtomicInteger refcount = new AtomicInteger(0);
    private final Map<SessionActivityReason, AtomicInteger> activeReasons =
            new ConcurrentHashMap<>();
    private final AtomicLong oldestActivityStartedAt = new AtomicLong(-1L);
    private volatile ScheduledFuture<?> heartbeatTimer;
    private volatile ScheduledFuture<?> idleTimer;

    /**
     * Register a keep-alive callback.
     * If work is already in progress (e.g. reconnect during streaming),
     * restarts the heartbeat timer.
     * Translated from registerSessionActivityCallback() in sessionActivity.ts
     */
    public synchronized void registerActivityCallback(Runnable callback) {
        this.activityCallback = callback;
        if (refcount.get() > 0 && heartbeatTimer == null) {
            startHeartbeatTimer();
        }
    }

    /**
     * Unregister the keep-alive callback and stop all timers.
     * Translated from unregisterSessionActivityCallback() in sessionActivity.ts
     */
    public synchronized void unregisterActivityCallback() {
        this.activityCallback = null;
        stopHeartbeatTimer();
        clearIdleTimer();
    }

    /**
     * Send a one-shot session activity signal if keepalives are enabled.
     * Translated from sendSessionActivitySignal() in sessionActivity.ts
     */
    public void sendActivitySignal() {
        if (isKeepAlivesEnabled()) {
            invokeCallback();
        }
    }

    /**
     * Returns true if a keep-alive callback is currently registered.
     * Translated from isSessionActivityTrackingActive() in sessionActivity.ts
     */
    public boolean isTrackingActive() {
        return activityCallback != null;
    }

    /**
     * Increment the activity refcount. When it transitions from 0→1 and a callback
     * is registered, start a periodic heartbeat timer.
     * Translated from startSessionActivity() in sessionActivity.ts
     */
    public synchronized void startActivity(SessionActivityReason reason) {
        int count = refcount.incrementAndGet();
        activeReasons.computeIfAbsent(reason, k -> new AtomicInteger(0)).incrementAndGet();
        if (count == 1) {
            oldestActivityStartedAt.set(System.currentTimeMillis());
            if (activityCallback != null && heartbeatTimer == null) {
                startHeartbeatTimer();
            }
        }
        log.debug("session_activity_started reason={} refcount={}", reason, count);
    }

    /**
     * Decrement the activity refcount. When it reaches 0, stop the heartbeat
     * timer and start an idle timer that logs after 30s of inactivity.
     * Translated from stopSessionActivity() in sessionActivity.ts
     */
    public synchronized void stopActivity(SessionActivityReason reason) {
        int count = refcount.get();
        if (count > 0) {
            refcount.decrementAndGet();
        }
        AtomicInteger reasonCount = activeReasons.get(reason);
        if (reasonCount != null) {
            int n = reasonCount.decrementAndGet();
            if (n <= 0) {
                activeReasons.remove(reason);
            }
        }
        if (refcount.get() == 0 && heartbeatTimer != null) {
            stopHeartbeatTimer();
            startIdleTimer();
        }
        log.debug("session_activity_stopped reason={} refcount={}", reason, refcount.get());
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void startHeartbeatTimer() {
        clearIdleTimer();
        heartbeatTimer = scheduler.scheduleAtFixedRate(() -> {
            log.debug("session_keepalive_heartbeat refcount={}", refcount.get());
            if (isKeepAlivesEnabled()) {
                invokeCallback();
            }
        }, SESSION_ACTIVITY_INTERVAL_MS, SESSION_ACTIVITY_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeatTimer() {
        ScheduledFuture<?> timer = heartbeatTimer;
        if (timer != null) {
            timer.cancel(false);
            heartbeatTimer = null;
        }
    }

    private void startIdleTimer() {
        clearIdleTimer();
        if (activityCallback == null) {
            return;
        }
        idleTimer = scheduler.schedule(() -> {
            log.info("session_idle_30s");
            idleTimer = null;
        }, SESSION_ACTIVITY_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void clearIdleTimer() {
        ScheduledFuture<?> timer = idleTimer;
        if (timer != null) {
            timer.cancel(false);
            idleTimer = null;
        }
    }

    private void invokeCallback() {
        Runnable cb = activityCallback;
        if (cb != null) {
            try {
                cb.run();
            } catch (Exception e) {
                log.debug("Activity callback failed: {}", e.getMessage());
            }
        }
    }

    private static boolean isKeepAlivesEnabled() {
        String val = System.getenv("CLAUDE_CODE_REMOTE_SEND_KEEPALIVES");
        return "1".equals(val) || "true".equalsIgnoreCase(val) || "yes".equalsIgnoreCase(val);
    }
}
