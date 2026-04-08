package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Session cache clearing utilities.
 * Translated from src/commands/clear/caches.ts
 *
 * Clears all session-related caches without touching conversation messages,
 * session IDs, or session-lifecycle hooks.  Used both by the {@code /clear}
 * command and by {@code --resume}/{@code --continue} startup paths.
 *
 * When {@code preservedAgentIds} is non-empty, per-agent state for those
 * agents (invoked skills) is kept intact so that background tasks that survive
 * a {@code /clear} keep functioning.  Request-ID-keyed state (pending
 * permission callbacks, dump state, cache-break tracking) cannot be scoped to
 * individual agents and is therefore left alone whenever any agents are
 * preserved.
 */
@Slf4j
@Service
public class ClearCachesService {



    // ---------------------------------------------------------------------------
    // Dependencies
    // ---------------------------------------------------------------------------

    private final ContextService contextService;
    private final SkillsLoaderService skillsLoaderService;
    private final PostCompactCleanupService postCompactCleanupService;
    private final AttachmentService attachmentService;
    private final ClaudeMdService claudeMdService;
    private final SessionIngressService sessionIngressService;
    private final ImageStoreService imageStoreService;
    private final SessionEnvVarsService sessionEnvVarsService;
    private final LspDiagnosticRegistry lspDiagnosticRegistry;
    private final MagicDocsService magicDocsService;
    private final BootstrapStateService bootstrapStateService;
    private final PromptCacheService promptCacheService;

    @Autowired
    public ClearCachesService(ContextService contextService,
                               SkillsLoaderService skillsLoaderService,
                               PostCompactCleanupService postCompactCleanupService,
                               AttachmentService attachmentService,
                               ClaudeMdService claudeMdService,
                               SessionIngressService sessionIngressService,
                               ImageStoreService imageStoreService,
                               SessionEnvVarsService sessionEnvVarsService,
                               LspDiagnosticRegistry lspDiagnosticRegistry,
                               MagicDocsService magicDocsService,
                               BootstrapStateService bootstrapStateService,
                               PromptCacheService promptCacheService) {
        this.contextService = contextService;
        this.skillsLoaderService = skillsLoaderService;
        this.postCompactCleanupService = postCompactCleanupService;
        this.attachmentService = attachmentService;
        this.claudeMdService = claudeMdService;
        this.sessionIngressService = sessionIngressService;
        this.imageStoreService = imageStoreService;
        this.sessionEnvVarsService = sessionEnvVarsService;
        this.lspDiagnosticRegistry = lspDiagnosticRegistry;
        this.magicDocsService = magicDocsService;
        this.bootstrapStateService = bootstrapStateService;
        this.promptCacheService = promptCacheService;
    }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Clear all session-related caches using an empty preserved-agents set
     * (i.e. no background tasks to protect).
     */
    public void clearSessionCaches() {
        clearSessionCaches(Set.of());
    }

    /**
     * Clear all session-related caches.
     * Mirrors {@code clearSessionCaches()} in caches.ts.
     *
     * @param preservedAgentIds Agent IDs whose per-agent state should survive
     *                          the clear (e.g. background tasks preserved
     *                          across /clear).
     */
    public void clearSessionCaches(Set<String> preservedAgentIds) {
        boolean hasPreserved = !preservedAgentIds.isEmpty();

        log.debug("Clearing session caches (preservedAgents={})", preservedAgentIds.size());

        // Clear context caches (user context, system context, git status,
        // session start date).
        contextService.clearCaches();

        // Clear file-suggestion caches used by @ mentions
        contextService.clearFileSuggestionCaches();

        // Clear commands / skills cache
        skillsLoaderService.clearCommandsCache();

        // Clear prompt cache break detection state (skip if agents are preserved
        // since their in-flight requests may still rely on this state).
        if (!hasPreserved) {
            promptCacheService.resetPromptCacheBreakDetection();
        }

        // Clear system-prompt injection (cache breaker token)
        contextService.setSystemPromptInjection(null);

        // Clear last-emitted date so it is re-detected on the next turn
        bootstrapStateService.setLastEmittedDate(null);

        // Run post-compaction cleanup (system-prompt sections, microcompact
        // tracking, classifier approvals, speculative checks, memory-files cache
        // with load_reason='compact').
        postCompactCleanupService.runPostCompactCleanup();

        // Reset sent skill names so the full skill listing is re-sent after /clear.
        // runPostCompactCleanup intentionally skips this (post-compact re-injection
        // costs ~4 K tokens), but /clear wipes messages entirely so the model needs
        // the full listing again.
        attachmentService.resetSentSkillNames();

        // Override the memory-cache reset reason to 'session_start' so the
        // InstructionsLoaded hook fires correctly on the next getMemoryFiles() call.
        claudeMdService.resetGetMemoryFilesCache("session_start");

        // Clear stored image paths cache
        imageStoreService.clearStoredImagePaths();

        // Clear all session-ingress caches (lastUuidMap, sequentialAppendBySession)
        sessionIngressService.clearAllSessions();

        // Clear swarm permission pending callbacks (skip if agents are preserved)
        if (!hasPreserved) {
            bootstrapStateService.clearAllPendingCallbacks();
        }

        // Clear repository detection caches
        contextService.clearRepositoryCaches();

        // Clear bash command prefix caches (Haiku-extracted prefixes)
        contextService.clearCommandPrefixCaches();

        // Clear dump-prompts state (skip if agents are preserved)
        if (!hasPreserved) {
            bootstrapStateService.clearAllDumpState();
        }

        // Clear invoked-skills cache; per-agent entries for preserved agents survive
        skillsLoaderService.clearInvokedSkills(preservedAgentIds);

        // Clear git dir resolution cache
        contextService.clearResolveGitDirCache();

        // Clear dynamic skills loaded from skill directories
        skillsLoaderService.clearDynamicSkills();

        // Clear LSP diagnostic tracking state
        lspDiagnosticRegistry.resetAllLSPDiagnosticState();

        // Clear tracked magic docs
        magicDocsService.clearTrackedMagicDocs();

        // Clear session environment variables
        sessionEnvVarsService.clearSessionEnvVars();

        log.debug("Session caches cleared successfully");
    }
}
