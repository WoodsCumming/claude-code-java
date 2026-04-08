package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Activity manager for tracking user and CLI activity.
 * Translated from src/utils/activityManager.ts
 *
 * Handles activity tracking for both user and CLI operations.
 */
@Slf4j
@Component
public class ActivityManager {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ActivityManager.class);


    private static final long USER_ACTIVITY_TIMEOUT_MS = 5000;

    private final Set<String> activeOperations = ConcurrentHashMap.newKeySet();
    private volatile long lastUserActivityTime = 0;
    private volatile long lastCLIRecordedTime = System.currentTimeMillis();
    private volatile boolean cliActive = false;

    private static volatile ActivityManager instance;

    public static ActivityManager getInstance() {
        if (instance == null) {
            instance = new ActivityManager();
        }
        return instance;
    }

    /**
     * Register CLI activity start.
     * Translated from ActivityManager.startCLIActivity() in activityManager.ts
     */
    public void startCLIActivity(String operationId) {
        activeOperations.add(operationId);
        cliActive = true;
        lastCLIRecordedTime = System.currentTimeMillis();
    }

    /**
     * Register CLI activity end.
     */
    public void endCLIActivity(String operationId) {
        activeOperations.remove(operationId);
        if (activeOperations.isEmpty()) {
            cliActive = false;
        }
    }

    /**
     * Record user activity.
     */
    public void recordUserActivity() {
        lastUserActivityTime = System.currentTimeMillis();
    }

    /**
     * Check if CLI is currently active.
     */
    public boolean isCLIActive() {
        return cliActive;
    }

    /**
     * Check if user is currently active.
     */
    public boolean isUserActive() {
        if (lastUserActivityTime == 0) return false;
        return (System.currentTimeMillis() - lastUserActivityTime) < USER_ACTIVITY_TIMEOUT_MS;
    }

    /**
     * Get active operations count.
     */
    public int getActiveOperationCount() {
        return activeOperations.size();
    }
}
