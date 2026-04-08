package com.anthropic.claudecode.util;

/**
 * Privacy level utilities.
 * Translated from src/utils/privacyLevel.ts
 *
 * <p>Privacy level controls how much nonessential network traffic and telemetry
 * Claude Code generates.</p>
 *
 * <p>Levels are ordered by restrictiveness:</p>
 * <pre>
 *   DEFAULT &lt; NO_TELEMETRY &lt; ESSENTIAL_TRAFFIC
 * </pre>
 *
 * <ul>
 *   <li>{@link PrivacyLevel#DEFAULT}            — everything enabled.</li>
 *   <li>{@link PrivacyLevel#NO_TELEMETRY}        — analytics/telemetry disabled.</li>
 *   <li>{@link PrivacyLevel#ESSENTIAL_TRAFFIC}   — ALL nonessential network traffic disabled.</li>
 * </ul>
 *
 * <p>The resolved level is the most restrictive signal from:</p>
 * <ul>
 *   <li>{@code CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC} → ESSENTIAL_TRAFFIC</li>
 *   <li>{@code DISABLE_TELEMETRY}                        → NO_TELEMETRY</li>
 * </ul>
 */
public final class PrivacyUtils {

    /**
     * Mirrors the {@code PrivacyLevel} union type from privacyLevel.ts.
     */
    public enum PrivacyLevel {
        DEFAULT,
        NO_TELEMETRY,
        ESSENTIAL_TRAFFIC
    }

    /**
     * Returns the current privacy level based on environment variables.
     * Translated from {@code getPrivacyLevel()} in privacyLevel.ts.
     */
    public static PrivacyLevel getPrivacyLevel() {
        if (System.getenv("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC") != null) {
            return PrivacyLevel.ESSENTIAL_TRAFFIC;
        }
        if (System.getenv("DISABLE_TELEMETRY") != null) {
            return PrivacyLevel.NO_TELEMETRY;
        }
        return PrivacyLevel.DEFAULT;
    }

    /**
     * Returns {@code true} when all nonessential network traffic should be suppressed.
     * Equivalent to checking {@code CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC}.
     * Translated from {@code isEssentialTrafficOnly()} in privacyLevel.ts.
     */
    public static boolean isEssentialTrafficOnly() {
        return getPrivacyLevel() == PrivacyLevel.ESSENTIAL_TRAFFIC;
    }

    /**
     * Returns {@code true} when telemetry/analytics should be suppressed.
     * True at both {@link PrivacyLevel#NO_TELEMETRY} and {@link PrivacyLevel#ESSENTIAL_TRAFFIC}.
     * Translated from {@code isTelemetryDisabled()} in privacyLevel.ts.
     */
    public static boolean isTelemetryDisabled() {
        return getPrivacyLevel() != PrivacyLevel.DEFAULT;
    }

    /**
     * Returns the environment variable name that is currently enforcing the
     * essential-traffic restriction, or {@code null} if unrestricted.
     * Used for user-facing "unset X to re-enable" messages.
     * Translated from {@code getEssentialTrafficOnlyReason()} in privacyLevel.ts.
     */
    public static String getEssentialTrafficOnlyReason() {
        if (System.getenv("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC") != null) {
            return "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC";
        }
        return null;
    }

    private PrivacyUtils() {}
}
