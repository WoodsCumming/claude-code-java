package com.anthropic.claudecode.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Builder;

/**
 * Claude.ai rate limit and quota status.
 * Translated from ClaudeAILimits type in src/services/claudeAiLimits.ts
 */
@Data
@lombok.Builder

public class ClaudeAiLimits {

    public enum QuotaStatus {
        ALLOWED("allowed"),
        ALLOWED_WARNING("allowed_warning"),
        REJECTED("rejected");

        private final String value;
        QuotaStatus(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    public enum RateLimitType {
        FIVE_HOUR("five_hour"),
        SEVEN_DAY("seven_day"),
        SEVEN_DAY_OPUS("seven_day_opus"),
        SEVEN_DAY_SONNET("seven_day_sonnet"),
        OVERAGE("overage");

        private final String value;
        RateLimitType(String value) { this.value = value; }
    }

    private QuotaStatus quotaStatus;
    private RateLimitType rateLimitType;
    private boolean isUsingOverage;
    private String overageStatus;
    private Double utilizationPercent;
    private Long resetsAtMs;
    private String warningMessage;

    public boolean isRejected() {
        return quotaStatus == QuotaStatus.REJECTED;
    }

    public boolean hasWarning() {
        return quotaStatus == QuotaStatus.ALLOWED_WARNING;
    }
}
