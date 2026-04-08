package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the global pointer to the active REPL bridge handle.
 *
 * Provides a process-wide singleton so callers outside the React/hook tree
 * (tools, slash commands) can invoke handle methods like subscribePR. The
 * same one-bridge-per-process justification as bridgeDebug.ts — the handle's
 * closure captures the sessionId and getAccessToken that created the session.
 *
 * Set when bridge init completes; cleared on teardown.
 *
 * Translated from src/bridge/replBridgeHandle.ts
 */
@Slf4j
@Service
public class BridgeReplHandleService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BridgeReplHandleService.class);


    /**
     * The bridge session ID in the {@code session_*} compat format returned
     * by the /v1/sessions API, or null if the bridge is not connected.
     */
    public record BridgeCompatInfo(
        String bridgeSessionId,
        String compatSessionId
    ) {}

    // ─── Global handle state ──────────────────────────────────────────────────

    /**
     * Thread-safe holder for the active bridge handle.
     * Translated from the module-level {@code handle} variable in replBridgeHandle.ts
     */
    private final AtomicReference<BridgeReplService.ReplBridgeHandle> handle =
            new AtomicReference<>(null);

    /**
     * Callback called whenever the handle changes (set or cleared). Used to
     * publish/clear the bridge session ID in the session record so local peers
     * can dedup this process from their bridge list.
     * Mirrors the updateSessionBridgeId call in setReplBridgeHandle().
     */
    private java.util.function.Consumer<String> onBridgeIdChange;

    @Autowired
    public BridgeReplHandleService() {}

    // ─── Handle accessors ─────────────────────────────────────────────────────

    /**
     * Set (or clear) the active REPL bridge handle. Publishes the compat session
     * ID to the session record so other local peers can dedup this process.
     *
     * Translated from setReplBridgeHandle() in replBridgeHandle.ts
     *
     * @param h the new handle, or null to clear
     */
    public void setReplBridgeHandle(BridgeReplService.ReplBridgeHandle h) {
        handle.set(h);
        // Publish (or clear) our bridge session ID in the session record
        String compatId = h != null ? toCompatSessionId(h.getBridgeSessionId()) : null;
        if (onBridgeIdChange != null) {
            onBridgeIdChange.accept(compatId);
        }
        log.debug("[bridge:handle] handle {}, compatId={}",
                h != null ? "set" : "cleared", compatId);
    }

    /**
     * Register a callback to be invoked whenever the bridge session ID changes.
     * Used by the concurrent-session tracker to publish/clear the bridge session ID.
     */
    public void setOnBridgeIdChange(java.util.function.Consumer<String> callback) {
        this.onBridgeIdChange = callback;
    }

    /**
     * Returns the current active bridge handle, or null if not connected.
     * Translated from getReplBridgeHandle() in replBridgeHandle.ts
     */
    public BridgeReplService.ReplBridgeHandle getReplBridgeHandle() {
        return handle.get();
    }

    // ─── Session ID helpers ───────────────────────────────────────────────────

    /**
     * Our own bridge session ID in the {@code session_*} compat format the API
     * returns in /v1/sessions responses — or null if bridge is not connected.
     *
     * Translated from getSelfBridgeCompatId() in replBridgeHandle.ts
     */
    public String getSelfBridgeCompatId() {
        BridgeReplService.ReplBridgeHandle h = getReplBridgeHandle();
        return h != null ? toCompatSessionId(h.getBridgeSessionId()) : null;
    }

    /**
     * Returns compat info (raw + compat session IDs), or null if not connected.
     */
    public BridgeCompatInfo getSelfBridgeCompatInfo() {
        BridgeReplService.ReplBridgeHandle h = getReplBridgeHandle();
        if (h == null) return null;
        String bridgeId = h.getBridgeSessionId();
        return new BridgeCompatInfo(bridgeId, toCompatSessionId(bridgeId));
    }

    // ─── Tear down ────────────────────────────────────────────────────────────

    /**
     * Tear down the active handle (if any) and clear the reference.
     * Fire-and-forget — the caller should not await the returned future unless
     * they specifically need teardown completion.
     */
    public CompletableFuture<Void> teardownAndClear() {
        BridgeReplService.ReplBridgeHandle h = handle.getAndSet(null);
        if (h == null) return CompletableFuture.completedFuture(null);
        // Notify that the bridge session ID is now gone
        if (onBridgeIdChange != null) {
            onBridgeIdChange.accept(null);
        }
        return h.teardown();
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

    /**
     * Re-tag a {@code cse_*} session ID to {@code session_*}.
     * Mirrors toCompatSessionId() from BridgeSessionIdCompat.
     * Inline here to avoid a circular service dependency.
     */
    private static String toCompatSessionId(String id) {
        if (id == null || !id.startsWith("cse_")) return id;
        return "session_" + id.substring("cse_".length());
    }
}
