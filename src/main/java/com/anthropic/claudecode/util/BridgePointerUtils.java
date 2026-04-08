package com.anthropic.claudecode.util;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Crash-recovery pointer utilities for Remote Control sessions.
 * Translated from src/bridge/bridgePointer.ts
 *
 * Written immediately after a bridge session is created, periodically
 * refreshed during the session, and cleared on clean shutdown. If the
 * process dies unclean (crash, kill -9, terminal closed), the pointer
 * persists. On next startup, `claude remote-control` detects it and offers
 * to resume via the --session-id flow.
 *
 * Staleness is checked against the file's mtime (not an embedded timestamp)
 * so that a periodic re-write with the same content serves as a refresh.
 * Scoped per working directory so two concurrent bridges in different repos
 * don't clobber each other.
 */
@Slf4j
public final class BridgePointerUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BridgePointerUtils.class);


    /** Upper bound on worktree fanout. */
    public static final int MAX_WORKTREE_FANOUT = 50;

    /** TTL in milliseconds for bridge pointer staleness check (4 hours). */
    public static final long BRIDGE_POINTER_TTL_MS = 4L * 60 * 60 * 1000;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private BridgePointerUtils() {}

    /** Bridge pointer data. */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class BridgePointer {
        @JsonProperty("sessionId")
        private String sessionId;

        @JsonProperty("environmentId")
        private String environmentId;

        /** Source: "standalone" or "repl" */
        @JsonProperty("source")
        private String source;

        public String getSessionId() { return sessionId; }
        public void setSessionId(String v) { sessionId = v; }
        public String getEnvironmentId() { return environmentId; }
        public void setEnvironmentId(String v) { environmentId = v; }
        public String getSource() { return source; }
        public void setSource(String v) { source = v; }
    }

    /** Bridge pointer with age information. */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class BridgePointerWithAge {
        private String sessionId;
        private String environmentId;
        private String source;
        private long ageMs;

        public long getAgeMs() { return ageMs; }
        public void setAgeMs(long v) { ageMs = v; }
    }

    /** Fanout result containing pointer and the directory it was found in. */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class BridgePointerResult {
        private BridgePointerWithAge pointer;
        private String dir;

        public BridgePointerWithAge getPointer() { return pointer; }
        public void setPointer(BridgePointerWithAge v) { pointer = v; }
        public String getDir() { return dir; }
        public void setDir(String v) { dir = v; }
    }

    /**
     * Get the path to the bridge pointer file for a given working directory.
     */
    public static String getBridgePointerPath(String dir) {
        String projectsDir = SessionStoragePortable.getProjectsDir();
        String sanitized = SessionStoragePortable.sanitizePath(dir);
        return Paths.get(projectsDir, sanitized, "bridge-pointer.json").toString();
    }

    /**
     * Write the pointer. Also used to refresh mtime during long sessions.
     * Best-effort — logs and swallows on error.
     */
    public static CompletableFuture<Void> writeBridgePointer(String dir, BridgePointer pointer) {
        return CompletableFuture.runAsync(() -> {
            Path path = Paths.get(getBridgePointerPath(dir));
            try {
                Files.createDirectories(path.getParent());
                String json = OBJECT_MAPPER.writeValueAsString(pointer);
                Files.writeString(path, json);
                log.debug("[bridge:pointer] wrote {}", path);
            } catch (Exception err) {
                log.warn("[bridge:pointer] write failed: {}", err.getMessage());
            }
        });
    }

    /**
     * Read the pointer and its age (ms since last write).
     * Returns null on any failure: missing file, corrupted JSON, schema mismatch,
     * or stale (mtime > 4h ago). Stale/invalid pointers are deleted.
     */
    public static CompletableFuture<BridgePointerWithAge> readBridgePointer(String dir) {
        return CompletableFuture.supplyAsync(() -> {
            Path path = Paths.get(getBridgePointerPath(dir));
            long mtimeMs;
            String raw;
            try {
                mtimeMs = Files.getLastModifiedTime(path).toMillis();
                raw = Files.readString(path);
            } catch (NoSuchFileException e) {
                return null;
            } catch (IOException e) {
                return null;
            }

            BridgePointer parsed;
            try {
                parsed = OBJECT_MAPPER.readValue(raw, BridgePointer.class);
                if (parsed.getSessionId() == null || parsed.getEnvironmentId() == null
                        || parsed.getSource() == null) {
                    throw new IllegalArgumentException("Missing required fields");
                }
            } catch (Exception e) {
                log.debug("[bridge:pointer] invalid schema, clearing: {}", path);
                clearBridgePointer(dir);
                return null;
            }

            long ageMs = Math.max(0, Instant.now().toEpochMilli() - mtimeMs);
            if (ageMs > BRIDGE_POINTER_TTL_MS) {
                log.debug("[bridge:pointer] stale (>4h mtime), clearing: {}", path);
                clearBridgePointer(dir);
                return null;
            }

            BridgePointerWithAge result = new BridgePointerWithAge();
            result.setSessionId(parsed.getSessionId());
            result.setEnvironmentId(parsed.getEnvironmentId());
            result.setSource(parsed.getSource());
            result.setAgeMs(ageMs);
            return result;
        });
    }

    /**
     * Worktree-aware read for --continue.
     * Fans out across git worktree siblings to find the freshest pointer.
     * Fast path: checks dir first. Returns pointer AND dir it was found in.
     */
    public static CompletableFuture<BridgePointerResult> readBridgePointerAcrossWorktrees(String dir) {
        return readBridgePointer(dir).thenCompose(here -> {
            if (here != null) {
                BridgePointerResult result = new BridgePointerResult();
                result.setPointer(here);
                result.setDir(dir);
                return CompletableFuture.completedFuture(result);
            }

            return WorktreePathsPortable.getWorktreePaths(dir).thenCompose(worktrees -> {
                if (worktrees.size() <= 1) {
                    return CompletableFuture.completedFuture(null);
                }
                if (worktrees.size() > MAX_WORKTREE_FANOUT) {
                    log.debug("[bridge:pointer] {} worktrees exceeds fanout cap {}, skipping",
                            worktrees.size(), MAX_WORKTREE_FANOUT);
                    return CompletableFuture.completedFuture(null);
                }

                String dirKey = SessionStoragePortable.sanitizePath(dir);
                List<String> candidates = new ArrayList<>();
                for (String wt : worktrees) {
                    if (!SessionStoragePortable.sanitizePath(wt).equals(dirKey)) {
                        candidates.add(wt);
                    }
                }

                @SuppressWarnings("unchecked")
                CompletableFuture<BridgePointerResult>[] futures = candidates.stream()
                        .map(wt -> readBridgePointer(wt).thenApply(p -> {
                            if (p == null) return null;
                            BridgePointerResult r = new BridgePointerResult();
                            r.setPointer(p);
                            r.setDir(wt);
                            return r;
                        }))
                        .toArray(CompletableFuture[]::new);

                return CompletableFuture.allOf(futures).thenApply(_v -> {
                    BridgePointerResult freshest = null;
                    for (CompletableFuture<BridgePointerResult> f : futures) {
                        BridgePointerResult r = f.join();
                        if (r != null && (freshest == null
                                || r.getPointer().getAgeMs() < freshest.getPointer().getAgeMs())) {
                            freshest = r;
                        }
                    }
                    if (freshest != null) {
                        log.debug("[bridge:pointer] fanout found pointer in worktree {} (ageMs={})",
                                freshest.getDir(), freshest.getPointer().getAgeMs());
                    }
                    return freshest;
                });
            });
        });
    }

    /**
     * Delete the pointer. Idempotent — ENOENT is expected when the process
     * shut down cleanly previously.
     */
    public static void clearBridgePointer(String dir) {
        Path path = Paths.get(getBridgePointerPath(dir));
        try {
            Files.deleteIfExists(path);
            log.debug("[bridge:pointer] cleared {}", path);
        } catch (IOException err) {
            log.warn("[bridge:pointer] clear failed: {}", err.getMessage());
        }
    }
}
