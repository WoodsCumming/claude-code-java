package com.anthropic.claudecode.util;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Scheduler lease lock for .claude/scheduled_tasks.json.
 *
 * Translated from src/utils/cronTasksLock.ts
 *
 * When multiple Claude sessions run in the same project directory, only one
 * should drive the cron scheduler. The first session to acquire this lock
 * becomes the scheduler; others stay passive and periodically probe the lock.
 * If the owner dies (PID no longer running), a passive session takes over.
 *
 * Pattern: atomic exclusive file creation, PID liveness probe, stale-lock
 * recovery, cleanup-on-exit.
 */
@Slf4j
public final class CronTasksLock {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CronTasksLock.class);

    private static final String LOCK_FILE_REL = ".claude/scheduled_tasks.lock";

    // Module-level state (mirrors the TS module-level variables)
    private static volatile String lastBlockedBy = null;

    private CronTasksLock() {}

    // ------------------------------------------------------------------
    // Public types
    // ------------------------------------------------------------------

    /**
     * Options for out-of-REPL callers (Agent SDK daemon) that don't have
     * bootstrap state.
     * Mirrors SchedulerLockOptions in cronTasksLock.ts
     */
    public static class SchedulerLockOptions {
        private String dir;
        private String lockIdentity;
        public SchedulerLockOptions() {}
        public SchedulerLockOptions(String dir, String lockIdentity) { this.dir = dir; this.lockIdentity = lockIdentity; }
        public String getDir() { return dir; }
        public void setDir(String v) { dir = v; }
        public String getLockIdentity() { return lockIdentity; }
        public void setLockIdentity(String v) { lockIdentity = v; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SchedulerLock {
        private String sessionId;
        private long pid;
        private long acquiredAt;
        public SchedulerLock() {}
        public SchedulerLock(String sessionId, long pid, long acquiredAt) { this.sessionId = sessionId; this.pid = pid; this.acquiredAt = acquiredAt; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String v) { sessionId = v; }
        public long getPid() { return pid; }
        public void setPid(long v) { pid = v; }
        public long getAcquiredAt() { return acquiredAt; }
        public void setAcquiredAt(long v) { acquiredAt = v; }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Try to acquire the scheduler lock for the current session.
     * Returns true on success, false if another live session holds it.
     *
     * Uses O_EXCL (CREATE_NEW) for atomic test-and-set. If the file exists:
     *   - Already ours → true (idempotent re-acquire)
     *   - Another live PID → false
     *   - Stale (PID dead / corrupt) → unlink and retry exclusive create once
     *
     * Mirrors tryAcquireSchedulerLock() in cronTasksLock.ts
     */
    public static CompletableFuture<Boolean> tryAcquireSchedulerLock(SchedulerLockOptions opts) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String dir = opts != null ? opts.getDir() : null;
                String sessionId = resolveSessionId(opts);
                long pid = ProcessHandle.current().pid();

                SchedulerLock newLock = new SchedulerLock(sessionId, pid, System.currentTimeMillis());

                // Try exclusive create
                if (tryCreateExclusive(newLock, dir)) {
                    lastBlockedBy = null;
                    log.debug("[ScheduledTasks] acquired scheduler lock (PID {})", pid);
                    registerShutdownHook(opts);
                    return true;
                }

                // Lock file already exists — read it
                Optional<SchedulerLock> existing = readLock(dir);

                // Already ours (idempotent). If PID changed (e.g. after resume),
                // update the lock file so other sessions see a live PID.
                if (existing.isPresent() && sessionId.equals(existing.get().getSessionId())) {
                    if (existing.get().getPid() != pid) {
                        writeLock(newLock, dir);
                        registerShutdownHook(opts);
                    }
                    return true;
                }

                // Another live session holds it
                if (existing.isPresent() && isProcessRunning(existing.get().getPid())) {
                    if (!existing.get().getSessionId().equals(lastBlockedBy)) {
                        lastBlockedBy = existing.get().getSessionId();
                        log.debug("[ScheduledTasks] scheduler lock held by session {} (PID {})",
                                existing.get().getSessionId(), existing.get().getPid());
                    }
                    return false;
                }

                // Stale lock — unlink and retry once
                if (existing.isPresent()) {
                    log.debug("[ScheduledTasks] recovering stale scheduler lock from PID {}",
                            existing.get().getPid());
                }
                Path lockPath = getLockPath(dir);
                try {
                    Files.deleteIfExists(lockPath);
                } catch (IOException ignored) {}

                if (tryCreateExclusive(newLock, dir)) {
                    lastBlockedBy = null;
                    registerShutdownHook(opts);
                    return true;
                }

                // Another session won the recovery race
                return false;

            } catch (Exception e) {
                log.error("[ScheduledTasks] tryAcquireSchedulerLock error", e);
                return false;
            }
        });
    }

    /**
     * Convenience overload with null options (uses process cwd + session ID).
     */
    public static CompletableFuture<Boolean> tryAcquireSchedulerLock() {
        return tryAcquireSchedulerLock(null);
    }

    /**
     * Release the scheduler lock if the current session owns it.
     * Mirrors releaseSchedulerLock() in cronTasksLock.ts
     */
    public static CompletableFuture<Void> releaseSchedulerLock(SchedulerLockOptions opts) {
        return CompletableFuture.runAsync(() -> {
            lastBlockedBy = null;
            try {
                String dir = opts != null ? opts.getDir() : null;
                String sessionId = resolveSessionId(opts);
                Optional<SchedulerLock> existing = readLock(dir);
                if (existing.isEmpty() || !sessionId.equals(existing.get().getSessionId())) return;

                try {
                    Files.deleteIfExists(getLockPath(dir));
                    log.debug("[ScheduledTasks] released scheduler lock");
                } catch (IOException ignored) {
                    // Already gone — fine
                }
            } catch (Exception e) {
                log.error("[ScheduledTasks] releaseSchedulerLock error", e);
            }
        });
    }

    /**
     * Convenience overload.
     */
    public static CompletableFuture<Void> releaseSchedulerLock() {
        return releaseSchedulerLock(null);
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private static Path getLockPath(String dir) {
        String base = dir != null ? dir : System.getProperty("user.dir");
        return Path.of(base, LOCK_FILE_REL);
    }

    private static Optional<SchedulerLock> readLock(String dir) {
        Path path = getLockPath(dir);
        if (!Files.exists(path)) return Optional.empty();
        try {
            String raw = Files.readString(path);
            ObjectMapper mapper = new ObjectMapper();
            SchedulerLock lock = mapper.readValue(raw, SchedulerLock.class);
            return Optional.ofNullable(lock);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Atomic exclusive file creation — mirrors O_EXCL / 'wx' flag.
     * Returns true if the file was created, false if it already existed (EEXIST).
     */
    private static boolean tryCreateExclusive(SchedulerLock lock, String dir) throws IOException {
        Path path = getLockPath(dir);
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException ignored) {}

        ObjectMapper mapper = new ObjectMapper();
        String body = mapper.writeValueAsString(lock);
        try {
            Files.writeString(path, body, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return true;
        } catch (FileAlreadyExistsException e) {
            return false;
        }
    }

    private static void writeLock(SchedulerLock lock, String dir) {
        try {
            Path path = getLockPath(dir);
            ObjectMapper mapper = new ObjectMapper();
            Files.writeString(path, mapper.writeValueAsString(lock), StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
        } catch (IOException e) {
            log.error("[ScheduledTasks] failed to write lock file", e);
        }
    }

    /**
     * Checks whether the given PID belongs to a running process.
     * Mirrors isProcessRunning() from genericProcessUtils.ts
     */
    private static boolean isProcessRunning(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static String resolveSessionId(SchedulerLockOptions opts) {
        if (opts != null && opts.getLockIdentity() != null) {
            return opts.getLockIdentity();
        }
        // Fall back to a stable process-level identity
        return "session-" + ProcessHandle.current().pid();
    }

    private static volatile boolean shutdownHookRegistered = false;

    private static void registerShutdownHook(SchedulerLockOptions opts) {
        if (!shutdownHookRegistered) {
            shutdownHookRegistered = true;
            Runtime.getRuntime().addShutdownHook(new Thread(() ->
                releaseSchedulerLock(opts).join()
            ));
        }
    }
}
