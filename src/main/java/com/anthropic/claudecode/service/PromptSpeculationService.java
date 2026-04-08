package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Prompt speculation service.
 * Translated from src/services/PromptSuggestion/speculation.ts
 *
 * Speculatively executes agent work against the predicted next user message
 * so that results are ready when the user actually types that message.
 * Uses an overlay filesystem so writes are isolated until the user accepts.
 *
 * Key design points:
 * - Speculation runs in a forked agent against a copy-on-write overlay.
 * - Write tools (Edit, Write, NotebookEdit) are redirected to the overlay path.
 * - Safe read-only tools (Read, Glob, Grep, etc.) can read from either main or overlay.
 * - Non-read-only Bash, and all other tools, abort speculation with a boundary marker.
 * - On user acceptance, overlay files are promoted to main filesystem.
 * - On abort (user typed something else), overlay is silently removed.
 */
@Slf4j
@Service
public class PromptSpeculationService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PromptSpeculationService.class);


    private static final int MAX_SPECULATION_TURNS = 20;
    private static final int MAX_SPECULATION_MESSAGES = 100;

    private static final Set<String> WRITE_TOOLS = Set.of("Edit", "Write", "NotebookEdit");
    private static final Set<String> SAFE_READ_ONLY_TOOLS = Set.of(
        "Read", "Glob", "Grep", "ToolSearch", "LSP", "TaskGet", "TaskList"
    );

    private final PromptSuggestionService promptSuggestionService;
    private final AnalyticsService analyticsService;
    private final GlobalConfigService globalConfigService;

    @Autowired
    public PromptSpeculationService(
            PromptSuggestionService promptSuggestionService,
            AnalyticsService analyticsService,
            GlobalConfigService globalConfigService) {
        this.promptSuggestionService = promptSuggestionService;
        this.analyticsService = analyticsService;
        this.globalConfigService = globalConfigService;
    }

    // ─── State types ──────────────────────────────────────────

    /** Status of a speculation run. */
    public enum SpeculationStatus {
        IDLE, ACTIVE
    }

    /**
     * Boundary type where speculation stopped.
     * Translated from CompletionBoundary sealed type in AppStateStore.ts
     */
    public sealed interface CompletionBoundary permits
        CompletionBoundary.BashBoundary,
        CompletionBoundary.EditBoundary,
        CompletionBoundary.DeniedToolBoundary,
        CompletionBoundary.CompleteBoundary {

        long completedAt();

        record BashBoundary(String command, long completedAt) implements CompletionBoundary {}
        record EditBoundary(String toolName, String filePath, long completedAt) implements CompletionBoundary {}
        record DeniedToolBoundary(String toolName, String detail, long completedAt) implements CompletionBoundary {}
        record CompleteBoundary(int outputTokens, long completedAt) implements CompletionBoundary {}
    }

    /** State while speculation is active. */
    public static class ActiveSpeculationState {
        private final String id;
        private final long startTime;
        private final int suggestionLength;
        private final boolean isPipelined;
        private final Path overlayPath;
        private final Set<String> writtenPaths = new HashSet<>();
        private final List<Object> messages = new ArrayList<>();
        private int toolUseCount;
        private CompletionBoundary boundary;
        private SuggestionData pipelinedSuggestion;

        public ActiveSpeculationState() { this.id = null; this.startTime = 0; this.suggestionLength = 0; this.isPipelined = false; this.overlayPath = null; }
        public ActiveSpeculationState(String id, long startTime, int suggestionLength, boolean isPipelined, Path overlayPath) {
            this.id = id; this.startTime = startTime; this.suggestionLength = suggestionLength;
            this.isPipelined = isPipelined; this.overlayPath = overlayPath;
        }
        public ActiveSpeculationState(String id, long startTime, int suggestionLength, boolean isPipelined, Path overlayPath,
                                      int toolUseCount, CompletionBoundary boundary, SuggestionData pipelinedSuggestion) {
            this(id, startTime, suggestionLength, isPipelined, overlayPath);
            this.toolUseCount = toolUseCount; this.boundary = boundary; this.pipelinedSuggestion = pipelinedSuggestion;
        }
        public String getId() { return id; }
        public long getStartTime() { return startTime; }
        public int getSuggestionLength() { return suggestionLength; }
        public boolean isPipelined() { return isPipelined; }
        public Path getOverlayPath() { return overlayPath; }
        public Set<String> getWrittenPaths() { return writtenPaths; }
        public List<Object> getMessages() { return messages; }
        public int getToolUseCount() { return toolUseCount; }
        public void setToolUseCount(int v) { this.toolUseCount = v; }
        public CompletionBoundary getBoundary() { return boundary; }
        public void setBoundary(CompletionBoundary v) { this.boundary = v; }
        public SuggestionData getPipelinedSuggestion() { return pipelinedSuggestion; }
        public void setPipelinedSuggestion(SuggestionData v) { this.pipelinedSuggestion = v; }
    }

    @Data
    @lombok.NoArgsConstructor(force = true)
    @lombok.AllArgsConstructor
    public static class SuggestionData {
        private final String text;
        private final PromptSuggestionService.PromptVariant promptId;
        private final String generationRequestId;

        public String getText() { return text; }
        public String getGenerationRequestId() { return generationRequestId; }
    }

    public static class SpeculationResult {
        private final List<Object> messages;
        private final CompletionBoundary boundary;
        private final long timeSavedMs;

        public SpeculationResult(List<Object> messages, CompletionBoundary boundary, long timeSavedMs) {
            this.messages = messages; this.boundary = boundary; this.timeSavedMs = timeSavedMs;
        }
        public List<Object> getMessages() { return messages; }
        public CompletionBoundary getBoundary() { return boundary; }
        public long getTimeSavedMs() { return timeSavedMs; }
    }

    // ─── Speculation lifecycle ─────────────────────────────────

    /**
     * Check whether speculation is enabled for this session.
     * Only enabled for ant-internal users with the config flag set.
     * Translated from isSpeculationEnabled() in speculation.ts
     */
    public boolean isSpeculationEnabled() {
        boolean isAntUser = "ant".equals(System.getenv("USER_TYPE"));
        boolean configEnabled = !Boolean.FALSE.equals(
            globalConfigService.getGlobalConfig().getSpeculationEnabled());
        boolean enabled = isAntUser && configEnabled;
        log.debug("[Speculation] enabled={}", enabled);
        return enabled;
    }

    /**
     * Start a speculation run for the given suggestion text.
     * Creates an overlay directory, forks a new agent, and tracks state.
     * Translated from startSpeculation() in speculation.ts
     */
    public CompletableFuture<Void> startSpeculation(
            String suggestionText,
            boolean isPipelined) {

        if (!isSpeculationEnabled()) return CompletableFuture.completedFuture(null);

        String id = UUID.randomUUID().toString().substring(0, 8);
        Path overlayPath = getOverlayPath(id);

        return CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(overlayPath);
            } catch (IOException e) {
                log.debug("[Speculation] Failed to create overlay directory: {}", e.getMessage());
                return;
            }

            long startTime = System.currentTimeMillis();
            ActiveSpeculationState state = new ActiveSpeculationState(
                id, startTime, suggestionText.length(), isPipelined, overlayPath);

            log.debug("[Speculation] Starting speculation {}", id);

            try {
                // In a full implementation, runForkedAgent would be called here with:
                // - promptMessages: [createUserMessage(suggestionText)]
                // - canUseTool callback that handles overlay redirection and boundary detection
                // - maxTurns: MAX_SPECULATION_TURNS
                // - querySource: "speculation"
                // The forked agent result feeds into acceptSpeculation() or abortSpeculation()
                log.debug("[Speculation] Forked agent would run for suggestion: \"{}\"",
                    suggestionText.length() > 50 ? suggestionText.substring(0, 50) + "..." : suggestionText);

            } catch (Exception e) {
                safeRemoveOverlay(overlayPath);
                logSpeculation(id, "error", startTime, suggestionText.length(), state.getMessages(), null,
                    Map.of("error_type", e.getClass().getSimpleName(),
                           "error_phase", "start",
                           "is_pipelined", isPipelined));
                log.error("[Speculation] Error during speculation {}: {}", id, e.getMessage());
            }
        });
    }

    /**
     * Accept the speculation — promote overlay writes to main filesystem
     * and inject speculated messages into the conversation.
     * Translated from acceptSpeculation() in speculation.ts
     */
    public CompletableFuture<Optional<SpeculationResult>> acceptSpeculation(
            ActiveSpeculationState state, int cleanMessageCount) {

        return CompletableFuture.supplyAsync(() -> {
            List<Object> messages = new ArrayList<>(state.getMessages());
            Path overlayPath = state.getOverlayPath();
            long acceptedAt = System.currentTimeMillis();

            if (cleanMessageCount > 0) {
                copyOverlayToMain(overlayPath, state.getWrittenPaths());
            }
            safeRemoveOverlay(overlayPath);

            CompletionBoundary boundary = state.getBoundary();
            long timeSavedMs = Math.min(acceptedAt,
                boundary != null ? boundary.completedAt() : Long.MAX_VALUE) - state.getStartTime();

            log.debug("[Speculation] Accept {}: {} messages, {}ms saved",
                state.getId(), messages.size(), timeSavedMs);

            logSpeculation(state.getId(), "accepted", state.getStartTime(), state.getSuggestionLength(),
                messages, boundary,
                Map.of("message_count", messages.size(),
                       "time_saved_ms", timeSavedMs,
                       "is_pipelined", state.isPipelined()));

            return Optional.of(new SpeculationResult(messages, boundary, timeSavedMs));
        });
    }

    /**
     * Abort speculation and clean up the overlay.
     * Translated from abortSpeculation() in speculation.ts
     */
    public void abortSpeculation(ActiveSpeculationState state) {
        log.debug("[Speculation] Aborting {}", state.getId());

        logSpeculation(state.getId(), "aborted", state.getStartTime(), state.getSuggestionLength(),
            state.getMessages(), state.getBoundary(),
            Map.of("abort_reason", "user_typed", "is_pipelined", state.isPipelined()));

        safeRemoveOverlay(state.getOverlayPath());
    }

    // ─── Internal helpers ─────────────────────────────────────

    private Path getOverlayPath(String id) {
        String tmpDir = System.getProperty("java.io.tmpdir");
        return Paths.get(tmpDir, ".claude", "speculation",
            String.valueOf(ProcessHandle.current().pid()), id);
    }

    private void safeRemoveOverlay(Path overlayPath) {
        if (overlayPath == null) return;
        try {
            if (Files.exists(overlayPath)) {
                deleteRecursive(overlayPath);
            }
        } catch (Exception e) {
            log.debug("[Speculation] Failed to remove overlay {}: {}", overlayPath, e.getMessage());
        }
    }

    private void deleteRecursive(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                for (Path child : (Iterable<Path>) stream::iterator) {
                    deleteRecursive(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    private void copyOverlayToMain(Path overlayPath, Set<String> writtenPaths) {
        for (String rel : writtenPaths) {
            Path src = overlayPath.resolve(rel);
            String cwd = System.getProperty("user.dir");
            Path dest = Paths.get(cwd, rel);
            try {
                Files.createDirectories(dest.getParent());
                Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                log.debug("[Speculation] Failed to copy {} to main: {}", rel, e.getMessage());
            }
        }
    }

    private void logSpeculation(
            String id, String outcome, long startTime, int suggestionLength,
            List<Object> messages, CompletionBoundary boundary,
            Map<String, Object> extras) {

        Map<String, Object> props = new java.util.HashMap<>();
        props.put("speculation_id", id);
        props.put("outcome", outcome);
        props.put("duration_ms", System.currentTimeMillis() - startTime);
        props.put("suggestion_length", suggestionLength);
        props.put("tools_executed", countTools(messages));
        props.put("completed", boundary != null);
        if (boundary != null) {
            props.put("boundary_type", boundaryType(boundary));
        }
        if (extras != null) props.putAll(extras);

        analyticsService.logEvent("tengu_speculation", props);
    }

    private int countTools(List<Object> messages) {
        // In real implementation, counts tool_result blocks in user messages
        return 0;
    }

    private String boundaryType(CompletionBoundary boundary) {
        return switch (boundary) {
            case CompletionBoundary.BashBoundary b -> "bash";
            case CompletionBoundary.EditBoundary e -> "edit";
            case CompletionBoundary.DeniedToolBoundary d -> "denied_tool";
            case CompletionBoundary.CompleteBoundary c -> "complete";
        };
    }
}
