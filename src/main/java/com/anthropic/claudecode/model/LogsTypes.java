package com.anthropic.claudecode.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Log and transcript entry types for session storage and listing.
 * Translated from src/types/logs.ts
 *
 * Note: {@link LogOption} and {@link SerializedMessage} are also available in
 * the standalone {@link LogTypes} and {@link LogOption} classes. This class
 * provides the complete set of transcript entry types plus the {@code Entry}
 * sealed union defined at the bottom of logs.ts.
 */
public class LogsTypes {

    // -------------------------------------------------------------------------
    // SerializedMessage
    // -------------------------------------------------------------------------

    /**
     * A message serialized to the session transcript on disk.
     * Extends the base Message shape with session-level metadata.
     */
    @Data
    @lombok.Builder
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SerializedMessage {
        private String cwd;
        private String userType;
        private String entrypoint;
        private String sessionId;
        private String timestamp;
        private String version;
        private String gitBranch;
        private String slug;
    

        public SerializedMessage() {}
        public SerializedMessage(String cwd, String userType, String entrypoint, String sessionId, String timestamp, String version, String gitBranch, String slug) {
            this.cwd = cwd;
            this.userType = userType;
            this.entrypoint = entrypoint;
            this.sessionId = sessionId;
            this.timestamp = timestamp;
            this.version = version;
            this.gitBranch = gitBranch;
            this.slug = slug;
        }
    }

    // -------------------------------------------------------------------------
    // LogOption
    // -------------------------------------------------------------------------

    /**
     * Metadata for a session log entry used in the session browser.
     */
    @Data
    @lombok.Builder
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LogOption {
        private String date;
        private List<SerializedMessage> messages;
        private String fullPath;
        private int value;
        private Date created;
        private Date modified;
        private String firstPrompt;
        private int messageCount;
        private Long fileSize;
        private boolean isSidechain;
        private Boolean isLite;
        private String sessionId;
        private String teamName;
        private String agentName;
        private String agentColor;
        private String agentSetting;
        private Boolean isTeammate;
        private String leafUuid;
        private String summary;
        private String customTitle;
        private String tag;
        /** File history snapshots associated with this session. */
        private List<Map<String, Object>> fileHistorySnapshots;
        /** Attribution snapshots for commit attribution tracking. */
        private List<AttributionSnapshotMessage> attributionSnapshots;
        /** Ordered context-collapse commits for this session. */
        private List<ContextCollapseCommitEntry> contextCollapseCommits;
        /** Last-wins staged queue and spawn state snapshot. */
        private ContextCollapseSnapshotEntry contextCollapseSnapshot;
        private String gitBranch;
        private String projectPath;
        private Integer prNumber;
        private String prUrl;
        private String prRepository;
        /** Session mode: "coordinator" or "normal". */
        private String mode;
        /** Worktree state at session end (null = exited, absent = never entered). */
        private PersistedWorktreeSession worktreeSession;
        /** Replacement decisions for resume reconstruction. */
        private List<Map<String, Object>> contentReplacements;
    
        public Date getCreated() { return created; }
    
        public Date getModified() { return modified; }
    }

    // -------------------------------------------------------------------------
    // Transcript metadata message types
    // -------------------------------------------------------------------------

    /** AI-generated or user-provided conversation summary. */
    public record SummaryMessage(
        String type,       // "summary"
        String leafUuid,
        String summary
    ) {}

    /** User-set custom title for the session. */
    public record CustomTitleMessage(
        String type,       // "custom-title"
        String sessionId,
        String customTitle
    ) {}

    /**
     * AI-generated session title. Distinct from {@link CustomTitleMessage}:
     * user renames always win, and AI titles are not re-appended on resume.
     */
    public record AiTitleMessage(
        String type,       // "ai-title"
        String sessionId,
        String aiTitle
    ) {}

    /** Records the last user prompt in the session (for display in session lists). */
    public record LastPromptMessage(
        String type,       // "last-prompt"
        String sessionId,
        String lastPrompt
    ) {}

    /**
     * Periodic fork-generated summary of what the agent is currently doing.
     * Written every min(5 steps, 2 min) by forking the main thread mid-turn.
     */
    public record TaskSummaryMessage(
        String type,       // "task-summary"
        String sessionId,
        String summary,
        String timestamp
    ) {}

    /** User-applied tag for a session (searchable in /resume). */
    public record TagMessage(
        String type,       // "tag"
        String sessionId,
        String tag
    ) {}

    /** Agent display-name override (from /rename or swarm configuration). */
    public record AgentNameMessage(
        String type,       // "agent-name"
        String sessionId,
        String agentName
    ) {}

    /** Agent color override (from /rename or swarm configuration). */
    public record AgentColorMessage(
        String type,       // "agent-color"
        String sessionId,
        String agentColor
    ) {}

    /** Agent definition used (from --agent flag or settings.agent). */
    public record AgentSettingMessage(
        String type,       // "agent-setting"
        String sessionId,
        String agentSetting
    ) {}

