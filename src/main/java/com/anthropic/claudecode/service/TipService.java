package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import lombok.Data;

/**
 * Tip service for displaying helpful tips to users.
 * Translated from src/services/tips/tipRegistry.ts
 *
 * Manages tips shown to users during sessions.
 */
@Slf4j
@Service
public class TipService {



    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class Tip {
        private String id;
        private String text;
        private String category;
        private int cooldownSessions = 3;


        // no-arg constructor provided by @NoArgsConstructor

        public String getId() { return id; }
        public void setId(String v) { id = v; }
        public String getText() { return text; }
        public void setText(String v) { text = v; }
        public String getCategory() { return category; }
        public void setCategory(String v) { category = v; }
        public int getCooldownSessions() { return cooldownSessions; }
        public void setCooldownSessions(int v) { cooldownSessions = v; }
    }

    /**
     * Get available tips.
     * Translated from getTips() in tipRegistry.ts
     */
    public List<Tip> getAvailableTips() {
        return List.of(
            new Tip("keyboard_shortcuts", "Press Ctrl+C to cancel the current operation", "usage", 3),
            new Tip("slash_commands", "Type / to see available slash commands", "usage", 3),
            new Tip("multiline", "Press Shift+Enter to start a new line in your message", "usage", 3)
        );
    }

    /**
     * Get the next tip to show.
     * Translated from getNextTip() in tipScheduler.ts
     */
    public Optional<Tip> getNextTip(int numStartups) {
        List<Tip> tips = getAvailableTips();
        if (tips.isEmpty()) return Optional.empty();
        return Optional.of(tips.get(numStartups % tips.size()));
    }

    /**
     * Get relevant tips for a given context.
     * Translated from getRelevantTips() in tipRegistry.ts
     */
    public List<Tip> getRelevantTips(Map<String, Object> context) {
        return getAvailableTips();
    }
}
