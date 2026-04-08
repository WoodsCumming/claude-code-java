package com.anthropic.claudecode.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Insights service — session analysis and usage statistics.
 * Translated from src/commands/insights.ts
 *
 * Provides:
 *  - Per-session metadata extraction (tool counts, token usage, languages, …)
 *  - Session branch deduplication
 *  - Data-directory path resolution
 *  - Aggregation helpers consumed by the /insights command
 */
@Slf4j
@Service
public class InsightsService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InsightsService.class);


    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /** Maps file extension → human-readable language name. */
    public static final Map<String, String> EXTENSION_TO_LANGUAGE = Map.ofEntries(
        Map.entry(".ts",   "TypeScript"),
        Map.entry(".tsx",  "TypeScript"),
        Map.entry(".js",   "JavaScript"),
        Map.entry(".jsx",  "JavaScript"),
        Map.entry(".py",   "Python"),
        Map.entry(".rb",   "Ruby"),
        Map.entry(".go",   "Go"),
        Map.entry(".rs",   "Rust"),
        Map.entry(".java", "Java"),
        Map.entry(".md",   "Markdown"),
        Map.entry(".json", "JSON"),
        Map.entry(".yaml", "YAML"),
        Map.entry(".yml",  "YAML"),
        Map.entry(".sh",   "Shell"),
        Map.entry(".css",  "CSS"),
        Map.entry(".html", "HTML")
    );

    /** Maps internal key names to display labels. */
    public static final Map<String, String> LABEL_MAP = Map.ofEntries(
        Map.entry("debug_investigate",       "Debug/Investigate"),
        Map.entry("implement_feature",       "Implement Feature"),
        Map.entry("fix_bug",                 "Fix Bug"),
        Map.entry("write_script_tool",       "Write Script/Tool"),
        Map.entry("refactor_code",           "Refactor Code"),
        Map.entry("configure_system",        "Configure System"),
        Map.entry("create_pr_commit",        "Create PR/Commit"),
        Map.entry("analyze_data",            "Analyze Data"),
        Map.entry("understand_codebase",     "Understand Codebase"),
        Map.entry("write_tests",             "Write Tests"),
        Map.entry("write_docs",              "Write Docs"),
        Map.entry("deploy_infra",            "Deploy/Infra"),
        Map.entry("warmup_minimal",          "Cache Warmup"),
        Map.entry("fast_accurate_search",    "Fast/Accurate Search"),
        Map.entry("correct_code_edits",      "Correct Code Edits"),
        Map.entry("good_explanations",       "Good Explanations"),
        Map.entry("proactive_help",          "Proactive Help"),
        Map.entry("multi_file_changes",      "Multi-file Changes"),
        Map.entry("handled_complexity",      "Multi-file Changes"),
        Map.entry("good_debugging",          "Good Debugging"),
        Map.entry("misunderstood_request",   "Misunderstood Request"),
        Map.entry("wrong_approach",          "Wrong Approach"),
        Map.entry("buggy_code",              "Buggy Code"),
        Map.entry("user_rejected_action",    "User Rejected Action"),
        Map.entry("claude_got_blocked",      "Claude Got Blocked"),
        Map.entry("user_stopped_early",      "User Stopped Early"),
        Map.entry("wrong_file_or_location",  "Wrong File/Location"),
        Map.entry("excessive_changes",       "Excessive Changes"),
        Map.entry("slow_or_verbose",         "Slow/Verbose"),
        Map.entry("tool_failed",             "Tool Failed"),
        Map.entry("user_unclear",            "User Unclear"),
        Map.entry("external_issue",          "External Issue"),
        Map.entry("frustrated",              "Frustrated"),
        Map.entry("dissatisfied",            "Dissatisfied"),
        Map.entry("likely_satisfied",        "Likely Satisfied"),
        Map.entry("satisfied",               "Satisfied"),
        Map.entry("happy",                   "Happy"),
        Map.entry("unsure",                  "Unsure"),
        Map.entry("neutral",                 "Neutral"),
        Map.entry("delighted",               "Delighted"),
        Map.entry("single_task",             "Single Task"),
        Map.entry("multi_task",              "Multi Task"),
        Map.entry("iterative_refinement",    "Iterative Refinement"),
        Map.entry("exploration",             "Exploration"),
        Map.entry("quick_question",          "Quick Question"),
        Map.entry("fully_achieved",          "Fully Achieved"),
        Map.entry("mostly_achieved",         "Mostly Achieved"),
        Map.entry("partially_achieved",      "Partially Achieved"),
        Map.entry("not_achieved",            "Not Achieved"),
        Map.entry("unclear_from_transcript", "Unclear"),
        Map.entry("unhelpful",               "Unhelpful"),
        Map.entry("slightly_helpful",        "Slightly Helpful"),
        Map.entry("moderately_helpful",      "Moderately Helpful"),
        Map.entry("very_helpful",            "Very Helpful"),
        Map.entry("essential",               "Essential")
    );

    private final SessionStorageService sessionStorageService;
    private final GlobalConfigService globalConfigService;

    @Autowired
    public InsightsService(SessionStorageService sessionStorageService,
                            GlobalConfigService globalConfigService) {
        this.sessionStorageService = sessionStorageService;
        this.globalConfigService = globalConfigService;
    }

    // -------------------------------------------------------------------------
    // DTOs
    // -------------------------------------------------------------------------

    /**
     * Per-session metadata.
     * Mirrors SessionMeta in insights.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SessionMeta {
        private String sessionId;
        private String projectPath;
        private String startTime;
        private int durationMinutes;
        private int userMessageCount;
        private int assistantMessageCount;
        private Map<String, Integer> toolCounts;
        private Map<String, Integer> languages;
        private int gitCommits;
        private int gitPushes;
        private long inputTokens;
        private long outputTokens;
        private String firstPrompt;
        private String summary;
        private int userInterruptions;
        private List<Double> userResponseTimes;
        private int toolErrors;
        private Map<String, Integer> toolErrorCategories;
        private boolean usesTaskAgent;
        private boolean usesMcp;
        private boolean usesWebSearch;
        private boolean usesWebFetch;
        private int linesAdded;
        private int linesRemoved;
        private int filesModified;
        private List<Integer> messageHours;
        private List<String> userMessageTimestamps;
    
        public int getDurationMinutes() { return durationMinutes; }
    
        public String getSessionId() { return sessionId; }
    
        public int getUserMessageCount() { return userMessageCount; }
    
        public void setAssistantMessageCount(int v) { assistantMessageCount = v; }
    
        public void setDurationMinutes(int v) { durationMinutes = v; }
    
        public void setFilesModified(int v) { filesModified = v; }
    
        public void setFirstPrompt(String v) { firstPrompt = v; }
    
        public void setGitCommits(int v) { gitCommits = v; }
    
        public void setGitPushes(int v) { gitPushes = v; }
    
        public void setInputTokens(long v) { inputTokens = v; }
    
        public void setLanguages(Map<String, Integer> v) { languages = v; }
    
        public void setLinesAdded(int v) { linesAdded = v; }
    
        public void setLinesRemoved(int v) { linesRemoved = v; }
    
        public void setMessageHours(List<Integer> v) { messageHours = v; }
    
        public void setOutputTokens(long v) { outputTokens = v; }
    
        public void setProjectPath(String v) { projectPath = v; }
    
        public void setSessionId(String v) { sessionId = v; }
    
        public void setStartTime(String v) { startTime = v; }
    
        public void setSummary(String v) { summary = v; }
    
        public void setToolCounts(Map<String, Integer> v) { toolCounts = v; }
    
        public void setToolErrorCategories(Map<String, Integer> v) { toolErrorCategories = v; }
    
        public void setToolErrors(int v) { toolErrors = v; }
    
        public void setUserInterruptions(int v) { userInterruptions = v; }
    
        public void setUserMessageCount(int v) { userMessageCount = v; }
    
        public void setUserMessageTimestamps(List<String> v) { userMessageTimestamps = v; }
    
        public void setUserResponseTimes(List<Double> v) { userResponseTimes = v; }
    
        public void setUsesMcp(boolean v) { usesMcp = v; }
    
        public void setUsesTaskAgent(boolean v) { usesTaskAgent = v; }
    
        public void setUsesWebFetch(boolean v) { usesWebFetch = v; }
    
        public void setUsesWebSearch(boolean v) { usesWebSearch = v; }
    }

    /**
     * Extracted facets for a session.
     * Mirrors SessionFacets in insights.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SessionFacets {
        private String sessionId;
        private String underlyingGoal;
        private Map<String, Integer> goalCategories;
        private String outcome;
        private Map<String, Integer> userSatisfactionCounts;
        private String claudeHelpfulness;
        private String sessionType;
        private Map<String, Integer> frictionCounts;
        private String frictionDetail;
        private String primarySuccess;
        private String briefSummary;
        private List<String> userInstructionsToClaude;

        public String getSessionId() { return sessionId; }
        public void setSessionId(String v) { sessionId = v; }
        public String getUnderlyingGoal() { return underlyingGoal; }
        public void setUnderlyingGoal(String v) { underlyingGoal = v; }
        public Map<String, Integer> getGoalCategories() { return goalCategories; }
        public void setGoalCategories(Map<String, Integer> v) { goalCategories = v; }
        public String getOutcome() { return outcome; }
        public void setOutcome(String v) { outcome = v; }
        public Map<String, Integer> getUserSatisfactionCounts() { return userSatisfactionCounts; }
        public void setUserSatisfactionCounts(Map<String, Integer> v) { userSatisfactionCounts = v; }
        public String getClaudeHelpfulness() { return claudeHelpfulness; }
        public void setClaudeHelpfulness(String v) { claudeHelpfulness = v; }
        public String getSessionType() { return sessionType; }
        public void setSessionType(String v) { sessionType = v; }
        public Map<String, Integer> getFrictionCounts() { return frictionCounts; }
        public void setFrictionCounts(Map<String, Integer> v) { frictionCounts = v; }
        public String getFrictionDetail() { return frictionDetail; }
        public void setFrictionDetail(String v) { frictionDetail = v; }
        public String getPrimarySuccess() { return primarySuccess; }
        public void setPrimarySuccess(String v) { primarySuccess = v; }
        public String getBriefSummary() { return briefSummary; }
        public void setBriefSummary(String v) { briefSummary = v; }
        public List<String> getUserInstructionsToClaude() { return userInstructionsToClaude; }
        public void setUserInstructionsToClaude(List<String> v) { userInstructionsToClaude = v; }
    }

    /**
     * Aggregated statistics across multiple sessions.
     * Mirrors AggregatedData in insights.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AggregatedData {
        private int totalSessions;
        private int sessionsWithFacets;
        private DateRange dateRange;
        private long totalMessages;
        private double totalDurationHours;
        private long totalInputTokens;
        private long totalOutputTokens;
        private Map<String, Integer> toolCounts = new LinkedHashMap<>();
        private Map<String, Integer> languages = new LinkedHashMap<>();
        private int gitCommits;
        private int gitPushes;
        private Map<String, Integer> projects = new LinkedHashMap<>();
        private Map<String, Integer> goalCategories = new LinkedHashMap<>();
        private Map<String, Integer> outcomes = new LinkedHashMap<>();
        private Map<String, Integer> satisfaction = new LinkedHashMap<>();
        private Map<String, Integer> helpfulness = new LinkedHashMap<>();
        private Map<String, Integer> sessionTypes = new LinkedHashMap<>();
        private Map<String, Integer> friction = new LinkedHashMap<>();
        private Map<String, Integer> success = new LinkedHashMap<>();
        private List<Map<String, String>> sessionSummaries = new ArrayList<>();
        private int totalInterruptions;
        private int totalToolErrors;
        private Map<String, Integer> toolErrorCategories = new LinkedHashMap<>();
        private List<Double> userResponseTimes = new ArrayList<>();
        private double medianResponseTime;
        private double avgResponseTime;
        private int sessionsUsingTaskAgent;
        private int sessionsUsingMcp;
        private int sessionsUsingWebSearch;
        private int sessionsUsingWebFetch;
        private int totalLinesAdded;
        private int totalLinesRemoved;
        private int totalFilesModified;
        private int daysActive;
        private double messagesPerDay;
        private List<Integer> messageHours = new ArrayList<>();
        private MultiClaudingStats multiClaudioing = new MultiClaudingStats();
    }

    public record DateRange(String start, String end) {}

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class MultiClaudingStats {
        private int overlapEvents;
        private int sessionsInvolved;
        private int userMessagesDuring;

        public int getOverlapEvents() { return overlapEvents; }
        public void setOverlapEvents(int v) { overlapEvents = v; }
        public int getSessionsInvolved() { return sessionsInvolved; }
        public void setSessionsInvolved(int v) { sessionsInvolved = v; }
        public int getUserMessagesDuring() { return userMessagesDuring; }
        public void setUserMessagesDuring(int v) { userMessagesDuring = v; }
    

    }

    /** A session entry pairing a log option with its extracted metadata. */
    public record SessionEntry(SessionLogOption log, SessionMeta meta) {}

    // -------------------------------------------------------------------------
    // Path helpers (lazy — mirrors the lazy getters in insights.ts)
    // -------------------------------------------------------------------------

    /**
     * Root usage-data directory.
     * Translated from getDataDir() in insights.ts
     */
    public Path getDataDir() {
        return globalConfigService.getClaudeConfigHomeDir().resolve("usage-data");
    }

    /**
     * Directory where per-session facets JSON files are stored.
     * Translated from getFacetsDir() in insights.ts
     */
    public Path getFacetsDir() {
        return getDataDir().resolve("facets");
    }

    /**
     * Directory where per-session meta JSON files are stored.
     * Translated from getSessionMetaDir() in insights.ts
     */
    public Path getSessionMetaDir() {
        return getDataDir().resolve("session-meta");
    }

    // -------------------------------------------------------------------------
    // Core logic
    // -------------------------------------------------------------------------

    /**
     * Deduplicate conversation branches within the same session.
     *
     * When a session file has multiple leaf messages (from retries or branching),
     * this keeps only the branch with the most user messages (tie-break: longest
     * duration).
     * Translated from deduplicateSessionBranches() in insights.ts
     */
    public List<SessionEntry> deduplicateSessionBranches(List<SessionEntry> entries) {
        Map<String, SessionEntry> bestBySession = new LinkedHashMap<>();
        for (SessionEntry entry : entries) {
            String id = entry.meta().getSessionId();
            SessionEntry existing = bestBySession.get(id);
            if (existing == null
                    || entry.meta().getUserMessageCount() > existing.meta().getUserMessageCount()
                    || (entry.meta().getUserMessageCount() == existing.meta().getUserMessageCount()
                        && entry.meta().getDurationMinutes() > existing.meta().getDurationMinutes())) {
                bestBySession.put(id, entry);
            }
        }
        return new ArrayList<>(bestBySession.values());
    }

    /**
     * Extract tool usage statistics and other metrics from a session log.
     * Translated from extractToolStats() in insights.ts
     */
    public ToolStats extractToolStats(SessionLogOption log) {
        Map<String, Integer> toolCounts = new LinkedHashMap<>();
        Map<String, Integer> languages = new LinkedHashMap<>();
        int gitCommits = 0, gitPushes = 0;
        long inputTokens = 0, outputTokens = 0;
        int userInterruptions = 0;
        List<Double> userResponseTimes = new ArrayList<>();
        int toolErrors = 0;
        Map<String, Integer> toolErrorCategories = new LinkedHashMap<>();
        boolean usesTaskAgent = false, usesMcp = false, usesWebSearch = false, usesWebFetch = false;
        int linesAdded = 0, linesRemoved = 0;
        Set<String> filesModified = new LinkedHashSet<>();
        List<Integer> messageHours = new ArrayList<>();
        List<String> userMessageTimestamps = new ArrayList<>();
        String lastAssistantTimestamp = null;

        for (SessionMessage msg : log.getMessages()) {
            String msgTimestamp = msg.getTimestamp();

            if ("assistant".equals(msg.getType()) && msg.getContent() != null) {
                if (msgTimestamp != null) lastAssistantTimestamp = msgTimestamp;

                // Token usage
                if (msg.getInputTokens() != null) inputTokens += msg.getInputTokens();
                if (msg.getOutputTokens() != null) outputTokens += msg.getOutputTokens();

                // Tool use blocks
                for (SessionMessage.ContentBlock block : msg.getContentBlocks()) {
                    if (!"tool_use".equals(block.getType())) continue;
                    String toolName = block.getName();
                    if (toolName == null) continue;

                    toolCounts.merge(toolName, 1, Integer::sum);
                    if ("Task".equals(toolName) || "dispatch_agent".equals(toolName)) usesTaskAgent = true;
                    if (toolName.startsWith("mcp__")) usesMcp = true;
                    if ("WebSearch".equals(toolName)) usesWebSearch = true;
                    if ("WebFetch".equals(toolName)) usesWebFetch = true;

                    String filePath = block.getFilePath();
                    if (filePath != null && !filePath.isEmpty()) {
                        String lang = getLanguageFromPath(filePath);
                        if (lang != null) languages.merge(lang, 1, Integer::sum);
                        if ("Edit".equals(toolName) || "Write".equals(toolName)) {
                            filesModified.add(filePath);
                        }
                    }

                    String command = block.getCommand();
                    if (command != null) {
                        if (command.contains("git commit")) gitCommits++;
                        if (command.contains("git push")) gitPushes++;
                    }

                    // Count lines from Edit tool diffs
                    if ("Edit".equals(toolName) && block.getDiffLinesAdded() != null) {
                        linesAdded += block.getDiffLinesAdded();
                        linesRemoved += Optional.ofNullable(block.getDiffLinesRemoved()).orElse(0);
                    }

                    // Count lines from Write tool (all added)
                    if ("Write".equals(toolName) && block.getWriteContent() != null) {
                        long newlines = block.getWriteContent().chars()
                            .filter(c -> c == '\n').count();
                        linesAdded += (int) newlines + 1;
                    }
                }
            }

            if ("user".equals(msg.getType())) {
                boolean isHumanMessage = msg.hasHumanText();

                if (isHumanMessage) {
                    if (msgTimestamp != null) {
                        try {
                            Instant msgInstant = Instant.parse(msgTimestamp);
                            int hour = java.time.ZonedDateTime
                                .ofInstant(msgInstant, java.time.ZoneId.systemDefault())
                                .getHour();
                            messageHours.add(hour);
                            userMessageTimestamps.add(msgTimestamp);
                        } catch (Exception ignored) {}

                        if (lastAssistantTimestamp != null) {
                            try {
                                double responseTimeSec =
                                    (Instant.parse(msgTimestamp).toEpochMilli()
                                        - Instant.parse(lastAssistantTimestamp).toEpochMilli()) / 1000.0;
                                if (responseTimeSec > 2 && responseTimeSec < 3600) {
                                    userResponseTimes.add(responseTimeSec);
                                }
                            } catch (Exception ignored) {}
                        }
                    }

                    // Interruptions
                    String textContent = msg.getTextContent();
                    if (textContent != null && textContent.contains("[Request interrupted by user")) {
                        userInterruptions++;
                    }
                }

                // Tool errors
                for (SessionMessage.ContentBlock block : msg.getContentBlocks()) {
                    if ("tool_result".equals(block.getType()) && Boolean.TRUE.equals(block.getIsError())) {
                        toolErrors++;
                        String category = categorizeTooError(block.getResultContent());
                        toolErrorCategories.merge(category, 1, Integer::sum);
                    }
                }
            }
        }

        return new ToolStats(toolCounts, languages, gitCommits, gitPushes, inputTokens, outputTokens,
            userInterruptions, userResponseTimes, toolErrors, toolErrorCategories,
            usesTaskAgent, usesMcp, usesWebSearch, usesWebFetch,
            linesAdded, linesRemoved, filesModified, messageHours, userMessageTimestamps);
    }

    /**
     * Convert a session log to a SessionMeta record.
     * Translated from logToSessionMeta() in insights.ts
     */
    public SessionMeta logToSessionMeta(SessionLogOption log) {
        ToolStats stats = extractToolStats(log);
        String sessionId = log.getSessionId() != null ? log.getSessionId() : "unknown";
        String startTime = log.getCreated() != null ? log.getCreated().toString() : "";
        int durationMinutes = 0;
        if (log.getCreated() != null && log.getModified() != null) {
            durationMinutes = (int) Math.round(
                (log.getModified().toEpochMilli() - log.getCreated().toEpochMilli()) / 60_000.0);
        }

        int userMessageCount = 0, assistantMessageCount = 0;
        for (SessionMessage msg : log.getMessages()) {
            if ("assistant".equals(msg.getType())) assistantMessageCount++;
            if ("user".equals(msg.getType()) && msg.hasHumanText()) userMessageCount++;
        }

        SessionMeta meta = new SessionMeta();
        meta.setSessionId(sessionId);
        meta.setProjectPath(log.getProjectPath() != null ? log.getProjectPath() : "");
        meta.setStartTime(startTime);
        meta.setDurationMinutes(durationMinutes);
        meta.setUserMessageCount(userMessageCount);
        meta.setAssistantMessageCount(assistantMessageCount);
        meta.setToolCounts(stats.toolCounts());
        meta.setLanguages(stats.languages());
        meta.setGitCommits(stats.gitCommits());
        meta.setGitPushes(stats.gitPushes());
        meta.setInputTokens(stats.inputTokens());
        meta.setOutputTokens(stats.outputTokens());
        meta.setFirstPrompt(log.getFirstPrompt() != null ? log.getFirstPrompt() : "");
        meta.setSummary(log.getSummary());
        meta.setUserInterruptions(stats.userInterruptions());
        meta.setUserResponseTimes(stats.userResponseTimes());
        meta.setToolErrors(stats.toolErrors());
        meta.setToolErrorCategories(stats.toolErrorCategories());
        meta.setUsesTaskAgent(stats.usesTaskAgent());
        meta.setUsesMcp(stats.usesMcp());
        meta.setUsesWebSearch(stats.usesWebSearch());
        meta.setUsesWebFetch(stats.usesWebFetch());
        meta.setLinesAdded(stats.linesAdded());
        meta.setLinesRemoved(stats.linesRemoved());
        meta.setFilesModified(stats.filesModified().size());
        meta.setMessageHours(stats.messageHours());
        meta.setUserMessageTimestamps(stats.userMessageTimestamps());
        return meta;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static String getLanguageFromPath(String filePath) {
        if (filePath == null) return null;
        int dot = filePath.lastIndexOf('.');
        if (dot < 0) return null;
        return EXTENSION_TO_LANGUAGE.get(filePath.substring(dot).toLowerCase());
    }

    private static String categorizeTooError(String resultContent) {
        if (resultContent == null) return "Other";
        String lower = resultContent.toLowerCase();
        if (lower.contains("exit code"))                                          return "Command Failed";
        if (lower.contains("rejected") || lower.contains("doesn't want"))        return "User Rejected";
        if (lower.contains("string to replace not found") || lower.contains("no changes")) return "Edit Failed";
        if (lower.contains("modified since read"))                               return "File Changed";
        if (lower.contains("exceeds maximum") || lower.contains("too large"))    return "File Too Large";
        if (lower.contains("file not found") || lower.contains("does not exist")) return "File Not Found";
        return "Other";
    }

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    /**
     * Raw tool statistics extracted from a session log.
     * Mirrors the return type of extractToolStats() in insights.ts
     */
    public record ToolStats(
        Map<String, Integer> toolCounts,
        Map<String, Integer> languages,
        int gitCommits,
        int gitPushes,
        long inputTokens,
        long outputTokens,
        int userInterruptions,
        List<Double> userResponseTimes,
        int toolErrors,
        Map<String, Integer> toolErrorCategories,
        boolean usesTaskAgent,
        boolean usesMcp,
        boolean usesWebSearch,
        boolean usesWebFetch,
        int linesAdded,
        int linesRemoved,
        Set<String> filesModified,
        List<Integer> messageHours,
        List<String> userMessageTimestamps
    ) {}

    /**
     * Thin wrapper around session log data consumed by this service.
     * In a full implementation this would correspond to the LogOption type from
     * sessionStorage.ts.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SessionLogOption {
        private String sessionId;
        private String projectPath;
        private String firstPrompt;
        private String summary;
        private Instant created;
        private Instant modified;
        private List<SessionMessage> messages = new ArrayList<>();

        public String getProjectPath() { return projectPath; }
        public void setProjectPath(String v) { projectPath = v; }
        public String getFirstPrompt() { return firstPrompt; }
        public void setFirstPrompt(String v) { firstPrompt = v; }
        public String getSummary() { return summary; }
        public void setSummary(String v) { summary = v; }
        public Instant getCreated() { return created; }
        public void setCreated(Instant v) { created = v; }
        public Instant getModified() { return modified; }
        public void setModified(Instant v) { modified = v; }
        public List<SessionMessage> getMessages() { return messages; }
        public void setMessages(List<SessionMessage> v) { messages = v; }
    
        public String getSessionId() { return sessionId; }
    }

    /**
     * A single message in a session log.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SessionMessage {
        private String type;          // "user" | "assistant"
        private String timestamp;
        private Long inputTokens;
        private Long outputTokens;
        private Object content;       // raw content — string or list
        private List<ContentBlock> contentBlocks = new ArrayList<>();

        public String getType() { return type; }
        public void setType(String v) { type = v; }

        /** True when this is an actual human text message (not just tool_result). */
        public boolean hasHumanText() {
            if (content instanceof String s) return !s.isBlank();
            if (content instanceof List<?> blocks) {
                for (Object b : blocks) {
                    if (b instanceof ContentBlock cb && "text".equals(cb.getType())) return true;
                }
            }
            return false;
        }

        /** Return the first text content or null. */
        public String getTextContent() {
            if (content instanceof String s) return s;
            if (content instanceof List<?> blocks) {
                for (Object b : blocks) {
                    if (b instanceof ContentBlock cb && "text".equals(cb.getType())) {
                        return cb.getText();
                    }
                }
            }
            return null;
        }

        @Data
        @lombok.NoArgsConstructor
        @lombok.AllArgsConstructor
        public static class ContentBlock {
            private String type;     // "tool_use" | "tool_result" | "text"
            private String name;     // tool name for tool_use blocks
            private String text;     // text for text blocks
            private String filePath;
            private String command;
            private String writeContent;
            private Integer diffLinesAdded;
            private Integer diffLinesRemoved;
            private Boolean isError;
            private String resultContent;

        public String getType() { return type; }
        public void setType(String v) { type = v; }
        public String getName() { return name; }
        public void setName(String v) { name = v; }
        public String getText() { return text; }
        public void setText(String v) { text = v; }
        public String getFilePath() { return filePath; }
        public void setFilePath(String v) { filePath = v; }
        public String getCommand() { return command; }
        public void setCommand(String v) { command = v; }
        public String getWriteContent() { return writeContent; }
        public void setWriteContent(String v) { writeContent = v; }
        public Integer getDiffLinesAdded() { return diffLinesAdded; }
        public void setDiffLinesAdded(Integer v) { diffLinesAdded = v; }
        public Integer getDiffLinesRemoved() { return diffLinesRemoved; }
        public void setDiffLinesRemoved(Integer v) { diffLinesRemoved = v; }
        public boolean isIsError() { return isError; }
        public void setIsError(Boolean v) { isError = v; }
        public String getResultContent() { return resultContent; }
        public void setResultContent(String v) { resultContent = v; }
        
        public Boolean getIsError() { return isError; }
    }
    
        public Object getContent() { return content; }
    
        public List<ContentBlock> getContentBlocks() { return contentBlocks; }
    
        public Long getInputTokens() { return inputTokens; }
    
        public Long getOutputTokens() { return outputTokens; }
    
        public String getTimestamp() { return timestamp; }
    }
}
