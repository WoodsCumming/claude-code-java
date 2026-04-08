package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.Data;

/**
 * Auto mode denial tracking service.
 * Translated from src/utils/autoModeDenials.ts
 *
 * Tracks commands recently denied by the auto mode classifier.
 */
@Slf4j
@Service
public class AutoModeDenialsService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AutoModeDenialsService.class);


    private static final int MAX_DENIALS = 20;

    private final List<AutoModeDenial> denials = new CopyOnWriteArrayList<>();

    /**
     * Record an auto mode denial.
     * Translated from recordAutoModeDenial() in autoModeDenials.ts
     */
    public void recordAutoModeDenial(AutoModeDenial denial) {
        denials.add(0, denial);
        if (denials.size() > MAX_DENIALS) {
            denials.remove(denials.size() - 1);
        }
    }

    /**
     * Get all recorded denials.
     * Translated from getAutoModeDenials() in autoModeDenials.ts
     */
    public List<AutoModeDenial> getAutoModeDenials() {
        return Collections.unmodifiableList(denials);
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AutoModeDenial {
        private String toolName;
        private String display;
        private String reason;
        private long timestamp;

        public String getToolName() { return toolName; }
        public void setToolName(String v) { toolName = v; }
        public String getDisplay() { return display; }
        public void setDisplay(String v) { display = v; }
        public String getReason() { return reason; }
        public void setReason(String v) { reason = v; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long v) { timestamp = v; }
    }
}
