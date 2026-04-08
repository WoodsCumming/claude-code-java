package com.anthropic.claudecode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.stream.Stream;

/**
 * Command history management and search service.
 *
 * Translated from:
 *   - src/history.ts                  — persistence, reader, reference expansion
 *   - src/hooks/useHistorySearch.ts   — interactive reverse search session
 *
 * <h3>TypeScript → Java mapping for useHistorySearch</h3>
 * <pre>
 * useHistorySearch(...)              → HistorySearchSession (inner class)
 * useState(historyQuery)             → HistorySearchSession.historyQuery field
 * useState(historyMatch)             → HistorySearchSession.historyMatch field
 * useState(historyFailedMatch)       → HistorySearchSession.historyFailedMatch field
 * historyReader.current              → HistorySearchSession.historyReader (Iterator)
 * seenPrompts.current                → HistorySearchSession.seenPrompts
 * searchAbortController.current      → HistorySearchSession.searchAbortRef (AtomicBoolean)
 * searchHistory(resume, signal)      → HistorySearchSession.searchHistory(boolean)
 * handleStartSearch()                → HistorySearchSession.startSearch(...)
 * handleNextMatch()                  → HistorySearchSession.nextMatch()
 * handleAccept()                     → HistorySearchSession.accept(callback)
 * handleCancel()                     → HistorySearchSession.cancel(callback)
 * handleExecute()                    → HistorySearchSession.execute(callback)
 * reset()                            → HistorySearchSession.reset()
 * </pre>
 *
 * Manages the user's input history for the REPL, persisted to ~/.claude/history.jsonl.
 * Handles pasted content references (inline for small content, hash-reference for large).
 */
@Slf4j
@Service
public class HistoryService {



    private static final int MAX_HISTORY_ITEMS = 100;
    private static final int MAX_PASTED_CONTENT_LENGTH = 1024;
    // Matches [Pasted text #N], [Image #N], [...Truncated text #N] with optional "+ lines"
    private static final Pattern REFERENCE_PATTERN =
        Pattern.compile("\\[(Pasted text|Image|\\.\\.\\.Truncated text) #(\\d+)(?: \\+\\d+ lines)?(\\.)*\\]");

    private final ObjectMapper objectMapper;
    private final BootstrapStateService bootstrapStateService;
    private final PasteStoreService pasteStoreService;

    // In-memory pending entries not yet flushed to disk
    private final List<LogEntry> pendingEntries = new CopyOnWriteArrayList<>();
    private volatile LogEntry lastAddedEntry = null;
    // Timestamps of flushed entries that should be skipped (removed after flush)
    private final Set<Long> skippedTimestamps = ConcurrentHashMap.newKeySet();
    private volatile boolean isWriting = false;
    private volatile CompletableFuture<Void> currentFlushFuture = null;

    @Autowired
    public HistoryService(ObjectMapper objectMapper,
                          BootstrapStateService bootstrapStateService,
                          PasteStoreService pasteStoreService) {
        this.objectMapper = objectMapper;
        this.bootstrapStateService = bootstrapStateService;
        this.pasteStoreService = pasteStoreService;
    }

    // =========================================================================
    // Reference formatting (translated from history.ts exported functions)
    // =========================================================================

