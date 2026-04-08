package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.GlobalConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tip history service for tracking shown tips.
 * Translated from src/services/tips/tipHistory.ts
 *
 * Records which tips have been shown during which startup session,
 * and exposes how many sessions have elapsed since a tip was last shown.
 */
@Slf4j
@Service
public class TipHistoryService {



    private final GlobalConfigService globalConfigService;

    @Autowired
    public TipHistoryService(GlobalConfigService globalConfigService) {
        this.globalConfigService = globalConfigService;
    }

    /**
     * Record that a tip was shown.
     * Associates the tip with the current startup counter so that
     * getSessionsSinceLastShown can compute elapsed sessions.
     * Translated from recordTipShown() in tipHistory.ts
     */
    public void recordTipShown(String tipId) {
        GlobalConfig config = globalConfigService.getGlobalConfig();
        int numStartups = config.getNumStartups();

        // Only update if the stored value differs from the current startup counter.
        // Mirroring the TS: if (history[tipId] === numStartups) return c
        Map<String, Integer> history = config.getTipsHistory() != null
            ? new LinkedHashMap<>(config.getTipsHistory())
            : new LinkedHashMap<>();

        if (Integer.valueOf(numStartups).equals(history.get(tipId))) {
            return; // Already recorded for this startup
        }

        history.put(tipId, numStartups);
        config.setTipsHistory(history);
        globalConfigService.saveGlobalConfig(config);
    }

    /**
     * Get the number of sessions that have elapsed since a tip was last shown.
     * Returns {@link Integer#MAX_VALUE} if the tip has never been shown
     * (mirrors the TypeScript Infinity return value).
     * Translated from getSessionsSinceLastShown() in tipHistory.ts
     */
    public int getSessionsSinceLastShown(String tipId) {
        GlobalConfig config = globalConfigService.getGlobalConfig();
        int numStartups = config.getNumStartups();

        if (config.getTipsHistory() == null) return Integer.MAX_VALUE;
        Integer lastShown = config.getTipsHistory().get(tipId);
        if (lastShown == null) return Integer.MAX_VALUE;
        return numStartups - lastShown;
    }
}