    /**
     * Links a session to a GitHub pull request.
     */
    public record PRLinkMessage(
        String type,          // "pr-link"
        String sessionId,
        int prNumber,
        String prUrl,
        String prRepository,  // "owner/repo"
        String timestamp      // ISO timestamp when linked
    ) {}

    /** Session mode entry (coordinator / normal). */
    public record ModeEntry(
        String type,      // "mode"
        String sessionId,
        String mode       // "coordinator" | "normal"
    ) {}

    // -------------------------------------------------------------------------
    // PersistedWorktreeSession
    // -------------------------------------------------------------------------

    /**
     * Worktree session state persisted to the transcript for resume.
     * Subset of WorktreeSession — excludes ephemeral analytics-only fields.
     */
    public record PersistedWorktreeSession(
        String originalCwd,
        String worktreePath,
        String worktreeName,
        String worktreeBranch,
        String originalBranch,
        String originalHeadCommit,
        String sessionId,
        String tmuxSessionName,
        Boolean hookBased
    ) {}

    /**
     * Records whether the session is currently inside a worktree.
     * Last-wins: an enter writes the session, an exit writes null.
     */
    public record WorktreeStateEntry(
        String type,           // "worktree-state"
        String sessionId,
        PersistedWorktreeSession worktreeSession  // null = exited
    ) {}

    // -------------------------------------------------------------------------
    // ContentReplacementEntry
    // -------------------------------------------------------------------------

    /**
     * Records content blocks whose in-context representation was replaced with a
     * smaller stub. Replayed on resume for prompt cache stability.
     */
    @Data
    @lombok.Builder
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContentReplacementEntry {
        private String type;       // "content-replacement"
        private String sessionId;
        private String agentId;    // set for subagent sidechains
        private List<Map<String, Object>> replacements;
    }

    // -------------------------------------------------------------------------
    // FileHistorySnapshotMessage
    // -------------------------------------------------------------------------

    /** File history snapshot stored in the transcript. */
    public record FileHistorySnapshotMessage(
        String type,           // "file-history-snapshot"
        String messageId,
        Map<String, Object> snapshot,
        boolean isSnapshotUpdate
    ) {}

    // -------------------------------------------------------------------------
    // Attribution types
    // -------------------------------------------------------------------------

    /** Per-file attribution state for commit attribution tracking. */
    public record FileAttributionState(
        String contentHash,        // SHA-256 hash of file content
        int claudeContribution,    // Characters written by Claude
        long mtime                 // File modification time
    ) {}

    /**
     * Attribution snapshot stored in the transcript.
     * Tracks character-level contributions by Claude for commit attribution.
     */
    @Data
    @lombok.Builder
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AttributionSnapshotMessage {
        private String type;        // "attribution-snapshot"
        private String messageId;
        private String surface;     // e.g. "cli", "ide", "web", "api"
        private Map<String, FileAttributionState> fileStates;
        private Integer promptCount;
        private Integer promptCountAtLastCommit;
        private Integer permissionPromptCount;
        private Integer permissionPromptCountAtLastCommit;
        private Integer escapeCount;
        private Integer escapeCountAtLastCommit;
    }

    // -------------------------------------------------------------------------
    // TranscriptMessage
    // -------------------------------------------------------------------------

    /**
     * A fully-resolved transcript message (SerializedMessage + transcript fields).
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TranscriptMessage extends SerializedMessage {
        private String parentUuid;
        private String logicalParentUuid;
        private boolean isSidechain;
        private String gitBranch;
        private String agentId;
        private String teamName;
        private String agentName;
        private String agentColor;
        private String promptId;

        public String getParentUuid() { return parentUuid; }
        public void setParentUuid(String v) { parentUuid = v; }
        public String getLogicalParentUuid() { return logicalParentUuid; }
        public void setLogicalParentUuid(String v) { logicalParentUuid = v; }
        public boolean isIsSidechain() { return isSidechain; }
        public void setIsSidechain(boolean v) { isSidechain = v; }
        public String getGitBranch() { return gitBranch; }
        public void setGitBranch(String v) { gitBranch = v; }
        public String getAgentId() { return agentId; }
        public void setAgentId(String v) { agentId = v; }
        public String getTeamName() { return teamName; }
        public void setTeamName(String v) { teamName = v; }
        public String getAgentName() { return agentName; }
        public void setAgentName(String v) { agentName = v; }
        public String getAgentColor() { return agentColor; }
        public void setAgentColor(String v) { agentColor = v; }
        public String getPromptId() { return promptId; }
        public void setPromptId(String v) { promptId = v; }
    }

    // -------------------------------------------------------------------------
    // SpeculationAcceptMessage
    // -------------------------------------------------------------------------

    /** Records that a speculative response was accepted by the user. */
    public record SpeculationAcceptMessage(
        String type,           // "speculation-accept"
        String timestamp,
        long timeSavedMs
    ) {}

