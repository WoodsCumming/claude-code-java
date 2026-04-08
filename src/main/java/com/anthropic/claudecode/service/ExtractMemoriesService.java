package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.Message;
import com.anthropic.claudecode.model.ToolUseContext;
import com.anthropic.claudecode.util.ExtractMemoriesPrompts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Extract memories service for background memory extraction.
 * Translated from src/services/extractMemories/extractMemories.ts
 *
 * Runs once at the end of each complete query loop (when the model produces a
 * final response with no tool calls) and writes durable memories to the
 * auto-memory directory.
 *
 * Uses a forked-agent pattern — a perfect copy of the main conversation that
 * shares the parent's prompt cache.
 *
 * Key design properties (mirroring the TS closure pattern):
 * - Re-entrant: if a new call arrives while extraction is in progress, the new
 *   context is stashed and a trailing extraction runs after the current one.
 * - Throttleable: turnsSinceLastExtraction allows skipping N-1 turns out of N.
 * - Cursor-based: only messages since lastMemoryMessageUuid are processed each run.
 */
@Slf4j
@Service
public class ExtractMemoriesService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ExtractMemoriesService.class);


    // =========================================================================
    // Constants
    // =========================================================================

    /** Well-behaved extractions complete in 2–4 turns; hard cap prevents rabbit-holes. */
    private static final int MAX_EXTRACTION_TURNS = 5;

    /** Default minimum eligible turns between extraction runs (feature-flag governed in TS). */
    private static final int DEFAULT_TURNS_BETWEEN_EXTRACTIONS = 1;

    // =========================================================================
    // Dependencies
    // =========================================================================

    private final ForkedAgentService forkedAgentService;
    private final MemoryScanService memoryScanService;
    private final MemoryPathService memoryPathService;

    // =========================================================================
    // Closure-scoped mutable state (mirrors TS initExtractMemories closure)
    // =========================================================================

    /** UUID of the last message processed; null = start from the beginning. */
    private volatile String lastMemoryMessageUuid = null;

    /** True while runExtraction is executing — prevents overlapping runs. */
    private final AtomicBoolean inProgress = new AtomicBoolean(false);

    /** Eligible turns since the last extraction; resets to 0 after each run. */
    private final AtomicInteger turnsSinceLastExtraction = new AtomicInteger(0);

    /** Whether this service has been initialized (feature gate). */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /**
     * When a new call arrives during an in-progress run, the latest context
     * is stashed here for a trailing extraction.
     */
    private final AtomicReference<PendingContext> pendingContext = new AtomicReference<>(null);

    /**
     * In-flight extraction futures; used by drainPendingExtraction to await them.
     */
    private final Set<CompletableFuture<Void>> inFlightExtractions =
            Collections.synchronizedSet(new HashSet<>());

    @Autowired
    public ExtractMemoriesService(ForkedAgentService forkedAgentService,
                                   MemoryScanService memoryScanService,
                                   MemoryPathService memoryPathService) {
        this.forkedAgentService = forkedAgentService;
        this.memoryScanService = memoryScanService;
        this.memoryPathService = memoryPathService;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Initialize the memory extraction system.
     * Translated from initExtractMemories() in extractMemories.ts
     * Call once at startup (or in tests' beforeEach for fresh state).
     */
    public void initExtractMemories() {
        lastMemoryMessageUuid = null;
        inProgress.set(false);
        turnsSinceLastExtraction.set(0);
        pendingContext.set(null);
        inFlightExtractions.clear();
        initialized.set(true);
        log.debug("[extractMemories] service initialized");
    }

    /**
     * Run memory extraction at the end of a query loop.
     * Fire-and-forget: returns immediately; actual work happens asynchronously.
     * No-ops until initExtractMemories() has been called.
     * Translated from executeExtractMemories() in extractMemories.ts
     *
     * @param messages        full conversation message list
     * @param toolUseContext  current tool-use context
     * @param teamMemEnabled  whether team memory feature is active
     */
    public void executeExtractMemories(
            List<Message> messages,
            ToolUseContext toolUseContext,
            boolean teamMemEnabled) {

        if (!initialized.get()) return;

        // Only run for the main agent (not subagents)
        if (toolUseContext != null && toolUseContext.getAgentId() != null) return;

        // Auto-memory must be enabled
        if (!memoryPathService.isAutoMemoryEnabled()) return;

        // Remote mode is not supported
        if (isRemoteMode()) return;

        CompletableFuture<Void> future = CompletableFuture.runAsync(() ->
                executeExtractMemoriesImpl(messages, toolUseContext, teamMemEnabled));

        inFlightExtractions.add(future);
        future.whenComplete((__, ___) -> inFlightExtractions.remove(future));
    }

    /**
     * Await all in-flight extractions (including trailing stashed runs) with a
     * soft timeout. Called before graceful shutdown so the forked agent completes
     * before the process exits.
     * Translated from drainPendingExtraction() in extractMemories.ts
     *
     * @param timeoutMs soft timeout in milliseconds (default 60 000)
     */
    public void drainPendingExtraction(long timeoutMs) {
        if (inFlightExtractions.isEmpty()) return;
        try {
            CompletableFuture<Void> all = CompletableFuture.allOf(
                    inFlightExtractions.toArray(new CompletableFuture[0]));
            all.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.debug("[extractMemories] drain timed out after {}ms", timeoutMs);
        } catch (Exception e) {
            log.debug("[extractMemories] drain interrupted: {}", e.getMessage());
        }
    }

    /**
     * Convenience overload: drain with 60-second default timeout.
     */
    public void drainPendingExtraction() {
        drainPendingExtraction(60_000);
    }

    // =========================================================================
    // Internal — main extraction flow
    // =========================================================================

    private void executeExtractMemoriesImpl(
            List<Message> messages,
            ToolUseContext toolUseContext,
            boolean teamMemEnabled) {

        if (inProgress.get()) {
            log.debug("[extractMemories] extraction in progress — stashing for trailing run");
            pendingContext.set(new PendingContext(messages, toolUseContext, teamMemEnabled));
            return;
        }

        runExtraction(messages, toolUseContext, teamMemEnabled, false);
    }

    /**
     * Core extraction logic.
     * Translated from runExtraction() in extractMemories.ts
     */
    private void runExtraction(
            List<Message> messages,
            ToolUseContext toolUseContext,
            boolean teamMemEnabled,
            boolean isTrailingRun) {

        String memoryDir = memoryPathService.getAutoMemPath();
        int newMessageCount = countModelVisibleMessagesSince(messages, lastMemoryMessageUuid);

        // Skip if the main agent already wrote memories this turn
        if (hasMemoryWritesSince(messages, lastMemoryMessageUuid)) {
            log.debug("[extractMemories] skipping — conversation already wrote to memory files");
            advanceCursor(messages);
            return;
        }

        // Throttle: skip unless turnsSinceLastExtraction has reached threshold
        if (!isTrailingRun) {
            int turns = turnsSinceLastExtraction.incrementAndGet();
            if (turns < DEFAULT_TURNS_BETWEEN_EXTRACTIONS) {
                log.debug("[extractMemories] throttled ({}/{})", turns, DEFAULT_TURNS_BETWEEN_EXTRACTIONS);
                return;
            }
        }
        turnsSinceLastExtraction.set(0);

        inProgress.set(true);
        long startTime = System.currentTimeMillis();

        try {
            log.debug("[extractMemories] starting — {} new messages, memoryDir={}", newMessageCount, memoryDir);

            // Pre-inject the memory directory manifest
            String existingMemories = memoryScanService.formatMemoryManifest(
                    memoryScanService.scanMemoryFiles(memoryDir).join());

            // Build the extraction prompt
            String userPrompt = teamMemEnabled
                    ? ExtractMemoriesPrompts.buildExtractCombinedPrompt(
                            newMessageCount, existingMemories, teamMemEnabled)
                    : ExtractMemoriesPrompts.buildExtractAutoOnlyPrompt(
                            newMessageCount, existingMemories);

            // Run the forked agent
            ForkedAgentService.ForkedAgentResult result = forkedAgentService.runForkedAgent(
                    ForkedAgentService.ForkedAgentParams.builder()
                            .initialMessage(userPrompt)
                            .querySource("extract_memories")
                            .build(),
                    null)   // no parent context needed for background memory extraction
                    .get(60, java.util.concurrent.TimeUnit.SECONDS);

            // Advance cursor on success
            advanceCursor(messages);

            List<String> writtenPaths = extractWrittenPaths(result.getMessages(), memoryDir);
            long durationMs = System.currentTimeMillis() - startTime;

            log.debug("[extractMemories] finished — {} files written in {}ms",
                    writtenPaths.size(), durationMs);

            if (!writtenPaths.isEmpty()) {
                log.debug("[extractMemories] memories saved: {}", String.join(", ", writtenPaths));
            }

        } catch (Exception e) {
            log.debug("[extractMemories] error: {}", e.getMessage());
        } finally {
            inProgress.set(false);

            // If a call arrived while we were running, run a trailing extraction
            PendingContext trailing = pendingContext.getAndSet(null);
            if (trailing != null) {
                log.debug("[extractMemories] running trailing extraction for stashed context");
                runExtraction(trailing.messages(), trailing.toolUseContext(),
                        trailing.teamMemEnabled(), true);
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Count model-visible messages since a given UUID.
     * Translated from countModelVisibleMessagesSince() in extractMemories.ts
     */
    private int countModelVisibleMessagesSince(List<Message> messages, String sinceUuid) {
        if (sinceUuid == null) {
            return (int) messages.stream().filter(this::isModelVisible).count();
        }
        boolean found = false;
        int count = 0;
        for (Message m : messages) {
            if (!found) {
                if (sinceUuid.equals(m.getUuid())) found = true;
                continue;
            }
            if (isModelVisible(m)) count++;
        }
        // UUID not found (e.g. removed by context compaction): count all
        return found ? count : (int) messages.stream().filter(this::isModelVisible).count();
    }

    /**
     * Return true if the message is sent in API calls (user or assistant).
     * Translated from isModelVisibleMessage() in extractMemories.ts
     */
    private boolean isModelVisible(Message m) {
        return "user".equals(m.getType()) || "assistant".equals(m.getType());
    }

    /**
     * Return true if any assistant message after the cursor contains a
     * Write/Edit tool_use targeting an auto-memory path.
     * Translated from hasMemoryWritesSince() in extractMemories.ts
     */
    private boolean hasMemoryWritesSince(List<Message> messages, String sinceUuid) {
        boolean foundStart = (sinceUuid == null);
        for (Message msg : messages) {
            if (!foundStart) {
                if (sinceUuid.equals(msg.getUuid())) foundStart = true;
                continue;
            }
            if (!"assistant".equals(msg.getType())) continue;
            if (!(msg instanceof Message.AssistantMessage am)) continue;
            if (am.getContent() == null) continue;
            for (var block : am.getContent()) {
                if (isMemoryWriteBlock(block)) return true;
            }
        }
        return false;
    }

    private boolean isMemoryWriteBlock(Object block) {
        if (!(block instanceof com.anthropic.claudecode.model.ContentBlock.ToolUseBlock tu)) return false;
        String name = tu.getName();
        if (!"Edit".equals(name) && !"Write".equals(name)) return false;
        Map<String, Object> input = tu.getInput();
        if (input == null) return false;
        Object fp = input.get("file_path");
        return fp instanceof String s && memoryPathService.isAutoMemPath(s);
    }

    private List<String> extractWrittenPaths(List<Message> agentMessages, String memoryDir) {
        List<String> paths = new ArrayList<>();
        for (Message msg : agentMessages) {
            if (!"assistant".equals(msg.getType())) continue;
            if (!(msg instanceof Message.AssistantMessage am)) continue;
            if (am.getContent() == null) continue;
            for (var block : am.getContent()) {
                if (!(block instanceof com.anthropic.claudecode.model.ContentBlock.ToolUseBlock tu)) continue;
                String name = tu.getName();
                if (!"Edit".equals(name) && !"Write".equals(name)) continue;
                Map<String, Object> input = tu.getInput();
                if (input == null) continue;
                Object fp = input.get("file_path");
                if (fp instanceof String s && !paths.contains(s)) {
                    paths.add(s);
                }
            }
        }
        return paths;
    }

    private void advanceCursor(List<Message> messages) {
        if (!messages.isEmpty()) {
            Message last = messages.get(messages.size() - 1);
            if (last.getUuid() != null) {
                lastMemoryMessageUuid = last.getUuid();
            }
        }
    }

    private boolean isRemoteMode() {
        // Delegate to environment / bootstrap state; conservative default = false
        String remote = System.getenv("CLAUDE_REMOTE_MODE");
        return "true".equalsIgnoreCase(remote);
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /** Stashed context waiting for a trailing extraction. */
    private record PendingContext(
            List<Message> messages,
            ToolUseContext toolUseContext,
            boolean teamMemEnabled) {}
}
