package com.anthropic.claudecode.model;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Common constants and utilities.
 * Translated from src/constants/common.ts and src/constants/messages.ts
 */
public class CommonConstants {

    /** Placeholder for messages with no content. */
    public static final String NO_CONTENT_MESSAGE = "(no content)";

    /** Past tense verbs for turn completion messages. */
    public static final String[] TURN_COMPLETION_VERBS = {
        "Baked", "Brewed", "Churned", "Cogitated", "Cooked",
        "Crunched", "Saut\u00e9ed", "Worked"
    };

    /**
     * Get the local ISO date string (YYYY-MM-DD).
     * Translated from getLocalISODate() in common.ts
     */
    public static String getLocalISODate() {
        // Check for ant-only date override
        String override = System.getenv("CLAUDE_CODE_OVERRIDE_DATE");
        if (override != null && !override.isBlank()) {
            return override;
        }
        return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /**
     * Get the local month and year (e.g., "February 2026").
     * Translated from getLocalMonthYear() in common.ts
     */
    public static String getLocalMonthYear() {
        String override = System.getenv("CLAUDE_CODE_OVERRIDE_DATE");
        LocalDate date = override != null && !override.isBlank()
            ? LocalDate.parse(override)
            : LocalDate.now();
        return date.format(DateTimeFormatter.ofPattern("MMMM yyyy",
            java.util.Locale.ENGLISH));
    }

    // Session start date (cached once per session)
    private static String sessionStartDate = null;

    /**
     * Get the session start date (memoized).
     * Translated from getSessionStartDate() in common.ts
     */
    public static String getSessionStartDate() {
        if (sessionStartDate == null) {
            sessionStartDate = getLocalISODate();
        }
        return sessionStartDate;
    }

    private CommonConstants() {}
}
