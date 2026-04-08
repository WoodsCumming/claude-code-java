package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Stats cache service for persisting aggregated usage statistics.
 * Translated from src/utils/statsCache.ts
 *
 * Manages a bounded, versioned cache of daily activity and token usage.
 * Uses atomic write (temp file + rename) to prevent cache corruption.
 */
@Slf4j
@Service
public class StatsCacheService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StatsCacheService.class);


    public static final int STATS_CACHE_VERSION = 3;
    private static final int MIN_MIGRATABLE_VERSION = 1;
    private static final String STATS_CACHE_FILENAME = "stats-cache.json";

    private final ObjectMapper objectMapper;
    private final ReentrantLock lock = new ReentrantLock();

    @Autowired
    public StatsCacheService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Data types
    // -------------------------------------------------------------------------

    /**
     * Persisted stats cache stored on disk.
     * Translated from PersistedStatsCache in statsCache.ts
     */
    @Data
    @lombok.Builder
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PersistedStatsCache {
        private int version;
        /** Last date fully computed (YYYY-MM-DD). Stats up to this date are complete. */
        private String lastComputedDate;
        private List<StatsService.DailyActivity> dailyActivity;
        private List<StatsService.DailyModelTokens> dailyModelTokens;
        /** Model usage aggregated by model name. */
        private Map<String, StatsService.ModelUsage> modelUsage;
        private int totalSessions;
        private int totalMessages;
        private StatsService.SessionStats longestSession;
        /** First session date ever recorded. */
        private String firstSessionDate;
        /** Hour counts for peak hour calculation (bounded to 24 entries). */
        private Map<Integer, Integer> hourCounts;
        /** Speculation time saved across all sessions. */
        private long totalSpeculationTimeSavedMs;
        /** Shot distribution: shot count → number of sessions (ant-only). */
        private Map<Integer, Integer> shotDistribution;
    
        public String getLastComputedDate() { return lastComputedDate; }
    
        public int getVersion() { return version; }
    
        public int getTotalMessages() { return totalMessages; }
    
        public int getTotalSessions() { return totalSessions; }
    }

    // -------------------------------------------------------------------------
    // Lock
    // -------------------------------------------------------------------------

    /**
     * Execute a callable while holding the stats cache lock (one at a time).
     * Translated from withStatsCacheLock() in statsCache.ts
     */
    public <T> T withStatsCacheLock(java.util.concurrent.Callable<T> fn) throws Exception {
        lock.lock();
        try {
            return fn.call();
        } finally {
            lock.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Path
    // -------------------------------------------------------------------------

    /**
     * Translated from getStatsCachePath() in statsCache.ts
     */
    public String getStatsCachePath() {
        return EnvUtils.getClaudeConfigHomeDir() + "/" + STATS_CACHE_FILENAME;
    }

    // -------------------------------------------------------------------------
    // Empty cache factory
    // -------------------------------------------------------------------------

    private PersistedStatsCache getEmptyCache() {
        return PersistedStatsCache.builder()
                .version(STATS_CACHE_VERSION)
                .lastComputedDate(null)
                .dailyActivity(new ArrayList<>())
                .dailyModelTokens(new ArrayList<>())
                .modelUsage(new HashMap<>())
                .totalSessions(0)
                .totalMessages(0)
                .longestSession(null)
                .firstSessionDate(null)
                .hourCounts(new HashMap<>())
                .totalSpeculationTimeSavedMs(0)
                .shotDistribution(new HashMap<>())
                .build();
    }

    // -------------------------------------------------------------------------
    // Migration
    // -------------------------------------------------------------------------

    /**
     * Migrate an older cache to the current schema.
     * Returns null if the version is unknown or too old to migrate.
     * Translated from migrateStatsCache() in statsCache.ts
     */
    private PersistedStatsCache migrateStatsCache(PersistedStatsCache parsed) {
        if (parsed.getVersion() < MIN_MIGRATABLE_VERSION || parsed.getVersion() > STATS_CACHE_VERSION) {
            return null;
        }
        if (parsed.getDailyActivity() == null || parsed.getDailyModelTokens() == null) {
            return null;
        }
        return PersistedStatsCache.builder()
                .version(STATS_CACHE_VERSION)
                .lastComputedDate(parsed.getLastComputedDate())
                .dailyActivity(parsed.getDailyActivity())
                .dailyModelTokens(parsed.getDailyModelTokens())
                .modelUsage(parsed.getModelUsage() != null ? parsed.getModelUsage() : new HashMap<>())
                .totalSessions(parsed.getTotalSessions())
                .totalMessages(parsed.getTotalMessages())
                .longestSession(parsed.getLongestSession())
                .firstSessionDate(parsed.getFirstSessionDate())
                .hourCounts(parsed.getHourCounts() != null ? parsed.getHourCounts() : new HashMap<>())
                .totalSpeculationTimeSavedMs(parsed.getTotalSpeculationTimeSavedMs())
                // Preserve null vs empty to allow SHOT_STATS recompute trigger
                .shotDistribution(parsed.getShotDistribution())
                .build();
    }

    // -------------------------------------------------------------------------
    // Load / Save
    // -------------------------------------------------------------------------

    /**
     * Load the stats cache from disk.
     * Returns an empty cache if the file doesn't exist or is invalid.
     * Translated from loadStatsCache() in statsCache.ts
     */
    public PersistedStatsCache loadStatsCache() {
        String path = getStatsCachePath();
        File file = new File(path);
        if (!file.exists()) {
            return getEmptyCache();
        }

        try {
            PersistedStatsCache parsed = objectMapper.readValue(file, PersistedStatsCache.class);

            if (parsed.getVersion() != STATS_CACHE_VERSION) {
                PersistedStatsCache migrated = migrateStatsCache(parsed);
                if (migrated == null) {
                    log.debug("Stats cache version {} not migratable (expected {}), returning empty cache",
                            parsed.getVersion(), STATS_CACHE_VERSION);
                    return getEmptyCache();
                }
                log.debug("Migrated stats cache from v{} to v{}", parsed.getVersion(), STATS_CACHE_VERSION);
                saveStatsCache(migrated);
                return migrated;
            }

            // Basic validation
            if (parsed.getDailyActivity() == null || parsed.getDailyModelTokens() == null) {
                log.debug("Stats cache has invalid structure, returning empty cache");
                return getEmptyCache();
            }

            return parsed;
        } catch (Exception e) {
            log.debug("Failed to load stats cache: {}", e.getMessage());
            return getEmptyCache();
        }
    }

    /**
     * Save the stats cache to disk atomically using a temp file + rename.
     * Translated from saveStatsCache() in statsCache.ts
     */
    public void saveStatsCache(PersistedStatsCache cache) {
        String cachePath = getStatsCachePath();
        Path tempPath = Path.of(cachePath + "." + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + ".tmp");

        try {
            // Ensure directory exists
            Path dir = Path.of(cachePath).getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }

            // Write pretty JSON to temp file
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempPath.toFile(), cache);

            // Atomic rename
            Files.move(tempPath, Path.of(cachePath), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.debug("Stats cache saved successfully (lastComputedDate: {})", cache.getLastComputedDate());
        } catch (IOException e) {
            log.error("Failed to save stats cache: {}", e.getMessage());
            try {
                Files.deleteIfExists(tempPath);
            } catch (IOException ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Merge
    // -------------------------------------------------------------------------

    /**
     * Merge new stats into an existing cache.
     * Used when incrementally adding new days to the cache.
     * Translated from mergeCacheWithNewStats() in statsCache.ts
     */
    public PersistedStatsCache mergeCacheWithNewStats(
            PersistedStatsCache existingCache,
            StatsService.ProcessedStats newStats,
            String newLastComputedDate) {

        // Merge daily activity by date
        Map<String, StatsService.DailyActivity> dailyActivityMap = new LinkedHashMap<>();
        for (StatsService.DailyActivity day : existingCache.getDailyActivity()) {
            dailyActivityMap.put(day.getDate(), day.toBuilder().build());
        }
        for (StatsService.DailyActivity day : newStats.getDailyActivity()) {
            StatsService.DailyActivity existing = dailyActivityMap.get(day.getDate());
            if (existing != null) {
                existing.setMessageCount(existing.getMessageCount() + day.getMessageCount());
                existing.setSessionCount(existing.getSessionCount() + day.getSessionCount());
                existing.setToolCallCount(existing.getToolCallCount() + day.getToolCallCount());
            } else {
                dailyActivityMap.put(day.getDate(), day.toBuilder().build());
            }
        }

        // Merge daily model tokens by date
        Map<String, Map<String, Long>> dailyModelTokensMap = new LinkedHashMap<>();
        for (StatsService.DailyModelTokens day : existingCache.getDailyModelTokens()) {
            dailyModelTokensMap.put(day.getDate(), new HashMap<>(day.getTokensByModel()));
        }
        for (StatsService.DailyModelTokens day : newStats.getDailyModelTokens()) {
            Map<String, Long> existing = dailyModelTokensMap.get(day.getDate());
            if (existing != null) {
                for (Map.Entry<String, Long> e : day.getTokensByModel().entrySet()) {
                    existing.merge(e.getKey(), e.getValue(), Long::sum);
                }
            } else {
                dailyModelTokensMap.put(day.getDate(), new HashMap<>(day.getTokensByModel()));
            }
        }

        // Merge model usage
        Map<String, StatsService.ModelUsage> modelUsage = new HashMap<>(existingCache.getModelUsage());
        for (Map.Entry<String, StatsService.ModelUsage> entry : newStats.getModelUsage().entrySet()) {
            String model = entry.getKey();
            StatsService.ModelUsage usage = entry.getValue();
            if (modelUsage.containsKey(model)) {
                StatsService.ModelUsage existing = modelUsage.get(model);
                modelUsage.put(model, StatsService.ModelUsage.builder()
                        .inputTokens(existing.getInputTokens() + usage.getInputTokens())
                        .outputTokens(existing.getOutputTokens() + usage.getOutputTokens())
                        .cacheReadInputTokens(existing.getCacheReadInputTokens() + usage.getCacheReadInputTokens())
                        .cacheCreationInputTokens(existing.getCacheCreationInputTokens() + usage.getCacheCreationInputTokens())
                        .webSearchRequests(existing.getWebSearchRequests() + usage.getWebSearchRequests())
                        .costUSD(existing.getCostUSD() + usage.getCostUSD())
                        .contextWindow(Math.max(existing.getContextWindow(), usage.getContextWindow()))
                        .maxOutputTokens(Math.max(existing.getMaxOutputTokens(), usage.getMaxOutputTokens()))
                        .build());
            } else {
                modelUsage.put(model, usage.toBuilder().build());
            }
        }

        // Merge hour counts
        Map<Integer, Integer> hourCounts = new HashMap<>(existingCache.getHourCounts());
        for (Map.Entry<Integer, Integer> e : newStats.getHourCounts().entrySet()) {
            hourCounts.merge(e.getKey(), e.getValue(), Integer::sum);
        }

        // Session aggregates
        int totalSessions = existingCache.getTotalSessions() + newStats.getSessionStats().size();
        int totalMessages = existingCache.getTotalMessages() +
                newStats.getSessionStats().stream().mapToInt(StatsService.SessionStats::getMessageCount).sum();

        // Longest session
        StatsService.SessionStats longestSession = existingCache.getLongestSession();
        for (StatsService.SessionStats s : newStats.getSessionStats()) {
            if (longestSession == null || s.getDuration() > longestSession.getDuration()) {
                longestSession = s;
            }
        }

        // First session date
        String firstSessionDate = existingCache.getFirstSessionDate();
        for (StatsService.SessionStats s : newStats.getSessionStats()) {
            if (firstSessionDate == null || s.getTimestamp().compareTo(firstSessionDate) < 0) {
                firstSessionDate = s.getTimestamp();
            }
        }

        // Sort daily activity and model tokens by date
        List<StatsService.DailyActivity> sortedDailyActivity = new ArrayList<>(dailyActivityMap.values());
        sortedDailyActivity.sort(Comparator.comparing(StatsService.DailyActivity::getDate));

        List<StatsService.DailyModelTokens> sortedDailyModelTokens = new ArrayList<>();
        for (Map.Entry<String, Map<String, Long>> e : dailyModelTokensMap.entrySet()) {
            sortedDailyModelTokens.add(StatsService.DailyModelTokens.builder()
                    .date(e.getKey())
                    .tokensByModel(e.getValue())
                    .build());
        }
        sortedDailyModelTokens.sort(Comparator.comparing(StatsService.DailyModelTokens::getDate));

        return PersistedStatsCache.builder()
                .version(STATS_CACHE_VERSION)
                .lastComputedDate(newLastComputedDate)
                .dailyActivity(sortedDailyActivity)
                .dailyModelTokens(sortedDailyModelTokens)
                .modelUsage(modelUsage)
                .totalSessions(totalSessions)
                .totalMessages(totalMessages)
                .longestSession(longestSession)
                .firstSessionDate(firstSessionDate)
                .hourCounts(hourCounts)
                .totalSpeculationTimeSavedMs(
                        existingCache.getTotalSpeculationTimeSavedMs() + newStats.getTotalSpeculationTimeSavedMs())
                .build();
    }

    // -------------------------------------------------------------------------
    // Date utilities
    // -------------------------------------------------------------------------

    /**
     * Extract the date portion (YYYY-MM-DD) from a Date object.
     * Translated from toDateString() in statsCache.ts
     */
    public static String toDateString(java.util.Date date) {
        return date.toInstant()
                .atZone(java.time.ZoneOffset.UTC)
                .toLocalDate()
                .format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /**
     * Get today's date string in YYYY-MM-DD format.
     * Translated from getTodayDateString() in statsCache.ts
     */
    public static String getTodayDateString() {
        return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /**
     * Get yesterday's date string in YYYY-MM-DD format.
     * Translated from getYesterdayDateString() in statsCache.ts
     */
    public static String getYesterdayDateString() {
        return LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /**
     * Check if date1 is strictly before date2 (both YYYY-MM-DD).
     * Translated from isDateBefore() in statsCache.ts
     */
    public static boolean isDateBefore(String date1, String date2) {
        return date1.compareTo(date2) < 0;
    }
}
