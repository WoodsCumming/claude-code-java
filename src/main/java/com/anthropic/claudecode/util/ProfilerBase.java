package com.anthropic.claudecode.util;

/**
 * Shared infrastructure for profiler modules (StartupProfiler, QueryProfiler, HeadlessProfiler).
 * All three use the same clock source and the same line format for detailed reports.
 *
 * Translated from src/utils/profilerBase.ts
 */
public class ProfilerBase {

    /**
     * Format a millisecond value to three decimal places.
     * Translated from formatMs() in profilerBase.ts
     *
     * @param ms The millisecond value to format
     * @return The formatted string, e.g. "123.456"
     */
    public static String formatMs(double ms) {
        return String.format("%.3f", ms);
    }

    /**
     * Render a single timeline line in the shared profiler report format:
     * {@code [+  total.ms] (+  delta.ms) name [extra] [| RSS: .., Heap: ..]}
     *
     * {@code totalPad}/{@code deltaPad} control the padStart width so callers can
     * align columns based on their expected magnitude
     * (startup uses 8/7, query uses 10/9).
     *
     * Translated from formatTimelineLine() in profilerBase.ts
     *
     * @param totalMs  Total elapsed milliseconds since the profiler epoch
     * @param deltaMs  Delta milliseconds since the previous event
     * @param name     Event name / label
     * @param memory   Optional memory snapshot; pass {@code null} to omit
     * @param totalPad Minimum width (left-padded) for the total column
     * @param deltaPad Minimum width (left-padded) for the delta column
     * @param extra    Optional extra text appended after the name (pass "" for none)
     * @return The formatted timeline line
     */
    public static String formatTimelineLine(
            double totalMs,
            double deltaMs,
            String name,
            MemorySnapshot memory,
            int totalPad,
            int deltaPad,
            String extra) {

        String totalStr = padStart(formatMs(totalMs), totalPad);
        String deltaStr = padStart(formatMs(deltaMs), deltaPad);

        String memInfo = "";
        if (memory != null) {
            memInfo = " | RSS: " + FormatUtils.formatFileSize(memory.rssBytes()) +
                      ", Heap: " + FormatUtils.formatFileSize(memory.heapUsedBytes());
        }

        String extraPart = (extra != null && !extra.isEmpty()) ? extra : "";
        return "[+" + totalStr + "ms] (+" + deltaStr + "ms) " + name + extraPart + memInfo;
    }

    /** Convenience overload with no extra text. */
    public static String formatTimelineLine(
            double totalMs,
            double deltaMs,
            String name,
            MemorySnapshot memory,
            int totalPad,
            int deltaPad) {
        return formatTimelineLine(totalMs, deltaMs, name, memory, totalPad, deltaPad, "");
    }

    /**
     * Get the current time in milliseconds with sub-millisecond precision,
     * using {@link System#nanoTime()} as the backing clock.
     * Mirrors the {@code performance.now()} semantic from profilerBase.ts
     * (same process-wide singleton behaviour).
     */
    public static double now() {
        return System.nanoTime() / 1_000_000.0;
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /**
     * Snapshot of JVM memory at a point in time.
     * Mirrors the {@code NodeJS.MemoryUsage} shape used by profilerBase.ts
     * ({@code rss}, {@code heapUsed}).
     */
    public record MemorySnapshot(long rssBytes, long heapUsedBytes) {

        /**
         * Capture the current JVM heap usage.
         * Note: Java does not expose RSS directly without JVM-specific APIs;
         * we approximate with total-memory minus free-memory.
         */
        public static MemorySnapshot capture() {
            Runtime rt = Runtime.getRuntime();
            long heapUsed = rt.totalMemory() - rt.freeMemory();
            // RSS approximation: total committed heap
            long rss = rt.totalMemory();
            return new MemorySnapshot(rss, heapUsed);
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /** Left-pad a string to at least {@code width} characters with spaces. */
    private static String padStart(String s, int width) {
        if (s.length() >= width) return s;
        return " ".repeat(width - s.length()) + s;
    }

    private ProfilerBase() {}
}
