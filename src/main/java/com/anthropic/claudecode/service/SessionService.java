package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;
import lombok.Data;

/**
 * Session management service.
 * Combines session storage CRUD with session listing (listSessionsImpl.ts logic).
 * Translated from src/utils/listSessionsImpl.ts and session storage utilities.
 */
@Slf4j
@Service
public class SessionService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SessionService.class);


    private static final String SESSIONS_DIR = "sessions";
    private static final int READ_BATCH_SIZE = 32;

    private final ObjectMapper objectMapper;
    private final SettingsService settingsService;
    private final SessionStorageService sessionStorageService;

    // Current in-memory session state
    private String currentSessionId;
    private List<Message> currentMessages = new ArrayList<>();

    @Autowired
    public SessionService(ObjectMapper objectMapper,
                          SettingsService settingsService,
                          SessionStorageService sessionStorageService) {
        this.objectMapper = objectMapper;
        this.settingsService = settingsService;
        this.sessionStorageService = sessionStorageService;
    }

    // =========================================================================
    // SessionInfo — translated from SessionInfo in listSessionsImpl.ts
    // =========================================================================

    /**
     * Session metadata returned by {@link #listSessions(ListSessionsOptions)}.
     * Translated from SessionInfo in listSessionsImpl.ts
     */
    public record SessionInfo(
            String sessionId,
            String summary,
            long lastModified,
            Long fileSize,
            String customTitle,
            String firstPrompt,
            String gitBranch,
            String cwd,
            String tag,
            Long createdAt
    ) {}

    /**
     * Options for session listing.
     * Translated from ListSessionsOptions in listSessionsImpl.ts
     */
    public record ListSessionsOptions(
            String dir,
            Integer limit,
            Integer offset,
            Boolean includeWorktrees
    ) {
        public static ListSessionsOptions defaults() {
            return new ListSessionsOptions(null, null, null, null);
        }
    }

    // =========================================================================
    // listSessionsImpl — translated from listSessionsImpl() in listSessionsImpl.ts
    // =========================================================================

    /**
     * Lists sessions with metadata from stat + head/tail reads.
     *
     * When {@code dir} is provided, returns sessions for that project directory
     * (and optionally git worktrees). Otherwise returns sessions across all projects.
     * Translated from listSessionsImpl() in listSessionsImpl.ts
     */
    public CompletableFuture<List<SessionInfo>> listSessionsImpl(ListSessionsOptions options) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String dir = options != null ? options.dir() : null;
                Integer limit = options != null ? options.limit() : null;
                Integer offset = options != null ? options.offset() : null;

                int off = offset != null ? offset : 0;
                boolean doStat = (limit != null && limit > 0) || off > 0;

                String projectsDir = getProjectsDir();

                List<Candidate> candidates;
                if (dir != null) {
                    candidates = gatherProjectCandidates(dir, projectsDir, doStat);
                } else {
                    candidates = gatherAllCandidates(projectsDir, doStat);
                }

                if (!doStat) {
                    return readAllAndSort(candidates);
                }
                return applySortAndLimit(candidates, limit, off);
            } catch (Exception e) {
                log.warn("listSessionsImpl error: {}", e.getMessage());
                return List.of();
            }
        });
    }

    // =========================================================================
    // Candidate type — translated from Candidate in listSessionsImpl.ts
    // =========================================================================

    private record Candidate(String sessionId, Path filePath, long mtime, String projectPath) {}

    // =========================================================================
    // Candidate gathering helpers
    // =========================================================================

    private List<Candidate> gatherAllCandidates(String projectsDir, boolean doStat) {
        try {
            File dir = new File(projectsDir);
            File[] subdirs = dir.listFiles(File::isDirectory);
            if (subdirs == null) return List.of();

            List<Candidate> all = new ArrayList<>();
            for (File subdir : subdirs) {
                all.addAll(listCandidates(subdir.toPath(), doStat, null));
            }
            return all;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Candidate> gatherProjectCandidates(String dir, String projectsDir, boolean doStat) {
        try {
            // Simplified: find the matching project dir based on sanitized path
            String sanitized = sanitizePath(dir);
            File projectDir = new File(projectsDir, sanitized);
            if (!projectDir.isDirectory()) return List.of();
            return listCandidates(projectDir.toPath(), doStat, dir);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Lists candidate session .jsonl files in a directory.
     * Translated from listCandidates() in listSessionsImpl.ts
     */
    private List<Candidate> listCandidates(Path dir, boolean doStat, String projectPath) {
        try {
            File[] files = dir.toFile().listFiles(f -> f.getName().endsWith(".jsonl"));
            if (files == null) return List.of();

            List<Candidate> results = new ArrayList<>();
            for (File f : files) {
                String name = f.getName();
                String sessionId = name.substring(0, name.length() - 6); // strip .jsonl
                if (!isValidUuid(sessionId)) continue;
                long mtime = 0;
                if (doStat) {
                    try {
                        mtime = f.lastModified();
                    } catch (Exception e) {
                        continue;
                    }
                }
                results.add(new Candidate(sessionId, f.toPath(), mtime, projectPath));
            }
            return results;
        } catch (Exception e) {
            return List.of();
        }
    }

    // =========================================================================
    // Sort + limit — translated from applySortAndLimit() / readAllAndSort()
    // =========================================================================

    private List<SessionInfo> readAllAndSort(List<Candidate> candidates) {
        Map<String, SessionInfo> byId = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            SessionInfo info = readCandidate(c);
            if (info == null) continue;
            SessionInfo existing = byId.get(info.sessionId());
            if (existing == null || info.lastModified() > existing.lastModified()) {
                byId.put(info.sessionId(), info);
            }
        }
        return byId.values().stream()
                .sorted(SessionService::compareDesc)
                .collect(Collectors.toList());
    }

    private List<SessionInfo> applySortAndLimit(List<Candidate> candidates, Integer limit, int offset) {
        candidates.sort(SessionService::compareCandidateDesc);

        long want = (limit != null && limit > 0) ? limit : Long.MAX_VALUE;
        int skipped = 0;
        Set<String> seen = new LinkedHashSet<>();
        List<SessionInfo> sessions = new ArrayList<>();

        for (int i = 0; i < candidates.size() && sessions.size() < want; i++) {
            SessionInfo info = readCandidate(candidates.get(i));
            if (info == null) continue;
            if (seen.contains(info.sessionId())) continue;
            seen.add(info.sessionId());
            if (skipped < offset) { skipped++; continue; }
            sessions.add(info);
        }
        return sessions;
    }

    // =========================================================================
    // Candidate reader — translated from readCandidate() in listSessionsImpl.ts
    // =========================================================================

    private SessionInfo readCandidate(Candidate c) {
        try {
            byte[] rawBytes = Files.readAllBytes(c.filePath());
            String content = new String(rawBytes);
            String[] lines = content.split("\n", -1);
            if (lines.length == 0) return null;

            String head = buildHead(lines);
            String tail = buildTail(lines);

            // Sidechain check
            String firstLine = lines[0];
            if (firstLine.contains("\"isSidechain\":true") || firstLine.contains("\"isSidechain\": true")) {
                return null;
            }

            // Extract fields
            String customTitle = lastJsonString(tail, "customTitle");
            if (customTitle == null) customTitle = lastJsonString(head, "customTitle");
            if (customTitle == null) customTitle = lastJsonString(tail, "aiTitle");
            if (customTitle == null) customTitle = lastJsonString(head, "aiTitle");

            String firstPrompt = extractFirstPromptFromHead(head);

            String createdAtStr = extractJsonString(head, "timestamp");
            Long createdAt = null;
            if (createdAtStr != null) {
                try { createdAt = java.time.Instant.parse(createdAtStr).toEpochMilli(); }
                catch (Exception ignored) {}
            }

            String summary = customTitle != null ? customTitle
                    : lastJsonString(tail, "lastPrompt");
            if (summary == null) summary = lastJsonString(tail, "summary");
            if (summary == null) summary = firstPrompt;
            if (summary == null) return null; // metadata-only session

            String gitBranch = lastJsonString(tail, "gitBranch");
            if (gitBranch == null) gitBranch = extractJsonString(head, "gitBranch");
            String sessionCwd = extractJsonString(head, "cwd");
            if (sessionCwd == null) sessionCwd = c.projectPath();

            String tagLine = null;
            for (int i = lines.length - 1; i >= 0; i--) {
                if (lines[i].startsWith("{\"type\":\"tag\"")) { tagLine = lines[i]; break; }
            }
            String tag = tagLine != null ? lastJsonString(tagLine, "tag") : null;

            long mtime = c.mtime() > 0 ? c.mtime() : Files.getLastModifiedTime(c.filePath()).toMillis();
            long size = Files.size(c.filePath());

            return new SessionInfo(c.sessionId(), summary, mtime, size,
                    customTitle, firstPrompt, gitBranch, sessionCwd, tag, createdAt);

        } catch (Exception e) {
            return null;
        }
    }

    // =========================================================================
    // Field extraction helpers (head/tail / JSON string extraction)
    // =========================================================================

    private static final int HEAD_LINES = 10;
    private static final int TAIL_LINES = 10;

    private String buildHead(String[] lines) {
        return String.join("\n", Arrays.copyOfRange(lines, 0, Math.min(HEAD_LINES, lines.length)));
    }

    private String buildTail(String[] lines) {
        int start = Math.max(0, lines.length - TAIL_LINES);
        return String.join("\n", Arrays.copyOfRange(lines, start, lines.length));
    }

    /** Extract first occurrence of a JSON string field. */
    private String extractJsonString(String text, String field) {
        String needle = "\"" + field + "\":\"";
        int idx = text.indexOf(needle);
        if (idx < 0) return null;
        int start = idx + needle.length();
        int end = text.indexOf("\"", start);
        return end > start ? text.substring(start, end) : null;
    }

    /** Extract last occurrence of a JSON string field. */
    private String lastJsonString(String text, String field) {
        String needle = "\"" + field + "\":\"";
        int idx = text.lastIndexOf(needle);
        if (idx < 0) return null;
        int start = idx + needle.length();
        int end = text.indexOf("\"", start);
        return end > start ? text.substring(start, end) : null;
    }

    /** Extract first user prompt from head lines. */
    private String extractFirstPromptFromHead(String head) {
        // Look for {"type":"prompt", ... "content":"..."}
        String needle = "\"content\":\"";
        int idx = head.indexOf("\"type\":\"user\"");
        if (idx < 0) idx = head.indexOf("\"type\":\"prompt\"");
        if (idx < 0) return null;
        int ci = head.indexOf(needle, idx);
        if (ci < 0) return null;
        int start = ci + needle.length();
        int end = head.indexOf("\"", start);
        return end > start ? head.substring(start, end) : null;
    }

    // =========================================================================
    // Comparators
    // =========================================================================

    private static int compareDesc(SessionInfo a, SessionInfo b) {
        if (b.lastModified() != a.lastModified())
            return Long.compare(b.lastModified(), a.lastModified());
        return b.sessionId().compareTo(a.sessionId());
    }

    private static int compareCandidateDesc(Candidate a, Candidate b) {
        if (b.mtime() != a.mtime()) return Long.compare(b.mtime(), a.mtime());
        return b.sessionId().compareTo(a.sessionId());
    }

    // =========================================================================
    // Utility
    // =========================================================================

    private boolean isValidUuid(String s) {
        return s != null && s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private String sanitizePath(String path) {
        // Mirror the JS sanitizePath: replace separators with dashes
        return path.replaceAll("[/\\\\:*?\"<>|]", "-").replaceAll("^-+|-+$", "");
    }

    private String getProjectsDir() {
        return System.getProperty("user.home") + "/.claude/projects";
    }

    // =========================================================================
    // CRUD session management (pre-existing)
    // =========================================================================

    /**
     * Start a new session.
     */
    public String startSession() {
        currentSessionId = UUID.randomUUID().toString();
        currentMessages = new ArrayList<>();
        log.info("Started session: {}", currentSessionId);
        return currentSessionId;
    }

    /**
     * Get the current session ID, starting a new one if needed.
     */
    public String getCurrentSessionId() {
        if (currentSessionId == null) {
            currentSessionId = startSession();
        }
        return currentSessionId;
    }

    /**
     * Save the current session to disk.
     */
    public void saveSession() {
        if (currentSessionId == null || currentMessages.isEmpty()) return;
        try {
            String sessionsPath = settingsService.getConfigHomeDir() + "/" + SESSIONS_DIR;
            new File(sessionsPath).mkdirs();
            SessionData sessionData = new SessionData(currentSessionId,
                    System.currentTimeMillis(), currentMessages);
            File sessionFile = new File(sessionsPath + "/" + currentSessionId + ".json");
            objectMapper.writeValue(sessionFile, sessionData);
            log.debug("Saved session: {}", currentSessionId);
        } catch (Exception e) {
            log.warn("Could not save session: {}", e.getMessage());
        }
    }

    /**
     * Load a session from disk.
     */
    public Optional<SessionData> loadSession(String sessionId) {
        try {
            String sessionsPath = settingsService.getConfigHomeDir() + "/" + SESSIONS_DIR;
            File sessionFile = new File(sessionsPath + "/" + sessionId + ".json");
            if (!sessionFile.exists()) return Optional.empty();
            return Optional.of(objectMapper.readValue(sessionFile, SessionData.class));
        } catch (Exception e) {
            log.warn("Could not load session {}: {}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Resume a session.
     */
    public boolean resumeSession(String sessionId) {
        Optional<SessionData> data = loadSession(sessionId);
        if (data.isEmpty()) return false;
        currentSessionId = sessionId;
        currentMessages = new ArrayList<>(data.get().getMessages());
        log.info("Resumed session: {}", sessionId);
        return true;
    }

    /**
     * List recent sessions (simple overload using the new listSessionsImpl logic).
     */
    public List<SessionInfo> listSessions(int limit) {
        return listSessionsImpl(new ListSessionsOptions(null, limit, null, null)).join();
    }

    /** Add messages to current session. */
    public void addMessages(List<Message> messages) {
        currentMessages.addAll(messages);
    }

    /** Get current session messages (unmodifiable view). */
    public List<Message> getCurrentMessages() {
        return Collections.unmodifiableList(currentMessages);
    }

    /** Set the title/name for the current session. */
    public void setSessionName(String name) {
        if (currentSessionId == null) return;
        var metadata = SessionStorageService.SessionMetadata.builder()
                .sessionId(currentSessionId)
                .timestamp(System.currentTimeMillis())
                .projectPath(System.getProperty("user.dir"))
                .name(name)
                .messageCount(currentMessages.size())
                .build();
        String projectDir = sessionStorageService.getProjectDir(System.getProperty("user.dir", ""));
        sessionStorageService.saveSessionMetadata(projectDir, currentSessionId, metadata);
        log.info("Session {} renamed to: {}", currentSessionId, name);
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    public static class SessionData {
        private String sessionId;
        private long timestamp;
        private List<Message> messages;

        public SessionData() {}
        public SessionData(String sessionId, long timestamp, List<Message> messages) {
            this.sessionId = sessionId; this.timestamp = timestamp; this.messages = messages;
        }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String v) { sessionId = v; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long v) { timestamp = v; }
        public List<Message> getMessages() { return messages; }
        public void setMessages(List<Message> v) { messages = v; }
    }

    /** Lightweight session summary for listings. */
    public record SessionSummary(String sessionId, long timestamp, int messageCount) {}

    /** Returns the current working directory for this session. */
    public String getCurrentWorkingDirectory() {
        return System.getProperty("user.dir");
    }

    /** Returns the project directory used for session storage. */
    public String getProjectDir() {
        return sessionStorageService.getProjectDir(System.getProperty("user.dir", ""));
    }
}
