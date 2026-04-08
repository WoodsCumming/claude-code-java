package com.anthropic.claudecode.service;

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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Statistics service for tracking Claude Code usage across all sessions.
 * Translated from src/utils/stats.ts
 *
 * Tracks usage statistics like sessions, messages, tool calls, model tokens,
 * streaks, and peak activity. Uses a disk cache for efficient incremental updates.
 */
@Slf4j
@Service
public class StatsService {



    private static final int BATCH_SIZE = 20;
    private static final Set<String> TRANSCRIPT_MESSAGE_TYPES = Set.of(
            "user", "assistant", "attachment", "system", "progress");
    private static final String SYNTHETIC_MODEL = "__synthetic__";

    private final ObjectMapper objectMapper;
    private final StatsCacheService statsCacheService;
    private final SessionStorageService sessionStorageService;

    @Autowired
    public StatsService(ObjectMapper objectMapper,
                        StatsCacheService statsCacheService,
                        SessionStorageService sessionStorageService) {
        this.objectMapper = objectMapper;
        this.statsCacheService = statsCacheService;
        this.sessionStorageService = sessionStorageService;
    }

    // -------------------------------------------------------------------------
    // Data models (translated from TypeScript types)
    // -------------------------------------------------------------------------

    /**
     * Translated from DailyActivity in stats.ts
     */
    @Data
    @Builder(toBuilder = true)
@lombok.NoArgsConstructor
@AllArgsConstructor
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DailyActivity {
        private String date;
        private int messageCount;
        private int sessionCount;
        private int toolCallCount;
    }

    /**
     * Translated from DailyModelTokens in stats.ts
     */
    @Data
    @Builder(toBuilder = true)
@lombok.NoArgsConstructor
@AllArgsConstructor
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DailyModelTokens {
        private String date;
        private Map<String, Long> tokensByModel;
    }

    /**
     * Translated from StreakInfo in stats.ts
     */
    @Data
    @lombok.Builder
    
    public static class StreakInfo {
        private int currentStreak;
        private int longestStreak;
        private String currentStreakStart;
        private String longestStreakStart;
        private String longestStreakEnd;
    }

    /**
     * Translated from SessionStats in stats.ts
     */
    @Data
    @Builder(toBuilder = true)
@lombok.NoArgsConstructor
@AllArgsConstructor
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SessionStats {
        private String sessionId;
        private long duration;
        private int messageCount;
        private String timestamp;
    }

    /**
     * Translated from ModelUsage in stats.ts / agentSdkTypes.ts
     */
    @Data
    @Builder(toBuilder = true)
@lombok.NoArgsConstructor
@AllArgsConstructor
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelUsage {
        private long inputTokens;
        private long outputTokens;
        private long cacheReadInputTokens;
        private long cacheCreationInputTokens;
        private int webSearchRequests;
        private double costUSD;
        private int contextWindow;
        private int maxOutputTokens;
    }

    /**
     * Translated from ClaudeCodeStats in stats.ts
     */
    @Data
    @lombok.Builder
    
    public static class ClaudeCodeStats {
        private int totalSessions;
        private int totalMessages;
        private int totalDays;
        private int activeDays;
        private StreakInfo streaks;
        private List<DailyActivity> dailyActivity;
        private List<DailyModelTokens> dailyModelTokens;
        private SessionStats longestSession;
        private Map<String, ModelUsage> modelUsage;
        private String firstSessionDate;
        private String lastSessionDate;
        private String peakActivityDay;
        private Integer peakActivityHour;
        private long totalSpeculationTimeSavedMs;
        private Map<Integer, Integer> shotDistribution;
        private Integer oneShotRate;
    }

    /**
     * Intermediate stats produced by processing session files.
     * Translated from ProcessedStats in stats.ts
     */
    @Data
    @lombok.Builder
    
