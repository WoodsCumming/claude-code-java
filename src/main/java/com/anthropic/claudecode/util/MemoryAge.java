package com.anthropic.claudecode.util;

/**
 * Memory age utilities.
 * Translated from src/memdir/memoryAge.ts
 */
public class MemoryAge {

    /**
     * Calculate days elapsed since mtime.
     * Translated from memoryAgeDays() in memoryAge.ts
     */
    public static int memoryAgeDays(long mtimeMs) {
        return Math.max(0, (int) Math.floor((System.currentTimeMillis() - mtimeMs) / 86_400_000.0));
    }

    /**
     * Get human-readable age string.
     * Translated from memoryAge() in memoryAge.ts
     */
    public static String memoryAge(long mtimeMs) {
        int d = memoryAgeDays(mtimeMs);
        if (d == 0) return "today";
        if (d == 1) return "yesterday";
        return d + " days ago";
    }

    /**
     * Get staleness caveat for memories older than 1 day.
     * Translated from memoryFreshnessText() in memoryAge.ts
     */
    public static String memoryFreshnessText(long mtimeMs) {
        int d = memoryAgeDays(mtimeMs);
        if (d <= 1) return "";
        return "This memory is " + d + " days old. "
            + "Memories are point-in-time observations, not live state — "
            + "claims about code behavior or file:line citations may be outdated. "
            + "Verify against current code before asserting as fact.";
    }

    /**
     * Per-memory staleness note wrapped in &lt;system-reminder&gt; tags.
     * Returns empty string for memories &lt;= 1 day old. Use this for callers that
     * don't add their own system-reminder wrapper (e.g. FileReadTool output).
     * Translated from memoryFreshnessNote() in memoryAge.ts
     */
    public static String memoryFreshnessNote(long mtimeMs) {
        String text = memoryFreshnessText(mtimeMs);
        if (text.isEmpty()) return "";
        return "<system-reminder>" + text + "</system-reminder>\n";
    }

    private MemoryAge() {}
}
