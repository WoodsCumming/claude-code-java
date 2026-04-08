package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Tip scheduler service.
 * Translated from src/services/tips/tipScheduler.ts
 *
 * Selects which tip to show based on session history (the tip unseen longest
 * gets priority) and handles recording shown tips and analytics.
 */
@Slf4j
@Service
public class TipSchedulerService {



    private final TipHistoryService tipHistoryService;
    private final TipService tipService;
    private final AnalyticsService analyticsService;
    private final SettingsService settingsService;

    @Autowired
    public TipSchedulerService(
            TipHistoryService tipHistoryService,
            TipService tipService,
            AnalyticsService analyticsService,
            SettingsService settingsService) {
        this.tipHistoryService = tipHistoryService;
        this.tipService = tipService;
        this.analyticsService = analyticsService;
        this.settingsService = settingsService;
    }

    /**
     * Select the tip with the longest time since last shown from a list.
     * Tips that have never been shown (MAX_VALUE sessions) naturally rank first.
     * Translated from selectTipWithLongestTimeSinceShown() in tipScheduler.ts
     */
    public Optional<TipService.Tip> selectTipWithLongestTimeSinceShown(List<TipService.Tip> availableTips) {
        if (availableTips == null || availableTips.isEmpty()) return Optional.empty();
        if (availableTips.size() == 1) return Optional.of(availableTips.get(0));

        // Sort by sessions since last shown (descending) and take the first one.
        // This is the tip that hasn't been shown for the longest time.
        return availableTips.stream()
            .max(Comparator.comparingInt(tip -> tipHistoryService.getSessionsSinceLastShown(tip.getId())));
    }

    /**
     * Get the tip to show on the spinner, if any.
     * Returns empty if tips are disabled or no relevant tip is available.
     * Translated from getTipToShowOnSpinner() in tipScheduler.ts
     */
    public CompletableFuture<Optional<TipService.Tip>> getTipToShowOnSpinner(TipContext context) {
        return CompletableFuture.supplyAsync(() -> {
            // Check if tips are disabled (default to true if not set)
            if (Boolean.FALSE.equals(settingsService.getSpinnerTipsEnabled())) {
                return Optional.empty();
            }

            // Convert TipContext to Map<String, Object> for TipService
            java.util.Map<String, Object> contextMap = context != null ? context.toMap() : new java.util.HashMap<>();
            List<TipService.Tip> tips = tipService.getRelevantTips(contextMap);
            if (tips.isEmpty()) return Optional.empty();

            return selectTipWithLongestTimeSinceShown(tips);
        });
    }

    /**
     * Record that a tip was shown and log the analytics event.
     * Translated from recordShownTip() in tipScheduler.ts
     */
    public void recordShownTip(TipService.Tip tip) {
        // Record in history
        tipHistoryService.recordTipShown(tip.getId());

        // Log event for analytics
        analyticsService.logEvent("tengu_tip_shown", java.util.Map.of(
            "tipIdLength", tip.getId(),
            "cooldownSessions", tip.getCooldownSessions()
        ));
    }

    /**
     * Context for tip selection.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TipContext {
        private String currentTool;
        private String sessionMode;
        private boolean isFirstSession;

        public java.util.Map<String, Object> toMap() {
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            if (currentTool != null) map.put("currentTool", currentTool);
            if (sessionMode != null) map.put("sessionMode", sessionMode);
            map.put("isFirstSession", isFirstSession);
            return map;
        }

        public String getCurrentTool() { return currentTool; }
        public void setCurrentTool(String v) { currentTool = v; }
        public String getSessionMode() { return sessionMode; }
        public void setSessionMode(String v) { sessionMode = v; }
        public boolean isIsFirstSession() { return isFirstSession; }
        public void setIsFirstSession(boolean v) { isFirstSession = v; }
    }
}
