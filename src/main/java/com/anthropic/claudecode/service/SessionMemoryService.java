package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.SessionMemoryPrompts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Session Memory service — automatically maintains a per-session markdown notes file.
 * Translated from src/services/SessionMemory/sessionMemory.ts
 *
 * Runs periodically in the background using a forked subagent to extract key information
 * without interrupting the main conversation flow.
 */
@Slf4j
@Service
public class SessionMemoryService {



    // -------------------------------------------------------------------------
    // Config record
    // -------------------------------------------------------------------------

    /**
     * Scheduling thresholds for when to run an extraction.
     * Translated from SessionMemoryConfig in sessionMemoryUtils.ts
     */
    public record SessionMemoryConfig(
        int minimumMessageTokensToInit,
        int minimumTokensBetweenUpdate,
        int toolCallsBetweenUpdates) {

        public static final SessionMemoryConfig DEFAULT = new SessionMemoryConfig(
            10_000,  // minimumMessageTokensToInit
            5_000,   // minimumTokensBetweenUpdate
            5        // toolCallsBetweenUpdates
        );
    }

    // -------------------------------------------------------------------------
    // Result type
    // -------------------------------------------------------------------------

    /**
     * Result of a manual extraction attempt.
     * Translated from ManualExtractionResult in sessionMemory.ts
     */
    public sealed interface ManualExtractionResult permits
        ManualExtractionResult.Success,
        ManualExtractionResult.Failure {

        record Success(String memoryPath) implements ManualExtractionResult {}
        record Failure(String error) implements ManualExtractionResult {}
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    /** The UUID of the last message for which extraction was triggered. */
    private volatile String lastMemoryMessageUuid;

    /** Whether the memory file has been created/initialized this session. */
    private final AtomicBoolean sessionMemoryInitialized = new AtomicBoolean(false);

    /** Context token count recorded at the last extraction. */
    private final AtomicInteger lastExtractionTokenCount = new AtomicInteger(0);

    /** Whether an extraction is currently in progress. */
    private final AtomicBoolean extractionInProgress = new AtomicBoolean(false);

    /** Active config (may be updated from remote config). */
    private volatile SessionMemoryConfig config = SessionMemoryConfig.DEFAULT;

    private final GrowthBookService growthBookService;
    private final BootstrapStateService bootstrapStateService;

    @Autowired
    public SessionMemoryService(
            GrowthBookService growthBookService,
            BootstrapStateService bootstrapStateService) {
        this.growthBookService = growthBookService;
        this.bootstrapStateService = bootstrapStateService;
    }

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    /**
     * Initialize session memory by registering the post-sampling hook.
     * Gate check and config loading happen lazily when the hook fires.
     * Translated from initSessionMemory() in sessionMemory.ts
     */
    public void initSessionMemory() {
        if (bootstrapStateService.isRemoteMode()) {
            log.debug("[sessionMemory] remote mode — skipping init");
            return;
        }
        // In a full implementation: register a post-sampling hook that calls extractSessionMemory().
        log.debug("[sessionMemory] initialized — hook registered");
    }

    /**
     * Reset the last memory message UUID (for testing).
     * Translated from resetLastMemoryMessageUuid() in sessionMemory.ts
     */
    public void resetLastMemoryMessageUuid() {
        lastMemoryMessageUuid = null;
    }

    // -------------------------------------------------------------------------
    // Extraction trigger logic
    // -------------------------------------------------------------------------

    /**
     * Decide whether to run an extraction given the current token count.
     * Translated from shouldExtractMemory() in sessionMemory.ts
     *
     * @param currentTokenCount estimated token count of the current context window
     * @param toolCallsSince    tool-call count since the last extraction message
     * @param lastTurnHasTools  whether the last assistant turn still has pending tool calls
     * @return true if extraction should run
     */
    public boolean shouldExtractMemory(
            int currentTokenCount,
            int toolCallsSince,
            boolean lastTurnHasTools) {

        // Initialization threshold
        if (!sessionMemoryInitialized.get()) {
            if (currentTokenCount < config.minimumMessageTokensToInit()) {
                return false;
            }
            sessionMemoryInitialized.set(true);
        }

        // Minimum tokens between updates
        int tokensSinceLast = currentTokenCount - lastExtractionTokenCount.get();
        boolean hasMetTokenThreshold = tokensSinceLast >= config.minimumTokensBetweenUpdate();

        // Tool-call threshold
        boolean hasMetToolCallThreshold = toolCallsSince >= config.toolCallsBetweenUpdates();

        // Trigger when: (token AND tool-calls) OR (token AND no pending tools)
        return hasMetTokenThreshold && (hasMetToolCallThreshold || !lastTurnHasTools);
    }

    // -------------------------------------------------------------------------
    // File setup
    // -------------------------------------------------------------------------

    /**
     * Ensure the session memory directory and file exist.
     * Translated from setupSessionMemoryFile() in sessionMemory.ts
     *
     * @param sessionMemoryDir  directory for the memory file
     * @param sessionMemoryPath full path for the memory file
     * @param claudeConfigHome  path to ~/.claude (for template lookup)
     * @return current content of the memory file
     * @throws IOException if directory or file creation fails
     */
    public String setupSessionMemoryFile(
            String sessionMemoryDir,
            String sessionMemoryPath,
            String claudeConfigHome) throws IOException {

        Path dir = Path.of(sessionMemoryDir);
        if (!Files.exists(dir)) {
            try {
                Set<PosixFilePermission> perms =
                    PosixFilePermissions.fromString("rwx------");
                Files.createDirectories(dir,
                    PosixFilePermissions.asFileAttribute(perms));
            } catch (UnsupportedOperationException e) {
                // Non-POSIX filesystem (Windows) — create without permissions
                Files.createDirectories(dir);
            }
        }

        Path memPath = Path.of(sessionMemoryPath);
        if (!Files.exists(memPath)) {
            // Exclusive create (O_CREAT|O_EXCL equivalent)
            try {
                try {
                    Set<PosixFilePermission> filePerms =
                        PosixFilePermissions.fromString("rw-------");
                    Files.createFile(memPath,
                        PosixFilePermissions.asFileAttribute(filePerms));
                } catch (UnsupportedOperationException e) {
                    Files.createFile(memPath);
                }
                // Write template only when we just created the file
                String template = SessionMemoryPrompts.loadSessionMemoryTemplate(claudeConfigHome);
                Files.writeString(memPath, template, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (FileAlreadyExistsException ignored) {
                // Another process created it — that's fine
            }
        }

        try {
            return Files.readString(memPath);
        } catch (IOException e) {
            log.warn("[sessionMemory] could not read memory file: {}", e.getMessage());
            return "";
        }
    }

    // -------------------------------------------------------------------------
    // Extraction
    // -------------------------------------------------------------------------

    /**
     * Run a background extraction, recording token count and updating state.
     * Translated from the extractSessionMemory sequential function in sessionMemory.ts
     *
     * @param currentTokenCount current context-window token estimate
     * @param sessionMemoryPath path to the session memory file
     * @param currentNotes      current content of the memory file
     * @param claudeConfigHome  path to ~/.claude
     * @return CompletableFuture that completes when extraction finishes
     */
    public CompletableFuture<Void> extractSessionMemory(
            int currentTokenCount,
            String sessionMemoryPath,
            String currentNotes,
            String claudeConfigHome) {

        if (!isSessionMemoryGateEnabled()) {
            log.debug("[sessionMemory] gate disabled — skipping extraction");
            return CompletableFuture.completedFuture(null);
        }

        if (!extractionInProgress.compareAndSet(false, true)) {
            log.debug("[sessionMemory] extraction already in progress — skipping");
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                String prompt = SessionMemoryPrompts.buildSessionMemoryUpdatePrompt(
                    currentNotes, sessionMemoryPath, claudeConfigHome);

                // In a full implementation this would call runForkedAgent(prompt).
                log.debug("[sessionMemory] extraction prompt built ({} chars), would run forked agent",
                          prompt.length());

                lastExtractionTokenCount.set(currentTokenCount);
                log.debug("[sessionMemory] extraction completed");
            } finally {
                extractionInProgress.set(false);
            }
        });
    }

    /**
     * Manually trigger session memory extraction, bypassing threshold checks.
     * Used by the /summary command.
     * Translated from manuallyExtractSessionMemory() in sessionMemory.ts
     *
     * @param sessionMemoryPath path to the session memory file
     * @param currentNotes      current content of the memory file
     * @param claudeConfigHome  path to ~/.claude
     * @return extraction result
     */
    public CompletableFuture<ManualExtractionResult> manuallyExtractSessionMemory(
            String sessionMemoryPath,
            String currentNotes,
            String claudeConfigHome) {

        if (extractionInProgress.compareAndSet(false, true)) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    String prompt = SessionMemoryPrompts.buildSessionMemoryUpdatePrompt(
                        currentNotes, sessionMemoryPath, claudeConfigHome);

                    // In a full implementation this would call runForkedAgent(prompt).
                    log.info("[sessionMemory] manual extraction — prompt built ({} chars)",
                              prompt.length());

                    return (ManualExtractionResult) new ManualExtractionResult.Success(sessionMemoryPath);
                } catch (Exception e) {
                    log.warn("[sessionMemory] manual extraction failed: {}", e.getMessage());
                    return new ManualExtractionResult.Failure(e.getMessage());
                } finally {
                    extractionInProgress.set(false);
                }
            });
        } else {
            return CompletableFuture.completedFuture(
                new ManualExtractionResult.Failure("Extraction already in progress"));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Check if the session memory feature gate is enabled (cached, non-blocking).
     * Translated from isSessionMemoryGateEnabled() in sessionMemory.ts
     */
    public boolean isSessionMemoryGateEnabled() {
        try {
            return Boolean.TRUE.equals(
                growthBookService.getFeatureValueCachedMayBeStale("tengu_session_memory", false));
        } catch (Exception e) {
            return false;
        }
    }

    /** Whether the memory file has been initialized this session. */
    public boolean isSessionMemoryInitialized() {
        return sessionMemoryInitialized.get();
    }

    /** Mark the memory file as initialized. */
    public void markSessionMemoryInitialized() {
        sessionMemoryInitialized.set(true);
    }

    /** Mark extraction as started. */
    public void markExtractionStarted() {
        extractionInProgress.set(true);
    }

    /** Mark extraction as completed. */
    public void markExtractionCompleted() {
        extractionInProgress.set(false);
    }

    /** Record the token count at extraction time. */
    public void recordExtractionTokenCount(int tokenCount) {
        lastExtractionTokenCount.set(tokenCount);
    }

    /** Update the active config from remote values (non-zero values only). */
    public void applyRemoteConfig(
            int minimumMessageTokensToInit,
            int minimumTokensBetweenUpdate,
            int toolCallsBetweenUpdates) {

        SessionMemoryConfig defaults = SessionMemoryConfig.DEFAULT;
        this.config = new SessionMemoryConfig(
            minimumMessageTokensToInit > 0 ? minimumMessageTokensToInit : defaults.minimumMessageTokensToInit(),
            minimumTokensBetweenUpdate > 0 ? minimumTokensBetweenUpdate : defaults.minimumTokensBetweenUpdate(),
            toolCallsBetweenUpdates > 0 ? toolCallsBetweenUpdates : defaults.toolCallsBetweenUpdates()
        );
    }

    /** Get the currently active config. */
    public SessionMemoryConfig getConfig() {
        return config;
    }

    /**
     * Get the session memory file path for a given session.
     * Mirrors getSessionMemoryPath() from filesystem.ts
     */
    public String getSessionMemoryPath(String sessionMemoryDir, String sessionId) {
        return sessionMemoryDir + "/" + sessionId + "-session-memory.md";
    }

    /**
     * Get the session memory directory path.
     * Mirrors getSessionMemoryDir() from filesystem.ts
     */
    public String getSessionMemoryDir(String claudeConfigHome) {
        return claudeConfigHome + "/session-memory";
    }

    /**
     * Read current session memory content from disk.
     * Returns null if the file doesn't exist or can't be read.
     */
    public String getSessionMemoryContent() {
        return getSessionMemoryContentAsString();
    }

    /**
     * Read session memory content for the given session ID.
     * Returns Optional.empty() if the file doesn't exist or can't be read.
     */
    public java.util.Optional<String> getSessionMemoryContent(String sessionId) {
        try {
            String claudeHome = com.anthropic.claudecode.util.EnvUtils.getClaudeConfigHomeDir();
            String memDir = getSessionMemoryDir(claudeHome);
            String currentSessionId = sessionId != null ? sessionId : "current";
            String path = getSessionMemoryPath(memDir, currentSessionId);
            java.io.File file = new java.io.File(path);
            if (!file.exists()) return java.util.Optional.empty();
            String content = java.nio.file.Files.readString(file.toPath());
            return java.util.Optional.ofNullable(content);
        } catch (Exception e) {
            log.debug("Could not read session memory: {}", e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /**
     * Read current session memory content.
     * Returns null if not found (for AwaySummaryService).
     */
    public String getSessionMemoryContentAsString() {
        return getSessionMemoryContent(null).orElse(null);
    }
}