    // -------------------------------------------------------------------------
    // ContextCollapse types
    // -------------------------------------------------------------------------

    /**
     * Persisted context-collapse commit.
     * Only persists enough to reconstruct the splice instruction and summary
     * placeholder on resume.
     */
    @Data
    @lombok.Builder
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContextCollapseCommitEntry {
        private String type;              // "marble-origami-commit"
        private String sessionId;
        private String collapseId;        // 16-digit collapse ID
        private String summaryUuid;       // uuid of the summary placeholder
        private String summaryContent;    // full <collapsed …> placeholder string
        private String summary;           // plain summary text for ctx_inspect
        private String firstArchivedUuid;
        private String lastArchivedUuid;
    }

    /** A staged span entry inside a {@link ContextCollapseSnapshotEntry}. */
    public record StagedSpan(
        String startUuid,
        String endUuid,
        String summary,
        double risk,
        long stagedAt
    ) {}

    /**
     * Snapshot of the staged queue and spawn trigger state for context collapse.
     * Last-wins — only the most recent snapshot entry is applied on restore.
     */
    @Data
    @lombok.Builder
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContextCollapseSnapshotEntry {
        private String type;          // "marble-origami-snapshot"
        private String sessionId;
        private List<StagedSpan> staged;
        private boolean armed;
        private long lastSpawnTokens;
    }

    // -------------------------------------------------------------------------
    // Entry — sealed union of all transcript entry types
    // -------------------------------------------------------------------------

    /**
     * Union of all entry types that can appear in a session transcript.
     * Translated from the {@code Entry} union type in logs.ts.
     *
     * In Java we represent this as a sealed interface so callers can use
     * pattern matching ({@code instanceof} with record patterns) to dispatch.
     */
    public sealed interface Entry
        permits LogsTypes.TranscriptMessageEntry,
                LogsTypes.SummaryEntry,
                LogsTypes.CustomTitleEntry,
                LogsTypes.AiTitleEntry,
                LogsTypes.LastPromptEntry,
                LogsTypes.TaskSummaryEntry,
                LogsTypes.TagEntry,
                LogsTypes.AgentNameEntry,
                LogsTypes.AgentColorEntry,
                LogsTypes.AgentSettingEntry,
                LogsTypes.PRLinkEntry,
                LogsTypes.FileHistorySnapshotEntry,
                LogsTypes.AttributionSnapshotEntry,
                LogsTypes.SpeculationAcceptEntry,
                LogsTypes.ModeEntry2,
                LogsTypes.WorktreeStateEntry2,
                LogsTypes.ContentReplacementEntry2,
                LogsTypes.ContextCollapseCommitEntry2,
                LogsTypes.ContextCollapseSnapshotEntry2 {}

    public record TranscriptMessageEntry(TranscriptMessage message) implements Entry {}
    public record SummaryEntry(SummaryMessage message) implements Entry {}
    public record CustomTitleEntry(CustomTitleMessage message) implements Entry {}
    public record AiTitleEntry(AiTitleMessage message) implements Entry {}
    public record LastPromptEntry(LastPromptMessage message) implements Entry {}
    public record TaskSummaryEntry(TaskSummaryMessage message) implements Entry {}
    public record TagEntry(TagMessage message) implements Entry {}
    public record AgentNameEntry(AgentNameMessage message) implements Entry {}
    public record AgentColorEntry(AgentColorMessage message) implements Entry {}
    public record AgentSettingEntry(AgentSettingMessage message) implements Entry {}
    public record PRLinkEntry(PRLinkMessage message) implements Entry {}
    public record FileHistorySnapshotEntry(FileHistorySnapshotMessage message) implements Entry {}
    public record AttributionSnapshotEntry(AttributionSnapshotMessage message) implements Entry {}
    public record SpeculationAcceptEntry(SpeculationAcceptMessage message) implements Entry {}
    public record ModeEntry2(ModeEntry message) implements Entry {}
    public record WorktreeStateEntry2(WorktreeStateEntry message) implements Entry {}
    public record ContentReplacementEntry2(ContentReplacementEntry message) implements Entry {}
    public record ContextCollapseCommitEntry2(ContextCollapseCommitEntry message) implements Entry {}
    public record ContextCollapseSnapshotEntry2(ContextCollapseSnapshotEntry message) implements Entry {}

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /**
     * Sort a list of log options by modification date (newest first), then by
     * creation date (newest first) as a tiebreaker.
     * Translated from sortLogs() in logs.ts
     */
    public static List<LogOption> sortLogs(List<LogOption> logs) {
        return logs.stream()
            .sorted(Comparator
                .<LogOption, Long>comparing(
                    l -> l.getModified() != null ? l.getModified().getTime() : 0L,
                    Comparator.reverseOrder()
                )
                .thenComparing(
                    l -> l.getCreated() != null ? l.getCreated().getTime() : 0L,
                    Comparator.reverseOrder()
                )
            )
            .toList();
    }

    private LogsTypes() {}
}
