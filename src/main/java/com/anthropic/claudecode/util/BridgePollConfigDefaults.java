package com.anthropic.claudecode.util;

/**
 * Bridge poll interval defaults.
 *
 * Extracted so callers that don't need live GrowthBook tuning (daemon via
 * Agent SDK) can avoid the growthbook transitive dependency chain.
 *
 * Translated from src/bridge/pollConfigDefaults.ts
 */
public final class BridgePollConfigDefaults {

    private BridgePollConfigDefaults() {}

    // ─── Base constants ───────────────────────────────────────────────────────

    /**
     * Poll interval when actively seeking work (no transport / below maxSessions).
     * Governs user-visible "connecting…" latency on initial work pickup and
     * recovery speed after the server re-dispatches a work item.
     */
    public static final int POLL_INTERVAL_MS_NOT_AT_CAPACITY = 2_000;

    /**
     * Poll interval when the transport is connected. Runs independently of
     * heartbeat — when both are enabled, the heartbeat loop breaks out to poll
     * at this interval. Set to 0 to disable at-capacity polling entirely.
     *
     * Server-side constraints:
     * - BRIDGE_LAST_POLL_TTL = 4h (Redis key expiry → environment auto-archived)
     * - max_poll_stale_seconds = 24h (currently disabled)
     *
     * 10 minutes gives 24× headroom on the Redis TTL.
     */
    public static final int POLL_INTERVAL_MS_AT_CAPACITY = 600_000;

    /**
     * Multisession bridge poll intervals. Defaults match single-session values.
     */
    public static final int MULTISESSION_POLL_INTERVAL_MS_NOT_AT_CAPACITY =
            POLL_INTERVAL_MS_NOT_AT_CAPACITY;
    public static final int MULTISESSION_POLL_INTERVAL_MS_PARTIAL_CAPACITY =
            POLL_INTERVAL_MS_NOT_AT_CAPACITY;
    public static final int MULTISESSION_POLL_INTERVAL_MS_AT_CAPACITY =
            POLL_INTERVAL_MS_AT_CAPACITY;

    // ─── Config record ────────────────────────────────────────────────────────

    /**
     * Bridge poll interval configuration.
     * Translated from PollIntervalConfig in pollConfigDefaults.ts
     */
    public record PollIntervalConfig(
        /** Poll interval while seeking work (below maxSessions). */
        int pollIntervalMsNotAtCapacity,
        /** Poll interval while transport is connected (0 = disabled). */
        int pollIntervalMsAtCapacity,
        /**
         * Heartbeat interval while at capacity (0 = disabled). Runs alongside
         * at-capacity polling when both are enabled. Named non-exclusive to
         * distinguish from the old either-or heartbeat_interval_ms.
         */
        int nonExclusiveHeartbeatIntervalMs,
        /** Multisession — below-capacity poll interval. */
        int multisessionPollIntervalMsNotAtCapacity,
        /** Multisession — partial-capacity poll interval. */
        int multisessionPollIntervalMsPartialCapacity,
        /** Multisession — at-capacity poll interval (0 = disabled). */
        int multisessionPollIntervalMsAtCapacity,
        /**
         * Poll query param: reclaim unacknowledged work items older than this (ms).
         * Matches the server's DEFAULT_RECLAIM_OLDER_THAN_MS.
         */
        int reclaimOlderThanMs,
        /**
         * Keep-alive interval for session-ingress proxies (ms). 0 = disabled.
         * 2 min default. _v2: bridge-only gate (pre-v2 clients read old key).
         */
        int sessionKeepaliveIntervalV2Ms
    ) {}

    // ─── Default config ───────────────────────────────────────────────────────

    /**
     * Default poll interval configuration used when GrowthBook is unavailable
     * or the flag value is absent/malformed.
     * Translated from DEFAULT_POLL_CONFIG in pollConfigDefaults.ts
     */
    public static final PollIntervalConfig DEFAULT_POLL_CONFIG = new PollIntervalConfig(
        /* pollIntervalMsNotAtCapacity            */ POLL_INTERVAL_MS_NOT_AT_CAPACITY,
        /* pollIntervalMsAtCapacity               */ POLL_INTERVAL_MS_AT_CAPACITY,
        /* nonExclusiveHeartbeatIntervalMs        */ 0,
        /* multisessionPollIntervalMsNotAtCapacity*/ MULTISESSION_POLL_INTERVAL_MS_NOT_AT_CAPACITY,
        /* multisessionPollIntervalMsPartialCapacity */ MULTISESSION_POLL_INTERVAL_MS_PARTIAL_CAPACITY,
        /* multisessionPollIntervalMsAtCapacity   */ MULTISESSION_POLL_INTERVAL_MS_AT_CAPACITY,
        /* reclaimOlderThanMs                     */ 5_000,
        /* sessionKeepaliveIntervalV2Ms           */ 120_000
    );
}
