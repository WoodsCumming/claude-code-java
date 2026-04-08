package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.time.*;
import java.util.*;

/**
 * Cron expression parsing and scheduling utilities.
 * Translated from src/utils/cron.ts
 *
 * Supports standard 5-field cron expressions:
 *   minute hour day-of-month month day-of-week
 */
@Slf4j
public class CronUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CronUtils.class);


    private static final int[][] FIELD_RANGES = {
        {0, 59},  // minute
        {0, 23},  // hour
        {1, 31},  // dayOfMonth
        {1, 12},  // month
        {0, 6}    // dayOfWeek (0=Sunday)
    };

    /**
     * Parse a cron expression into fields.
     * Translated from parseCronExpression() in cron.ts
     *
     * @return parsed fields, or null if invalid
     */
    public static int[][] parseCronExpression(String expression) {
        if (expression == null || expression.isBlank()) return null;

        String[] parts = expression.trim().split("\\s+");
        if (parts.length != 5) return null;

        int[][] fields = new int[5][];
        for (int i = 0; i < 5; i++) {
            int[] expanded = expandField(parts[i], FIELD_RANGES[i][0], FIELD_RANGES[i][1], i == 4);
            if (expanded == null) return null;
            fields[i] = expanded;
        }

        return fields;
    }

    /**
     * Calculate the next run time in milliseconds from now.
     * Translated from nextCronRunMs() in cron.ts
     */
    public static long nextCronRunMs(String expression) {
        int[][] fields = parseCronExpression(expression);
        if (fields == null) return -1;

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = findNextRun(fields, now);
        if (next == null) return -1;

        return Duration.between(now, next).toMillis();
    }

    /**
     * Convert a cron expression to a human-readable string.
     * Translated from cronToHuman() in cron.ts
     */
    public static String cronToHuman(String expression) {
        if (expression == null) return "invalid";

        String[] parts = expression.trim().split("\\s+");
        if (parts.length != 5) return "invalid";

        String minute = parts[0];
        String hour = parts[1];

        // Simple descriptions for common patterns
        if ("*".equals(minute) && "*".equals(hour)) {
            return "every minute";
        }
        if (minute.startsWith("*/") && "*".equals(hour)) {
            return "every " + minute.substring(2) + " minutes";
        }
        if ("0".equals(minute) && "*".equals(hour)) {
            return "every hour";
        }
        if (minute.startsWith("*/") && "*/".equals(hour.substring(0, Math.min(2, hour.length())))) {
            return "every " + minute.substring(2) + " minutes, every " + hour.substring(2) + " hours";
        }

        return "at " + minute + " " + hour + " " + parts[2] + " " + parts[3] + " " + parts[4];
    }

    private static int[] expandField(String field, int min, int max, boolean isDow) {
        Set<Integer> out = new TreeSet<>();
        int effectiveMax = isDow ? 7 : max;

        for (String part : field.split(",")) {
            // Wildcard or step
            if (part.startsWith("*")) {
                int step = 1;
                if (part.contains("/")) {
                    try {
                        step = Integer.parseInt(part.substring(part.indexOf('/') + 1));
                        if (step < 1) return null;
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
                for (int i = min; i <= max; i += step) out.add(i);
                continue;
            }

            // Range with optional step
            if (part.contains("-")) {
                String[] rangeParts = part.split("-");
                String[] stepParts = rangeParts[rangeParts.length - 1].split("/");
                try {
                    int lo = Integer.parseInt(rangeParts[0]);
                    int hi = Integer.parseInt(stepParts[0]);
                    int step = stepParts.length > 1 ? Integer.parseInt(stepParts[1]) : 1;

                    if (lo > hi || step < 1 || lo < min || hi > effectiveMax) return null;
                    for (int i = lo; i <= hi; i += step) {
                        out.add(isDow && i == 7 ? 0 : i);
                    }
                } catch (NumberFormatException e) {
                    return null;
                }
                continue;
            }

            // Single value
            try {
                int val = Integer.parseInt(part);
                if (isDow && val == 7) val = 0;
                if (val < min || val > max) return null;
                out.add(val);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static LocalDateTime findNextRun(int[][] fields, LocalDateTime from) {
        LocalDateTime candidate = from.plusMinutes(1).withSecond(0).withNano(0);

        // Try up to 4 years
        LocalDateTime limit = from.plusYears(4);

        while (candidate.isBefore(limit)) {
            if (matchesField(fields[3], candidate.getMonthValue())) {
                if (matchesField(fields[2], candidate.getDayOfMonth())
                        && matchesField(fields[4], candidate.getDayOfWeek().getValue() % 7)) {
                    if (matchesField(fields[1], candidate.getHour())) {
                        if (matchesField(fields[0], candidate.getMinute())) {
                            return candidate;
                        }
                        // Advance to next matching minute
                        candidate = candidate.plusMinutes(1);
                        continue;
                    }
                    // Advance to next matching hour
                    candidate = candidate.plusHours(1).withMinute(0);
                    continue;
                }
                // Advance to next day
                candidate = candidate.plusDays(1).withHour(0).withMinute(0);
                continue;
            }
            // Advance to next month
            candidate = candidate.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0);
        }

        return null;
    }

    private static boolean matchesField(int[] values, int value) {
        for (int v : values) {
            if (v == value) return true;
        }
        return false;
    }

    private CronUtils() {}
}
