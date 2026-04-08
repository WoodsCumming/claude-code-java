package com.anthropic.claudecode.model;

/**
 * Reasons why overage credits are disabled.
 * Translated from OverageDisabledReason in src/services/claudeAiLimits.ts
 */
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

    private final String value;

    OverageDisabledReason(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static OverageDisabledReason fromValue(String value) {
        for (OverageDisabledReason reason : values()) {
            if (reason.value.equals(value)) return reason;
        }
        return UNKNOWN;
    }
}
