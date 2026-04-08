package com.anthropic.claudecode.util;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Pure display formatters — no UI dependencies.
 * Translated from src/utils/format.ts
 */
public class FormatUtils {

    // ---------------------------------------------------------------------------
    // formatFileSize
    // ---------------------------------------------------------------------------

    /**
     * Formats a byte count to a human-readable string (KB, MB, GB).
     *
     * Examples: formatFileSize(1536) → "1.5KB"
     *
     * Translated from formatFileSize() in format.ts
     */
    public static String formatFileSize(long sizeInBytes) {
        double kb = sizeInBytes / 1024.0;
        if (kb < 1) {
            return sizeInBytes + " bytes";
        }
        if (kb < 1024) {
            String s = String.format("%.1f", kb);
            s = s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
            return s + "KB";
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            String s = String.format("%.1f", mb);
            s = s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
            return s + "MB";
        }
        double gb = mb / 1024.0;
        String s = String.format("%.1f", gb);
        s = s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
        return s + "GB";
    }

    // ---------------------------------------------------------------------------
    // formatSecondsShort
    // ---------------------------------------------------------------------------

    /**
     * Formats milliseconds as seconds with 1 decimal place (e.g. 1234 → "1.2s").
     * Unlike formatDuration, always keeps the decimal.
     *
     * Translated from formatSecondsShort() in format.ts
     */
    public static String formatSecondsShort(long ms) {
        return String.format("%.1fs", ms / 1000.0);
    }

    // ---------------------------------------------------------------------------
    // formatDuration
    // ---------------------------------------------------------------------------

    /**
     * Options for {@link #formatDuration(long, boolean, boolean)}.
     */
    public record DurationOptions(boolean hideTrailingZeros, boolean mostSignificantOnly) {
        public static final DurationOptions DEFAULT = new DurationOptions(false, false);
    }

    /**
     * Formats a duration in milliseconds to a human-readable string.
     *
     * Translated from formatDuration() in format.ts
     */
    public static String formatDuration(long ms) {
        return formatDuration(ms, false, false);
    }

