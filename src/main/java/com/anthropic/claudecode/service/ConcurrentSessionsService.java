package com.anthropic.claudecode.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Concurrent sessions management service.
 *
 * Translated from src/utils/concurrentSessions.ts
 *
 * Writes a PID file for each registered session so {@code claude ps} can
 * enumerate running sessions. Stale PID files (crashed sessions) are
 * swept on {@link #countConcurrentSessions()}.
 */
@Slf4j
@Service
public class ConcurrentSessionsService {



    // ------------------------------------------------------------------
    // Enums — mirror SessionKind / SessionStatus from concurrentSessions.ts
    // ------------------------------------------------------------------

    public enum SessionKind {
        INTERACTIVE("interactive"),
        BG("bg"),
        DAEMON("daemon"),
        DAEMON_WORKER("daemon-worker");

        private final String value;
        SessionKind(String value) { this.value = value; }
        public String getValue() { return value; }

        public static SessionKind fromEnv(String envValue) {
            for (SessionKind k : values()) {
                if (k.value.equals(envValue)) return k;
            }
            return null;
        }
    }

    public enum SessionStatus {
        BUSY("busy"),
        IDLE("idle"),
        WAITING("waiting");

        private final String value;
        SessionStatus(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    // ------------------------------------------------------------------
    // Session PID-file record (written to <sessions-dir>/<pid>.json)
    // ------------------------------------------------------------------

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SessionPidRecord {
        @JsonProperty("pid")
        private long pid;
        @JsonProperty("sessionId")
        private String sessionId;
        @JsonProperty("cwd")
        private String cwd;
        @JsonProperty("startedAt")
        private long startedAt;
        @JsonProperty("kind")
        private String kind;
        @JsonProperty("name")
        private String name;
        @JsonProperty("status")
        private String status;
        @JsonProperty("waitingFor")
        private String waitingFor;
        @JsonProperty("updatedAt")
        private Long updatedAt;
        @JsonProperty("entrypoint")
        private String entrypoint;
        @JsonProperty("bridgeSessionId")
        private String bridgeSessionId;

        public long getPid() { return pid; }
        public void setPid(long v) { pid = v; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String v) { sessionId = v; }
        public String getCwd() { return cwd; }
        public void setCwd(String v) { cwd = v; }
        public long getStartedAt() { return startedAt; }
        public void setStartedAt(long v) { startedAt = v; }
        public String getKind() { return kind; }
        public void setKind(String v) { kind = v; }
        public String getName() { return name; }
        public void setName(String v) { name = v; }
        public String getStatus() { return status; }
        public void setStatus(String v) { status = v; }
        public String getWaitingFor() { return waitingFor; }
        public void setWaitingFor(String v) { waitingFor = v; }
        public Long getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(Long v) { updatedAt = v; }
        public String getEntrypoint() { return entrypoint; }
        public void setEntrypoint(String v) { entrypoint = v; }
        public String getBridgeSessionId() { return bridgeSessionId; }
        public void setBridgeSessionId(String v) { bridgeSessionId = v; }
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private final ObjectMapper objectMapper;
    /** Path of this session's PID file; set after registerSession(). */
    private volatile Path pidFile;

    @Autowired
    public ConcurrentSessionsService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // Sessions directory
    // ------------------------------------------------------------------

    /**
     * Returns the sessions directory path.
     * Mirrors getSessionsDir() in concurrentSessions.ts
     */
    public String getSessionsDir() {
        String claudeHome = Optional.ofNullable(System.getenv("CLAUDE_CONFIG_DIR"))
                .filter(s -> !s.isBlank())
                .orElse(System.getProperty("user.home") + "/.claude");
        return claudeHome + "/sessions";
    }

    // ------------------------------------------------------------------
    // Register / update / deregister
    // ------------------------------------------------------------------

    /**
     * Writes a PID file for the current session and registers JVM shutdown cleanup.
     *
     * Skips sub-agents (when agentId env var is set) — mirrors the getAgentId() check.
     * Returns true if registered, false if skipped.
     *
     * Mirrors registerSession() in concurrentSessions.ts
     */
    public CompletableFuture<Boolean> registerSession(String sessionId, String cwd) {
        return CompletableFuture.supplyAsync(() -> {
            // Skip teammates/sub-agents (mirrors `if (getAgentId() != null) return false`)
            String agentId = System.getenv("CLAUDE_CODE_AGENT_ID");
            if (agentId != null && !agentId.isBlank()) return false;

            SessionKind kind = resolveSessionKind();
            String sessionsDir = getSessionsDir();
            long pid = ProcessHandle.current().pid();
            Path file = Path.of(sessionsDir, pid + ".json");
            this.pidFile = file;

            // Register shutdown cleanup (mirrors registerCleanup)
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { Files.deleteIfExists(file); } catch (IOException ignored) {}
            }));

            try {
                Files.createDirectories(Path.of(sessionsDir));
                trySetPermissions(Path.of(sessionsDir), "rwx------");

                SessionPidRecord record = new SessionPidRecord();
                record.setPid(pid);
                record.setSessionId(sessionId);
                record.setCwd(cwd);
                record.setStartedAt(System.currentTimeMillis());
                record.setKind(kind.getValue());
                record.setEntrypoint(System.getenv("CLAUDE_CODE_ENTRYPOINT"));

                Files.writeString(file, objectMapper.writeValueAsString(record),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                return true;
            } catch (Exception e) {
                log.debug("[concurrentSessions] register failed: {}", e.getMessage());
                return false;
            }
        });
    }

    /**
     * Patches the current session's PID file with the given fields.
     * Mirrors updatePidFile(patch) in concurrentSessions.ts
     */
    public CompletableFuture<Void> updatePidFile(Map<String, Object> patch) {
        return CompletableFuture.runAsync(() -> {
            Path file = this.pidFile;
            if (file == null) return;
            try {
                String raw = Files.readString(file);
                ObjectNode node = (ObjectNode) objectMapper.readTree(raw);
                patch.forEach((k, v) -> {
                    if (v == null) node.putNull(k);
                    else if (v instanceof String s) node.put(k, s);
                    else if (v instanceof Long l) node.put(k, l);
                    else if (v instanceof Integer i) node.put(k, i);
                    else if (v instanceof Boolean b) node.put(k, b);
                    else node.putPOJO(k, v);
                });
                Files.writeString(file, objectMapper.writeValueAsString(node),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (Exception e) {
                log.debug("[concurrentSessions] updatePidFile failed: {}", e.getMessage());
            }
        });
    }

    /**
     * Updates the session name in the PID file.
     * Mirrors updateSessionName() in concurrentSessions.ts
     */
    public CompletableFuture<Void> updateSessionName(String name) {
        if (name == null || name.isBlank()) return CompletableFuture.completedFuture(null);
        return updatePidFile(Map.of("name", name));
    }

    /**
     * Records the remote-control bridge session ID in the PID file.
     * Mirrors updateSessionBridgeId() in concurrentSessions.ts
     */
    public CompletableFuture<Void> updateSessionBridgeId(String bridgeSessionId) {
        Map<String, Object> patch = new HashMap<>();
        patch.put("bridgeSessionId", bridgeSessionId); // null is intentionally allowed
        return updatePidFile(patch);
    }

    /**
     * Pushes live activity state to the PID file for {@code claude ps}.
     * Mirrors updateSessionActivity() in concurrentSessions.ts
     */
    public CompletableFuture<Void> updateSessionActivity(SessionStatus status, String waitingFor) {
        Map<String, Object> patch = new HashMap<>();
        if (status != null) patch.put("status", status.getValue());
        if (waitingFor != null) patch.put("waitingFor", waitingFor);
        patch.put("updatedAt", System.currentTimeMillis());
        return updatePidFile(patch);
    }

    // ------------------------------------------------------------------
    // Count concurrent sessions
    // ------------------------------------------------------------------

    /**
     * Counts live concurrent CLI sessions (including this one).
     * Filters out stale PID files (crashed sessions) and deletes them.
     * Returns 0 on any error.
     *
     * Mirrors countConcurrentSessions() in concurrentSessions.ts
     */
    public CompletableFuture<Integer> countConcurrentSessions() {
        return CompletableFuture.supplyAsync(() -> {
            Path dir = Path.of(getSessionsDir());
            String[] files;
            try {
                files = dir.toFile().list();
            } catch (Exception e) {
                log.debug("[concurrentSessions] readdir failed: {}", e.getMessage());
                return 0;
            }
            if (files == null) return 0;

            long thisPid = ProcessHandle.current().pid();
            boolean isWsl = isWsl();
            int count = 0;

            for (String file : files) {
                // Strict guard: only <pid>.json files are session files
                // (mirrors /^\d+\.json$/ in TS to avoid misidentifying other files)
                if (!file.matches("^\\d+\\.json$")) continue;

                long pid;
                try {
                    pid = Long.parseLong(file.substring(0, file.length() - 5));
                } catch (NumberFormatException e) {
                    continue;
                }

                if (pid == thisPid) {
                    count++;
                    continue;
                }

                if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                    count++;
                } else if (!isWsl) {
                    // Stale file — sweep it
                    try { Files.deleteIfExists(dir.resolve(file)); } catch (IOException ignored) {}
                }
            }

            return count;
        });
    }

    // ------------------------------------------------------------------
    // isBgSession
    // ------------------------------------------------------------------

    /**
     * True when this REPL is running inside a {@code claude --bg} tmux session.
     * Mirrors isBgSession() in concurrentSessions.ts
     */
    public boolean isBgSession() {
        return SessionKind.BG == resolveSessionKind();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static SessionKind resolveSessionKind() {
        String k = System.getenv("CLAUDE_CODE_SESSION_KIND");
        if (k != null) {
            SessionKind parsed = SessionKind.fromEnv(k);
            if (parsed != null) return parsed;
        }
        return SessionKind.INTERACTIVE;
    }

    private static boolean isWsl() {
        String os = System.getenv("WSL_DISTRO_NAME");
        return os != null && !os.isBlank();
    }

    private static void trySetPermissions(Path path, String perms) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(perms));
        } catch (Exception ignored) {
            // Non-POSIX filesystem or insufficient privileges — best effort
        }
    }
}
