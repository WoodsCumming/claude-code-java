package com.anthropic.claudecode.util;

import java.time.*;
import java.time.format.*;
import java.util.Locale;

/**
 * Common utilities.
 * Translated from src/constants/common.ts
 */
public class CommonUtils {

    private static volatile String sessionStartDate;

    /**
     * Get the local ISO date.
     * Translated from getLocalISODate() in common.ts
     */
    public static String getLocalISODate() {
        String override = System.getenv("CLAUDE_CODE_OVERRIDE_DATE");
        if (override != null) return override;

        return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

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

    /**
     * Get the local month and year.
     * Translated from getLocalMonthYear() in common.ts
     */
    public static String getLocalMonthYear() {
        String override = System.getenv("CLAUDE_CODE_OVERRIDE_DATE");
        LocalDate date = override != null
            ? LocalDate.parse(override)
            : LocalDate.now();

        return date.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US));
    }

    /**
     * Reset the session start date (for testing).
     */
    public static void resetSessionStartDate() {
        sessionStartDate = null;
    }

    private CommonUtils() {}
}