    /**
     * Formats a duration in milliseconds to a human-readable string.
     *
     * @param ms                  duration in milliseconds
     * @param hideTrailingZeros   omit zero-valued units at the tail
     * @param mostSignificantOnly return only the most significant unit
     */
    public static String formatDuration(long ms, boolean hideTrailingZeros, boolean mostSignificantOnly) {
        if (ms < 60_000) {
            if (ms == 0) return "0s";
            if (ms < 1) {
                return String.format("%.1fs", ms / 1000.0);
            }
            return (ms / 1000) + "s";
        }

        long days    = ms / 86_400_000L;
        long hours   = (ms % 86_400_000L) / 3_600_000L;
        long minutes = (ms % 3_600_000L)  / 60_000L;
        long seconds = Math.round((ms % 60_000L) / 1000.0);

        // Handle rounding carry-over
        if (seconds == 60) { seconds = 0; minutes++; }
        if (minutes == 60) { minutes = 0; hours++; }
        if (hours   == 24) { hours   = 0; days++; }

        if (mostSignificantOnly) {
            if (days    > 0) return days + "d";
            if (hours   > 0) return hours + "h";
            if (minutes > 0) return minutes + "m";
            return seconds + "s";
        }

        if (days > 0) {
            if (hideTrailingZeros && hours == 0 && minutes == 0) return days + "d";
            if (hideTrailingZeros && minutes == 0)               return days + "d " + hours + "h";
            return days + "d " + hours + "h " + minutes + "m";
        }
        if (hours > 0) {
            if (hideTrailingZeros && minutes == 0 && seconds == 0) return hours + "h";
            if (hideTrailingZeros && seconds == 0)                 return hours + "h " + minutes + "m";
            return hours + "h " + minutes + "m " + seconds + "s";
        }
        if (minutes > 0) {
            if (hideTrailingZeros && seconds == 0) return minutes + "m";
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }

    // ---------------------------------------------------------------------------
    // formatNumber / formatTokens
    // ---------------------------------------------------------------------------

    /**
     * Formats a number with compact notation (e.g. 1321 → "1.3k").
     *
     * Translated from formatNumber() in format.ts
     */
    public static String formatNumber(long number) {
        if (number >= 1_000_000_000L) {
            // Consistent decimals for very large numbers
            return String.format("%.1fb", number / 1_000_000_000.0);
        }
        if (number >= 1_000_000L) {
            return String.format("%.1fm", number / 1_000_000.0);
        }
        if (number >= 1_000L) {
            // Use consistent 1 decimal for compact notation (≥1000)
            return String.format("%.1fk", number / 1_000.0);
        }
        return String.valueOf(number);
    }

    /**
     * Formats a token count, removing superfluous ".0" suffixes.
     *
     * Translated from formatTokens() in format.ts
     */
    public static String formatTokens(long count) {
        return formatNumber(count).replace(".0", "");
    }

    // ---------------------------------------------------------------------------
    // formatRelativeTime / formatRelativeTimeAgo
    // ---------------------------------------------------------------------------

    /**
     * Style options for relative time formatting.
     */
    public enum RelativeTimeStyle { LONG, SHORT, NARROW }

    /**
     * Formats a date relative to now using the "narrow" style.
     *
     * Translated from formatRelativeTime() in format.ts
     */
    public static String formatRelativeTime(Instant date) {
        return formatRelativeTime(date, Instant.now(), RelativeTimeStyle.NARROW, true);
    }

    /**
     * Formats a date relative to a reference instant.
     *
     * @param date    the target date
     * @param now     reference instant (defaults to Instant.now())
     * @param style   LONG, SHORT, or NARROW
     * @param numeric if true, always show numeric value (e.g. "1 day ago" vs "yesterday")
     */
    public static String formatRelativeTime(
            Instant date, Instant now, RelativeTimeStyle style, boolean numeric) {
        if (now == null) now = Instant.now();
        long diffMs      = date.toEpochMilli() - now.toEpochMilli();
        long diffSeconds = diffMs / 1000; // truncate toward zero (same as Math.trunc)

        record Interval(String unit, long seconds, String shortUnit) {}
        Interval[] intervals = {
            new Interval("year",   31_536_000, "y"),
            new Interval("month",   2_592_000, "mo"),
            new Interval("week",      604_800, "w"),
            new Interval("day",        86_400, "d"),
            new Interval("hour",        3_600, "h"),
            new Interval("minute",         60, "m"),
            new Interval("second",          1, "s"),
        };

        for (Interval iv : intervals) {
            if (Math.abs(diffSeconds) >= iv.seconds()) {
                long value = diffSeconds / iv.seconds(); // truncate
                if (style == RelativeTimeStyle.NARROW) {
                    return diffSeconds < 0
                            ? Math.abs(value) + iv.shortUnit() + " ago"
                            : "in " + value + iv.shortUnit();
                }
                // Simplified long/short: delegate to a descriptive string
                return diffSeconds < 0
                        ? Math.abs(value) + " " + iv.unit() + (Math.abs(value) == 1 ? "" : "s") + " ago"
                        : "in " + value + " " + iv.unit() + (value == 1 ? "" : "s");
            }
        }

        // Less than 1 second
        if (style == RelativeTimeStyle.NARROW) {
            return diffSeconds <= 0 ? "0s ago" : "in 0s";
        }
        return numeric ? "0 seconds ago" : "just now";
    }

    /**
     * Formats a past date as "X units ago".
     *
     * Translated from formatRelativeTimeAgo() in format.ts
     */
    public static String formatRelativeTimeAgo(Instant date) {
        return formatRelativeTimeAgo(date, Instant.now(), RelativeTimeStyle.NARROW);
    }

    public static String formatRelativeTimeAgo(Instant date, Instant now, RelativeTimeStyle style) {
        if (now == null) now = Instant.now();
        if (date.isAfter(now)) {
            return formatRelativeTime(date, now, style, true);
        }
        return formatRelativeTime(date, now, style, true);
    }

    // ---------------------------------------------------------------------------
    // formatLogMetadata
    // ---------------------------------------------------------------------------

    /**
     * Log metadata record for use with formatLogMetadata.
     */
    public record LogMetadata(
            Instant modified,
            int messageCount,
            Long fileSize,        // nullable
            String gitBranch,     // nullable
            String tag,           // nullable
            String agentSetting,  // nullable
            Integer prNumber,     // nullable
            String prRepository   // nullable
    ) {}

    /**
     * Formats log metadata for display (time, size or message count, branch, tag, PR).
     *
     * Translated from formatLogMetadata() in format.ts
     */
    public static String formatLogMetadata(LogMetadata log) {
        String sizeOrCount = log.fileSize() != null
                ? formatFileSize(log.fileSize())
                : log.messageCount() + " messages";

        StringBuilder sb = new StringBuilder();
        sb.append(formatRelativeTimeAgo(log.modified(), Instant.now(), RelativeTimeStyle.SHORT));
        if (log.gitBranch() != null) sb.append(" · ").append(log.gitBranch());
        sb.append(" · ").append(sizeOrCount);
        if (log.tag() != null) sb.append(" · #").append(log.tag());
        if (log.agentSetting() != null) sb.append(" · @").append(log.agentSetting());
        if (log.prNumber() != null) {
            sb.append(" · ");
            if (log.prRepository() != null) {
                sb.append(log.prRepository()).append("#").append(log.prNumber());
            } else {
                sb.append("#").append(log.prNumber());
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------------
    // formatResetTime / formatResetText
    // ---------------------------------------------------------------------------

    /**
     * Formats a reset timestamp (Unix seconds) to a human-readable time string.
     *
     * Translated from formatResetTime() in format.ts
     *
     * @param timestampInSeconds Unix epoch seconds
     * @param showTimezone       append timezone abbreviation
     * @param showTime           include the time component for dates > 24h away
     * @return formatted string, or null if timestampInSeconds is 0
     */
    public static String formatResetTime(long timestampInSeconds, boolean showTimezone, boolean showTime) {
        if (timestampInSeconds == 0) return null;

        Instant resetInstant = Instant.ofEpochSecond(timestampInSeconds);
        Instant now = Instant.now();
        ZoneId zoneId = ZoneId.systemDefault();
        ZonedDateTime resetZdt = resetInstant.atZone(zoneId);

        double hoursUntilReset = (resetInstant.toEpochMilli() - now.toEpochMilli()) / 3_600_000.0;

        String formatted;
        if (hoursUntilReset > 24) {
            // Show date and time for resets more than a day away
            DateTimeFormatter fmt = showTime
                    ? DateTimeFormatter.ofPattern("MMM d, h:mma", Locale.US)
                    : DateTimeFormatter.ofPattern("MMM d", Locale.US);
            formatted = resetZdt.format(fmt).replace(" AM", "am").replace(" PM", "pm");
        } else {
            // Show just the time
            int minute = resetZdt.getMinute();
            DateTimeFormatter fmt = minute == 0
                    ? DateTimeFormatter.ofPattern("ha", Locale.US)
                    : DateTimeFormatter.ofPattern("h:mma", Locale.US);
            formatted = resetZdt.format(fmt).replace("AM", "am").replace("PM", "pm");
        }

        if (showTimezone) {
            formatted = formatted + " (" + zoneId.getDisplayName(
                    java.time.format.TextStyle.SHORT, Locale.US) + ")";
        }
        return formatted;
    }

    /**
     * Formats a reset ISO-8601 timestamp string to a human-readable time string.
     *
     * Translated from formatResetText() in format.ts
     */
    public static String formatResetText(String resetsAt, boolean showTimezone, boolean showTime) {
        Instant dt = Instant.parse(resetsAt);
        return formatResetTime(dt.getEpochSecond(), showTimezone, showTime);
    }

    // ---------------------------------------------------------------------------
    // formatBriefTimestamp
    // ---------------------------------------------------------------------------

    /**
     * Format an ISO timestamp for the brief/chat message label line.
     *
     * <p>Display scales with age (like a messaging app):
     * <ul>
     *   <li>same day:      "1:30 PM" or "13:30" (locale-dependent)</li>
     *   <li>within 6 days: "Sunday, 4:15 PM" (locale-dependent)</li>
     *   <li>older:         "Sunday, Feb 20, 4:30 PM" (locale-dependent)</li>
     * </ul>
     *
     * <p>Respects POSIX locale env vars (LC_ALL &gt; LC_TIME &gt; LANG) for time
     * format (12h/24h), weekday names, month names, and overall structure.
     *
     * Translated from {@code formatBriefTimestamp()} in formatBriefTimestamp.ts.
     *
     * @param isoString ISO-8601 date-time string
     * @param now       reference instant (pass {@code null} to use current time)
     * @return formatted string, or empty string if {@code isoString} is invalid
     */
    public static String formatBriefTimestamp(String isoString, java.time.Instant now) {
        if (isoString == null || isoString.isBlank()) return "";
        java.time.Instant d;
        try {
            d = java.time.Instant.parse(isoString);
        } catch (java.time.format.DateTimeParseException e) {
            return "";
        }

        if (now == null) now = java.time.Instant.now();

        Locale locale = getBriefTimestampLocale();
        ZoneId zone = ZoneId.systemDefault();
        java.time.ZonedDateTime zdtNow = now.atZone(zone);
        java.time.ZonedDateTime zdtD   = d.atZone(zone);

        long nowDay = startOfDayEpochMs(zdtNow);
        long dDay   = startOfDayEpochMs(zdtD);
        long dayDiff = nowDay - dDay;
        long daysAgo = Math.round(dayDiff / 86_400_000.0);

        if (daysAgo == 0) {
            // Same day: show time only
            return zdtD.format(java.time.format.DateTimeFormatter
                    .ofLocalizedTime(java.time.format.FormatStyle.SHORT)
                    .withLocale(locale));
        }

        if (daysAgo > 0 && daysAgo < 7) {
            // Within a week: "Sunday, 4:15 PM"
            return zdtD.format(java.time.format.DateTimeFormatter
                    .ofPattern("EEEE, " + shortTimePattern(locale), locale));
        }

        // Older: "Sunday, Feb 20, 4:30 PM"
        return zdtD.format(java.time.format.DateTimeFormatter
                .ofPattern("EEEE, MMM d, " + shortTimePattern(locale), locale));
    }

    /**
     * Format an ISO timestamp using the current system time as reference.
     *
     * @param isoString ISO-8601 date-time string
     */
    public static String formatBriefTimestamp(String isoString) {
        return formatBriefTimestamp(isoString, null);
    }

    /**
     * Derive a locale from POSIX env vars (LC_ALL &gt; LC_TIME &gt; LANG).
     * Falls back to {@link Locale#getDefault()} for unrecognized values.
     * Translated from {@code getLocale()} in formatBriefTimestamp.ts.
     */
    private static Locale getBriefTimestampLocale() {
        String raw = System.getenv("LC_ALL");
        if (raw == null || raw.isBlank()) raw = System.getenv("LC_TIME");
        if (raw == null || raw.isBlank()) raw = System.getenv("LANG");
        if (raw == null || raw.isBlank() || "C".equals(raw) || "POSIX".equals(raw)) {
            return Locale.getDefault();
        }

        // Strip codeset (.UTF-8) and modifier (@euro), replace _ with -
        String base = raw.split("\\.", 2)[0].split("@", 2)[0].replace('_', '-');
        if (base.isBlank()) return Locale.getDefault();

        try {
            Locale l = Locale.forLanguageTag(base);
            // Validate by constructing a DateTimeFormatter
            java.time.format.DateTimeFormatter.ofLocalizedTime(
                    java.time.format.FormatStyle.SHORT).withLocale(l);
            return l;
        } catch (Exception e) {
            return Locale.getDefault();
        }
    }

    /** Return a short time pattern string appropriate for the locale (approx). */
    private static String shortTimePattern(Locale locale) {
        // Use a locale-aware approach: extract the time portion from a SHORT pattern.
        // For simplicity we approximate with h:mm a for 12h locales, HH:mm for 24h.
        java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter
                .ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(locale);
        // Detect 12h vs 24h by formatting a known hour
        java.time.LocalTime testTime = java.time.LocalTime.of(13, 0);
        String formatted = testTime.format(dtf);
        // If formatted contains "1" (not "13") then it is 12h
        return formatted.contains("1:") && !formatted.contains("13") ? "h:mm a" : "HH:mm";
    }

    /** Returns epoch ms of the start of the day for the given ZonedDateTime. */
    private static long startOfDayEpochMs(java.time.ZonedDateTime zdt) {
        return zdt.toLocalDate().atStartOfDay(zdt.getZone()).toInstant().toEpochMilli();
    }

    private FormatUtils() {}
}
