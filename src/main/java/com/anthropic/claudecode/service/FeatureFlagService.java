package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Feature flag service.
 * Stub implementation — delegates to GrowthBook or environment-variable flags.
 * Translated from src/services/analytics/growthbook.ts (isEnabled helper).
 */
@Slf4j
@Service
public class FeatureFlagService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FeatureFlagService.class);


    /**
     * Returns true when the named feature flag is enabled.
     * Checks environment variable {@code CLAUDE_CODE_<FLAG>} first,
     * then falls back to false (disabled by default).
     */
    public boolean isEnabled(String flagName) {
        String envKey = "CLAUDE_CODE_" + flagName.toUpperCase().replace("-", "_");
        String val = System.getenv(envKey);
        if (val == null) return false;
        return "1".equals(val) || "true".equalsIgnoreCase(val) || "yes".equalsIgnoreCase(val);
    }
}
