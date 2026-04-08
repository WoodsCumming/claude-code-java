package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;

/**
 * Centralized rate limit message generation.
 * Translated from src/services/rateLimitMessages.ts
 *
 * Single source of truth for all rate limit-related messages shown
 * in the UI footer and inline in assistant messages.
 */
@Slf4j
@Service
public class RateLimitMessagesService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RateLimitMessagesService.class);


    private static final String FEEDBACK_CHANNEL_ANT = "#briarpatch-cc";

    /**
     * All possible rate limit error message prefixes.
     * Export this to avoid fragile string matching in UI components.
     * Translated from RATE_LIMIT_ERROR_PREFIXES in rateLimitMessages.ts
     */
    public static final List<String> RATE_LIMIT_ERROR_PREFIXES = List.of(
        "You've hit your",
        "You've used",
        "You're now using extra usage",
        "You're close to",
        "You're out of extra usage"
    );

    // -------------------------------------------------------------------------
    // Sealed types
    // -------------------------------------------------------------------------

    /**
     * A rate limit message with a severity level.
     * Translated from RateLimitMessage in rateLimitMessages.ts
     */
    public sealed interface RateLimitMessage permits
        RateLimitMessage.Error,
        RateLimitMessage.Warning {

        String message();

        record Error(String message) implements RateLimitMessage {}
        record Warning(String message) implements RateLimitMessage {}
    }

    /** Rate limit types from ClaudeAILimits. */
    public enum RateLimitType {
        SEVEN_DAY,
        SEVEN_DAY_SONNET,
        SEVEN_DAY_OPUS,
        FIVE_HOUR,
        OVERAGE
    }

    /** Overage status values. */
    public enum OverageStatus {
        ALLOWED,
        ALLOWED_WARNING,
        REJECTED
    }

    /** Subscription types. */
    public enum SubscriptionType {
        FREE, PRO, MAX, TEAM, ENTERPRISE
    }

    // -------------------------------------------------------------------------
    // Rate limit limits data object
    // -------------------------------------------------------------------------

    /**
     * Snapshot of ClaudeAI rate-limit state.
     * Translated from ClaudeAILimits in claudeAiLimits.ts (subset used here).
     */
    public record ClaudeAiLimitsSnapshot(
        String status,                  // "allowed" | "allowed_warning" | "rejected"
        RateLimitType rateLimitType,
        Double utilization,
        Long resetsAt,
        boolean isUsingOverage,
        OverageStatus overageStatus,
        Long overageResetsAt,
        String overageDisabledReason    // e.g. "out_of_credits"
    ) {}

    // -------------------------------------------------------------------------
    // Collaborators
    // -------------------------------------------------------------------------

    private final AuthService authService;
    private final BillingService billingService;

    @Autowired
    public RateLimitMessagesService(AuthService authService, BillingService billingService) {
        this.authService = authService;
        this.billingService = billingService;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Check if a message text is a rate limit error.
     * Translated from isRateLimitErrorMessage() in rateLimitMessages.ts
     */
    public boolean isRateLimitErrorMessage(String text) {
        if (text == null) return false;
        return RATE_LIMIT_ERROR_PREFIXES.stream().anyMatch(text::startsWith);
    }

    /**
     * Get the appropriate rate limit message based on limit state.
     * Returns null if no message should be shown.
     * Translated from getRateLimitMessage() in rateLimitMessages.ts
     */
    public RateLimitMessage getRateLimitMessage(ClaudeAiLimitsSnapshot limits, String model) {
        // Overage scenarios (subscription rejected, overage available)
        if (limits.isUsingOverage()) {
            if (limits.overageStatus() == OverageStatus.ALLOWED_WARNING) {
                return new RateLimitMessage.Warning("You're close to your extra usage spending limit");
            }
            return null;
        }

        // ERROR: limits rejected
        if ("rejected".equals(limits.status())) {
            return new RateLimitMessage.Error(getLimitReachedText(limits, model));
        }

        // WARNING: approaching limits
        if ("allowed_warning".equals(limits.status())) {
            final double WARNING_THRESHOLD = 0.7;
            if (limits.utilization() != null && limits.utilization() < WARNING_THRESHOLD) {
                return null;
            }

            // Don't warn non-billing Team/Enterprise users if overages are enabled
            SubscriptionType subscriptionType = getSubscriptionType();
            boolean isTeamOrEnterprise =
                subscriptionType == SubscriptionType.TEAM ||
                subscriptionType == SubscriptionType.ENTERPRISE;
            boolean hasExtraUsageEnabled = authService.hasExtraUsageEnabled();

            if (isTeamOrEnterprise && hasExtraUsageEnabled && !billingService.hasClaudeAiBillingAccess()) {
                return null;
            }

            String text = getEarlyWarningText(limits);
            if (text != null) {
                return new RateLimitMessage.Warning(text);
            }
        }

        return null;
    }

    /**
     * Get error message for API errors.
     * Returns the message string or null.
     * Translated from getRateLimitErrorMessage() in rateLimitMessages.ts
     */
    public String getRateLimitErrorMessage(ClaudeAiLimitsSnapshot limits, String model) {
        RateLimitMessage msg = getRateLimitMessage(limits, model);
        if (msg instanceof RateLimitMessage.Error e) {
            return e.message();
        }
        return null;
    }

    /**
     * Get warning message for the UI footer.
     * Translated from getRateLimitWarning() in rateLimitMessages.ts
     */
    public String getRateLimitWarning(ClaudeAiLimitsSnapshot limits, String model) {
        RateLimitMessage msg = getRateLimitMessage(limits, model);
        if (msg instanceof RateLimitMessage.Warning w) {
            return w.message();
        }
        return null;
    }

    /**
     * Get notification text for overage mode transitions.
     * Translated from getUsingOverageText() in rateLimitMessages.ts
     */
    public String getUsingOverageText(ClaudeAiLimitsSnapshot limits) {
        String resetTime = limits.resetsAt() != null
            ? formatResetTime(limits.resetsAt(), true) : "";

        String limitName = "";
        if (limits.rateLimitType() == RateLimitType.FIVE_HOUR) {
            limitName = "session limit";
        } else if (limits.rateLimitType() == RateLimitType.SEVEN_DAY) {
            limitName = "weekly limit";
        } else if (limits.rateLimitType() == RateLimitType.SEVEN_DAY_OPUS) {
            limitName = "Opus limit";
        } else if (limits.rateLimitType() == RateLimitType.SEVEN_DAY_SONNET) {
            SubscriptionType sub = getSubscriptionType();
            boolean isProOrEnterprise =
                sub == SubscriptionType.PRO || sub == SubscriptionType.ENTERPRISE;
            limitName = isProOrEnterprise ? "weekly limit" : "Sonnet limit";
        }

        if (limitName.isEmpty()) {
            return "Now using extra usage";
        }

        String resetMessage = !resetTime.isEmpty()
            ? " · Your " + limitName + " resets " + resetTime : "";
        return "You're now using extra usage" + resetMessage;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String getLimitReachedText(ClaudeAiLimitsSnapshot limits, String model) {
        String resetTime = limits.resetsAt() != null
            ? formatResetTime(limits.resetsAt(), true) : null;
        String overageResetTime = limits.overageResetsAt() != null
            ? formatResetTime(limits.overageResetsAt(), true) : null;
        String resetMessage = resetTime != null ? " · resets " + resetTime : "";

        // Both subscription AND overage exhausted
        if (limits.overageStatus() == OverageStatus.REJECTED) {
            String overageResetMessage = "";
            if (limits.resetsAt() != null && limits.overageResetsAt() != null) {
                overageResetMessage = limits.resetsAt() < limits.overageResetsAt()
                    ? " · resets " + resetTime
                    : " · resets " + overageResetTime;
            } else if (resetTime != null) {
                overageResetMessage = " · resets " + resetTime;
            } else if (overageResetTime != null) {
                overageResetMessage = " · resets " + overageResetTime;
            }

            if ("out_of_credits".equals(limits.overageDisabledReason())) {
                return "You're out of extra usage" + overageResetMessage;
            }
            return formatLimitReachedText("limit", overageResetMessage, model);
        }

        if (limits.rateLimitType() == RateLimitType.SEVEN_DAY_SONNET) {
            SubscriptionType sub = getSubscriptionType();
            boolean isProOrEnterprise =
                sub == SubscriptionType.PRO || sub == SubscriptionType.ENTERPRISE;
            String limit = isProOrEnterprise ? "weekly limit" : "Sonnet limit";
            return formatLimitReachedText(limit, resetMessage, model);
        }

        if (limits.rateLimitType() == RateLimitType.SEVEN_DAY_OPUS) {
            return formatLimitReachedText("Opus limit", resetMessage, model);
        }

        if (limits.rateLimitType() == RateLimitType.SEVEN_DAY) {
            return formatLimitReachedText("weekly limit", resetMessage, model);
        }

        if (limits.rateLimitType() == RateLimitType.FIVE_HOUR) {
            return formatLimitReachedText("session limit", resetMessage, model);
        }

        return formatLimitReachedText("usage limit", resetMessage, model);
    }

    private String getEarlyWarningText(ClaudeAiLimitsSnapshot limits) {
        String limitName = switch (limits.rateLimitType()) {
            case SEVEN_DAY -> "weekly limit";
            case FIVE_HOUR -> "session limit";
            case SEVEN_DAY_OPUS -> "Opus limit";
            case SEVEN_DAY_SONNET -> "Sonnet limit";
            case OVERAGE -> "extra usage";
            default -> null;
        };

        if (limitName == null) return null;

        Integer used = limits.utilization() != null
            ? (int) Math.floor(limits.utilization() * 100) : null;
        String resetTime = limits.resetsAt() != null
            ? formatResetTime(limits.resetsAt(), true) : null;

        String upsell = getWarningUpsellText(limits.rateLimitType());

        if (used != null && resetTime != null) {
            String base = "You've used " + used + "% of your " + limitName + " · resets " + resetTime;
            return upsell != null ? base + " · " + upsell : base;
        }

        if (used != null) {
            String base = "You've used " + used + "% of your " + limitName;
            return upsell != null ? base + " · " + upsell : base;
        }

        if (limits.rateLimitType() == RateLimitType.OVERAGE) {
            limitName += " limit";
        }

        if (resetTime != null) {
            String base = "Approaching " + limitName + " · resets " + resetTime;
            return upsell != null ? base + " · " + upsell : base;
        }

        String base = "Approaching " + limitName;
        return upsell != null ? base + " · " + upsell : base;
    }

    private String getWarningUpsellText(RateLimitType rateLimitType) {
        SubscriptionType subscriptionType = getSubscriptionType();
        boolean hasExtraUsageEnabled = authService.hasExtraUsageEnabled();

        if (rateLimitType == RateLimitType.FIVE_HOUR) {
            if (subscriptionType == SubscriptionType.TEAM ||
                subscriptionType == SubscriptionType.ENTERPRISE) {
                if (!hasExtraUsageEnabled && authService.isOverageProvisioningAllowed()) {
                    return "/extra-usage to request more";
                }
                return null;
            }
            if (subscriptionType == SubscriptionType.PRO ||
                subscriptionType == SubscriptionType.MAX) {
                return "/upgrade to keep using Claude Code";
            }
        }

        if (rateLimitType == RateLimitType.OVERAGE) {
            if (subscriptionType == SubscriptionType.TEAM ||
                subscriptionType == SubscriptionType.ENTERPRISE) {
                if (!hasExtraUsageEnabled && authService.isOverageProvisioningAllowed()) {
                    return "/extra-usage to request more";
                }
            }
        }

        return null;
    }

    private String formatLimitReachedText(String limit, String resetMessage, String model) {
        String userType = System.getenv("USER_TYPE");
        if ("ant".equals(userType)) {
            return "You've hit your " + limit + resetMessage +
                ". If you have feedback about this limit, post in " + FEEDBACK_CHANNEL_ANT +
                ". You can reset your limits with /reset-limits";
        }
        return "You've hit your " + limit + resetMessage;
    }

    /** Delegate to auth service for subscription type. */
    private SubscriptionType getSubscriptionType() {
        String raw = authService.getSubscriptionType();
        if (raw == null) return SubscriptionType.FREE;
        return switch (raw.toLowerCase()) {
            case "pro" -> SubscriptionType.PRO;
            case "max" -> SubscriptionType.MAX;
            case "team" -> SubscriptionType.TEAM;
            case "enterprise" -> SubscriptionType.ENTERPRISE;
            default -> SubscriptionType.FREE;
        };
    }

    /**
     * Format a Unix-epoch reset timestamp to a human-readable string.
     * Mirrors formatResetTime() from utils/format.ts
     */
    private String formatResetTime(long epochMs, boolean relative) {
        java.time.Instant instant = java.time.Instant.ofEpochMilli(epochMs);
        java.time.ZonedDateTime zdt = instant.atZone(java.time.ZoneId.systemDefault());
        java.time.format.DateTimeFormatter fmt =
            java.time.format.DateTimeFormatter.ofPattern("MMM d 'at' h:mm a");
        return zdt.format(fmt);
    }
}