    public static class ProcessedStats {
        private List<DailyActivity> dailyActivity;
        private List<DailyModelTokens> dailyModelTokens;
        private Map<String, ModelUsage> modelUsage;
        private List<SessionStats> sessionStats;
        private Map<Integer, Integer> hourCounts;
        private int totalMessages;
        private long totalSpeculationTimeSavedMs;
        private Map<Integer, Integer> shotDistribution;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Aggregates stats from all Claude Code sessions across all projects.
     * Uses a disk cache to avoid reprocessing historical data.
     * Translated from aggregateClaudeCodeStats() in stats.ts
     */
    public CompletableFuture<ClaudeCodeStats> aggregateClaudeCodeStats() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<String> allSessionFiles = getAllSessionFiles();
                if (allSessionFiles.isEmpty()) {
                    return getEmptyStats();
                }

                StatsCacheService.PersistedStatsCache updatedCache =
                        statsCacheService.withStatsCacheLock(() -> {
                            StatsCacheService.PersistedStatsCache cache =
                                    statsCacheService.loadStatsCache();
                            String yesterday = StatsCacheService.getYesterdayDateString();
                            StatsCacheService.PersistedStatsCache result = cache;

                            if (cache.getLastComputedDate() == null) {
                                log.debug("Stats cache empty, processing all historical data");
                                ProcessedStats historicalStats = processSessionFiles(
                                        allSessionFiles,
                                        null, yesterday);
                                if (!historicalStats.getSessionStats().isEmpty()
                                        || !historicalStats.getDailyActivity().isEmpty()) {
                                    result = statsCacheService.mergeCacheWithNewStats(
                                            cache, historicalStats, yesterday);
                                    statsCacheService.saveStatsCache(result);
                                }
                            } else if (StatsCacheService.isDateBefore(
                                    cache.getLastComputedDate(), yesterday)) {
                                String nextDay = getNextDay(cache.getLastComputedDate());
                                log.debug("Stats cache stale ({}), processing {} to {}",
                                        cache.getLastComputedDate(), nextDay, yesterday);
                                ProcessedStats newStats = processSessionFiles(
                                        allSessionFiles, nextDay, yesterday);
                                if (!newStats.getSessionStats().isEmpty()
                                        || !newStats.getDailyActivity().isEmpty()) {
                                    result = statsCacheService.mergeCacheWithNewStats(
                                            cache, newStats, yesterday);
                                    statsCacheService.saveStatsCache(result);
                                } else {
                                    result = buildCacheWithNewDate(cache, yesterday);
                                    statsCacheService.saveStatsCache(result);
                                }
                            }
                            return result;
                        });

                // Always process today live (data incomplete)
                String today = StatsCacheService.getTodayDateString();
                ProcessedStats todayStats = processSessionFiles(allSessionFiles, today, today);

