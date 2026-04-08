package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import com.anthropic.claudecode.util.PrivacyUtils;
import org.springframework.stereotype.Service;

/**
 * Shared analytics configuration.
 * Translated from src/services/analytics/config.ts
 *
 * Common logic for determining when analytics should be disabled
 * across all analytics systems (Datadog, 1P).
 */
@Service
public class AnalyticsConfigService {

    /**
     * Check if analytics operations should be disabled.
     *
     * Analytics is disabled in the following cases:
     * - Test environment (NODE_ENV === 'test')
     * - Third-party cloud providers (Bedrock / Vertex / Foundry)
     * - Privacy level is no-telemetry or essential-traffic
     *
     * Translated from isAnalyticsDisabled() in config.ts
     */
    public boolean isAnalyticsDisabled() {
        return "test".equals(System.getenv("NODE_ENV"))
            || EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_BEDROCK"))
            || EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_VERTEX"))
            || EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_FOUNDRY"))
            || PrivacyUtils.isTelemetryDisabled();
    }

    /**
     * Check if the feedback survey should be suppressed.
     *
     * Unlike isAnalyticsDisabled(), this does NOT block on 3P providers
     * (Bedrock/Vertex/Foundry). The survey is a local UI prompt with no
     * transcript data.
     *
     * Translated from isFeedbackSurveyDisabled() in config.ts
     */
    public boolean isFeedbackSurveyDisabled() {
        return "test".equals(System.getenv("NODE_ENV"))
            || PrivacyUtils.isTelemetryDisabled();
    }
}
