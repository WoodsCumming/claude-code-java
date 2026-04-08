package com.anthropic.claudecode.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * GitHub-style activity heatmap generator for the terminal.
 * Translated from src/utils/heatmap.ts
 */
public class HeatmapUtils {

    // =========================================================================
    // Types
    // =========================================================================

    /**
     * Daily activity entry.
     * Mirrors DailyActivity from stats.ts
     */
    public record DailyActivity(String date, int messageCount) {}

    /**
     * Heatmap rendering options.
     * Translated from HeatmapOptions in heatmap.ts
     */
    public record HeatmapOptions(int terminalWidth, boolean showMonthLabels) {
        public HeatmapOptions() { this(80, true); }
        public HeatmapOptions(int terminalWidth) { this(terminalWidth, true); }
    }

    private record Percentiles(int p25, int p50, int p75) {}

    // =========================================================================
    // ANSI colour constants
    // Claude orange: #da7756 ≈ ANSI 256-colour 173
    // =========================================================================

    // Use ANSI 256-colour escape sequences — no external dependency needed.
    private static final String RESET = "\u001B[0m";
    private static final String GREY  = "\u001B[90m";

    /** Approximate #da7756 via ANSI 256 palette colour 173 */
    private static String claudeOrange(String text) {
        return "\u001B[38;5;173m" + text + RESET;
    }

    private static String grey(String text) {
        return GREY + text + RESET;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Generates a GitHub-style activity heatmap for the terminal.
     * Translated from generateHeatmap() in heatmap.ts
     *
     * @param dailyActivity List of daily activity entries
     * @param options       Rendering options
     * @return Multi-line string ready to print to a terminal
     */
    public static String generateHeatmap(List<DailyActivity> dailyActivity, HeatmapOptions options) {
        if (options == null) options = new HeatmapOptions();

        int terminalWidth = options.terminalWidth();
        boolean showMonthLabels = options.showMonthLabels();

        // Day labels take 4 chars ("Mon "); cap at 52 weeks like GitHub
        int dayLabelWidth = 4;
        int availableWidth = terminalWidth - dayLabelWidth;
        int width = Math.min(52, Math.max(10, availableWidth));

        // Build activity map by date
        Map<String, DailyActivity> activityMap = new HashMap<>();
        for (DailyActivity a : dailyActivity) {
            activityMap.put(a.date(), a);
        }

        Percentiles percentiles = calculatePercentiles(dailyActivity);

        // Calculate date range: end at today, go back (width) weeks
        LocalDate today = LocalDate.now();

        // Find the Sunday of the current week
        int todayDow = today.getDayOfWeek().getValue() % 7; // Sun=0…Sat=6
        LocalDate currentWeekStart = today.minusDays(todayDow);

        // Start date: (width - 1) weeks before currentWeekStart
        LocalDate startDate = currentWeekStart.minusWeeks(width - 1);

        // Generate grid: 7 rows (days), width columns (weeks)
        String[][] grid = new String[7][width];
        for (String[] row : grid) Arrays.fill(row, " ");

        List<int[]> monthStarts = new ArrayList<>(); // {month, week}
        int lastMonth = -1;

        LocalDate currentDate = startDate;
        for (int week = 0; week < width; week++) {
            for (int day = 0; day < 7; day++) {
                if (currentDate.isAfter(today)) {
                    grid[day][week] = " ";
                    currentDate = currentDate.plusDays(1);
                    continue;
                }

                String dateStr = currentDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
                DailyActivity activity = activityMap.get(dateStr);

                if (day == 0) {
                    int month = currentDate.getMonthValue() - 1; // 0-based
                    if (month != lastMonth) {
                        monthStarts.add(new int[]{month, week});
                        lastMonth = month;
                    }
                }

                int intensity = getIntensity(activity != null ? activity.messageCount() : 0, percentiles);
                grid[day][week] = getHeatmapChar(intensity);
                currentDate = currentDate.plusDays(1);
            }
        }

        // Build output
        List<String> lines = new ArrayList<>();

        // Month labels
        if (showMonthLabels) {
            String[] monthNames = {
                "Jan", "Feb", "Mar", "Apr", "May", "Jun",
                "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
            };
            int uniqueCount = Math.max(monthStarts.size(), 1);
            int labelWidth = Math.max(1, width / uniqueCount);

            StringBuilder monthLabel = new StringBuilder("    ");
            for (int[] ms : monthStarts) {
                String name = monthNames[ms[0]];
                monthLabel.append(padEnd(name, labelWidth));
            }
            lines.add(monthLabel.toString());
        }

        // Day rows
        String[] dayLabels = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
        for (int day = 0; day < 7; day++) {
            // Only show Mon (1), Wed (3), Fri (5) labels
            String label = (day == 1 || day == 3 || day == 5)
                ? padEnd(dayLabels[day], 3) : "   ";
            StringBuilder row = new StringBuilder(label).append(" ");
            for (int week = 0; week < width; week++) {
                row.append(grid[day][week]);
            }
            lines.add(row.toString());
        }

        // Legend
        lines.add("");
        lines.add("    Less " +
            claudeOrange("░") + " " +
            claudeOrange("▒") + " " +
            claudeOrange("▓") + " " +
            claudeOrange("█") +
            " More");

        return String.join("\n", lines);
    }

    /**
     * Convenience overload using default options.
     */
    public static String generateHeatmap(List<DailyActivity> dailyActivity) {
        return generateHeatmap(dailyActivity, new HeatmapOptions());
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Pre-calculates 25th, 50th, and 75th percentiles of message counts.
     * Translated from calculatePercentiles() in heatmap.ts
     */
    private static Percentiles calculatePercentiles(List<DailyActivity> dailyActivity) {
        List<Integer> counts = new ArrayList<>();
        for (DailyActivity a : dailyActivity) {
            if (a.messageCount() > 0) counts.add(a.messageCount());
        }
        if (counts.isEmpty()) return null;

        counts.sort(Integer::compareTo);
        int n = counts.size();
        return new Percentiles(
            counts.get((int) Math.floor(n * 0.25)),
            counts.get((int) Math.floor(n * 0.50)),
            counts.get((int) Math.floor(n * 0.75))
        );
    }

    /**
     * Map a message count to an intensity level 0–4.
     * Translated from getIntensity() in heatmap.ts
     */
    private static int getIntensity(int messageCount, Percentiles percentiles) {
        if (messageCount == 0 || percentiles == null) return 0;
        if (messageCount >= percentiles.p75()) return 4;
        if (messageCount >= percentiles.p50()) return 3;
        if (messageCount >= percentiles.p25()) return 2;
        return 1;
    }

    /**
     * Map an intensity level to its heatmap character.
     * Translated from getHeatmapChar() in heatmap.ts
     */
    private static String getHeatmapChar(int intensity) {
        return switch (intensity) {
            case 0 -> grey("·");
            case 1 -> claudeOrange("░");
            case 2 -> claudeOrange("▒");
            case 3 -> claudeOrange("▓");
            case 4 -> claudeOrange("█");
            default -> grey("·");
        };
    }

    private static String padEnd(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    private HeatmapUtils() {}
}
