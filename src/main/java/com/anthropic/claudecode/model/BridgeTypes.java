package com.anthropic.claudecode.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Bridge protocol types for the environments API and session management.
 * Translated from src/bridge/types.ts
 */
public class BridgeTypes {

    /** Default per-session timeout (24 hours). */
    public static final long DEFAULT_SESSION_TIMEOUT_MS = 24L * 60 * 60 * 1000;

    /** Reusable login guidance appended to bridge auth errors. */
    public static final String BRIDGE_LOGIN_INSTRUCTION =
        "Remote Control is only available with claude.ai subscriptions. " +
        "Please use `/login` to sign in with your claude.ai account.";

    /** Full error printed when `claude remote-control` is run without auth. */
    public static final String BRIDGE_LOGIN_ERROR =
        "Error: You must be logged in to use Remote Control.\n\n" + BRIDGE_LOGIN_INSTRUCTION;

    /** Shown when the user disconnects Remote Control. */
    public static final String REMOTE_CONTROL_DISCONNECTED_MSG = "Remote Control disconnected.";

    // ─── Protocol types for the environments API ──────────────────────────────

    /**
     * The type discriminant for work data.
     * Translated from TypeScript union type: 'session' | 'healthcheck'
     */
    public enum WorkDataType {
        @JsonProperty("session") SESSION,
        @JsonProperty("healthcheck") HEALTHCHECK
    }

    /**
     * Translated from WorkData in types.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WorkData {
        private WorkDataType type;
        private String id;

        public WorkDataType getType() { return type; }
        public void setType(WorkDataType v) { type = v; }
        public String getId() { return id; }
        public void setId(String v) { id = v; }
    }

    /**
     * Translated from WorkResponse in types.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WorkResponse {
        private String id;
        private String type;
        @JsonProperty("environment_id")
        private String environmentId;
        private String state;
        private WorkData data;
        /** base64url-encoded JSON */
        private String secret;
        @JsonProperty("created_at")
        private String createdAt;

        public String getEnvironmentId() { return environmentId; }
        public void setEnvironmentId(String v) { environmentId = v; }
        public String getState() { return state; }
        public void setState(String v) { state = v; }
        public WorkData getData() { return data; }
        public void setData(WorkData v) { data = v; }
        public String getSecret() { return secret; }
        public void setSecret(String v) { secret = v; }
        public String getCreatedAt() { return createdAt; }
        public void setCreatedAt(String v) { createdAt = v; }
    
