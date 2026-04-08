package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Computer use lock service.
 * Translated from src/utils/computerUse/computerUseLock.ts
 *
 * Manages a lock file to prevent concurrent computer use sessions.
 */
@Slf4j
@Service
public class ComputerUseLockService {



    private static final String LOCK_FILENAME = "computer-use.lock";

    private final ObjectMapper objectMapper;
    private final AppState appState;

    @Autowired
    public ComputerUseLockService(ObjectMapper objectMapper, AppState appState) {
        this.objectMapper = objectMapper;
        this.appState = appState;
    }

    private String getLockPath() {
        return EnvUtils.getClaudeConfigHomeDir() + "/" + LOCK_FILENAME;
    }

    /**
     * Acquire the computer use lock.
     * Translated from acquireComputerUseLock() in computerUseLock.ts
     */
    public CompletableFuture<AcquireResult> acquireLock() {
        return CompletableFuture.supplyAsync(() -> {
            String lockPath = getLockPath();
            File lockFile = new File(lockPath);

            // Check existing lock
            if (lockFile.exists()) {
                try {
                    Map<String, Object> existing = objectMapper.readValue(lockFile, Map.class);
                    String existingSessionId = (String) existing.get("sessionId");
                    Number existingPid = (Number) existing.get("pid");

                    if (existingSessionId != null && !existingSessionId.equals(appState.getSessionId())) {
                        // Check if the process is still running
                        if (existingPid != null && isProcessRunning(existingPid.longValue())) {
                            return new AcquireResult("blocked", "Session " + existingSessionId);
                        }
                        // Stale lock - remove it
                        lockFile.delete();
                    }
                } catch (Exception e) {
                    // Corrupt lock file - remove it
                    lockFile.delete();
                }
            }

            // Acquire the lock
            try {
                Map<String, Object> lock = new LinkedHashMap<>();
                lock.put("sessionId", appState.getSessionId());
                lock.put("pid", ProcessHandle.current().pid());
                lock.put("acquiredAt", System.currentTimeMillis());

                objectMapper.writeValue(lockFile, lock);
                return new AcquireResult("acquired", null);
            } catch (Exception e) {
                log.error("Could not acquire computer use lock: {}", e.getMessage());
                return new AcquireResult("blocked", "Lock acquisition failed");
            }
        });
    }

    /**
     * Release the computer use lock.
     * Translated from releaseComputerUseLock() in computerUseLock.ts
     */
    public void releaseLock() {
        new File(getLockPath()).delete();
    }

    private boolean isProcessRunning(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AcquireResult {
        private String kind; // "acquired" | "blocked"
        private String by;

        public String getKind() { return kind; }
        public void setKind(String v) { kind = v; }
        public String getBy() { return by; }
        public void setBy(String v) { by = v; }
    
    }
}
