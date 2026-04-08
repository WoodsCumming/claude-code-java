package com.anthropic.claudecode.util;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Session ID tag translation helpers for the CCR v2 compat layer.
 *
 * Lives in its own file (rather than workSecret.ts) so that sessionHandle.ts
 * and replBridgeTransport.ts can import from workSecret.ts without pulling
 * in these retag functions.
 *
 * The isCseShimEnabled kill switch is injected via {@link #setCseShimGate(BooleanSupplier)}
 * to avoid a static import of bridgeEnabled → growthbook → config — all
 * banned from the sdk bundle. Callers that already import bridgeEnabled
 * register the gate; the SDK path never does, so the shim defaults to active
 * (matching isCseShimEnabled()'s own default).
 *
 * Translated from src/bridge/sessionIdCompat.ts
 */
public final class BridgeSessionIdCompat {

    private BridgeSessionIdCompat() {}

    /**
     * Injected GrowthBook gate for the cse_ shim.
     * null = not registered; defaults to "shim active" (true).
     */
    private static final AtomicReference<BooleanSupplier> cseShimGate =
            new AtomicReference<>(null);

    // ─── Gate registration ────────────────────────────────────────────────────

    /**
     * Register the GrowthBook gate for the cse_ shim. Called from bridge
     * init code that already has access to the feature-flag layer.
     *
     * Translated from setCseShimGate() in sessionIdCompat.ts
     *
     * @param gate returns true when the cse_ shim is enabled
     */
    public static void setCseShimGate(BooleanSupplier gate) {
        cseShimGate.set(gate);
    }

    // ─── Tag conversion ───────────────────────────────────────────────────────

    /**
     * Re-tag a {@code cse_*} session ID to {@code session_*} for use with the
     * v1 compat API.
     *
     * Worker endpoints ({@code /v1/code/sessions/{id}/worker/*}) want
     * {@code cse_*}; that's what the work poll delivers. Client-facing compat
     * endpoints ({@code /v1/sessions/{id}}, archive, events) want
     * {@code session_*} — compat/convert.go:27 validates TagSession. Same
     * UUID, different costume. No-op for IDs that are not {@code cse_*}.
     *
     * Translated from toCompatSessionId() in sessionIdCompat.ts
     *
     * @param id the session ID, possibly prefixed with {@code cse_}
     * @return the re-tagged session ID, or the original if no re-tag needed
     */
    public static String toCompatSessionId(String id) {
        if (id == null || !id.startsWith("cse_")) return id;
        BooleanSupplier gate = cseShimGate.get();
        // Gate not registered → shim defaults to active
        if (gate != null && !gate.getAsBoolean()) return id;
        return "session_" + id.substring("cse_".length());
    }

    /**
     * Re-tag a {@code session_*} session ID to {@code cse_*} for
     * infrastructure-layer calls.
     *
     * Inverse of {@link #toCompatSessionId}. POST
     * {@code /v1/environments/{id}/bridge/reconnect} lives below the compat
     * layer: once {@code ccr_v2_compat_enabled} is on server-side, it looks
     * sessions up by their infra tag ({@code cse_*}). createBridgeSession
     * still returns {@code session_*} (compat/convert.go:41) and that's what
     * bridge-pointer stores — so perpetual reconnect passes the wrong costume
     * and gets "Session not found" back. No-op for IDs that are not
     * {@code session_*}.
     *
     * Translated from toInfraSessionId() in sessionIdCompat.ts
     *
     * @param id the session ID, possibly prefixed with {@code session_}
     * @return the infra-tagged session ID, or the original if no re-tag needed
     */
    public static String toInfraSessionId(String id) {
        if (id == null || !id.startsWith("session_")) return id;
        return "cse_" + id.substring("session_".length());
    }
}
