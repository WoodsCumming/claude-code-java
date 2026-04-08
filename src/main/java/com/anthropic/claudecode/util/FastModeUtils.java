package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Fast mode utilities and runtime state management.
 * Translated from src/utils/fastMode.ts
 *
 * Fast mode enables faster output generation using Claude Opus 4.6.
 * It tracks org-level enable/disable status as well as per-session
 * runtime state (active, cooldown).
 */
@Slf4j
public class FastModeUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FastModeUtils.class);


    /** Display name for the fast-mode model. Translated from FAST_MODE_MODEL_DISPLAY. */
    public static final String FAST_MODE_MODEL_DISPLAY = "Opus 4.6";

    // -------------------------------------------------------------------------
    // Sealed-interface equivalents for discriminated union types
    // -------------------------------------------------------------------------

    /**
     * Reason the org has disabled fast mode.
     * Translated from FastModeDisabledReason in fastMode.ts
     */
    public enum FastModeDisabledReason {
        FREE,
        PREFERENCE,
        EXTRA_USAGE_DISABLED,
        NETWORK_ERROR,
        UNKNOWN
    }

    /**
     * Why a fast-mode cooldown was triggered.
     * Translated from CooldownReason in fastMode.ts
     */
    public enum CooldownReason {
        RATE_LIMIT,
        OVERLOADED
    }

    /**
     * Runtime state of fast mode within a session.
     * Translated from FastModeRuntimeState in fastMode.ts
     */
    public sealed interface FastModeRuntimeState
            permits FastModeRuntimeState.Active, FastModeRuntimeState.Cooldown {

        record Active() implements FastModeRuntimeState {}

        record Cooldown(long resetAtEpochMilli, CooldownReason reason)
                implements FastModeRuntimeState {}
    }

    /**
     * Org-level fast mode availability.
     * Translated from FastModeOrgStatus in fastMode.ts
     */
    public sealed interface FastModeOrgStatus
            permits FastModeOrgStatus.Pending, FastModeOrgStatus.Enabled,
                    FastModeOrgStatus.Disabled {

        record Pending() implements FastModeOrgStatus {}
        record Enabled() implements FastModeOrgStatus {}
        record Disabled(FastModeDisabledReason reason) implements FastModeOrgStatus {}
    }

    /**
     * Overall fast-mode state visible to callers.
     * Translated from the return type of getFastModeState() in fastMode.ts
     */
    public enum FastModeState {
        OFF, COOLDOWN, ON
    }

    // -------------------------------------------------------------------------
    // Module-level mutable state  (mirrors TS module-level variables)
    // -------------------------------------------------------------------------

    private static volatile FastModeRuntimeState runtimeState =
            new FastModeRuntimeState.Active();
    private static volatile boolean hasLoggedCooldownExpiry = false;

    private static volatile FastModeOrgStatus orgStatus =
            new FastModeOrgStatus.Pending();

    // Cooldown listeners
    private static volatile Consumer<Object[]> cooldownTriggeredListener = null;
    private static volatile Runnable cooldownExpiredListener = null;
    // Org change listener
    private static volatile Consumer<Boolean> orgFastModeChangeListener = null;
    // Overage rejection listener
    private static volatile Consumer<String> overageRejectionListener = null;

    // -------------------------------------------------------------------------
    // Basic enable/disable
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} when fast mode has not been explicitly disabled.
     * Translated from isFastModeEnabled() in fastMode.ts
     */
    public static boolean isFastModeEnabled() {
        return !EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_DISABLE_FAST_MODE"));
    }

    /**
     * Returns {@code true} when fast mode is enabled AND no unavailability reason exists.
     * Translated from isFastModeAvailable() in fastMode.ts
     */
    public static boolean isFastModeAvailable() {
        if (!isFastModeEnabled()) return false;
        return getFastModeUnavailableReason() == null;
    }

    /**
     * Returns a human-readable reason why fast mode is unavailable, or {@code null}
     * if it is available.
     * Translated from getFastModeUnavailableReason() in fastMode.ts
     */
    public static String getFastModeUnavailableReason() {
        if (!isFastModeEnabled()) {
            return "Fast mode is not available";
        }

        // Only available for first-party API (not Bedrock/Vertex/Foundry)
        String apiProvider = System.getenv("ANTHROPIC_API_PROVIDER");
        if (apiProvider != null && !apiProvider.isEmpty() && !"firstParty".equals(apiProvider)) {
            String reason = "Fast mode is not available on Bedrock, Vertex, or Foundry";
            log.debug("Fast mode unavailable: {}", reason);
            return reason;
        }

        FastModeOrgStatus status = orgStatus;
        if (status instanceof FastModeOrgStatus.Disabled disabled) {
            FastModeDisabledReason disabledReason = disabled.reason();
            if (disabledReason == FastModeDisabledReason.NETWORK_ERROR
                    || disabledReason == FastModeDisabledReason.UNKNOWN) {
                if (EnvUtils.isEnvTruthy(
                        System.getenv("CLAUDE_CODE_SKIP_FAST_MODE_NETWORK_ERRORS"))) {
                    return null;
                }
            }
            String reason = getDisabledReasonMessage(disabledReason);
            log.debug("Fast mode unavailable: {}", reason);
            return reason;
        }

        return null;
    }

    // -------------------------------------------------------------------------
    // Runtime state
    // -------------------------------------------------------------------------

    /**
     * Returns the current runtime state, transitioning out of cooldown when
     * the reset timestamp has passed.
     * Translated from getFastModeRuntimeState() in fastMode.ts
     */
    public static FastModeRuntimeState getFastModeRuntimeState() {
        if (runtimeState instanceof FastModeRuntimeState.Cooldown cooldown
                && Instant.now().toEpochMilli() >= cooldown.resetAtEpochMilli()) {
            if (isFastModeEnabled() && !hasLoggedCooldownExpiry) {
                log.debug("Fast mode cooldown expired, re-enabling fast mode");
                hasLoggedCooldownExpiry = true;
                if (cooldownExpiredListener != null) cooldownExpiredListener.run();
            }
            runtimeState = new FastModeRuntimeState.Active();
        }
        return runtimeState;
    }

    /**
     * Returns {@code true} when fast mode is currently in cooldown.
     * Translated from isFastModeCooldown() in fastMode.ts
     */
    public static boolean isFastModeCooldown() {
        return getFastModeRuntimeState() instanceof FastModeRuntimeState.Cooldown;
    }

    /**
     * Trigger a fast-mode cooldown until the given epoch-millisecond timestamp.
     * Translated from triggerFastModeCooldown() in fastMode.ts
     */
    public static void triggerFastModeCooldown(long resetTimestampMs, CooldownReason reason) {
        if (!isFastModeEnabled()) return;
        runtimeState = new FastModeRuntimeState.Cooldown(resetTimestampMs, reason);
        hasLoggedCooldownExpiry = false;
        long durationMs = resetTimestampMs - Instant.now().toEpochMilli();
        log.debug("Fast mode cooldown triggered ({}), duration {}s",
                reason, Math.round(durationMs / 1000.0));
        if (cooldownTriggeredListener != null) {
            cooldownTriggeredListener.accept(new Object[]{resetTimestampMs, reason});
        }
    }

    /**
     * Clear the current fast-mode cooldown, returning to active state.
     * Translated from clearFastModeCooldown() in fastMode.ts
     */
    public static void clearFastModeCooldown() {
        runtimeState = new FastModeRuntimeState.Active();
    }

    // -------------------------------------------------------------------------
    // Org-level status
    // -------------------------------------------------------------------------

    /**
     * Set org status directly (used when the API returns fast-mode information).
     * Translated from orgStatus updates throughout fastMode.ts
     */
    public static void setOrgStatus(FastModeOrgStatus status) {
        boolean wasEnabled = orgStatus instanceof FastModeOrgStatus.Enabled;
        boolean nowEnabled = status instanceof FastModeOrgStatus.Enabled;
        orgStatus = status;
        if (wasEnabled != nowEnabled && orgFastModeChangeListener != null) {
            orgFastModeChangeListener.accept(nowEnabled);
        }
    }

    /**
     * Resolve org status from the local cache (no network call).
     * Translated from resolveFastModeStatusFromCache() in fastMode.ts
     */
    public static void resolveFastModeStatusFromCache(boolean cachedEnabled) {
        if (!isFastModeEnabled()) return;
        if (!(orgStatus instanceof FastModeOrgStatus.Pending)) return;

        boolean isAnt = "ant".equals(System.getenv("USER_TYPE"));
        orgStatus = (isAnt || cachedEnabled)
                ? new FastModeOrgStatus.Enabled()
                : new FastModeOrgStatus.Disabled(FastModeDisabledReason.UNKNOWN);
    }

    /**
     * Handle a rejection by the API because the org does not have fast mode enabled.
     * Permanently disables fast mode in the current session.
     * Translated from handleFastModeRejectedByAPI() in fastMode.ts
     */
    public static void handleFastModeRejectedByAPI() {
        if (orgStatus instanceof FastModeOrgStatus.Disabled) return;
        orgStatus = new FastModeOrgStatus.Disabled(FastModeDisabledReason.PREFERENCE);
        if (orgFastModeChangeListener != null) orgFastModeChangeListener.accept(false);
    }

    /**
     * Handle a 429 overage rejection — permanently disables fast mode unless the
     * user is simply out of credits (temporary condition).
     * Translated from handleFastModeOverageRejection() in fastMode.ts
     */
    public static void handleFastModeOverageRejection(String reason) {
        String message = getOverageDisabledMessage(reason);
        log.debug("Fast mode overage rejection: {} — {}", reason != null ? reason : "unknown", message);
        if (!isOutOfCreditsReason(reason)) {
            orgStatus = new FastModeOrgStatus.Disabled(FastModeDisabledReason.PREFERENCE);
        }
        if (overageRejectionListener != null) overageRejectionListener.accept(message);
    }

    // -------------------------------------------------------------------------
    // Model support
    // -------------------------------------------------------------------------

    /**
     * Returns the fast-mode model string.
     * Translated from getFastModeModel() in fastMode.ts
     */
    public static String getFastModeModel() {
        return "opus";
    }

    /**
     * Returns {@code true} when the given model supports fast mode (Opus 4.6+).
     * Translated from isFastModeSupportedByModel() in fastMode.ts
     */
    public static boolean isFastModeSupportedByModel(String modelSetting) {
        if (!isFastModeEnabled()) return false;
        if (modelSetting == null || modelSetting.isEmpty()) return false;
        return modelSetting.toLowerCase().contains("opus-4-6");
    }

    /**
     * Combined check: returns the effective fast-mode state for display purposes.
     * Translated from getFastModeState() in fastMode.ts
     */
    public static FastModeState getFastModeState(String modelSetting, boolean fastModeUserEnabled) {
        boolean enabled = isFastModeEnabled()
                && isFastModeAvailable()
                && fastModeUserEnabled
                && isFastModeSupportedByModel(modelSetting);
        if (enabled && isFastModeCooldown()) return FastModeState.COOLDOWN;
        if (enabled) return FastModeState.ON;
        return FastModeState.OFF;
    }

    // -------------------------------------------------------------------------
    // Listener registration  (signal equivalents)
    // -------------------------------------------------------------------------

    public static void onCooldownTriggered(Consumer<Object[]> listener) {
        cooldownTriggeredListener = listener;
    }

    public static void onCooldownExpired(Runnable listener) {
        cooldownExpiredListener = listener;
    }

    public static void onOrgFastModeChanged(Consumer<Boolean> listener) {
        orgFastModeChangeListener = listener;
    }

    public static void onFastModeOverageRejection(Consumer<String> listener) {
        overageRejectionListener = listener;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static String getDisabledReasonMessage(FastModeDisabledReason reason) {
        return switch (reason) {
            case FREE -> "Fast mode requires a paid subscription";
            case PREFERENCE -> "Fast mode has been disabled by your organization";
            case EXTRA_USAGE_DISABLED ->
                    "Fast mode requires extra usage billing · /extra-usage to enable";
            case NETWORK_ERROR ->
                    "Fast mode unavailable due to network connectivity issues";
            case UNKNOWN -> "Fast mode is currently unavailable";
        };
    }

    private static String getOverageDisabledMessage(String reason) {
        if (reason == null) return "Fast mode disabled · extra usage not available";
        return switch (reason) {
            case "out_of_credits" ->
                    "Fast mode disabled · extra usage credits exhausted";
            case "org_level_disabled", "org_service_level_disabled" ->
                    "Fast mode disabled · extra usage disabled by your organization";
            case "org_level_disabled_until" ->
                    "Fast mode disabled · extra usage spending cap reached";
            case "member_level_disabled" ->
                    "Fast mode disabled · extra usage disabled for your account";
            case "seat_tier_level_disabled", "seat_tier_zero_credit_limit",
                 "member_zero_credit_limit" ->
                    "Fast mode disabled · extra usage not available for your plan";
            case "overage_not_provisioned", "no_limits_configured" ->
                    "Fast mode requires extra usage billing · /extra-usage to enable";
            default -> "Fast mode disabled · extra usage not available";
        };
    }

    private static boolean isOutOfCreditsReason(String reason) {
        return "org_level_disabled_until".equals(reason) || "out_of_credits".equals(reason);
    }

    private FastModeUtils() {}
}
