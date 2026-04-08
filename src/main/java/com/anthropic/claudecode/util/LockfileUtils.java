package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.channels.FileLock;
import java.util.concurrent.CompletableFuture;

/**
 * File locking utilities.
 * Translated from src/utils/lockfile.ts
 *
 * The TypeScript module is a lazy accessor around the "proper-lockfile" npm
 * package (to avoid paying its startup cost on every invocation). In Java we
 * use java.nio.channels.FileLock, which is built-in and available on all
 * platforms, providing the same semantics.
 *
 * Public API mirrors the TypeScript exports:
 *   lock(file)   — acquire an exclusive lock; returns a Runnable that releases it
 *   lockSync(file) — synchronous variant
 *   unlock(file) — release an existing lock by path
 *   check(file)  — check whether the file is currently locked
 */
@Slf4j
public class LockfileUtils {



    /**
     * Acquire an exclusive lock on the given file path.
     * Returns a CompletableFuture that resolves to a {@link Runnable} which,
     * when called, releases the lock.
     *
     * Translated from lock() in lockfile.ts
     */
    public static CompletableFuture<Runnable> lock(String filePath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return lockSync(filePath);
            } catch (Exception e) {
                throw new RuntimeException("Lock failed for: " + filePath, e);
            }
        });
    }

    /**
     * Synchronous variant of {@link #lock}.
     * Translated from lockSync() in lockfile.ts
     *
     * @throws RuntimeException if the lock cannot be acquired
     */
    public static Runnable lockSync(String filePath) {
        try {
            String lockPath = filePath + ".lock";
            File lockFile = new File(lockPath);
            if (lockFile.getParentFile() != null) {
                lockFile.getParentFile().mkdirs();
            }

            // FileOutputStream kept open to hold the FileLock; closed on release
            FileOutputStream fos = new FileOutputStream(lockFile);
            FileLock fileLock = fos.getChannel().tryLock();

            if (fileLock == null) {
                fos.close();
                throw new RuntimeException("Could not acquire lock for: " + filePath);
            }

            return () -> {
                try {
                    fileLock.release();
                    fos.close();
                    lockFile.delete();
                } catch (Exception e) {
                    log.warn("Could not release lock for {}: {}", filePath, e.getMessage());
                }
            };

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Lock failed for: " + filePath, e);
        }
    }

    /**
     * Release the lock on the given file path.
     * This is a best-effort operation that deletes the .lock marker file.
     * For locks acquired via {@link #lock}/{@link #lockSync}, prefer calling the
     * returned Runnable directly.
     *
     * Translated from unlock() in lockfile.ts
     */
    public static CompletableFuture<Void> unlock(String filePath) {
        return CompletableFuture.runAsync(() -> {
            File lockFile = new File(filePath + ".lock");
            if (lockFile.exists() && !lockFile.delete()) {
                log.warn("Could not delete lock file: {}", lockFile.getAbsolutePath());
            }
        });
    }

    /**
     * Check whether the given file is currently locked.
     * Returns a CompletableFuture resolving to true if the file is locked.
     *
     * Translated from check() in lockfile.ts
     */
    public static CompletableFuture<Boolean> check(String filePath) {
        return CompletableFuture.supplyAsync(() -> isLocked(filePath));
    }

    /**
     * Synchronous check whether a file is locked.
     */
    public static boolean isLocked(String filePath) {
        File lockFile = new File(filePath + ".lock");
        if (!lockFile.exists()) return false;

        try (FileOutputStream fos = new FileOutputStream(lockFile)) {
            FileLock lock = fos.getChannel().tryLock();
            if (lock != null) {
                lock.release();
                return false; // Could acquire → not locked
            }
            return true; // Could not acquire → locked
        } catch (Exception e) {
            return true; // Assume locked if we can't check
        }
    }

    private LockfileUtils() {}
}
