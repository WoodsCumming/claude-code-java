package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.BridgePollConfigDefaults;
import com.anthropic.claudecode.util.BridgePollConfigDefaults.PollIntervalConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Fetches and validates the bridge poll interval configuration from GrowthBook.
 *
 * Validates the served JSON against schema constraints; falls back to
 * {@link BridgePollConfigDefaults#DEFAULT_POLL_CONFIG} if the flag is absent,
 * malformed, or partially-specified.
 *
 * Shared by bridgeMain (standalone) and replBridge (REPL) so ops can tune
 * both poll rates fleet-wide with a single config push.
 *
 * Translated from src/bridge/pollConfig.ts
 */
@Slf4j
@Service
public class BridgePollConfigService {



    // ─── Validation constraints ───────────────────────────────────────────────

    /**
     * Minimum value for seek-work poll intervals (defense against fat-fingered
     * GrowthBook values that would poll every 10ms).
     */
    private static final int MIN_SEEK_WORK_INTERVAL_MS = 100;

    /**
     * At-capacity intervals: 0 means "disabled" (heartbeat-only mode), otherwise
     * must be >= 100ms. Values 1–99 are rejected to prevent unit confusion.
     */
    private static final int MIN_AT_CAPACITY_NONZERO_MS = 100;

    // ─── Config fetch ─────────────────────────────────────────────────────────

    /**
     * Returns the poll interval config from GrowthBook with a 5-minute refresh.
     *
     * In the full Spring implementation this would read from a GrowthBook-backed
     * feature flag cache. Here we expose the hook so the application context
     * can inject the raw feature-flag value.
     *
     * Translated from getPollIntervalConfig() in pollConfig.ts
     *
     * @param rawFlagValue the raw GrowthBook flag value (may be null or a Map)
     * @return validated PollIntervalConfig, or the default on parse failure
     */
    public PollIntervalConfig getPollIntervalConfig(Object rawFlagValue) {
        if (rawFlagValue == null) {
            return BridgePollConfigDefaults.DEFAULT_POLL_CONFIG;
        }
        if (!(rawFlagValue instanceof Map<?, ?> rawMap)) {
            return BridgePollConfigDefaults.DEFAULT_POLL_CONFIG;
        }
        try {
            return parseAndValidate(rawMap);
        } catch (Exception e) {
            log.debug("[bridge:poll-config] Falling back to defaults: {}", e.getMessage());
            return BridgePollConfigDefaults.DEFAULT_POLL_CONFIG;
        }
    }

    /**
     * Convenience overload that always returns the default config.
     * Use when there is no live GrowthBook integration.
     */
    public PollIntervalConfig getPollIntervalConfig() {
        return BridgePollConfigDefaults.DEFAULT_POLL_CONFIG;
    }

    // ─── Parsing + validation ─────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private PollIntervalConfig parseAndValidate(Map<?, ?> raw) {
        Map<String, Object> m = (Map<String, Object>) raw;

        int notAtCap = requireMinInt(m, "poll_interval_ms_not_at_capacity",
                MIN_SEEK_WORK_INTERVAL_MS);

        int atCap = requireZeroOrAtLeast(m, "poll_interval_ms_at_capacity",
                MIN_AT_CAPACITY_NONZERO_MS);

        int heartbeat = optionalInt(m, "non_exclusive_heartbeat_interval_ms",
                BridgePollConfigDefaults.DEFAULT_POLL_CONFIG.nonExclusiveHeartbeatIntervalMs(), 0);

        int msNotAtCap = optionalMinInt(m, "multisession_poll_interval_ms_not_at_capacity",
                BridgePollConfigDefaults.DEFAULT_POLL_CONFIG.multisessionPollIntervalMsNotAtCapacity(),
                MIN_SEEK_WORK_INTERVAL_MS);

        int msPartialCap = optionalMinInt(m, "multisession_poll_interval_ms_partial_capacity",
                BridgePollConfigDefaults.DEFAULT_POLL_CONFIG.multisessionPollIntervalMsPartialCapacity(),
                MIN_SEEK_WORK_INTERVAL_MS);

        int msAtCap = optionalZeroOrAtLeast(m, "multisession_poll_interval_ms_at_capacity",
                BridgePollConfigDefaults.DEFAULT_POLL_CONFIG.multisessionPollIntervalMsAtCapacity(),
                MIN_AT_CAPACITY_NONZERO_MS);

        int reclaim = optionalMinInt(m, "reclaim_older_than_ms", 5_000, 1);

        int keepalive = optionalInt(m, "session_keepalive_interval_v2_ms", 120_000, 0);

        // Object-level refinement: at-capacity liveness requires heartbeat OR at-capacity polling
        if (heartbeat <= 0 && atCap <= 0) {
            throw new IllegalArgumentException(
                "at-capacity liveness requires non_exclusive_heartbeat_interval_ms > 0 " +
                "or poll_interval_ms_at_capacity > 0");
        }
        if (heartbeat <= 0 && msAtCap <= 0) {
            throw new IllegalArgumentException(
                "at-capacity liveness requires non_exclusive_heartbeat_interval_ms > 0 " +
                "or multisession_poll_interval_ms_at_capacity > 0");
        }

        return new PollIntervalConfig(
            notAtCap, atCap, heartbeat,
            msNotAtCap, msPartialCap, msAtCap,
            reclaim, keepalive
        );
    }

    // ─── Field extraction helpers ─────────────────────────────────────────────

    private static int requireMinInt(Map<String, Object> m, String key, int min) {
        Object val = m.get(key);
        if (!(val instanceof Number n)) {
            throw new IllegalArgumentException("Missing required integer field: " + key);
        }
        int v = n.intValue();
        if (v < min) {
            throw new IllegalArgumentException(
                key + " must be >= " + min + " but was " + v);
        }
        return v;
    }

    private static int requireZeroOrAtLeast(Map<String, Object> m, String key, int minNonZero) {
        Object val = m.get(key);
        if (!(val instanceof Number n)) {
            throw new IllegalArgumentException("Missing required integer field: " + key);
        }
        int v = n.intValue();
        if (v != 0 && v < minNonZero) {
            throw new IllegalArgumentException(
                key + " must be 0 (disabled) or >= " + minNonZero + " but was " + v);
        }
        return v;
    }

    private static int optionalInt(Map<String, Object> m, String key, int defaultVal, int min) {
        Object val = m.get(key);
        if (!(val instanceof Number n)) return defaultVal;
        int v = n.intValue();
        if (v < min) {
            throw new IllegalArgumentException(key + " must be >= " + min + " but was " + v);
        }
        return v;
    }

    private static int optionalMinInt(Map<String, Object> m, String key, int defaultVal, int min) {
        Object val = m.get(key);
        if (!(val instanceof Number n)) return defaultVal;
        int v = n.intValue();
        if (v < min) {
            throw new IllegalArgumentException(key + " must be >= " + min + " but was " + v);
        }
        return v;
    }

    private static int optionalZeroOrAtLeast(Map<String, Object> m, String key,
            int defaultVal, int minNonZero) {
        Object val = m.get(key);
        if (!(val instanceof Number n)) return defaultVal;
        int v = n.intValue();
        if (v != 0 && v < minNonZero) {
            throw new IllegalArgumentException(
                key + " must be 0 (disabled) or >= " + minNonZero + " but was " + v);
        }
        return v;
    }
}
