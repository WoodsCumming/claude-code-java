package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.BridgePollConfigDefaults;
import com.anthropic.claudecode.util.BridgePollConfigDefaults.PollIntervalConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Bootstrap-free bridge core: environment registration → session creation
 * → poll loop → ingress WebSocket → teardown.
 *
 * Reads nothing from bootstrap/state or sessionStorage — all context comes
 * from {@link BridgeCoreParams}. The caller (BridgeInitService / daemon) has
 * already passed entitlement gates and gathered git/auth/title.
 *
 * Returns null on registration or session-creation failure.
 *
 * Translated from src/bridge/replBridge.ts (initBridgeCore + public types)
 */
@Slf4j
@Service
public class BridgeReplService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BridgeReplService.class);


    // ─── Handle interface ─────────────────────────────────────────────────────

    /**
     * Returned by initBridgeCore and initEnvLessBridgeCore.
     * Provides all outbound write/control operations and teardown.
     * Translated from ReplBridgeHandle in replBridge.ts
     */
    public interface ReplBridgeHandle {
        String getBridgeSessionId();
        String getEnvironmentId();
        String getSessionIngressUrl();
        void writeMessages(List<Map<String, Object>> messages);
        void writeSdkMessages(List<Map<String, Object>> messages);
        void sendControlRequest(Map<String, Object> request);
        void sendControlResponse(Map<String, Object> response);
        void sendControlCancelRequest(String requestId);
        void sendResult();
        CompletableFuture<Void> teardown();
    }

    /**
     * Superset of ReplBridgeHandle for daemon callers that persist the SSE
     * sequence number across process restarts.
     * Translated from BridgeCoreHandle in replBridge.ts
     */
    public interface BridgeCoreHandle extends ReplBridgeHandle {
        /**
         * Current SSE sequence-number high-water mark. Daemon callers persist
         * this on shutdown and pass it back as initialSSESequenceNum on restart.
         */
        int getSSESequenceNum();
    }

    // ─── State enum ───────────────────────────────────────────────────────────

    /**
     * Bridge connection state.
     * Translated from BridgeState in replBridge.ts
     */
    public enum BridgeState {
        READY, CONNECTED, RECONNECTING, FAILED
    }

    // ─── Params ───────────────────────────────────────────────────────────────

    /**
     * All bootstrap-free parameters passed to initBridgeCore.
     * Everything that initReplBridge reads from bootstrap state becomes a field here.
     * Translated from BridgeCoreParams in replBridge.ts
     */
    public static class BridgeCoreParams {
        /** Working directory for the bridge session. */
        public String dir;
        /** Hostname of this machine. */
        public String machineName;
        /** Current git branch. */
        public String branch;
        /** Remote git repository URL (null if not in a git repo). */
        public String gitRepoUrl;
        /** Session display title shown in the claude.ai session list. */
        public String title;
        /** Bridge API base URL. */
        public String baseUrl;
        /** Session ingress URL (may differ from baseUrl for ant-internal sessions). */
        public String sessionIngressUrl;
        /**
         * Opaque string sent as metadata.worker_type.
         * Use "claude_code" or "claude_code_assistant" for CLI-originated values.
         */
        public String workerType;
        /** Returns the current OAuth access token (null if not authenticated). */
        public Supplier<String> getAccessToken;
        /**
         * Creates a bridge session (POST /v1/sessions).
         * Injected to keep the Session API tree out of the Agent SDK bundle.
         * @return session ID on success, null on failure
         */
        public Function<CreateSessionOpts, CompletableFuture<String>> createSession;
        /**
         * Archives the bridge session (POST /v1/sessions/{id}/archive).
         * Best-effort — must not throw.
         */
        public Function<String, CompletableFuture<Void>> archiveSession;
        /**
         * Returns the current session title. Invoked on reconnect-after-env-lost.
         * Defaults to returning the static {@code title} field.
         */
        public Supplier<String> getCurrentTitle;
        /**
         * Converts internal Message maps → SDKMessage maps for writeMessages().
         * Injected to keep mappers.ts out of the Agent SDK bundle.
         */
        public Function<List<Map<String, Object>>, List<Map<String, Object>>> toSDKMessages;
        /** OAuth 401 refresh handler. */
        public Function<String, CompletableFuture<Boolean>> onAuth401;
        /** Returns the current poll interval config. */
        public Supplier<PollIntervalConfig> getPollIntervalConfig;
        /** Max initial messages to replay on connect (default 200). */
        public int initialHistoryCap = 200;
        /** Pre-existing messages to replay as initial history. */
        public List<Map<String, Object>> initialMessages;
        /**
         * UUIDs already flushed in a prior bridge session. Messages with these
         * UUIDs are excluded from the initial flush. Mutated in place.
         */
        public Set<String> previouslyFlushedUUIDs;
        /** Called with each inbound SDKMessage from the bridge. */
        public Consumer<Map<String, Object>> onInboundMessage;
        /** Called when a permission response arrives. */
        public Consumer<Map<String, Object>> onPermissionResponse;
        /** Called when the user sends an interrupt. */
        public Runnable onInterrupt;
        /** Called when the model is changed remotely. */
        public Consumer<String> onSetModel;
        /** Called when max-thinking tokens are changed remotely. */
        public Consumer<Integer> onSetMaxThinkingTokens;
        /**
         * Called when the permission mode is changed remotely.
         * Returns a Map with "ok" → Boolean and optionally "error" → String.
         */
        public Function<String, Map<String, Object>> onSetPermissionMode;
        /** Called when the bridge state transitions. */
        public java.util.function.BiConsumer<BridgeState, String> onStateChange;
        /**
         * Fired on each real user message seen in writeMessages() until
         * the callback returns true (done). The caller owns the
         * derive-at-count-1-and-3 policy.
         *
         * @param text      the user message text
         * @param sessionId the current bridge session ID
         * @return true when title derivation is complete
         */
        public BiFunction<String, String, Boolean> onUserMessage;
        /** When true, enables cross-restart session continuity via bridge-pointer.json. */
        public boolean perpetual;
        /**
         * Seeds the SSE event-stream high-water mark for daemon callers that
         * persist seq-num across restarts.
         */
        public int initialSSESequenceNum = 0;
    }

    /**
     * Options for creating a bridge session.
     * Translated from the createSession callback parameter shape in replBridge.ts
     */
    public record CreateSessionOpts(
        String environmentId,
        String title,
        String gitRepoUrl,
        String branch,
        boolean cancelled
    ) {}

    // ─── Init ─────────────────────────────────────────────────────────────────

    /**
     * Bootstrap-free bridge core: env registration → session creation →
     * poll loop → ingress connection → teardown.
     *
     * Returns null on registration or session-creation failure.
     *
     * Translated from initBridgeCore() in replBridge.ts
     */
    public CompletableFuture<BridgeCoreHandle> initBridgeCore(BridgeCoreParams params) {
        return CompletableFuture.supplyAsync(() -> {
            if (params.getAccessToken == null) {
                log.debug("[bridge:repl] No getAccessToken supplier");
                return null;
            }
            String accessToken = params.getAccessToken.get();
            if (accessToken == null || accessToken.isBlank()) {
                log.debug("[bridge:repl] No access token available");
                notifyStateChange(params, BridgeState.FAILED, "no access token");
                return null;
            }

            String currentTitle = params.title;
            Supplier<String> getCurrentTitle = params.getCurrentTitle != null
                    ? params.getCurrentTitle : () -> currentTitle;

            PollIntervalConfig pollConfig = params.getPollIntervalConfig != null
                    ? params.getPollIntervalConfig.get()
                    : BridgePollConfigDefaults.DEFAULT_POLL_CONFIG;

            log.debug("[bridge:repl] initBridgeCore starting (dir={}, workerType={})",
                    params.dir, params.workerType);

            // In the full implementation this would:
            // 1. Register bridge environment via BridgeApiClient
            // 2. Create or reconnect session
            // 3. Start the poll loop
            // 4. Wire the ingress transport (HybridTransport or v2 SSE)
            // 5. Return a BridgeCoreHandle

            // Stub — returns null until transport layer is implemented
            notifyStateChange(params, BridgeState.FAILED,
                    "Bridge core not yet fully implemented");
            return null;
        });
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

    private static void notifyStateChange(
            BridgeCoreParams params, BridgeState state, String detail) {
        if (params.onStateChange != null) {
            params.onStateChange.accept(state, detail);
        }
    }
}
