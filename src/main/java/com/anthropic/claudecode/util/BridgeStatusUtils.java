package com.anthropic.claudecode.util;

import lombok.Data;
import lombok.experimental.UtilityClass;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Bridge status utility functions.
 * Translated from src/bridge/bridgeStatusUtil.ts
 *
 * Provides state machine types, URL builders, shimmer animation utilities,
 * and footer text for the bridge status display.
 */
@UtilityClass
public class BridgeStatusUtils {

    /** How long a tool activity line stays visible after last tool_start (ms). */
    public static final long TOOL_DISPLAY_EXPIRY_MS = 30_000L;

    /** Interval for the shimmer animation tick (ms). */
    public static final long SHIMMER_INTERVAL_MS = 150L;

    /** Footer text shown when the bridge has failed. */
    public static final String FAILED_FOOTER_TEXT = "Something went wrong, please try again";

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * Bridge status state machine states.
     */
    public enum StatusState {
        IDLE,
        ATTACHED,
        TITLED,
        RECONNECTING,
        FAILED
    }

    /**
     * Computed bridge status label and color from connection state.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class BridgeStatusInfo {
        private String label;
        private String color; // "error", "warning", "success"

        public String getLabel() { return label; }
        public void setLabel(String v) { label = v; }
        public String getColor() { return color; }
        public void setColor(String v) { color = v; }
    

    }

    /**
     * Result of splitting text for shimmer rendering.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ShimmerSegments {
        private String before;
        private String shimmer;
        private String after;

        public String getBefore() { return before; }
        public void setBefore(String v) { before = v; }
        public String getShimmer() { return shimmer; }
        public void setShimmer(String v) { shimmer = v; }
        public String getAfter() { return after; }
        public void setAfter(String v) { after = v; }
    

    }

    /**
     * Returns the current time as HH:mm:ss.
     */
    public static String timestamp() {
        return LocalTime.now().format(TIME_FORMATTER);
    }

    /**
     * Format duration in human-readable form (e.g., "1m 30s", "45s").
     */
    public static String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        long seconds = ms / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        long remainingSecs = seconds % 60;
        if (minutes < 60) {
            return remainingSecs > 0 ? minutes + "m " + remainingSecs + "s" : minutes + "m";
        }
        long hours = minutes / 60;
        long remainingMins = minutes % 60;
        return remainingMins > 0 ? hours + "h " + remainingMins + "m" : hours + "h";
    }

    /**
     * Truncate a string to a given visual width, appending "…" if truncated.
     */
    public static String truncateToWidth(String text, int maxWidth) {
        if (text == null) return "";
        if (text.length() <= maxWidth) return text;
        return text.substring(0, maxWidth - 1) + "\u2026";
    }

    /**
     * Abbreviate a tool activity summary for the trail display (max 30 chars).
     */
    public static String abbreviateActivity(String summary) {
        return truncateToWidth(summary, 30);
    }

    /**
     * Build the connect URL shown when the bridge is idle.
     */
    public static String buildBridgeConnectUrl(String environmentId, String ingressUrl) {
        String baseUrl = getClaudeAiBaseUrl(ingressUrl);
        return baseUrl + "/code?bridge=" + environmentId;
    }

    /**
     * Build the session URL shown when a session is attached.
     */
    public static String buildBridgeSessionUrl(String sessionId, String environmentId, String ingressUrl) {
        String sessionUrl = getRemoteSessionUrl(sessionId, ingressUrl);
        return sessionUrl + "?bridge=" + environmentId;
    }

    /**
     * Compute the glimmer index for a reverse-sweep shimmer animation.
     */
    public static int computeGlimmerIndex(int tick, int messageWidth) {
        int cycleLength = messageWidth + 20;
        return messageWidth + 10 - (tick % cycleLength);
    }

    /**
     * Split text into three segments by visual column position for shimmer rendering.
     * Returns ShimmerSegments with before, shimmer, and after fields.
     */
    public static ShimmerSegments computeShimmerSegments(String text, int glimmerIndex) {
        int messageWidth = text.length(); // simplified: no multi-byte grapheme segmentation
        int shimmerStart = glimmerIndex - 1;
        int shimmerEnd = glimmerIndex + 1;

        ShimmerSegments segments = new ShimmerSegments();
        // When shimmer is offscreen, return all text as "before"
        if (shimmerStart >= messageWidth || shimmerEnd < 0) {
            segments.setBefore(text);
            segments.setShimmer("");
            segments.setAfter("");
            return segments;
        }

        int clampedStart = Math.max(0, shimmerStart);
        StringBuilder before = new StringBuilder();
        StringBuilder shimmer = new StringBuilder();
        StringBuilder after = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (i < clampedStart) {
                before.append(ch);
            } else if (i > shimmerEnd) {
                after.append(ch);
            } else {
                shimmer.append(ch);
            }
        }

        segments.setBefore(before.toString());
        segments.setShimmer(shimmer.toString());
        segments.setAfter(after.toString());
        return segments;
    }

    /**
     * Derive a status label and color from the bridge connection state.
     */
    public static BridgeStatusInfo getBridgeStatus(
            String error,
            boolean connected,
            boolean sessionActive,
            boolean reconnecting) {
        BridgeStatusInfo info = new BridgeStatusInfo();
        if (error != null && !error.isEmpty()) {
            info.setLabel("Remote Control failed");
            info.setColor("error");
        } else if (reconnecting) {
            info.setLabel("Remote Control reconnecting");
            info.setColor("warning");
        } else if (sessionActive || connected) {
            info.setLabel("Remote Control active");
            info.setColor("success");
        } else {
            info.setLabel("Remote Control connecting\u2026");
            info.setColor("warning");
        }
        return info;
    }

    /**
     * Footer text shown when bridge is idle (Ready state).
     */
    public static String buildIdleFooterText(String url) {
        return "Code everywhere with the Claude app or " + url;
    }

    /**
     * Footer text shown when a session is active (Connected state).
     */
    public static String buildActiveFooterText(String url) {
        return "Continue coding in the Claude app or " + url;
    }

    /**
     * Wrap text in an OSC 8 terminal hyperlink. Zero visual width for layout purposes.
     */
    public static String wrapWithOsc8Link(String text, String url) {
        return "\u001b]8;;" + url + "\u0007" + text + "\u001b]8;;\u0007";
    }

    // --- Private helpers ---

    private static String getClaudeAiBaseUrl(String ingressUrl) {
        if (ingressUrl != null && !ingressUrl.isEmpty()) {
            return ingressUrl;
        }
        return "https://claude.ai";
    }

    private static String getRemoteSessionUrl(String sessionId, String ingressUrl) {
        String baseUrl = getClaudeAiBaseUrl(ingressUrl);
        // Translate cse_ prefix to session_ for compat surface
        String id = sessionId.startsWith("cse_")
                ? "session_" + sessionId.substring(4)
                : sessionId;
        return baseUrl + "/code/sessions/" + id;
    }
}