                return cacheToStats(updatedCache, todayStats);
            } catch (Exception e) {
                log.error("Failed to aggregate stats: {}", e.getMessage());
                return getEmptyStats();
            }
        });
    }

    /**
     * Aggregates stats for a specific date range.
     * Translated from aggregateClaudeCodeStatsForRange() in stats.ts
     */
    public CompletableFuture<ClaudeCodeStats> aggregateClaudeCodeStatsForRange(String range) {
        if ("all".equals(range)) {
            return aggregateClaudeCodeStats();
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<String> allSessionFiles = getAllSessionFiles();
                if (allSessionFiles.isEmpty()) {
                    return getEmptyStats();
                }
                int daysBack = "7d".equals(range) ? 7 : 30;
                String fromDate = LocalDate.now()
                        .minusDays(daysBack - 1)
                        .format(DateTimeFormatter.ISO_LOCAL_DATE);
                ProcessedStats stats = processSessionFiles(allSessionFiles, fromDate, null);
                return processedStatsToClaudeCodeStats(stats);
            } catch (Exception e) {
                log.error("Failed to aggregate stats for range {}: {}", range, e.getMessage());
                return getEmptyStats();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Session file discovery
    // -------------------------------------------------------------------------

    private List<String> getAllSessionFiles() {
        String projectsDir = sessionStorageService.getProjectsDir();
        File projectsDirFile = new File(projectsDir);
        if (!projectsDirFile.exists()) return Collections.emptyList();

        File[] projectDirs = projectsDirFile.listFiles(File::isDirectory);
        if (projectDirs == null) return Collections.emptyList();

        List<String> result = new ArrayList<>();
        for (File projectDir : projectDirs) {
            File[] jsonlFiles = projectDir.listFiles(
                    f -> f.isFile() && f.getName().endsWith(".jsonl"));
            if (jsonlFiles != null) {
                for (File f : jsonlFiles) result.add(f.getAbsolutePath());
            }
            // Collect subagent files: {projectDir}/{sessionId}/subagents/agent-*.jsonl
            File[] sessionSubDirs = projectDir.listFiles(File::isDirectory);
            if (sessionSubDirs != null) {
                for (File sessionDir : sessionSubDirs) {
                    File subagentsDir = new File(sessionDir, "subagents");
                    if (subagentsDir.isDirectory()) {
                        File[] agentFiles = subagentsDir.listFiles(
                                f -> f.isFile()
                                        && f.getName().endsWith(".jsonl")
                                        && f.getName().startsWith("agent-"));
                        if (agentFiles != null) {
                            for (File f : agentFiles) result.add(f.getAbsolutePath());
                        }
                    }
                }
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Session file processing
    // -------------------------------------------------------------------------

    /**
     * Process session files and extract stats with optional date range filtering.
     * Translated from processSessionFiles() in stats.ts
     */
    @SuppressWarnings("unchecked")
    private ProcessedStats processSessionFiles(List<String> sessionFiles,
                                               String fromDate,
                                               String toDate) {
        Map<String, DailyActivity> dailyActivityMap = new LinkedHashMap<>();
        Map<String, Map<String, Long>> dailyModelTokensMap = new LinkedHashMap<>();
        List<SessionStats> sessions = new ArrayList<>();
        Map<Integer, Integer> hourCounts = new HashMap<>();
        int totalMessages = 0;
        long totalSpeculationTimeSavedMs = 0;
        Map<String, ModelUsage> modelUsageAgg = new HashMap<>();

        for (int i = 0; i < sessionFiles.size(); i += BATCH_SIZE) {
            List<String> batch = sessionFiles.subList(i, Math.min(i + BATCH_SIZE, sessionFiles.size()));
            for (String sessionFile : batch) {
                try {
                    // Date-based pre-filter by file modification time
                    if (fromDate != null) {
                        long lastModified = new File(sessionFile).lastModified();
                        if (lastModified > 0) {
                            String fileModDate = StatsCacheService.toDateString(new Date(lastModified));
                            if (StatsCacheService.isDateBefore(fileModDate, fromDate)) {
                                continue;
                            }
                        }
                    }

                    // Read JSONL entries
                    List<Map<String, Object>> entries = readJsonlFile(sessionFile);
                    if (entries == null) continue;

                    String sessionId = Path.of(sessionFile).getFileName().toString()
                            .replaceAll("\\.jsonl$", "");
                    boolean isSubagentFile = sessionFile.contains(File.separator + "subagents" + File.separator);

                    List<Map<String, Object>> messages = new ArrayList<>();
                    for (Map<String, Object> entry : entries) {
                        String type = (String) entry.get("type");
                        if (type != null && TRANSCRIPT_MESSAGE_TYPES.contains(type)) {
                            messages.add(entry);
                        } else if ("speculation-accept".equals(type)) {
                            Number timeSaved = (Number) entry.get("timeSavedMs");
                            if (timeSaved != null) totalSpeculationTimeSavedMs += timeSaved.longValue();
                        }
                    }

                    if (messages.isEmpty()) continue;

                    // For non-subagent files, filter out sidechain messages
                    List<Map<String, Object>> mainMessages = isSubagentFile
                            ? messages
                            : messages.stream()
                                      .filter(m -> !Boolean.TRUE.equals(m.get("isSidechain")))
                                      .collect(Collectors.toList());
                    if (mainMessages.isEmpty()) continue;

                    Map<String, Object> firstMsg = mainMessages.get(0);
                    Map<String, Object> lastMsg = mainMessages.get(mainMessages.size() - 1);

                    String firstTsStr = (String) firstMsg.get("timestamp");
                    String lastTsStr = (String) lastMsg.get("timestamp");
                    if (firstTsStr == null || lastTsStr == null) continue;

                    Date firstTs, lastTs;
                    try {
                        firstTs = Date.from(java.time.Instant.parse(firstTsStr));
                        lastTs = Date.from(java.time.Instant.parse(lastTsStr));
                    } catch (Exception e) {
                        log.debug("Skipping session with invalid timestamp: {}", sessionFile);
                        continue;
                    }

                    String dateKey = StatsCacheService.toDateString(firstTs);

                    // Apply date filters
                    if (fromDate != null && StatsCacheService.isDateBefore(dateKey, fromDate)) continue;
                    if (toDate != null && StatsCacheService.isDateBefore(toDate, dateKey)) continue;

                    DailyActivity existing = dailyActivityMap.computeIfAbsent(dateKey, d ->
                            DailyActivity.builder().date(d).messageCount(0).sessionCount(0).toolCallCount(0).build());

                    if (!isSubagentFile) {
                        long duration = lastTs.getTime() - firstTs.getTime();
                        sessions.add(SessionStats.builder()
                                .sessionId(sessionId)
                                .duration(duration)
                                .messageCount(mainMessages.size())
                                .timestamp(firstTsStr)
                                .build());
                        totalMessages += mainMessages.size();
                        existing.setSessionCount(existing.getSessionCount() + 1);
                        existing.setMessageCount(existing.getMessageCount() + mainMessages.size());

                        int hour = firstTs.toInstant().atZone(java.time.ZoneId.systemDefault()).getHour();
                        hourCounts.merge(hour, 1, Integer::sum);
                    }

                    // Process messages for tool usage and model stats
                    for (Map<String, Object> message : mainMessages) {
                        if (!"assistant".equals(message.get("type"))) continue;

                        Map<String, Object> msgInner = (Map<String, Object>) message.get("message");
                        if (msgInner == null) continue;

                        List<Map<String, Object>> content = (List<Map<String, Object>>) msgInner.get("content");
                        if (content != null) {
                            for (Map<String, Object> block : content) {
                                if ("tool_use".equals(block.get("type"))) {
                                    existing.setToolCallCount(existing.getToolCallCount() + 1);
                                }
                            }
                        }

                        Map<String, Object> usage = (Map<String, Object>) msgInner.get("usage");
                        String model = (String) msgInner.get("model");
                        if (usage != null && model != null && !SYNTHETIC_MODEL.equals(model)) {
                            ModelUsage agg = modelUsageAgg.computeIfAbsent(model, k ->
                                    ModelUsage.builder().build());
                            agg.setInputTokens(agg.getInputTokens() + toLong(usage.get("input_tokens")));
                            agg.setOutputTokens(agg.getOutputTokens() + toLong(usage.get("output_tokens")));
                            agg.setCacheReadInputTokens(agg.getCacheReadInputTokens() + toLong(usage.get("cache_read_input_tokens")));
                            agg.setCacheCreationInputTokens(agg.getCacheCreationInputTokens() + toLong(usage.get("cache_creation_input_tokens")));

                            long totalTokens = toLong(usage.get("input_tokens")) + toLong(usage.get("output_tokens"));
                            if (totalTokens > 0) {
                                dailyModelTokensMap.computeIfAbsent(dateKey, d -> new HashMap<>())
                                        .merge(model, totalTokens, Long::sum);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to read session file {}: {}", sessionFile, e.getMessage());
                }
            }
        }

        List<DailyActivity> sortedDailyActivity = new ArrayList<>(dailyActivityMap.values());
        sortedDailyActivity.sort(Comparator.comparing(DailyActivity::getDate));

        List<DailyModelTokens> sortedDailyModelTokens = new ArrayList<>();
        for (Map.Entry<String, Map<String, Long>> e : dailyModelTokensMap.entrySet()) {
            sortedDailyModelTokens.add(DailyModelTokens.builder()
                    .date(e.getKey())
                    .tokensByModel(e.getValue())
                    .build());
        }
        sortedDailyModelTokens.sort(Comparator.comparing(DailyModelTokens::getDate));

        return ProcessedStats.builder()
                .dailyActivity(sortedDailyActivity)
                .dailyModelTokens(sortedDailyModelTokens)
                .modelUsage(modelUsageAgg)
                .sessionStats(sessions)
                .hourCounts(hourCounts)
                .totalMessages(totalMessages)
                .totalSpeculationTimeSavedMs(totalSpeculationTimeSavedMs)
                .build();
    }

    // -------------------------------------------------------------------------
    // Stats derivation
    // -------------------------------------------------------------------------

    /**
     * Convert a PersistedStatsCache to ClaudeCodeStats by computing derived fields.
     * Translated from cacheToStats() in stats.ts
     */
    private ClaudeCodeStats cacheToStats(StatsCacheService.PersistedStatsCache cache,
                                          ProcessedStats todayStats) {
        // Merge daily activity
        Map<String, DailyActivity> dailyActivityMap = new LinkedHashMap<>();
        for (DailyActivity day : cache.getDailyActivity()) {
            dailyActivityMap.put(day.getDate(), day.toBuilder().build());
        }
        for (DailyActivity day : todayStats.getDailyActivity()) {
            DailyActivity existing = dailyActivityMap.get(day.getDate());
            if (existing != null) {
                existing.setMessageCount(existing.getMessageCount() + day.getMessageCount());
                existing.setSessionCount(existing.getSessionCount() + day.getSessionCount());
                existing.setToolCallCount(existing.getToolCallCount() + day.getToolCallCount());
            } else {
                dailyActivityMap.put(day.getDate(), day.toBuilder().build());
            }
        }

        // Merge daily model tokens
        Map<String, Map<String, Long>> dailyModelTokensMap = new LinkedHashMap<>();
        for (DailyModelTokens day : cache.getDailyModelTokens()) {
            dailyModelTokensMap.put(day.getDate(), new HashMap<>(day.getTokensByModel()));
        }
        for (DailyModelTokens day : todayStats.getDailyModelTokens()) {
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
        Map<String, ModelUsage> modelUsage = new HashMap<>(cache.getModelUsage());
        for (Map.Entry<String, ModelUsage> entry : todayStats.getModelUsage().entrySet()) {
            String model = entry.getKey();
            ModelUsage usage = entry.getValue();
            if (modelUsage.containsKey(model)) {
                ModelUsage ex = modelUsage.get(model);
                modelUsage.put(model, ModelUsage.builder()
                        .inputTokens(ex.getInputTokens() + usage.getInputTokens())
                        .outputTokens(ex.getOutputTokens() + usage.getOutputTokens())
                        .cacheReadInputTokens(ex.getCacheReadInputTokens() + usage.getCacheReadInputTokens())
                        .cacheCreationInputTokens(ex.getCacheCreationInputTokens() + usage.getCacheCreationInputTokens())
                        .webSearchRequests(ex.getWebSearchRequests() + usage.getWebSearchRequests())
                        .costUSD(ex.getCostUSD() + usage.getCostUSD())
                        .contextWindow(Math.max(ex.getContextWindow(), usage.getContextWindow()))
                        .maxOutputTokens(Math.max(ex.getMaxOutputTokens(), usage.getMaxOutputTokens()))
                        .build());
            } else {
                modelUsage.put(model, usage.toBuilder().build());
            }
        }

        // Merge hour counts
        Map<Integer, Integer> hourCountsMap = new HashMap<>(cache.getHourCounts());
        for (Map.Entry<Integer, Integer> e : todayStats.getHourCounts().entrySet()) {
            hourCountsMap.merge(e.getKey(), e.getValue(), Integer::sum);
        }

        List<DailyActivity> dailyActivityArray = new ArrayList<>(dailyActivityMap.values());
        dailyActivityArray.sort(Comparator.comparing(DailyActivity::getDate));

        List<DailyModelTokens> dailyModelTokens = new ArrayList<>();
        for (Map.Entry<String, Map<String, Long>> e : dailyModelTokensMap.entrySet()) {
            dailyModelTokens.add(DailyModelTokens.builder()
                    .date(e.getKey()).tokensByModel(e.getValue()).build());
        }
        dailyModelTokens.sort(Comparator.comparing(DailyModelTokens::getDate));

        StreakInfo streaks = calculateStreaks(dailyActivityArray);

        int totalSessions = cache.getTotalSessions() + todayStats.getSessionStats().size();
        int totalMessages = cache.getTotalMessages() + todayStats.getTotalMessages();

        SessionStats longestSession = cache.getLongestSession();
        for (SessionStats s : todayStats.getSessionStats()) {
            if (longestSession == null || s.getDuration() > longestSession.getDuration()) {
                longestSession = s;
            }
        }

        String firstSessionDate = cache.getFirstSessionDate();
        String lastSessionDate = null;
        for (SessionStats s : todayStats.getSessionStats()) {
            if (firstSessionDate == null || s.getTimestamp().compareTo(firstSessionDate) < 0) {
                firstSessionDate = s.getTimestamp();
            }
            if (lastSessionDate == null || s.getTimestamp().compareTo(lastSessionDate) > 0) {
                lastSessionDate = s.getTimestamp();
            }
        }
        if (lastSessionDate == null && !dailyActivityArray.isEmpty()) {
            lastSessionDate = dailyActivityArray.get(dailyActivityArray.size() - 1).getDate();
        }

        String peakActivityDay = dailyActivityArray.isEmpty() ? null :
                dailyActivityArray.stream()
                        .max(Comparator.comparingInt(DailyActivity::getMessageCount))
                        .map(DailyActivity::getDate).orElse(null);

        Integer peakActivityHour = hourCountsMap.isEmpty() ? null :
                hourCountsMap.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey).orElse(null);

        long totalDays = (firstSessionDate != null && lastSessionDate != null)
                ? (long) Math.ceil(
                    (double)(java.time.Instant.parse(toIsoInstant(lastSessionDate)).toEpochMilli()
                           - java.time.Instant.parse(toIsoInstant(firstSessionDate)).toEpochMilli())
                    / (1000.0 * 60 * 60 * 24)) + 1
                : 0;

        long totalSpeculationTimeSavedMs = cache.getTotalSpeculationTimeSavedMs()
                + todayStats.getTotalSpeculationTimeSavedMs();

        return ClaudeCodeStats.builder()
                .totalSessions(totalSessions)
                .totalMessages(totalMessages)
                .totalDays((int) totalDays)
                .activeDays(dailyActivityMap.size())
                .streaks(streaks)
                .dailyActivity(dailyActivityArray)
                .dailyModelTokens(dailyModelTokens)
                .longestSession(longestSession)
                .modelUsage(modelUsage)
                .firstSessionDate(firstSessionDate)
                .lastSessionDate(lastSessionDate)
                .peakActivityDay(peakActivityDay)
                .peakActivityHour(peakActivityHour)
                .totalSpeculationTimeSavedMs(totalSpeculationTimeSavedMs)
                .build();
    }

    /**
     * Convert ProcessedStats to ClaudeCodeStats (for filtered ranges bypassing cache).
     * Translated from processedStatsToClaudeCodeStats() in stats.ts
     */
    private ClaudeCodeStats processedStatsToClaudeCodeStats(ProcessedStats stats) {
        List<DailyActivity> sortedActivity = stats.getDailyActivity().stream()
                .sorted(Comparator.comparing(DailyActivity::getDate))
                .collect(Collectors.toList());
        List<DailyModelTokens> sortedTokens = stats.getDailyModelTokens().stream()
                .sorted(Comparator.comparing(DailyModelTokens::getDate))
                .collect(Collectors.toList());

        StreakInfo streaks = calculateStreaks(sortedActivity);

        SessionStats longestSession = null;
        String firstSessionDate = null;
        String lastSessionDate = null;
        for (SessionStats s : stats.getSessionStats()) {
            if (longestSession == null || s.getDuration() > longestSession.getDuration()) longestSession = s;
            if (firstSessionDate == null || s.getTimestamp().compareTo(firstSessionDate) < 0) firstSessionDate = s.getTimestamp();
            if (lastSessionDate == null || s.getTimestamp().compareTo(lastSessionDate) > 0) lastSessionDate = s.getTimestamp();
        }

        String peakActivityDay = sortedActivity.isEmpty() ? null :
                sortedActivity.stream().max(Comparator.comparingInt(DailyActivity::getMessageCount))
                        .map(DailyActivity::getDate).orElse(null);

        Integer peakActivityHour = stats.getHourCounts().isEmpty() ? null :
                stats.getHourCounts().entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey).orElse(null);

        long totalDays = (firstSessionDate != null && lastSessionDate != null)
                ? (long) Math.ceil(
                    (double)(java.time.Instant.parse(toIsoInstant(lastSessionDate)).toEpochMilli()
                           - java.time.Instant.parse(toIsoInstant(firstSessionDate)).toEpochMilli())
                    / (1000.0 * 60 * 60 * 24)) + 1
                : 0;

        return ClaudeCodeStats.builder()
                .totalSessions(stats.getSessionStats().size())
                .totalMessages(stats.getTotalMessages())
                .totalDays((int) totalDays)
                .activeDays(stats.getDailyActivity().size())
                .streaks(streaks)
                .dailyActivity(sortedActivity)
                .dailyModelTokens(sortedTokens)
                .longestSession(longestSession)
                .modelUsage(stats.getModelUsage())
                .firstSessionDate(firstSessionDate)
                .lastSessionDate(lastSessionDate)
                .peakActivityDay(peakActivityDay)
                .peakActivityHour(peakActivityHour)
                .totalSpeculationTimeSavedMs(stats.getTotalSpeculationTimeSavedMs())
                .build();
    }

    // -------------------------------------------------------------------------
    // Streak calculation
    // -------------------------------------------------------------------------

    /**
     * Calculate current and longest activity streaks.
     * Translated from calculateStreaks() in stats.ts
     */
    private StreakInfo calculateStreaks(List<DailyActivity> dailyActivity) {
        if (dailyActivity.isEmpty()) {
            return StreakInfo.builder()
                    .currentStreak(0).longestStreak(0)
                    .currentStreakStart(null).longestStreakStart(null).longestStreakEnd(null)
                    .build();
        }

        String today = StatsCacheService.getTodayDateString();
        Set<String> activeDates = dailyActivity.stream()
                .map(DailyActivity::getDate)
                .collect(Collectors.toSet());

        // Current streak (working backwards from today)
        int currentStreak = 0;
        String currentStreakStart = null;
        LocalDate checkDate = LocalDate.now();
        while (true) {
            String dateStr = checkDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
            if (!activeDates.contains(dateStr)) break;
            currentStreak++;
            currentStreakStart = dateStr;
            checkDate = checkDate.minusDays(1);
        }

        // Longest streak
        int longestStreak = 0;
        String longestStreakStart = null;
        String longestStreakEnd = null;
        List<String> sortedDates = new ArrayList<>(activeDates);
        Collections.sort(sortedDates);

        int tempStreak = 1;
        String tempStart = sortedDates.get(0);
        for (int i = 1; i < sortedDates.size(); i++) {
            LocalDate prev = LocalDate.parse(sortedDates.get(i - 1));
            LocalDate curr = LocalDate.parse(sortedDates.get(i));
            long dayDiff = java.time.temporal.ChronoUnit.DAYS.between(prev, curr);
            if (dayDiff == 1) {
                tempStreak++;
            } else {
                if (tempStreak > longestStreak) {
                    longestStreak = tempStreak;
                    longestStreakStart = tempStart;
                    longestStreakEnd = sortedDates.get(i - 1);
                }
                tempStreak = 1;
                tempStart = sortedDates.get(i);
            }
        }
        if (tempStreak > longestStreak) {
            longestStreak = tempStreak;
            longestStreakStart = tempStart;
            longestStreakEnd = sortedDates.get(sortedDates.size() - 1);
        }

        return StreakInfo.builder()
                .currentStreak(currentStreak)
                .longestStreak(longestStreak)
                .currentStreakStart(currentStreakStart)
                .longestStreakStart(longestStreakStart)
                .longestStreakEnd(longestStreakEnd)
                .build();
    }

    // -------------------------------------------------------------------------
    // Helper utilities
    // -------------------------------------------------------------------------

    private String getNextDay(String dateStr) {
        return LocalDate.parse(dateStr).plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private StatsCacheService.PersistedStatsCache buildCacheWithNewDate(
            StatsCacheService.PersistedStatsCache cache, String newDate) {
        return StatsCacheService.PersistedStatsCache.builder()
                .version(cache.getVersion())
                .lastComputedDate(newDate)
                .dailyActivity(cache.getDailyActivity())
                .dailyModelTokens(cache.getDailyModelTokens())
                .modelUsage(cache.getModelUsage())
                .totalSessions(cache.getTotalSessions())
                .totalMessages(cache.getTotalMessages())
                .longestSession(cache.getLongestSession())
                .firstSessionDate(cache.getFirstSessionDate())
                .hourCounts(cache.getHourCounts())
                .totalSpeculationTimeSavedMs(cache.getTotalSpeculationTimeSavedMs())
                .shotDistribution(cache.getShotDistribution())
                .build();
    }

    private ClaudeCodeStats getEmptyStats() {
        return ClaudeCodeStats.builder()
                .totalSessions(0).totalMessages(0).totalDays(0).activeDays(0)
                .streaks(StreakInfo.builder().currentStreak(0).longestStreak(0)
                        .currentStreakStart(null).longestStreakStart(null).longestStreakEnd(null).build())
                .dailyActivity(Collections.emptyList())
                .dailyModelTokens(Collections.emptyList())
                .longestSession(null)
                .modelUsage(Collections.emptyMap())
                .firstSessionDate(null).lastSessionDate(null)
                .peakActivityDay(null).peakActivityHour(null)
                .totalSpeculationTimeSavedMs(0)
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readJsonlFile(String filePath) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(Path.of(filePath));
            for (String line : lines) {
                if (line.isBlank()) continue;
                try {
                    Map<String, Object> entry = objectMapper.readValue(line, Map.class);
                    result.add(entry);
                } catch (Exception ignored) {}
            }
        } catch (IOException e) {
            log.debug("Failed to read JSONL file {}: {}", filePath, e.getMessage());
            return null;
        }
        return result;
    }

    private static long toLong(Object val) {
        if (val == null) return 0;
        if (val instanceof Number n) return n.longValue();
        return 0;
    }

    /**
     * Convert a date string (YYYY-MM-DD or ISO instant) to ISO instant for parsing.
     */
    private static String toIsoInstant(String dateOrTimestamp) {
        if (dateOrTimestamp == null) return "1970-01-01T00:00:00Z";
        if (dateOrTimestamp.contains("T")) return dateOrTimestamp;
        return dateOrTimestamp + "T00:00:00Z";
    }
}
