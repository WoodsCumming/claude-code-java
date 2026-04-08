package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Denial tracking for permission classifiers.
 * Translated from src/utils/permissions/denialTracking.ts
 *
 * Tracks consecutive and total permission denials to determine
 * when to fall back to prompting the user.
 */
@Service
public class DenialTrackingService {

    public static final int MAX_CONSECUTIVE_DENIALS = 3;
    public static final int MAX_TOTAL_DENIALS = 20;

    /**
     * Create an initial denial tracking state.
     * Translated from createDenialTrackingState() in denialTracking.ts
     */
    public DenialTrackingState createInitialState() {
        return new DenialTrackingState(0, 0);
    }

    /**
     * Record a denial.
     * Translated from recordDenial() in denialTracking.ts
     */
    public DenialTrackingState recordDenial(DenialTrackingState state) {
        return new DenialTrackingState(
            state.getConsecutiveDenials() + 1,
            state.getTotalDenials() + 1
        );
    }

    /**
     * Record a success (resets consecutive denials).
     * Translated from recordSuccess() in denialTracking.ts
     */
    public DenialTrackingState recordSuccess(DenialTrackingState state) {
        if (state.getConsecutiveDenials() == 0) return state;
        return new DenialTrackingState(0, state.getTotalDenials());
    }

    /**
     * Check if we should fall back to prompting.
     * Translated from shouldFallbackToPrompting() in denialTracking.ts
     */
    public boolean shouldFallbackToPrompting(DenialTrackingState state) {
        return state.getConsecutiveDenials() >= MAX_CONSECUTIVE_DENIALS
            || state.getTotalDenials() >= MAX_TOTAL_DENIALS;
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DenialTrackingState {
        private int consecutiveDenials;
        private int totalDenials;

        public int getConsecutiveDenials() { return consecutiveDenials; }
        public void setConsecutiveDenials(int v) { consecutiveDenials = v; }
        public int getTotalDenials() { return totalDenials; }
        public void setTotalDenials(int v) { totalDenials = v; }
    }
}
