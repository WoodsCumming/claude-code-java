package com.anthropic.claudecode.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Server-related types for Claude Code server mode.
 * Translated from src/server/types.ts
 */
public class ServerTypes {

    /**
     * Server configuration.
     * Translated from ServerConfig in types.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ServerConfig {
        private int port;
        private String host;
        private String authToken;
        /** Unix socket path (optional). */
        private String unix;
        /** Idle timeout for detached sessions (ms). 0 = never expire. */
        private Long idleTimeoutMs;
        /** Maximum number of concurrent sessions. */
        private Integer maxSessions;
        /** Default workspace directory for sessions that don't specify cwd. */
        private String workspace;

        public int getPort() { return port; }
        public void setPort(int v) { port = v; }
        public String getHost() { return host; }
        public void setHost(String v) { host = v; }
        public String getAuthToken() { return authToken; }
        public void setAuthToken(String v) { authToken = v; }
        public String getUnix() { return unix; }
        public void setUnix(String v) { unix = v; }
        public Long getIdleTimeoutMs() { return idleTimeoutMs; }
        public void setIdleTimeoutMs(Long v) { idleTimeoutMs = v; }
        public Integer getMaxSessions() { return maxSessions; }
        public void setMaxSessions(Integer v) { maxSessions = v; }
        public String getWorkspace() { return workspace; }
        public void setWorkspace(String v) { workspace = v; }
    }

    /**
     * Session lifecycle state.
     * Translated from the SessionState union type in types.ts
     */
    public enum SessionState {
        STARTING("starting"),
        RUNNING("running"),
        DETACHED("detached"),
        STOPPING("stopping"),
        STOPPED("stopped");

        private final String value;

        SessionState(String value) { this.value = value; }

        public String getValue() { return value; }

        public static SessionState fromValue(String value) {
            for (SessionState s : values()) {
                if (s.value.equalsIgnoreCase(value)) return s;
            }
            throw new IllegalArgumentException("Unknown SessionState: " + value);
        }
    }

    /**
     * In-memory session information (includes the live process handle).
     * Translated from SessionInfo in types.ts
     *
     * Note: ChildProcess is represented as a {@link Process} reference.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SessionInfo {
        private String id;
        private SessionState status;
        private long createdAt;
        private String workDir;
        /** Transient: not serialised to JSON. */
        private transient Process process;
        private String sessionKey;

        public String getId() { return id; }
        public void setId(String v) { id = v; }
        public SessionState getStatus() { return status; }
        public void setStatus(SessionState v) { status = v; }
        public long getCreatedAt() { return createdAt; }
        public void setCreatedAt(long v) { createdAt = v; }
        public String getWorkDir() { return workDir; }
        public void setWorkDir(String v) { workDir = v; }
        public Process getProcess() { return process; }
        public void setProcess(Process v) { process = v; }
        public String getSessionKey() { return sessionKey; }
        public void setSessionKey(String v) { sessionKey = v; }
    }

    /**
     * Stable session key → session metadata.
     * Persisted to ~/.claude/server-sessions.json so sessions can be resumed across restarts.
     * Translated from SessionIndexEntry in types.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SessionIndexEntry {
        /** Server-assigned session ID (matches the subprocess's claude session). */
        @JsonProperty("sessionId")
        private String sessionId;
        /** The claude transcript session ID for --resume. Same as sessionId for direct sessions. */
        @JsonProperty("transcriptSessionId")
        private String transcriptSessionId;
        private String cwd;
        private String permissionMode;
        private long createdAt;
        private long lastActiveAt;

        public String getSessionId() { return sessionId; }
        public void setSessionId(String v) { sessionId = v; }
        public String getTranscriptSessionId() { return transcriptSessionId; }
        public void setTranscriptSessionId(String v) { transcriptSessionId = v; }
        public String getCwd() { return cwd; }
        public void setCwd(String v) { cwd = v; }
        public String getPermissionMode() { return permissionMode; }
        public void setPermissionMode(String v) { permissionMode = v; }
        public long getLastActiveAt() { return lastActiveAt; }
        public void setLastActiveAt(long v) { lastActiveAt = v; }
    }

    /**
     * Response received from POST /sessions when creating a new session.
     * Translated from the connectResponseSchema in types.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConnectResponse {
        @JsonProperty("session_id")
        private String sessionId;
        @JsonProperty("ws_url")
        private String wsUrl;
        @JsonProperty("work_dir")
        private String workDir;

        public String getWsUrl() { return wsUrl; }
        public void setWsUrl(String v) { wsUrl = v; }
    }
}