    /**
     * Count newlines in text for the pasted text reference.
     * Translated from getPastedTextRefNumLines() in history.ts
     * Note: counts newline sequences, not total lines. "line1\nline2\nline3" = 2.
     */
    public static int getPastedTextRefNumLines(String text) {
        if (text == null || text.isEmpty()) return 0;
        int count = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\r') {
                count++;
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++; // consume \n of \r\n
                }
            } else if (c == '\n') {
                count++;
            }
            i++;
        }
        return count;
    }

    /**
     * Format a pasted text reference string.
     * Translated from formatPastedTextRef() in history.ts
     */
    public static String formatPastedTextRef(int id, int numLines) {
        if (numLines == 0) {
            return "[Pasted text #" + id + "]";
        }
        return "[Pasted text #" + id + " +" + numLines + " lines]";
    }

    /**
     * Format an image reference string.
     * Translated from formatImageRef() in history.ts
     */
    public static String formatImageRef(int id) {
        return "[Image #" + id + "]";
    }

    /**
     * Parse all pasted-content references from an input string.
     * Translated from parseReferences() in history.ts
     */
    public static List<Reference> parseReferences(String input) {
        if (input == null) return List.of();
        List<Reference> refs = new ArrayList<>();
        Matcher m = REFERENCE_PATTERN.matcher(input);
        while (m.find()) {
            try {
                int id = Integer.parseInt(m.group(2));
                if (id > 0) {
                    refs.add(new Reference(id, m.group(0), m.start()));
                }
            } catch (NumberFormatException e) {
                // skip malformed id
            }
        }
        return refs;
    }

    /**
     * Replace [Pasted text #N] placeholders with actual content.
     * Image refs are left alone — they become content blocks, not inlined text.
     * Translated from expandPastedTextRefs() in history.ts
     */
    public static String expandPastedTextRefs(String input, Map<Integer, PastedContent> pastedContents) {
        if (input == null) return "";
        List<Reference> refs = parseReferences(input);
        if (refs.isEmpty()) return input;

        StringBuilder expanded = new StringBuilder(input);
        // Reverse order keeps earlier offsets valid after later replacements
        for (int i = refs.size() - 1; i >= 0; i--) {
            Reference ref = refs.get(i);
            PastedContent content = pastedContents.get(ref.id());
            if (content == null || !"text".equals(content.getType())) continue;
            expanded.replace(ref.index(), ref.index() + ref.match().length(), content.getContent());
        }
        return expanded.toString();
    }

    // =========================================================================
    // History management
    // =========================================================================

    /**
     * Add an entry to the history (non-blocking, enqueues for flush).
     * Translated from addToHistory() in history.ts
     */
    public void addToHistory(HistoryEntry entry) {
        LogEntry logEntry = buildLogEntry(entry);
        pendingEntries.add(logEntry);
        lastAddedEntry = logEntry;
        currentFlushFuture = CompletableFuture.runAsync(() -> flushPromptHistory(0));
    }

    /**
     * Add a plain string entry to history.
     */
    public void addToHistory(String display) {
        addToHistory(new HistoryEntry(display, new HashMap<>()));
    }

    /**
     * Clear all pending (not-yet-flushed) history entries.
     * Translated from clearPendingHistoryEntries() in history.ts
     */
    public void clearPendingHistoryEntries() {
        pendingEntries.clear();
        lastAddedEntry = null;
        skippedTimestamps.clear();
    }

    /**
     * Undo the most recent addToHistory call (for interrupt/rewind).
     * Fast path pops from pending buffer; if already flushed, adds to skip set.
     * Translated from removeLastFromHistory() in history.ts
     */
    public void removeLastFromHistory() {
        LogEntry entry = lastAddedEntry;
        if (entry == null) return;
        lastAddedEntry = null;

        boolean removed = pendingEntries.remove(entry);
        if (!removed) {
            skippedTimestamps.add(entry.getTimestamp());
        }
    }

    /**
     * Get history entries for the current project, current session first.
     * Translated from getHistory() in history.ts
     */
    public List<HistoryEntry> getHistory() {
        String currentProject = bootstrapStateService.getProjectRoot();
        String currentSession = bootstrapStateService.getSessionId();

        List<LogEntry> currentSessionEntries = new ArrayList<>();
        List<LogEntry> otherSessionEntries = new ArrayList<>();

        for (LogEntry entry : readAllLogEntries()) {
            if (entry.getProject() == null || !entry.getProject().equals(currentProject)) continue;

            if (currentSession.equals(entry.getSessionId())) {
                currentSessionEntries.add(entry);
            } else {
                otherSessionEntries.add(entry);
            }

            if (currentSessionEntries.size() + otherSessionEntries.size() >= MAX_HISTORY_ITEMS) break;
        }

        List<HistoryEntry> result = new ArrayList<>();
        for (LogEntry entry : currentSessionEntries) {
            result.add(logEntryToHistoryEntry(entry));
        }
        for (LogEntry entry : otherSessionEntries) {
            if (result.size() >= MAX_HISTORY_ITEMS) break;
            result.add(logEntryToHistoryEntry(entry));
        }
        return result;
    }

    /**
     * Get timestamped history for the ctrl+r picker, deduped by display text.
     * Translated from getTimestampedHistory() in history.ts
     */
    public List<TimestampedHistoryEntry> getTimestampedHistory() {
        String currentProject = bootstrapStateService.getProjectRoot();
        Set<String> seen = new LinkedHashSet<>();
        List<TimestampedHistoryEntry> result = new ArrayList<>();

        for (LogEntry entry : readAllLogEntries()) {
            if (entry.getProject() == null || !entry.getProject().equals(currentProject)) continue;
            if (seen.contains(entry.getDisplay())) continue;
            seen.add(entry.getDisplay());

            final LogEntry captured = entry;
            result.add(new TimestampedHistoryEntry(
                entry.getDisplay(),
                entry.getTimestamp(),
                () -> logEntryToHistoryEntry(captured)
            ));

            if (seen.size() >= MAX_HISTORY_ITEMS) break;
        }
        return result;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private LogEntry buildLogEntry(HistoryEntry entry) {
        String currentSession = bootstrapStateService.getSessionId();
        String projectRoot = bootstrapStateService.getProjectRoot();

        Map<Integer, StoredPastedContent> storedContents = new HashMap<>();
        if (entry.getPastedContents() != null) {
            for (Map.Entry<Integer, PastedContent> e : entry.getPastedContents().entrySet()) {
                PastedContent content = e.getValue();
                if ("image".equals(content.getType())) continue; // images stored separately

                if (content.getContent() != null && content.getContent().length() <= MAX_PASTED_CONTENT_LENGTH) {
                    // Store inline for small content
                    StoredPastedContent stored = new StoredPastedContent();
                    stored.setId(content.getId());
                    stored.setType(content.getType());
                    stored.setContent(content.getContent());
                    stored.setMediaType(content.getMediaType());
                    stored.setFilename(content.getFilename());
                    storedContents.put(e.getKey(), stored);
                } else if (content.getContent() != null) {
                    // Store hash reference for large content
                    String hash = pasteStoreService.hashPastedText(content.getContent());
                    StoredPastedContent stored = new StoredPastedContent();
                    stored.setId(content.getId());
                    stored.setType(content.getType());
                    stored.setContentHash(hash);
                    stored.setMediaType(content.getMediaType());
                    stored.setFilename(content.getFilename());
                    storedContents.put(e.getKey(), stored);
                    // Fire-and-forget disk write
                    CompletableFuture.runAsync(() -> pasteStoreService.storePastedText(hash, content.getContent()));
                }
            }
        }

        LogEntry logEntry = new LogEntry();
        logEntry.setDisplay(entry.getDisplay());
        logEntry.setPastedContents(storedContents);
        logEntry.setTimestamp(System.currentTimeMillis());
        logEntry.setProject(projectRoot);
        logEntry.setSessionId(currentSession);
        return logEntry;
    }

    private HistoryEntry logEntryToHistoryEntry(LogEntry entry) {
        Map<Integer, PastedContent> resolved = new HashMap<>();
        if (entry.getPastedContents() != null) {
            for (Map.Entry<Integer, StoredPastedContent> e : entry.getPastedContents().entrySet()) {
                PastedContent content = resolveStoredPastedContent(e.getValue());
                if (content != null) {
                    resolved.put(e.getKey(), content);
                }
            }
        }
        return new HistoryEntry(entry.getDisplay(), resolved);
    }

    private PastedContent resolveStoredPastedContent(StoredPastedContent stored) {
        if (stored.getContent() != null) {
            PastedContent c = new PastedContent();
            c.setId(stored.getId());
            c.setType(stored.getType());
            c.setContent(stored.getContent());
            c.setMediaType(stored.getMediaType());
            c.setFilename(stored.getFilename());
            return c;
        }
        if (stored.getContentHash() != null) {
            String content;
            try {
                content = pasteStoreService.retrievePastedText(stored.getContentHash()).get().orElse(null);
            } catch (Exception e) { content = null; }
            if (content != null) {
                PastedContent c = new PastedContent();
                c.setId(stored.getId());
                c.setType(stored.getType());
                c.setContent(content);
                c.setMediaType(stored.getMediaType());
                c.setFilename(stored.getFilename());
                return c;
            }
        }
        return null;
    }

    /**
     * Read log entries: pending (in reverse) then from disk (newest first).
     * Translated from makeLogEntryReader() generator in history.ts
     */
    private List<LogEntry> readAllLogEntries() {
        String currentSession = bootstrapStateService.getSessionId();
        List<LogEntry> entries = new ArrayList<>();

        // Pending entries in reverse (newest first)
        List<LogEntry> pending = new ArrayList<>(pendingEntries);
        for (int i = pending.size() - 1; i >= 0; i--) {
            entries.add(pending.get(i));
        }

        // Read from history.jsonl on disk
        Path historyPath = getHistoryPath();
        if (Files.exists(historyPath)) {
            try {
                List<String> lines = Files.readAllLines(historyPath, StandardCharsets.UTF_8);
                // Reverse to get newest-first
                for (int i = lines.size() - 1; i >= 0; i--) {
                    String line = lines.get(i).trim();
                    if (line.isEmpty()) continue;
                    try {
                        LogEntry entry = objectMapper.readValue(line, LogEntry.class);
                        // Skip entries that were removed after flushing
                        if (currentSession.equals(entry.getSessionId())
                                && skippedTimestamps.contains(entry.getTimestamp())) {
                            continue;
                        }
                        entries.add(entry);
                    } catch (Exception e) {
                        log.debug("Failed to parse history line: {}", e.getMessage());
                    }
                }
            } catch (IOException e) {
                log.debug("Could not read history file: {}", e.getMessage());
            }
        }
        return entries;
    }

    private void flushPromptHistory(int retries) {
        if (isWriting || pendingEntries.isEmpty()) return;
        if (retries > 5) return; // Give up until next user prompt

        isWriting = true;
        try {
            immediateFlushHistory();
        } finally {
            isWriting = false;
            if (!pendingEntries.isEmpty()) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                flushPromptHistory(retries + 1);
            }
        }
    }

    private synchronized void immediateFlushHistory() {
        if (pendingEntries.isEmpty()) return;
        Path historyPath = getHistoryPath();
        try {
            Files.createDirectories(historyPath.getParent());
            // Ensure file exists with correct permissions (600)
            if (!Files.exists(historyPath)) {
                Files.createFile(historyPath);
            }

            List<LogEntry> toFlush = new ArrayList<>(pendingEntries);
            pendingEntries.removeAll(toFlush);

            StringBuilder sb = new StringBuilder();
            for (LogEntry entry : toFlush) {
                sb.append(objectMapper.writeValueAsString(entry)).append('\n');
            }
            Files.writeString(historyPath, sb.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.debug("Failed to write prompt history: {}", e.getMessage());
        }
    }

    private Path getHistoryPath() {
        String home = System.getProperty("user.home");
        return Paths.get(home, ".claude", "history.jsonl");
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class HistoryEntry {
        private String display;
        private Map<Integer, PastedContent> pastedContents;

        public String getDisplay() { return display; }
        public void setDisplay(String v) { display = v; }
        public Map<Integer, PastedContent> getPastedContents() { return pastedContents; }
        public void setPastedContents(Map<Integer, PastedContent> v) { pastedContents = v; }
    }

    @Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PastedContent {
        private int id;
        /** "text" or "image" */
        private String type;
        private String content;
        private String mediaType;
        private String filename;
    }

    /**
     * Stored paste content — either inline content or a hash reference.
     * Translated from StoredPastedContent in history.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class StoredPastedContent {
        private int id;
        private String type;
        private String content;       // inline for small pastes
        private String contentHash;   // hash reference for large pastes
        private String mediaType;
        private String filename;

        public int getId() { return id; }
        public void setId(int v) { id = v; }
        public String getType() { return type; }
        public void setType(String v) { type = v; }
        public String getContent() { return content; }
        public void setContent(String v) { content = v; }
        public String getContentHash() { return contentHash; }
        public void setContentHash(String v) { contentHash = v; }
        public String getMediaType() { return mediaType; }
        public void setMediaType(String v) { mediaType = v; }
        public String getFilename() { return filename; }
        public void setFilename(String v) { filename = v; }
    }

    /**
     * Internal disk-persisted log entry.
     * Translated from LogEntry in history.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class LogEntry {
        private String display;
        private Map<Integer, StoredPastedContent> pastedContents;
        private long timestamp;
        private String project;
        private String sessionId;

        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long v) { timestamp = v; }
        public String getProject() { return project; }
        public void setProject(String v) { project = v; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String v) { sessionId = v; }
    }

    /**
     * Timestamped history entry for the ctrl+r picker.
     * Translated from TimestampedHistoryEntry in history.ts
     */
    public record TimestampedHistoryEntry(
        String display,
        long timestamp,
        java.util.function.Supplier<HistoryEntry> resolve
    ) {}

    public record Reference(int id, String match, int index) {}

    // =========================================================================
    // useHistorySearch() equivalent — interactive reverse-search session
    // =========================================================================

    /**
     * Stateful session that manages a single reverse-search interaction.
     *
     * Translated from {@code useHistorySearch()} in src/hooks/useHistorySearch.ts.
     *
     * <p>Lifecycle:
     * <ol>
     *   <li>Create a session via {@link HistoryService#newSearchSession()}.</li>
     *   <li>Call {@link #startSearch} when the user presses the history-search key
     *       (ctrl+r). Save the current input/cursor/mode so they can be restored.</li>
     *   <li>Update {@link #historyQuery} and call {@link #searchHistory(boolean)}
     *       each time the user types in the search box.</li>
     *   <li>Call {@link #nextMatch()}, {@link #accept}, {@link #cancel}, or
     *       {@link #execute} to complete the interaction.</li>
     * </ol>
     *
     * <p>Thread-safety: this class is not thread-safe; use it from a single
     * event-dispatch thread (the REPL input thread).
     */
    public class HistorySearchSession {

        // ---------------------------------------------------------------------
        // State (mirrors useState variables in useHistorySearch)
        // ---------------------------------------------------------------------

        /** Current search query typed by the user. Mirrors {@code historyQuery} state. */
        private String historyQuery = "";

        /** Whether the last search attempt failed to find a match. */
        private boolean historyFailedMatch = false;

        /** Most recent matching history entry. */
        private HistoryEntry historyMatch = null;

        // Saved state to restore on cancel/escape
        private String originalInput = "";
        private int originalCursorOffset = 0;
        private String originalMode = "prompt";
        private Map<Integer, PastedContent> originalPastedContents = new HashMap<>();

        // ---------------------------------------------------------------------
        // Refs (mirrors useRef variables)
        // ---------------------------------------------------------------------

        /** Iterator over history entries (replaces the async generator). */
        private java.util.Iterator<HistoryEntry> historyReader = null;

        /** Deduplicated prompt texts seen in the current search sweep. */
        private final Set<String> seenPrompts = new LinkedHashSet<>();

        /** Abort flag for the current search sweep (replaces AbortController). */
        private final java.util.concurrent.atomic.AtomicBoolean aborted =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        // ---------------------------------------------------------------------
        // Query accessor with side-effect (mirrors useEffect on historyQuery)
        // ---------------------------------------------------------------------

        /** Get the current search query. */
        public String getHistoryQuery() { return historyQuery; }

        /**
         * Update the search query and trigger a fresh search from the start.
         *
         * Mirrors the {@code useEffect} on {@code historyQuery} in useHistorySearch.ts:
         * aborts any in-flight search and starts a new one with {@code resume=false}.
         */
        public void setHistoryQuery(String query, SearchCallbacks callbacks) {
            this.historyQuery = query != null ? query : "";
            aborted.set(true);          // abort previous sweep
            aborted.set(false);         // arm for new sweep
            searchHistory(false, callbacks);
        }

        /** Get the most recent matching history entry. */
        public HistoryEntry getHistoryMatch() { return historyMatch; }

        /** Whether the last search attempt failed (no more matches). */
        public boolean isHistoryFailedMatch() { return historyFailedMatch; }

        // ---------------------------------------------------------------------
        // Search lifecycle handlers (mirrors useCallback handlers)
        // ---------------------------------------------------------------------

        /**
         * Start a history search session.
         *
         * Mirrors {@code handleStartSearch()} in useHistorySearch.ts:
         * saves the current input state, initialises the reader, clears seen-set.
         *
         * @param currentInput         current input text to restore on cancel
         * @param currentCursorOffset  current cursor position to restore on cancel
         * @param currentMode          current prompt mode to restore on cancel
         * @param currentPastedContents current pasted contents to restore on cancel
         */
        public void startSearch(
                String currentInput,
                int currentCursorOffset,
                String currentMode,
                Map<Integer, PastedContent> currentPastedContents) {

            originalInput         = currentInput != null ? currentInput : "";
            originalCursorOffset  = currentCursorOffset;
            originalMode          = currentMode != null ? currentMode : "prompt";
            originalPastedContents = currentPastedContents != null
                    ? new HashMap<>(currentPastedContents) : new HashMap<>();

            historyReader = getHistory().iterator();
            seenPrompts.clear();
        }

        /**
         * Find the next matching entry (continue the search).
         * Mirrors {@code handleNextMatch()} → {@code searchHistory(true)}.
         */
        public void nextMatch(SearchCallbacks callbacks) {
            searchHistory(true, callbacks);
        }

        /**
         * Accept the current match and exit search mode.
         *
         * Mirrors {@code handleAccept()} in useHistorySearch.ts:
         * if there is a match, apply it; otherwise restore original pasted contents.
         * Then calls {@link #reset()}.
         */
        public void accept(SearchCallbacks callbacks) {
            if (historyMatch != null) {
                String mode  = getModeFromInput(historyMatch.getDisplay());
                String value = getValueFromInput(historyMatch.getDisplay());
                if (callbacks != null) {
                    callbacks.onInputChange(value);
                    callbacks.onModeChange(mode);
                    callbacks.onPastedContentsChange(historyMatch.getPastedContents());
                }
            } else {
                if (callbacks != null) {
                    callbacks.onPastedContentsChange(originalPastedContents);
                }
            }
            reset(callbacks);
        }

        /**
         * Cancel search and restore the original input.
         * Mirrors {@code handleCancel()} in useHistorySearch.ts.
         */
        public void cancel(SearchCallbacks callbacks) {
            if (callbacks != null) {
                callbacks.onInputChange(originalInput);
                callbacks.onCursorChange(originalCursorOffset);
                callbacks.onPastedContentsChange(originalPastedContents);
            }
            reset(callbacks);
        }

        /**
         * Execute (accept and submit) the current match.
         * Mirrors {@code handleExecute()} in useHistorySearch.ts.
         *
         * @param onAcceptHistory callback to submit the selected entry
         */
        public void execute(
                java.util.function.Consumer<HistoryEntry> onAcceptHistory,
                SearchCallbacks callbacks) {

            if (historyQuery.isEmpty()) {
                if (onAcceptHistory != null) {
                    onAcceptHistory.accept(
                            new HistoryEntry(originalInput, originalPastedContents));
                }
            } else if (historyMatch != null) {
                String mode  = getModeFromInput(historyMatch.getDisplay());
                String value = getValueFromInput(historyMatch.getDisplay());
                if (callbacks != null) callbacks.onModeChange(mode);
                if (onAcceptHistory != null) {
                    onAcceptHistory.accept(
                            new HistoryEntry(value, historyMatch.getPastedContents()));
                }
            }
            reset(callbacks);
        }

        /**
         * Handle a backspace key event while in search mode.
         *
         * Mirrors the special-case backspace handling in useHistorySearch.ts:
         * when the query is empty and backspace is pressed, cancel the search.
         *
         * @return true if the event was consumed (caller should suppress default handling)
         */
        public boolean handleKeyDown(String key, boolean isBackspace, SearchCallbacks callbacks) {
            if (isBackspace && historyQuery.isEmpty()) {
                cancel(callbacks);
                return true;
            }
            return false;
        }

        // ---------------------------------------------------------------------
        // Core search logic
        // ---------------------------------------------------------------------

        /**
         * Execute or resume a history search.
         *
         * Mirrors {@code searchHistory(resume, signal)} in useHistorySearch.ts.
         *
         * @param resume if {@code true}, continue from the current reader position
         *               (next match); if {@code false}, restart from the beginning
         */
        public void searchHistory(boolean resume, SearchCallbacks callbacks) {
            if (historyQuery.isEmpty()) {
                // Empty query — reset reader, clear match, restore original input
                historyReader = null;
                seenPrompts.clear();
                historyMatch = null;
                historyFailedMatch = false;
                if (callbacks != null) {
                    callbacks.onInputChange(originalInput);
                    callbacks.onCursorChange(originalCursorOffset);
                    callbacks.onModeChange(originalMode);
                    callbacks.onPastedContentsChange(originalPastedContents);
                }
                return;
            }

            if (!resume) {
                // Restart reader from the top
                historyReader = getHistory().iterator();
                seenPrompts.clear();
            }

            if (historyReader == null) return;

            // Scan entries for the next match
            while (historyReader.hasNext()) {
                if (aborted.get()) return;

                HistoryEntry item = historyReader.next();
                String display = item.getDisplay();
                if (display == null) continue;

                int matchPosition = display.lastIndexOf(historyQuery);
                if (matchPosition != -1 && !seenPrompts.contains(display)) {
                    seenPrompts.add(display);
                    historyMatch = item;
                    historyFailedMatch = false;

                    String mode  = getModeFromInput(display);
                    String value = getValueFromInput(display);
                    if (callbacks != null) {
                        callbacks.onModeChange(mode);
                        callbacks.onInputChange(display);
                        callbacks.onPastedContentsChange(item.getPastedContents());

                        // Position cursor at the match within the clean value
                        int cleanPosition = value.lastIndexOf(historyQuery);
                        callbacks.onCursorChange(
                                cleanPosition != -1 ? cleanPosition : matchPosition);
                    }
                    return;
                }
            }

            // Exhausted — no more matches
            historyFailedMatch = true;
        }

        // ---------------------------------------------------------------------
        // Reset
        // ---------------------------------------------------------------------

        /**
         * Reset all search state and notify caller to exit search mode.
         * Mirrors {@code reset()} in useHistorySearch.ts.
         */
        public void reset(SearchCallbacks callbacks) {
            historyQuery          = "";
            historyFailedMatch    = false;
            originalInput         = "";
            originalCursorOffset  = 0;
            originalMode          = "prompt";
            originalPastedContents = new HashMap<>();
            historyMatch          = null;
            historyReader         = null;
            seenPrompts.clear();
            aborted.set(false);
            if (callbacks != null) callbacks.onSearchingChange(false);
        }

        // ---------------------------------------------------------------------
        // Helpers (mirror getModeFromInput / getValueFromInput from inputModes.ts)
        // ---------------------------------------------------------------------

        /**
         * Derive the prompt mode from the display string.
         * Mirrors {@code getModeFromInput(display)} in inputModes.ts:
         * bash-mode strings start with "!"; everything else is "prompt".
         */
        private String getModeFromInput(String display) {
            if (display != null && display.startsWith("!")) return "bash";
            return "prompt";
        }

        /**
         * Strip any mode prefix from the display string to get the plain value.
         * Mirrors {@code getValueFromInput(display)} in inputModes.ts.
         */
        private String getValueFromInput(String display) {
            if (display == null) return "";
            if (display.startsWith("!")) return display.substring(1);
            return display;
        }
    }

    /**
     * Create a new {@link HistorySearchSession} bound to this service's history.
     * Mirrors the "instantiation" of the {@code useHistorySearch} hook.
     */
    public HistorySearchSession newSearchSession() {
        return new HistorySearchSession();
    }

    /**
     * Callbacks interface for {@link HistorySearchSession} to communicate
     * state changes back to the UI / input layer.
     *
     * Mirrors the callback parameters passed into {@code useHistorySearch}:
     * {@code onInputChange}, {@code onCursorChange}, {@code onModeChange},
     * {@code setPastedContents}, {@code setIsSearching}.
     */
    public interface SearchCallbacks {
        /** Called to update the input text shown in the prompt. */
        void onInputChange(String input);

        /** Called to update the cursor position in the prompt. */
        void onCursorChange(int cursorOffset);

        /** Called to change the prompt mode ("prompt" | "bash"). */
        void onModeChange(String mode);

        /** Called to update the pasted-content map for the current prompt. */
        void onPastedContentsChange(Map<Integer, PastedContent> pastedContents);

        /** Called to toggle the "is searching" UI state. */
        void onSearchingChange(boolean isSearching);
    }
}
