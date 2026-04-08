package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.ContentBlock;
import com.anthropic.claudecode.model.Message;
import com.anthropic.claudecode.util.EnvUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Session storage service.
 * Translated from src/utils/sessionStorage.ts
 *
 * Manages persistent storage of conversation transcripts (JSONL) and session
 * metadata on the local filesystem. Each session is stored as a .jsonl file
 * under ~/.config/claude/projects/<sanitized-project-path>/<session-id>.jsonl
 */
@Slf4j
@Service
public class SessionStorageService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SessionStorageService.class);

    private static final String PROJECTS_SUBDIR = "projects";

    /** 50 MB — prevents OOM when loading large session files. */
    public static final long MAX_TRANSCRIPT_READ_BYTES = 50L * 1024 * 1024;

    private final ObjectMapper objectMapper;

    @Autowired
    public SessionStorageService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Path helpers — translated from getProjectsDir() / getTranscriptPath() in
    // sessionStorage.ts
    // -------------------------------------------------------------------------

    /**
     * Return the root projects directory (contains one sub-dir per project).
     * Translated from getProjectsDir() in sessionStorage.ts
     */
    public String getProjectsDir() {
        return EnvUtils.getClaudeConfigHomeDir() + "/" + PROJECTS_SUBDIR;
    }

    /**
     * Return the project-specific directory for the given absolute project path.
     * The project path is sanitised to a safe directory name.
     * Translated from getProjectDir() in sessionStorage.ts
     */
    public String getProjectDir(String projectPath) {
        if (projectPath == null) projectPath = System.getProperty("user.dir", "");
        // Replace path separators with dashes, strip everything non-alphanumeric except . and -
        String sanitized = projectPath.replace("/", "-").replace("\\", "-")
                .replaceAll("[^a-zA-Z0-9._-]", "_")
                .replaceAll("-+", "-")
                .replaceAll("^-+", "");
        return getProjectsDir() + "/" + sanitized;
    }

    /**
     * Return the JSONL transcript path for a session in a given project directory.
     * Translated from getTranscriptPath() / getTranscriptPathForSession() in sessionStorage.ts
     */
    public String getTranscriptPath(String projectDir, String sessionId) {
        return projectDir + "/" + sessionId + ".jsonl";
    }

    /**
     * Return the project directory as a Path.
     */
    public java.nio.file.Path getProjectDirPath(String projectPath) {
        return java.nio.file.Paths.get(getProjectDir(projectPath));
    }

    /**
     * Return the transcript path for the given session ID (uses current project dir).
     */
    public java.nio.file.Path getTranscriptPath(String sessionId) {
        String projectDir = getProjectDir(System.getProperty("user.dir", ""));
        return java.nio.file.Paths.get(getTranscriptPath(projectDir, sessionId));
    }

    /**
     * Return the transcript path for the given session ID (alias).
     */
    public java.nio.file.Path getTranscriptPathForSession(String sessionId) {
        return getTranscriptPath(sessionId);
    }

    /**
     * Save a custom title for a session.
     */
    public void saveCustomTitle(String sessionId, String title, String forkPath) {
        // Stub implementation
        log.debug("saveCustomTitle: sessionId={}, title={}, forkPath={}", sessionId, title, forkPath);
    }

    /**
     * Session info record for search results.
     */
    public record SessionInfo(String sessionId, String customTitle, String projectPath) {}

    /**
     * Search sessions by custom title.
     */
    public java.util.List<SessionInfo> searchSessionsByCustomTitle(String title, boolean exact) {
        return java.util.Collections.emptyList();
    }

    // -------------------------------------------------------------------------
    // Write operations
    // -------------------------------------------------------------------------

    /**
     * Append a single message to the session's JSONL transcript.
     * Translated from the appendFile pattern in sessionStorage.ts
     */
    public void appendToTranscript(String projectDir, String sessionId, Message message) {
        String transcriptPath = getTranscriptPath(projectDir, sessionId);
        try {
            new File(transcriptPath).getParentFile().mkdirs();
            String json = objectMapper.writeValueAsString(message) + "\n";
            Files.writeString(Paths.get(transcriptPath), json,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.warn("Could not append to transcript {}: {}", transcriptPath, e.getMessage());
        }
    }

    /**
     * Save session metadata as a JSON sidecar file alongside the transcript.
     */
    public void saveSessionMetadata(String projectDir, String sessionId,
                                     SessionMetadata metadata) {
        String metaPath = projectDir + "/" + sessionId + ".meta.json";
        try {
            new File(metaPath).getParentFile().mkdirs();
            objectMapper.writeValue(new File(metaPath), metadata);
        } catch (Exception e) {
            log.warn("Could not save session metadata {}: {}", metaPath, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Read operations
    // -------------------------------------------------------------------------

    /**
     * Load all messages from a JSONL transcript file.
     * Skips blank lines and unrecognised entry types.
     * Translated from loadTranscriptFile() in sessionStorage.ts
     */
    public List<Message> loadTranscript(String sessionId) {
        String projectDir = getProjectDir(System.getProperty("user.dir", ""));
        String transcriptPath = getTranscriptPath(projectDir, sessionId);
        File file = new File(transcriptPath);
        if (!file.exists()) return List.of();

        List<Message> messages = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(file.toPath());
            for (String line : lines) {
                if (line.isBlank()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = objectMapper.readValue(line, Map.class);
                    String type = (String) data.get("type");
                    if ("user".equals(type)) {
                        messages.add(objectMapper.readValue(line, Message.UserMessage.class));
                    } else if ("assistant".equals(type)) {
                        messages.add(objectMapper.readValue(line, Message.AssistantMessage.class));
                    }
                    // attachment / system / progress entries are intentionally skipped here
                } catch (Exception e) {
                    log.debug("Could not parse transcript line: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Could not load transcript {}: {}", transcriptPath, e.getMessage());
        }
        return messages;
    }

    /**
     * List all sessions for a project directory, most-recent first.
     */
    public List<SessionMetadata> listSessions() {
        String projectDir = getProjectDir(System.getProperty("user.dir", ""));
        File dir = new File(projectDir);
        if (!dir.exists()) return List.of();

        List<SessionMetadata> sessions = new ArrayList<>();
        File[] metaFiles = dir.listFiles((d, name) -> name.endsWith(".meta.json"));
        if (metaFiles == null) return sessions;

        for (File mf : metaFiles) {
            try {
                SessionMetadata meta = objectMapper.readValue(mf, SessionMetadata.class);
                sessions.add(meta);
            } catch (Exception e) {
                // skip corrupted metadata
            }
        }

        sessions.sort((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()));
        return sessions;
    }

    /**
     * Load the N most-recent sessions.
     */
    public List<SessionLog> loadRecentSessions(int limit) {
        List<SessionMetadata> all = listSessions();
        List<SessionLog> result = new ArrayList<>();
        int count = 0;
        for (SessionMetadata meta : all) {
            if (count++ >= limit) break;
            result.add(new SessionLog(
                    meta.getSessionId(),
                    meta.getName() != null ? meta.getName() : meta.getFirstPrompt(),
                    meta.getTimestamp()));
        }
        return result;
    }

    /** Find a session by exact session ID. */
    public Optional<SessionLog> findSessionById(String sessionId) {
        for (SessionMetadata meta : listSessions()) {
            if (meta.getSessionId().equals(sessionId)) {
                SessionLog log = new SessionLog(meta.getSessionId(),
                        meta.getName() != null ? meta.getName() : meta.getFirstPrompt(),
                        meta.getTimestamp());
                return Optional.of(log);
            }
        }
        return Optional.empty();
    }

    /** Get the most recent session log for a given session ID (direct file lookup). */
    public Optional<SessionLog> getLastSessionLog(String sessionId) {
        return findSessionById(sessionId);
    }

    /** Search sessions by title (name or first prompt). */
    public java.util.List<SessionLog> searchByTitle(String title) {
        java.util.List<SessionLog> result = new java.util.ArrayList<>();
        String lower = title.toLowerCase(java.util.Locale.ROOT);
        for (SessionMetadata meta : listSessions()) {
            String name = meta.getName() != null ? meta.getName() : meta.getFirstPrompt();
            if (name != null && name.toLowerCase(java.util.Locale.ROOT).contains(lower)) {
                result.add(new SessionLog(meta.getSessionId(), name, meta.getTimestamp()));
            }
        }
        return result;
    }

    /** Check if a session is in the same project/worktree as the given current directory. */
    public boolean isSameProjectOrWorktree(SessionLog session, String currentCwd) {
        if (session.getWorkingDirectory() == null || currentCwd == null) return true;
        return session.getWorkingDirectory().equals(currentCwd)
                || session.getWorkingDirectory().startsWith(currentCwd)
                || currentCwd.startsWith(session.getWorkingDirectory());
    }

    /**
     * Find a session whose ID starts with the given prefix (or whose name / first
     * prompt contains the search term).
     */
    public Optional<SessionLog> findSession(String searchTerm) {
        for (SessionMetadata meta : listSessions()) {
            if (meta.getSessionId().startsWith(searchTerm)
                    || (meta.getName()        != null && meta.getName().contains(searchTerm))
                    || (meta.getFirstPrompt() != null && meta.getFirstPrompt().contains(searchTerm))) {
                return Optional.of(new SessionLog(
                        meta.getSessionId(),
                        meta.getName() != null ? meta.getName() : meta.getFirstPrompt(),
                        meta.getTimestamp()));
            }
        }
        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // Nested value types
    // -------------------------------------------------------------------------

    public static class SessionLog {
        private String sessionId;
        private String title;
        private long timestamp;
        private boolean sidechain;
        private String workingDirectory;

        public SessionLog() {}
        public SessionLog(String sessionId, String title, long timestamp) {
            this.sessionId = sessionId; this.title = title; this.timestamp = timestamp;
        }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String v) { sessionId = v; }
        public String getTitle() { return title; }
        public void setTitle(String v) { title = v; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long v) { timestamp = v; }
        public boolean isSidechain() { return sidechain; }
        public void setSidechain(boolean v) { sidechain = v; }
        public String getWorkingDirectory() { return workingDirectory; }
        public void setWorkingDirectory(String v) { workingDirectory = v; }
    }

    public static class SessionMetadata {
        private String sessionId;
        private long timestamp;
        private String projectPath;
        private String firstPrompt;
        private String name;
        private String model;
        private int messageCount;
        private String summary;
        private String tag;
        private String gitBranch;

        public SessionMetadata() {}

        public String getSessionId() { return sessionId; }
        public void setSessionId(String v) { sessionId = v; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long v) { timestamp = v; }
        public String getProjectPath() { return projectPath; }
        public void setProjectPath(String v) { projectPath = v; }
        public String getFirstPrompt() { return firstPrompt; }
        public void setFirstPrompt(String v) { firstPrompt = v; }
        public String getName() { return name; }
        public void setName(String v) { name = v; }
        public String getModel() { return model; }
        public void setModel(String v) { model = v; }
        public int getMessageCount() { return messageCount; }
        public void setMessageCount(int v) { messageCount = v; }
        public String getSummary() { return summary; }
        public void setSummary(String v) { summary = v; }
        public String getTag() { return tag; }
        public void setTag(String v) { tag = v; }
        public String getGitBranch() { return gitBranch; }
        public void setGitBranch(String v) { gitBranch = v; }

        public static SessionMetadataBuilder builder() { return new SessionMetadataBuilder(); }
        public static class SessionMetadataBuilder {
            private final SessionMetadata s = new SessionMetadata();
            public SessionMetadataBuilder sessionId(String v) { s.sessionId = v; return this; }
            public SessionMetadataBuilder timestamp(long v) { s.timestamp = v; return this; }
            public SessionMetadataBuilder projectPath(String v) { s.projectPath = v; return this; }
            public SessionMetadataBuilder firstPrompt(String v) { s.firstPrompt = v; return this; }
            public SessionMetadataBuilder name(String v) { s.name = v; return this; }
            public SessionMetadataBuilder model(String v) { s.model = v; return this; }
            public SessionMetadataBuilder messageCount(int v) { s.messageCount = v; return this; }
            public SessionMetadataBuilder summary(String v) { s.summary = v; return this; }
            public SessionMetadataBuilder tag(String v) { s.tag = v; return this; }
            public SessionMetadataBuilder gitBranch(String v) { s.gitBranch = v; return this; }
            public SessionMetadata build() { return s; }
        }
    }

    /** Metadata stored per-agent in a session transcript. */
    public record AgentMetadata(String agentId, String agentName, String worktreePath, String model,
                                String agentType, String description) {}

    /** Agent transcript containing messages and content replacement records. */
    public record AgentTranscript(List<Message> messages, List<Object> contentReplacements) {}

    /** Clear session metadata (title, tag, etc.). */
    public void clearSessionMetadata() {
        log.debug("clearSessionMetadata called");
        // In a full implementation, clear in-memory session metadata
    }

    /** Reset the session file pointer to the new session directory. */
    public void resetSessionFilePointer() {
        log.debug("resetSessionFilePointer called");
        // In a full implementation, update the current session pointer
    }

    /** Get the transcript path for a sub-agent. */
    public String getAgentTranscriptPath(String agentId) {
        String projectDir = getProjectDir(System.getProperty("user.dir", ""));
        return getTranscriptPath(projectDir, agentId);
    }

    // -------------------------------------------------------------------------
    // Additional helpers used by FeedbackService and related services
    // -------------------------------------------------------------------------

    /**
     * Load sub-agent transcript files for the given agent IDs.
     */
    public Map<String, Object> loadSubagentTranscripts(List<String> agentIds) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        if (agentIds == null || agentIds.isEmpty()) return result;
        String projectDir = getProjectDir(System.getProperty("user.dir", ""));
        for (String agentId : agentIds) {
            try {
                String path = getTranscriptPath(projectDir, agentId);
                java.io.File file = new java.io.File(path);
                if (file.exists()) {
                    List<String> lines = Files.readAllLines(file.toPath());
                    result.put(agentId, lines);
                }
            } catch (Exception e) {
                log.debug("Could not load sub-agent transcript for {}: {}", agentId, e.getMessage());
            }
        }
        return result;
    }

    /**
     * Normalize messages for the API by converting to plain objects.
     */
    public List<Object> normalizeMessagesForApi(List<Object> messages) {
        if (messages == null) return List.of();
        return messages;
    }

    /**
     * Extract agent IDs referenced within a message list.
     */
    public List<String> extractAgentIdsFromMessages(List<Object> messages) {
        List<String> agentIds = new ArrayList<>();
        if (messages == null) return agentIds;
        for (Object msg : messages) {
            if (msg instanceof Message.UserMessage um) {
                if (um.getContent() != null) {
                    for (ContentBlock block : um.getContent()) {
                        if (block instanceof ContentBlock.ToolResultBlock tr) {
                            // Agent IDs are typically embedded in tool result metadata
                        }
                    }
                }
                if (um.getSourceToolAssistantUUID() != null) {
                    agentIds.add(um.getSourceToolAssistantUUID());
                }
            }
        }
        return agentIds;
    }

    /**
     * Load an agent's transcript by agent ID.
     */
    public CompletableFuture<AgentTranscript> getAgentTranscript(String agentId) {
        List<Message> messages = loadTranscript(agentId);
        return CompletableFuture.completedFuture(new AgentTranscript(messages, List.of()));
    }

    /**
     * Read agent metadata (stored as a JSON sidecar alongside the transcript).
     */
    public CompletableFuture<AgentMetadata> readAgentMetadata(String agentId) {
        String projectDir = getProjectDir(System.getProperty("user.dir", ""));
        String metaPath = projectDir + "/" + agentId + ".agent.meta.json";
        File file = new File(metaPath);
        if (!file.exists()) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            AgentMetadata meta = objectMapper.readValue(file, AgentMetadata.class);
            return CompletableFuture.completedFuture(meta);
        } catch (Exception e) {
            log.debug("Could not read agent metadata for {}: {}", agentId, e.getMessage());
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Record messages to a sidechain transcript (agent sub-conversation).
     */
    public CompletableFuture<Void> recordSidechainTranscript(List<Message> messages, String agentId) {
        return recordSidechainTranscript(messages, agentId, null);
    }

    public CompletableFuture<Void> recordSidechainTranscript(List<Message> messages, String agentId, String prevUuid) {
        String projectDir = getProjectDir(System.getProperty("user.dir", ""));
        for (Message message : messages) {
            appendToTranscript(projectDir, agentId, message);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Write agent metadata sidecar.
     */
    public CompletableFuture<Void> writeAgentMetadata(String agentId, String agentType,
                                                       String worktreePath, String description) {
        String projectDir = getProjectDir(System.getProperty("user.dir", ""));
        String metaPath = projectDir + "/" + agentId + ".agent.meta.json";
        try {
            new File(metaPath).getParentFile().mkdirs();
            AgentMetadata meta = new AgentMetadata(agentId, null, worktreePath, null, agentType, description);
            objectMapper.writeValue(new File(metaPath), meta);
        } catch (Exception e) {
            log.debug("Could not write agent metadata for {}: {}", agentId, e.getMessage());
        }
        return CompletableFuture.completedFuture(null);
    }
}
