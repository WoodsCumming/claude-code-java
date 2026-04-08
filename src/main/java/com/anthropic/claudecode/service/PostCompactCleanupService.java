package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Post-compact cleanup service.
 * Translated from src/services/compact/postCompactCleanup.ts
 *
 * Runs cleanup of caches and tracking state after compaction.
 * Called after both auto-compact and manual /compact to free memory
 * held by tracking structures that are invalidated by compaction.
 *
 * Note: We intentionally do NOT clear invoked skill content here.
 * Skill content must survive across multiple compactions so that
 * createSkillAttachmentIfNeeded() can include the full skill text
 * in subsequent compaction attachments.
 *
 * querySource: pass the compacting query's source so we can skip
 * resets that would clobber main-thread module-level state. Subagents
 * (agent:*) run in the same process and share module-level state;
 * resetting those when a SUBAGENT compacts would corrupt the MAIN
 * thread's state.
 */
@Slf4j
@Service
public class PostCompactCleanupService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PostCompactCleanupService.class);


    private final MicroCompactService microCompactService;
    private final CompactWarningService compactWarningService;

    @Autowired
    public PostCompactCleanupService(
            MicroCompactService microCompactService,
            CompactWarningService compactWarningService) {
        this.microCompactService = microCompactService;
        this.compactWarningService = compactWarningService;
    }

    /**
     * Run cleanup of caches and tracking state after compaction.
     * Translated from runPostCompactCleanup() in postCompactCleanup.ts
     *
     * <p>Subagents (agent:*) run in the same process and share module-level
     * state with the main thread. Only reset main-thread module-level state
     * (context-collapse, memory file cache) for main-thread compacts.
     * Same startsWith pattern as isMainThread in index.ts.
     *
     * @param querySource The query source of the compacting query, or {@code null}
     *                    for callers that are genuinely main-thread-only (/compact, /clear).
     */
    public void runPostCompactCleanup(String querySource) {
        // Subagents (agent:*) share module-level state with the main thread.
        // Only reset main-thread state for main-thread compacts.
        boolean isMainThreadCompact = querySource == null
                || querySource.startsWith("repl_main_thread")
                || "sdk".equals(querySource);

        log.debug("Running post-compact cleanup (main thread: {})", isMainThreadCompact);

        // Reset microcompact state — always, for all callers.
        microCompactService.resetMicrocompactState();

        if (isMainThreadCompact) {
            // getUserContext is a memoized outer layer wrapping getClaudeMds() →
            // getMemoryFiles(). Clear it so the next turn's InstructionsLoaded hook fires.
            // (In the Java port the equivalent would be clearing any memoized user-context cache.)
            log.debug("Clearing main-thread caches after compact");
            // TODO: wire up context-collapse reset when ContextCollapseService is available.
            // TODO: wire up getUserContext cache clear and resetGetMemoryFilesCache.
        }

        // Always clear these regardless of query source.
        // clearSystemPromptSections(), clearClassifierApprovals(), clearSpeculativeChecks(),
        // clearBetaTracingState(), clearSessionMessagesCache() — add wiring once those
        // services exist in the Java port.
        log.debug("Post-compact cleanup complete");
    }

    /**
     * Convenience overload for callers that are always on the main thread.
     */
    public void runPostCompactCleanup() {
        runPostCompactCleanup(null);
    }
}
