package com.anthropic.claudecode.util;

import java.text.BreakIterator;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Internationalization utilities with lazy-initialized, cached instances.
 *
 * <p>Mirrors the TypeScript caching strategy from {@code src/utils/intl.ts}:
 * expensive objects (segmenters, formatters) are created at most once per
 * process lifetime and stored in static fields / maps.</p>
 *
 * Translated from src/utils/intl.ts
 */
public final class IntlUtils {

    // =========================================================================
    // Grapheme / word segmenters
    // Translated from getGraphemeSegmenter() / getWordSegmenter() in intl.ts
    //
    // Java's Intl.Segmenter equivalent is java.text.BreakIterator.
    // BreakIterator is NOT thread-safe, so we use ThreadLocal caches to give
    // each thread its own instance — mirroring the single-threaded JS model.
    // =========================================================================

    private static final ThreadLocal<BreakIterator> GRAPHEME_SEGMENTER =
            ThreadLocal.withInitial(BreakIterator::getCharacterInstance);

    private static final ThreadLocal<BreakIterator> WORD_SEGMENTER =
            ThreadLocal.withInitial(BreakIterator::getWordInstance);

    /**
     * Get the per-thread grapheme (character) break iterator.
     * Translated from {@code getGraphemeSegmenter()} in intl.ts
     */
    public static BreakIterator getGraphemeSegmenter() {
        return GRAPHEME_SEGMENTER.get();
    }

    /**
     * Get the per-thread word break iterator.
     * Translated from {@code getWordSegmenter()} in intl.ts
     */
    public static BreakIterator getWordSegmenter() {
        return WORD_SEGMENTER.get();
    }

    // =========================================================================
    // firstGrapheme / lastGrapheme
    // Translated from firstGrapheme() / lastGrapheme() in intl.ts
    // =========================================================================

    /**
     * Extract the first grapheme cluster from a string.
     * Returns {@code ""} for null or empty strings.
     * Translated from {@code firstGrapheme()} in intl.ts
     */
    public static String firstGrapheme(String text) {
        if (text == null || text.isEmpty()) return "";
        BreakIterator bi = getGraphemeSegmenter();
        bi.setText(text);
        int start = bi.first();
        int end = bi.next();
        if (end == BreakIterator.DONE) return "";
        return text.substring(start, end);
    }

    /**
     * Extract the last grapheme cluster from a string.
     * Returns {@code ""} for null or empty strings.
     * Translated from {@code lastGrapheme()} in intl.ts
     */
    public static String lastGrapheme(String text) {
        if (text == null || text.isEmpty()) return "";
        BreakIterator bi = getGraphemeSegmenter();
        bi.setText(text);
        int end = bi.last();
        int start = bi.previous();
        if (start == BreakIterator.DONE) return "";
        return text.substring(start, end);
    }

    // =========================================================================
    // RelativeTimeFormat cache (keyed by "style:numeric")
    // Translated from getRelativeTimeFormat() in intl.ts
    // =========================================================================

    /** Relative-time format style. */
    public enum RelativeTimeStyle { LONG, SHORT, NARROW }

    /** Relative-time numeric option. */
    public enum RelativeTimeNumeric { ALWAYS, AUTO }

    private static final Map<String, Object> RTF_CACHE = new ConcurrentHashMap<>();

    /**
     * Get (or create) a cached relative-time formatter.
     *
     * <p>In Java there is no direct {@code Intl.RelativeTimeFormat} equivalent.
     * The method returns a formatter key string that callers can use with
     * {@link #formatRelativeTime(long)} for English relative-time strings.</p>
     *
     * Translated from {@code getRelativeTimeFormat()} in intl.ts
     *
     * @param style   long / short / narrow
     * @param numeric always / auto
     * @return cache key (style:numeric) that can be passed to a custom formatter
     */
    public static String getRelativeTimeFormatKey(RelativeTimeStyle style, RelativeTimeNumeric numeric) {
        return style.name().toLowerCase() + ":" + numeric.name().toLowerCase();
    }

    // =========================================================================
    // Timezone — constant for the process lifetime
    // Translated from getTimeZone() in intl.ts
    // =========================================================================

    private static volatile String cachedTimeZone = null;

    /**
     * Get the system default timezone ID (e.g. {@code "America/New_York"}).
     * Computed once and cached for the process lifetime.
     * Translated from {@code getTimeZone()} in intl.ts
     */
    public static String getTimeZone() {
        if (cachedTimeZone == null) {
            cachedTimeZone = ZoneId.systemDefault().getId();
        }
        return cachedTimeZone;
    }

    // =========================================================================
    // System locale language subtag
    // Translated from getSystemLocaleLanguage() in intl.ts
    //
    // null  = not yet computed
    // ""    = computed but unavailable (stripped-ICU equivalent)
    // =========================================================================

    private static volatile String cachedSystemLocaleLanguage = null;
    private static volatile boolean systemLocaleLanguageComputed = false;

    /**
     * Get the system locale language subtag (e.g. {@code "en"}, {@code "ja"}).
     *
     * <p>Returns {@code null} when the language cannot be determined (mirrors the
     * TypeScript {@code undefined} sentinel for a stripped-ICU environment).</p>
     *
     * Computed once and cached for the process lifetime.
     * Translated from {@code getSystemLocaleLanguage()} in intl.ts
     */
    public static String getSystemLocaleLanguage() {
        if (!systemLocaleLanguageComputed) {
            try {
                String lang = Locale.getDefault().getLanguage();
                cachedSystemLocaleLanguage = (lang != null && !lang.isEmpty()) ? lang : null;
            } catch (Exception e) {
                cachedSystemLocaleLanguage = null;
            }
            systemLocaleLanguageComputed = true;
        }
        return cachedSystemLocaleLanguage;
    }

    // =========================================================================
    // Convenience relative-time formatter
    // Complements getRelativeTimeFormatKey() with a concrete English formatter.
    // =========================================================================

    /**
     * Format a duration as a human-readable English relative-time string.
     *
     * <p>The output mirrors what {@code Intl.RelativeTimeFormat('en')} would
     * produce for simple positive durations expressed in the largest applicable
     * unit (days > hours > minutes > seconds).</p>
     *
     * @param durationMs elapsed milliseconds (positive = past)
     * @return e.g. {@code "3 days ago"}, {@code "2 hours ago"}, etc.
     */
    public static String formatRelativeTime(long durationMs) {
        long seconds = durationMs / 1000;
        if (seconds < 60) return seconds + " second" + (seconds == 1 ? "" : "s") + " ago";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " minute" + (minutes == 1 ? "" : "s") + " ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + " hour" + (hours == 1 ? "" : "s") + " ago";
        long days = hours / 24;
        return days + " day" + (days == 1 ? "" : "s") + " ago";
    }

    private IntlUtils() {}
}
