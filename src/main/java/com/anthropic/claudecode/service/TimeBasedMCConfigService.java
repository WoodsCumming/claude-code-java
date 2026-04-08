package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Time-based microcompact configuration service.
 * Translated from src/services/compact/timeBasedMCConfig.ts
 *
 * Configuration for time-based microcompact: triggers content-clearing
 * microcompact when the gap since the last main-loop assistant message
 * exceeds a threshold — the server-side prompt cache has almost certainly
 * expired, so the full prefix will be rewritten anyway.
 */
@Slf4j
@Service
public class TimeBasedMCConfigService {



    /**
     * GrowthBook config for time-based microcompact.
     * Translated from TimeBasedMCConfig in timeBasedMCConfig.ts
     */
    @Data
    public static class TimeBasedMCConfig {
        /** Master switch. When false, time-based microcompact is a no-op. */
        private boolean enabled;
        /**
         * Trigger when (now - last assistant timestamp) exceeds this many minutes.
         * 60 is the safe choice: the server's 1h cache TTL is guaranteed expired.
         */
        private int gapThresholdMinutes;
        /**
         * Keep this many most-recent compactable tool results.
         * When set, takes priority over any default; older results are cleared.
         */
        private int keepRecent;

        /**
         * Returns default configuration values.
         */
        public static TimeBasedMCConfig defaults() {
            TimeBasedMCConfig config = new TimeBasedMCConfig();
            config.setEnabled(false);
            config.setGapThresholdMinutes(60);
            config.setKeepRecent(5);
            return config;
        }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { enabled = v; }
        public int getGapThresholdMinutes() { return gapThresholdMinutes; }
        public void setGapThresholdMinutes(int v) { gapThresholdMinutes = v; }
        public int getKeepRecent() { return keepRecent; }
        public void setKeepRecent(int v) { keepRecent = v; }
    }

    /**
     * Get the time-based microcompact configuration.
     * Translated from getTimeBasedMCConfig() in timeBasedMCConfig.ts
     *
     * In the TypeScript source this reads from GrowthBook feature flag
     * 'tengu_slate_heron'. Here we return defaults; a real implementation
     * would integrate with the GrowthBook/remote-config service.
     */
    public TimeBasedMCConfig getTimeBasedMCConfig() {
        // TODO: integrate with GrowthBookService for remote flag 'tengu_slate_heron'
        return TimeBasedMCConfig.defaults();
    }
}
