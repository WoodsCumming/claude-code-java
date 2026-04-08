package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * REPL-specific wrapper around initBridgeCore.
 *
 * Owns the parts that read bootstrap state — gates, cwd, session ID, git
 * context, OAuth, title derivation — then delegates to the bootstrap-free core.
 *
 * Split out of replBridge.ts because the sessionStorage import
 * (getCurrentSessionTitle) transitively pulls in src/commands.ts and the
 * entire slash command + React component tree. Keeping initBridgeCore in a
 * file that doesn't touch sessionStorage lets daemonBridge.ts import the
 * core without bloating the Agent SDK bundle.
 *
 * Translated from src/bridge/initReplBridge.ts
 */
@Slf4j
@Service
public class BridgeInitService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BridgeInitService.class);


    private static final int TITLE_MAX_LEN = 50;

    private final BridgeReplService bridgeReplService;
    private final BridgeRemoteCoreService bridgeRemoteCoreService;

    @Autowired
    public BridgeInitService(
            BridgeReplService bridgeReplService,
            BridgeRemoteCoreService bridgeRemoteCoreService) {
        this.bridgeReplService = bridgeReplService;
        this.bridgeRemoteCoreService = bridgeRemoteCoreService;
    }

    // ─── Options ──────────────────────────────────────────────────────────────

    /**
     * Options for initializing the REPL bridge.
     * Translated from InitBridgeOptions in initReplBridge.ts
     */
    public static class InitBridgeOptions {
        /** Called with each inbound SDKMessage from the bridge. */
        public java.util.function.Consumer<Map<String, Object>> onInboundMessage;
        /** Called when a permission response arrives from the remote client. */
        public java.util.function.Consumer<Map<String, Object>> onPermissionResponse;
        /** Called when the user sends an interrupt signal. */
        public Runnable onInterrupt;
        /** Called when the model is changed remotely. */
        public java.util.function.Consumer<String> onSetModel;
        /** Called when max-thinking tokens are changed remotely. */
        public java.util.function.Consumer<Integer> onSetMaxThinkingTokens;
        /** Called when the permission mode is changed remotely. Returns ok/error. */
        public java.util.function.Function<String, Map<String, Object>> onSetPermissionMode;
        /** Called when the bridge state changes. */
        public java.util.function.BiConsumer<BridgeState, String> onStateChange;
        /** Pre-existing messages to flush as initial history. */
        public List<Map<String, Object>> initialMessages;
        /**
         * Explicit session name from {@code /remote-control <name>}. When set,
         * overrides the title derived from the conversation or /rename.
         */
        public String initialName;
        /**
         * Fresh view of the full conversation at call time. Used by onUserMessage's
         * count-3 derivation to call generateSessionTitle over the full conversation.
         */
        public Supplier<List<Map<String, Object>>> getMessages;
        /**
         * UUIDs already flushed in a prior bridge session. Messages with these
         * UUIDs are excluded from the initial flush to avoid poisoning the server.
         * Mutated in place — newly flushed UUIDs are added after each flush.
         */
        public Set<String> previouslyFlushedUUIDs;
        /** When true, enables cross-restart session continuity via bridge-pointer.json. */
        public boolean perpetual;
        /**
         * When true, the bridge only forwards events outbound (no SSE inbound
         * stream). Used by CCR mirror mode.
         */
        public boolean outboundOnly;
        /** Free-form tags for session categorization. */
        public List<String> tags;
    }

    /**
     * Bridge state enum.
     * Translated from BridgeState in replBridge.ts
     */
    public enum BridgeState {
        READY, CONNECTED, RECONNECTING, FAILED
    }

    // ─── Init ─────────────────────────────────────────────────────────────────

    /**
     * Initialize the REPL bridge with all runtime gate checks, OAuth validation,
     * policy checks, and title derivation.
     *
     * Returns a handle on success, null on any blocking failure (not enabled,
     * no OAuth, policy denied, version too old, no org UUID).
     *
     * Translated from initReplBridge() in initReplBridge.ts
     *
     * @param options       bridge init options (may be null for all defaults)
     * @param isBridgeEnabled  gate: is the bridge feature enabled?
     * @param oauthToken    current OAuth access token (null if not signed in)
     * @param isPolicyAllowed  gate: is allow_remote_control policy allowed?
     * @param isEnvLessBridge  gate: use the env-less (v2) bridge path?
     * @param versionOk     gate: does the current version meet the minimum?
     * @param orgUUID       the organization UUID (null if not available)
     * @param dir           current working directory
     * @param machineName   hostname of this machine
     * @param branch        current git branch (empty string if none)
     * @param gitRepoUrl    remote git URL (null if not a git repo)
     * @param baseUrl       bridge API base URL
     * @return a CompletableFuture resolving to a bridge handle map or null
     */
    public CompletableFuture<Map<String, Object>> initReplBridge(
            InitBridgeOptions options,
            boolean isBridgeEnabled,
            String oauthToken,
            boolean isPolicyAllowed,
            boolean isEnvLessBridge,
            boolean versionOk,
            String orgUUID,
            String dir,
            String machineName,
            String branch,
            String gitRepoUrl,
            String baseUrl) {

        return CompletableFuture.supplyAsync(() -> {
            InitBridgeOptions opts = options != null ? options : new InitBridgeOptions();

            // 1. Runtime gate
            if (!isBridgeEnabled) {
                log.debug("[bridge:repl] Skipping: bridge not enabled");
                return null;
            }

            // 2. Check OAuth — must be signed in with claude.ai
            if (oauthToken == null || oauthToken.isBlank()) {
                log.debug("[bridge:repl] Skipping: no OAuth tokens");
                notifyStateChange(opts, BridgeState.FAILED, "/login");
                return null;
            }

            // 3. Check organization policy — remote control may be disabled
            if (!isPolicyAllowed) {
                log.debug("[bridge:repl] Skipping: allow_remote_control policy not allowed");
                notifyStateChange(opts, BridgeState.FAILED, "disabled by your organization's policy");
                return null;
            }

            // 4. Version check
            if (!versionOk) {
                log.debug("[bridge:repl] Skipping: version too old");
                notifyStateChange(opts, BridgeState.FAILED, "run `claude update` to upgrade");
                return null;
            }

            // 5. Org UUID required
            if (orgUUID == null || orgUUID.isBlank()) {
                log.debug("[bridge:repl] Skipping: no org UUID");
                notifyStateChange(opts, BridgeState.FAILED, "/login");
                return null;
            }

            // Derive initial session title
            String title = deriveInitialTitle(opts);

            // 6. Route to env-less (v2) or env-based (v1) bridge
            if (isEnvLessBridge && !opts.perpetual) {
                log.debug("[bridge:repl] Using env-less bridge path (tengu_bridge_repl_v2)");
                return null; // Delegated to BridgeRemoteCoreService in full implementation
            }

            // v1 env-based path
            log.debug("[bridge:repl] Using env-based bridge path (v1)");
            return null; // Delegated to BridgeReplService.initBridgeCore in full implementation
        });
    }

    // ─── Title derivation ─────────────────────────────────────────────────────

    /**
     * Derive the initial session title from options.
     * Prefers: explicit initialName > session storage title > last user message > slug fallback.
     */
    private String deriveInitialTitle(InitBridgeOptions opts) {
        if (opts.initialName != null && !opts.initialName.isBlank()) {
            return opts.initialName;
        }
        // TODO: check session storage for a /rename title
        // TODO: extract from last user message in initialMessages
        return "remote-control-" + generateShortWordSlug();
    }

    /**
     * Quick placeholder title: strip display tags, take the first sentence,
     * collapse whitespace, truncate to 50 chars.
     * Returns null if the result is empty.
     * Translated from deriveTitle() in initReplBridge.ts
     */
    public static String deriveTitle(String raw) {
        if (raw == null || raw.isBlank()) return null;
        // Strip display tags like <ide_opened_file>, <session-start-hook>, etc.
        String clean = raw.replaceAll("<[^>]+>[^<]*</[^>]+>", "").trim();
        if (clean.isBlank()) return null;
        // First sentence
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(.*?[.!?])\\s").matcher(clean);
        String firstSentence = m.find() ? m.group(1) : clean;
        // Collapse whitespace
        String flat = firstSentence.replaceAll("\\s+", " ").trim();
        if (flat.isEmpty()) return null;
        if (flat.length() > TITLE_MAX_LEN) {
            return flat.substring(0, TITLE_MAX_LEN - 1) + "\u2026";
        }
        return flat;
    }

    /**
     * Generate a random short word slug (e.g. "graceful-unicorn").
     * Simplified — in the full implementation this would use a word list.
     */
    private static String generateShortWordSlug() {
        String[] adjectives = {"graceful", "swift", "bright", "calm", "bold"};
        String[] nouns = {"unicorn", "falcon", "cedar", "harbor", "beacon"};
        int a = (int) (Math.random() * adjectives.length);
        int n = (int) (Math.random() * nouns.length);
        return adjectives[a] + "-" + nouns[n];
    }

    private static void notifyStateChange(
            InitBridgeOptions opts, BridgeState state, String detail) {
        if (opts.onStateChange != null) {
            opts.onStateChange.accept(state, detail);
        }
    }
}