        public String getId() { return id; }
    }

    /**
     * Decoded work secret from base64url-encoded JSON.
     * Translated from WorkSecret in types.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WorkSecret {
        private int version;
        @JsonProperty("session_ingress_token")
        private String sessionIngressToken;
        @JsonProperty("api_base_url")
        private String apiBaseUrl;
        private List<SourceEntry> sources;
        private List<AuthEntry> auth;
        @JsonProperty("claude_code_args")
        private Map<String, String> claudeCodeArgs;
        @JsonProperty("mcp_config")
        private Object mcpConfig;
        @JsonProperty("environment_variables")
        private Map<String, String> environmentVariables;
        /**
         * Server-driven CCR v2 selector.
         */
        @JsonProperty("use_code_sessions")
        private Boolean useCodeSessions;

        @Data
        @lombok.NoArgsConstructor
        @lombok.AllArgsConstructor
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class SourceEntry {
            private String type;
            @JsonProperty("git_info")
            private GitInfo gitInfo;

            @Data
            @lombok.NoArgsConstructor
            @lombok.AllArgsConstructor
            @JsonIgnoreProperties(ignoreUnknown = true)
            public static class GitInfo {
                private String type;
                private String repo;
                private String ref;
                private String token;

        public String getRepo() { return repo; }
        public void setRepo(String v) { repo = v; }
        public String getRef() { return ref; }
        public void setRef(String v) { ref = v; }
        public String getToken() { return token; }
        public void setToken(String v) { token = v; }
            }

        public GitInfo getGitInfo() { return gitInfo; }
        public void setGitInfo(GitInfo v) { gitInfo = v; }
        }

        @Data
        @lombok.NoArgsConstructor
        @lombok.AllArgsConstructor
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class AuthEntry {
            private String type;
            private String token;

        }

        public int getVersion() { return version; }
        public void setVersion(int v) { version = v; }
        public String getSessionIngressToken() { return sessionIngressToken; }
        public void setSessionIngressToken(String v) { sessionIngressToken = v; }
        public String getApiBaseUrl() { return apiBaseUrl; }
        public void setApiBaseUrl(String v) { apiBaseUrl = v; }
        public List<SourceEntry> getSources() { return sources; }
        public void setSources(List<SourceEntry> v) { sources = v; }
        public List<AuthEntry> getAuth() { return auth; }
        public void setAuth(List<AuthEntry> v) { auth = v; }
        public Map<String, String> getClaudeCodeArgs() { return claudeCodeArgs; }
        public void setClaudeCodeArgs(Map<String, String> v) { claudeCodeArgs = v; }
        public Object getMcpConfig() { return mcpConfig; }
        public void setMcpConfig(Object v) { mcpConfig = v; }
        public Map<String, String> getEnvironmentVariables() { return environmentVariables; }
        public void setEnvironmentVariables(Map<String, String> v) { environmentVariables = v; }
        public boolean isUseCodeSessions() { return useCodeSessions; }
        public void setUseCodeSessions(Boolean v) { useCodeSessions = v; }
    }

    /**
     * Translated from SessionDoneStatus in types.ts.
     */
    public enum SessionDoneStatus {
        @JsonProperty("completed") COMPLETED,
        @JsonProperty("failed") FAILED,
        @JsonProperty("interrupted") INTERRUPTED
    }

    /**
     * Translated from SessionActivityType in types.ts.
     */
    public enum SessionActivityType {
        @JsonProperty("tool_start") TOOL_START,
        @JsonProperty("text") TEXT,
        @JsonProperty("result") RESULT,
        @JsonProperty("error") ERROR
    }

    /**
     * Translated from SessionActivity in types.ts
     */
    @Data
    @Builder
    public static class SessionActivity {
        private SessionActivityType type;
        /** e.g. "Editing src/foo.ts", "Reading package.json" */
        private String summary;
        private long timestamp;
    }

    /**
     * How sessions choose working directories.
     * Translated from SpawnMode in types.ts.
     */
    public enum SpawnMode {
        @JsonProperty("single-session") SINGLE_SESSION,
        @JsonProperty("worktree") WORKTREE,
        @JsonProperty("same-dir") SAME_DIR
    }

    /**
     * Well-known worker_type values.
     * Translated from BridgeWorkerType in types.ts.
     */
    public enum BridgeWorkerType {
        @JsonProperty("claude_code") CLAUDE_CODE,
        @JsonProperty("claude_code_assistant") CLAUDE_CODE_ASSISTANT
    }

    /**
     * Bridge configuration.
     * Translated from BridgeConfig in types.ts
     */
    @Data
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BridgeConfig {
        private String dir;
        private String machineName;
        private String branch;
        private String gitRepoUrl;
        private int maxSessions;
        private SpawnMode spawnMode;
        private boolean verbose;
        private boolean sandbox;
        /** Client-generated UUID identifying this bridge instance. */
        private String bridgeId;
        /**
         * Sent as metadata.worker_type. Backend treats this as opaque.
         */
        private String workerType;
        /** Client-generated UUID for idempotent environment registration. */
        private String environmentId;
        /**
         * Backend-issued environment_id to reuse on re-register (resume path).
         */
        private String reuseEnvironmentId;
        /** API base URL the bridge is connected to (used for polling). */
        private String apiBaseUrl;
        /** Session ingress base URL for WebSocket connections. */
        private String sessionIngressUrl;
        /** Debug file path passed via --debug-file. */
        private String debugFile;
        /** Per-session timeout in milliseconds. Sessions exceeding this are killed. */
        private Long sessionTimeoutMs;
    
        public String getBranch() { return branch; }
    
        public String getBridgeId() { return bridgeId; }
    
        public String getDir() { return dir; }
    
        public String getGitRepoUrl() { return gitRepoUrl; }
    
        public String getMachineName() { return machineName; }
    
        public int getMaxSessions() { return maxSessions; }
    
        public String getReuseEnvironmentId() { return reuseEnvironmentId; }
    
        public String getWorkerType() { return workerType; }
    }

    /**
     * A control_response event sent back to a session (e.g. a permission decision).
     * Translated from PermissionResponseEvent in types.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PermissionResponseEvent {
        private String type = "control_response";
        private PermissionResponse response;

        @Data
        @lombok.NoArgsConstructor
        @lombok.AllArgsConstructor
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class PermissionResponse {
            private String subtype = "success";
            @JsonProperty("request_id")
            private String requestId;
            private Map<String, Object> response;

        public String getSubtype() { return subtype; }
        public void setSubtype(String v) { subtype = v; }
        public String getRequestId() { return requestId; }
        public void setRequestId(String v) { requestId = v; }
        public Map<String, Object> getResponse() { return response; }
        public void setResponse(Map<String, Object> v) { response = v; }
        }

    
        public String getType() { return type; }
    }

    /**
     * Represents a live session handle.
     * Translated from SessionHandle in types.ts
     *
     * Note: The async `done` Promise is represented as CompletableFuture.
     */
    public interface SessionHandle {
        String getSessionId();
        CompletableFuture<SessionDoneStatus> getDone();
        void kill();
        void forceKill();
        List<SessionActivity> getActivities();
        SessionActivity getCurrentActivity();
        String getAccessToken();
        List<String> getLastStderr();
        void writeStdin(String data);
        /** Update the access token for a running session (e.g. after token refresh). */
        void updateAccessToken(String token);
    }

    /**
     * Options for spawning a session.
     * Translated from SessionSpawnOpts in types.ts
     */
    @Data
    @Builder
    public static class SessionSpawnOpts {
        private String sessionId;
        private String sdkUrl;
        private String accessToken;
        /** When true, spawn the child with CCR v2 env vars. */
        private boolean useCcrV2;
        /** Required when useCcrV2 is true. Obtained from POST /worker/register. */
        private Integer workerEpoch;
        /**
         * Fires once with the text of the first real user message seen on the child's stdout.
         */
        private Consumer<String> onFirstUserMessage;
    }

    /**
     * Factory interface for creating session handles.
     * Translated from SessionSpawner in types.ts
     */
    @FunctionalInterface
    public interface SessionSpawner {
        SessionHandle spawn(SessionSpawnOpts opts, String dir);
    }

    /**
     * Logger interface for bridge operations.
     * Translated from BridgeLogger in types.ts
     */
    public interface BridgeLogger {
        void printBanner(BridgeConfig config, String environmentId);
        void logSessionStart(String sessionId, String prompt);
        void logSessionComplete(String sessionId, long durationMs);
        void logSessionFailed(String sessionId, String error);
        void logStatus(String message);
        void logVerbose(String message);
        void logError(String message);
        void logReconnected(long disconnectedMs);
        void updateIdleStatus();
        void updateReconnectingStatus(String delayStr, String elapsedStr);
        void updateSessionStatus(String sessionId, String elapsed, SessionActivity activity, List<String> trail);
        void clearStatus();
        void setRepoInfo(String repoName, String branch);
        void setDebugLogPath(String path);
        void setAttached(String sessionId);
        void updateFailedStatus(String error);
        void toggleQr();
        void updateSessionCount(int active, int max, SpawnMode mode);
        void setSpawnModeDisplay(SpawnMode mode);
        void addSession(String sessionId, String url);
        void updateSessionActivity(String sessionId, SessionActivity activity);
        void setSessionTitle(String sessionId, String title);
        void removeSession(String sessionId);
        void refreshDisplay();
    }

    private BridgeTypes() {}
}
