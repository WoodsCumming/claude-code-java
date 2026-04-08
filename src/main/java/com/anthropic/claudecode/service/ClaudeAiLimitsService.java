package com.anthropic.claudecode.service;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * Claude.ai rate limits service.
 * Translated from src/services/claudeAiLimits.ts
 *
 * Manages rate limit state, early warning thresholds, and header extraction.
 */
@Slf4j
@Service
public class ClaudeAiLimitsService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ClaudeAiLimitsService.class);


    // -------------------------------------------------------------------------
    // Enums — mirrors TypeScript string union types
    // -------------------------------------------------------------------------

    /** Mirrors QuotaStatus union type in claudeAiLimits.ts */
    public enum QuotaStatus {
        ALLOWED("allowed"),
        ALLOWED_WARNING("allowed_warning"),
        REJECTED("rejected");

        @JsonValue
        private final String value;

        QuotaStatus(String value) { this.value = value; }

        public static QuotaStatus fromString(String s) {
            if (s == null) return ALLOWED;
            return switch (s) {
                case "rejected"        -> REJECTED;
                case "allowed_warning" -> ALLOWED_WARNING;
                default                -> ALLOWED;
            };
        }
    }

    /** Mirrors RateLimitType union type in claudeAiLimits.ts */
    public enum RateLimitType {
        FIVE_HOUR("five_hour"),
        SEVEN_DAY("seven_day"),
        SEVEN_DAY_OPUS("seven_day_opus"),
        SEVEN_DAY_SONNET("seven_day_sonnet"),
        OVERAGE("overage");

        @JsonValue
        private final String value;

        RateLimitType(String value) { this.value = value; }
        public String getValue() { return value; }

        public static RateLimitType fromString(String s) {
            if (s == null) return null;
            return switch (s) {
                case "five_hour"       -> FIVE_HOUR;
                case "seven_day"       -> SEVEN_DAY;
                case "seven_day_opus"  -> SEVEN_DAY_OPUS;
                case "seven_day_sonnet"-> SEVEN_DAY_SONNET;
                case "overage"         -> OVERAGE;
                default                -> null;
            };
        }

        public String claimAbbrev() {
            return switch (this) {
                case FIVE_HOUR -> "5h";
                case SEVEN_DAY -> "7d";
                case OVERAGE   -> "overage";
                default        -> null;
            };
        }
    }

    /** Mirrors OverageDisabledReason union type in claudeAiLimits.ts */
    public enum OverageDisabledReason {
        OVERAGE_NOT_PROVISIONED("overage_not_provisioned"),
        ORG_LEVEL_DISABLED("org_level_disabled"),
        ORG_LEVEL_DISABLED_UNTIL("org_level_disabled_until"),
        OUT_OF_CREDITS("out_of_credits"),
        SEAT_TIER_LEVEL_DISABLED("seat_tier_level_disabled"),
        MEMBER_LEVEL_DISABLED("member_level_disabled"),
        SEAT_TIER_ZERO_CREDIT_LIMIT("seat_tier_zero_credit_limit"),
        GROUP_ZERO_CREDIT_LIMIT("group_zero_credit_limit"),
        MEMBER_ZERO_CREDIT_LIMIT("member_zero_credit_limit"),
        ORG_SERVICE_LEVEL_DISABLED("org_service_level_disabled"),
        ORG_SERVICE_ZERO_CREDIT_LIMIT("org_service_zero_credit_limit"),
        NO_LIMITS_CONFIGURED("no_limits_configured"),
        UNKNOWN("unknown");

        @JsonValue
        private final String value;

        OverageDisabledReason(String value) { this.value = value; }

        public static OverageDisabledReason fromString(String s) {
            if (s == null) return null;
            for (OverageDisabledReason r : values()) {
                if (r.value.equals(s)) return r;
            }
            return UNKNOWN;
        }
    }

    // -------------------------------------------------------------------------
    // ClaudeAILimits — mirrors ClaudeAILimits type in claudeAiLimits.ts
    // -------------------------------------------------------------------------

    public static class ClaudeAILimits {
        private QuotaStatus status = QuotaStatus.ALLOWED;
        private boolean unifiedRateLimitFallbackAvailable = false;
        private Long resetsAt;
        private RateLimitType rateLimitType;
        private Double utilization;
        private QuotaStatus overageStatus;
        private Long overageResetsAt;
        private OverageDisabledReason overageDisabledReason;
        private Boolean isUsingOverage = false;
        private Double surpassedThreshold;

        public ClaudeAILimits() {}

        // Getters & setters
        public QuotaStatus getStatus() { return status; }
        public void setStatus(QuotaStatus s) { this.status = s; }
        public boolean isUnifiedRateLimitFallbackAvailable() { return unifiedRateLimitFallbackAvailable; }
        public void setUnifiedRateLimitFallbackAvailable(boolean v) { this.unifiedRateLimitFallbackAvailable = v; }
        public Long getResetsAt() { return resetsAt; }
        public void setResetsAt(Long v) { this.resetsAt = v; }
        public RateLimitType getRateLimitType() { return rateLimitType; }
        public void setRateLimitType(RateLimitType v) { this.rateLimitType = v; }
        public Double getUtilization() { return utilization; }
        public void setUtilization(Double v) { this.utilization = v; }
        public QuotaStatus getOverageStatus() { return overageStatus; }
        public void setOverageStatus(QuotaStatus v) { this.overageStatus = v; }
        public Long getOverageResetsAt() { return overageResetsAt; }
        public void setOverageResetsAt(Long v) { this.overageResetsAt = v; }
        public OverageDisabledReason getOverageDisabledReason() { return overageDisabledReason; }
        public void setOverageDisabledReason(OverageDisabledReason v) { this.overageDisabledReason = v; }
        public Boolean getIsUsingOverage() { return isUsingOverage; }
        public void setIsUsingOverage(Boolean v) { this.isUsingOverage = v; }
        public Double getSurpassedThreshold() { return surpassedThreshold; }
        public void setSurpassedThreshold(Double v) { this.surpassedThreshold = v; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ClaudeAILimits that)) return false;
            return unifiedRateLimitFallbackAvailable == that.unifiedRateLimitFallbackAvailable
                    && Objects.equals(status, that.status)
                    && Objects.equals(resetsAt, that.resetsAt)
                    && Objects.equals(rateLimitType, that.rateLimitType)
                    && Objects.equals(utilization, that.utilization)
                    && Objects.equals(overageStatus, that.overageStatus)
                    && Objects.equals(overageResetsAt, that.overageResetsAt)
                    && Objects.equals(overageDisabledReason, that.overageDisabledReason)
                    && Objects.equals(isUsingOverage, that.isUsingOverage)
                    && Objects.equals(surpassedThreshold, that.surpassedThreshold);
        }

        @Override
        public int hashCode() {
            return Objects.hash(status, unifiedRateLimitFallbackAvailable, resetsAt,
                    rateLimitType, utilization, overageStatus, overageResetsAt,
                    overageDisabledReason, isUsingOverage, surpassedThreshold);
        }
    }

    // -------------------------------------------------------------------------
    // RawWindowUtilization — mirrors RawWindowUtilization / RawUtilization in ts
    // -------------------------------------------------------------------------

    public record RawWindowUtilization(double utilization, long resetsAt) {}

    public static class RawUtilization {
        private RawWindowUtilization fiveHour;
        private RawWindowUtilization sevenDay;

        public RawWindowUtilization getFiveHour() { return fiveHour; }
        public void setFiveHour(RawWindowUtilization v) { this.fiveHour = v; }
        public RawWindowUtilization getSevenDay() { return sevenDay; }
        public void setSevenDay(RawWindowUtilization v) { this.sevenDay = v; }
    }

    // -------------------------------------------------------------------------
    // EarlyWarning config — mirrors EARLY_WARNING_CONFIGS in claudeAiLimits.ts
    // -------------------------------------------------------------------------

    private record EarlyWarningThreshold(double utilization, double timePct) {}
    private record EarlyWarningConfig(RateLimitType rateLimitType, String claimAbbrev,
                                       long windowSeconds, List<EarlyWarningThreshold> thresholds) {}

    private static final List<EarlyWarningConfig> EARLY_WARNING_CONFIGS = List.of(
            new EarlyWarningConfig(
                    RateLimitType.FIVE_HOUR, "5h", 5L * 60 * 60,
                    List.of(new EarlyWarningThreshold(0.9, 0.72))),
            new EarlyWarningConfig(
                    RateLimitType.SEVEN_DAY, "7d", 7L * 24 * 60 * 60,
                    List.of(
                            new EarlyWarningThreshold(0.75, 0.60),
                            new EarlyWarningThreshold(0.50, 0.35),
                            new EarlyWarningThreshold(0.25, 0.15)))
    );

    private static final Map<String, RateLimitType> EARLY_WARNING_CLAIM_MAP = Map.of(
            "5h",      RateLimitType.FIVE_HOUR,
            "7d",      RateLimitType.SEVEN_DAY,
            "overage", RateLimitType.OVERAGE
    );

    private static final Map<RateLimitType, String> RATE_LIMIT_DISPLAY_NAMES = Map.of(
            RateLimitType.FIVE_HOUR,       "session limit",
            RateLimitType.SEVEN_DAY,       "weekly limit",
            RateLimitType.SEVEN_DAY_OPUS,  "Opus limit",
            RateLimitType.SEVEN_DAY_SONNET,"Sonnet limit",
            RateLimitType.OVERAGE,         "extra usage limit"
    );

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private volatile ClaudeAILimits currentLimits;
    private volatile RawUtilization rawUtilization = new RawUtilization();
    private final Set<Consumer<ClaudeAILimits>> statusListeners = new CopyOnWriteArraySet<>();

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final GlobalConfigService globalConfigService;

    @Autowired
    public ClaudeAiLimitsService(GlobalConfigService globalConfigService) {
        this.globalConfigService = globalConfigService;
        ClaudeAILimits initial = new ClaudeAILimits();
        initial.setStatus(QuotaStatus.ALLOWED);
        initial.setUnifiedRateLimitFallbackAvailable(false);
        initial.setIsUsingOverage(false);
        this.currentLimits = initial;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public ClaudeAILimits getCurrentLimits() { return currentLimits; }
    public RawUtilization getRawUtilization()  { return rawUtilization; }

    /**
     * Get rate limit display name.
     * Translated from getRateLimitDisplayName() in claudeAiLimits.ts
     */
    public String getRateLimitDisplayName(RateLimitType type) {
        return RATE_LIMIT_DISPLAY_NAMES.getOrDefault(type,
                type != null ? type.getValue() : "unknown");
    }

    /**
     * Broadcast a limit change to all registered listeners.
     * Translated from emitStatusChange() in claudeAiLimits.ts
     */
    public void emitStatusChange(ClaudeAILimits limits) {
        currentLimits = limits;
        for (Consumer<ClaudeAILimits> listener : statusListeners) {
            try { listener.accept(limits); }
            catch (Exception e) { log.warn("statusListener failed: {}", e.getMessage()); }
        }
    }

    /** Subscribe to limit changes. Returns an unsubscribe runnable. */
    public Runnable subscribe(Consumer<ClaudeAILimits> listener) {
        statusListeners.add(listener);
        return () -> statusListeners.remove(listener);
    }

    /**
     * Extract quota status from response headers and update currentLimits.
     * Translated from extractQuotaStatusFromHeaders() in claudeAiLimits.ts
     */
    public void extractQuotaStatusFromHeaders(Map<String, String> headers) {
        if (!shouldProcessRateLimits()) {
            rawUtilization = new RawUtilization();
            if (currentLimits.getStatus() != QuotaStatus.ALLOWED
                    || currentLimits.getResetsAt() != null) {
                ClaudeAILimits defaults = new ClaudeAILimits();
                defaults.setStatus(QuotaStatus.ALLOWED);
                defaults.setUnifiedRateLimitFallbackAvailable(false);
                defaults.setIsUsingOverage(false);
                emitStatusChange(defaults);
            }
            return;
        }

        rawUtilization = extractRawUtilization(headers);
        ClaudeAILimits newLimits = computeNewLimitsFromHeaders(headers);
        cacheExtraUsageDisabledReason(headers);

        if (!newLimits.equals(currentLimits)) {
            emitStatusChange(newLimits);
        }
    }

    /**
     * Extract quota status from a 429 API error.
     * Translated from extractQuotaStatusFromError() in claudeAiLimits.ts
     */
    public void extractQuotaStatusFromError(int httpStatus, Map<String, String> errorHeaders) {
        if (!shouldProcessRateLimits() || httpStatus != 429) return;

        try {
            ClaudeAILimits newLimits;
            if (errorHeaders != null) {
                rawUtilization = extractRawUtilization(errorHeaders);
                newLimits = computeNewLimitsFromHeaders(errorHeaders);
                cacheExtraUsageDisabledReason(errorHeaders);
            } else {
                // Copy current state
                newLimits = copyLimits(currentLimits);
            }
            // For errors, always set status to rejected
            newLimits.setStatus(QuotaStatus.REJECTED);

            if (!newLimits.equals(currentLimits)) {
                emitStatusChange(newLimits);
            }
        } catch (Exception e) {
            log.error("extractQuotaStatusFromError: {}", e.getMessage(), e);
        }
    }

    public boolean isUsingOverage() {
        return currentLimits != null && Boolean.TRUE.equals(currentLimits.getIsUsingOverage());
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private ClaudeAILimits computeNewLimitsFromHeaders(Map<String, String> headers) {
        QuotaStatus status = QuotaStatus.fromString(
                headers.get("anthropic-ratelimit-unified-status"));
        String resetsAtRaw = headers.get("anthropic-ratelimit-unified-reset");
        Long resetsAt = resetsAtRaw != null ? parseLong(resetsAtRaw) : null;
        boolean fallbackAvailable =
                "available".equals(headers.get("anthropic-ratelimit-unified-fallback"));

        RateLimitType rateLimitType = RateLimitType.fromString(
                headers.get("anthropic-ratelimit-unified-representative-claim"));
        QuotaStatus overageStatus = QuotaStatus.fromString(
                headers.get("anthropic-ratelimit-unified-overage-status"));
        String overageResetRaw = headers.get("anthropic-ratelimit-unified-overage-reset");
        Long overageResetsAt = overageResetRaw != null ? parseLong(overageResetRaw) : null;
        OverageDisabledReason overageDisabledReason = OverageDisabledReason.fromString(
                headers.get("anthropic-ratelimit-unified-overage-disabled-reason"));

        boolean isUsingOverage = status == QuotaStatus.REJECTED
                && (overageStatus == QuotaStatus.ALLOWED
                        || overageStatus == QuotaStatus.ALLOWED_WARNING);

        // Check for early warning
        if (status == QuotaStatus.ALLOWED || status == QuotaStatus.ALLOWED_WARNING) {
            ClaudeAILimits earlyWarning = getEarlyWarningFromHeaders(headers, fallbackAvailable);
            if (earlyWarning != null) return earlyWarning;
            status = QuotaStatus.ALLOWED; // No early warning threshold surpassed
        }

        ClaudeAILimits limits = new ClaudeAILimits();
        limits.setStatus(status);
        limits.setResetsAt(resetsAt);
        limits.setUnifiedRateLimitFallbackAvailable(fallbackAvailable);
        limits.setRateLimitType(rateLimitType);
        limits.setOverageStatus(overageStatus);
        limits.setOverageResetsAt(overageResetsAt);
        limits.setOverageDisabledReason(overageDisabledReason);
        limits.setIsUsingOverage(isUsingOverage);
        return limits;
    }

    /**
     * Header-based early warning, then time-relative fallback.
     * Translated from getEarlyWarningFromHeaders() in claudeAiLimits.ts
     */
    private ClaudeAILimits getEarlyWarningFromHeaders(Map<String, String> headers,
                                                        boolean fallbackAvailable) {
        // 1. Check surpassed-threshold header (server-side approach)
        ClaudeAILimits headerBased = getHeaderBasedEarlyWarning(headers, fallbackAvailable);
        if (headerBased != null) return headerBased;

        // 2. Fallback: time-relative thresholds (client-side)
        for (EarlyWarningConfig config : EARLY_WARNING_CONFIGS) {
            ClaudeAILimits timeRelative = getTimeRelativeEarlyWarning(
                    headers, config, fallbackAvailable);
            if (timeRelative != null) return timeRelative;
        }
        return null;
    }

    /**
     * Translated from getHeaderBasedEarlyWarning() in claudeAiLimits.ts
     */
    private ClaudeAILimits getHeaderBasedEarlyWarning(Map<String, String> headers,
                                                        boolean fallbackAvailable) {
        for (Map.Entry<String, RateLimitType> entry : EARLY_WARNING_CLAIM_MAP.entrySet()) {
            String abbrev = entry.getKey();
            RateLimitType limitType = entry.getValue();
            String surpassed = headers.get(
                    "anthropic-ratelimit-unified-" + abbrev + "-surpassed-threshold");
            if (surpassed == null) continue;

            Double utilization = parseDouble(
                    headers.get("anthropic-ratelimit-unified-" + abbrev + "-utilization"));
            Long resetsAt = parseLong(
                    headers.get("anthropic-ratelimit-unified-" + abbrev + "-reset"));

            ClaudeAILimits w = new ClaudeAILimits();
            w.setStatus(QuotaStatus.ALLOWED_WARNING);
            w.setResetsAt(resetsAt);
            w.setRateLimitType(limitType);
            w.setUtilization(utilization);
            w.setUnifiedRateLimitFallbackAvailable(fallbackAvailable);
            w.setIsUsingOverage(false);
            w.setSurpassedThreshold(parseDouble(surpassed));
            return w;
        }
        return null;
    }

    /**
     * Translated from getTimeRelativeEarlyWarning() in claudeAiLimits.ts
     */
    private ClaudeAILimits getTimeRelativeEarlyWarning(Map<String, String> headers,
                                                         EarlyWarningConfig config,
                                                         boolean fallbackAvailable) {
        String abbrev = config.claimAbbrev();
        String utilRaw  = headers.get("anthropic-ratelimit-unified-" + abbrev + "-utilization");
        String resetRaw = headers.get("anthropic-ratelimit-unified-" + abbrev + "-reset");
        if (utilRaw == null || resetRaw == null) return null;

        double utilization = Double.parseDouble(utilRaw);
        long resetsAt = Long.parseLong(resetRaw);
        double timeProgress = computeTimeProgress(resetsAt, config.windowSeconds());

        boolean shouldWarn = config.thresholds().stream().anyMatch(
                t -> utilization >= t.utilization() && timeProgress <= t.timePct());
        if (!shouldWarn) return null;

        ClaudeAILimits w = new ClaudeAILimits();
        w.setStatus(QuotaStatus.ALLOWED_WARNING);
        w.setResetsAt(resetsAt);
        w.setRateLimitType(config.rateLimitType());
        w.setUtilization(utilization);
        w.setUnifiedRateLimitFallbackAvailable(fallbackAvailable);
        w.setIsUsingOverage(false);
        return w;
    }

    /**
     * Translated from computeTimeProgress() in claudeAiLimits.ts
     */
    private double computeTimeProgress(long resetsAtSeconds, long windowSeconds) {
        double nowSeconds = System.currentTimeMillis() / 1000.0;
        double windowStart = resetsAtSeconds - windowSeconds;
        double elapsed = nowSeconds - windowStart;
        return Math.max(0, Math.min(1, elapsed / windowSeconds));
    }

    /**
     * Translated from extractRawUtilization() in claudeAiLimits.ts
     */
    private RawUtilization extractRawUtilization(Map<String, String> headers) {
        RawUtilization result = new RawUtilization();
        for (Map.Entry<String, String> e : Map.of("five_hour", "5h", "seven_day", "7d").entrySet()) {
            String key   = e.getKey();
            String abbrev = e.getValue();
            String util  = headers.get("anthropic-ratelimit-unified-" + abbrev + "-utilization");
            String reset = headers.get("anthropic-ratelimit-unified-" + abbrev + "-reset");
            if (util == null || reset == null) continue;
            RawWindowUtilization rw = new RawWindowUtilization(
                    Double.parseDouble(util), Long.parseLong(reset));
            if ("five_hour".equals(key)) result.setFiveHour(rw);
            else result.setSevenDay(rw);
        }
        return result;
    }

    /**
     * Translated from cacheExtraUsageDisabledReason() in claudeAiLimits.ts
     */
    private void cacheExtraUsageDisabledReason(Map<String, String> headers) {
        String reason = headers.get("anthropic-ratelimit-unified-overage-disabled-reason");
        globalConfigService.setCachedExtraUsageDisabledReason(reason);
    }

    private boolean shouldProcessRateLimits() {
        // Delegates to subscriber check (real) or mock override
        try {
            return oauthService() != null && (oauthService().isClaudeAISubscriber()
                    || isMockEnabled());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isMockEnabled() {
        String userType = System.getenv("USER_TYPE");
        if (!"ant".equals(userType)) return false;
        String mock = System.getenv("CLAUDE_MOCK_HEADERLESS_429");
        return mock != null;
    }

    /** Lazy reference to avoid circular dependency. Subclasses/tests can override. */
    protected OAuthService oauthService() { return null; }

    private ClaudeAILimits copyLimits(ClaudeAILimits src) {
        ClaudeAILimits c = new ClaudeAILimits();
        c.setStatus(src.getStatus());
        c.setUnifiedRateLimitFallbackAvailable(src.isUnifiedRateLimitFallbackAvailable());
        c.setResetsAt(src.getResetsAt());
        c.setRateLimitType(src.getRateLimitType());
        c.setUtilization(src.getUtilization());
        c.setOverageStatus(src.getOverageStatus());
        c.setOverageResetsAt(src.getOverageResetsAt());
        c.setOverageDisabledReason(src.getOverageDisabledReason());
        c.setIsUsingOverage(src.getIsUsingOverage());
        c.setSurpassedThreshold(src.getSurpassedThreshold());
        return c;
    }

    private Long parseLong(String s) {
        if (s == null) return null;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }

    private Double parseDouble(String s) {
        if (s == null) return null;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
    }
}
