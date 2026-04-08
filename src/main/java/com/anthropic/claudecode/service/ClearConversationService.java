package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Conversation clearing utility.
 * Translated from src/commands/clear/conversation.ts
 *
 * Performs a full {@code /clear} cycle:
 * <ol>
 *   <li>Run SessionEnd hooks (bounded by a configurable timeout).</li>
 *   <li>Log a cache-eviction hint for the last request.</li>
 *   <li>Compute which background tasks survive the clear.</li>
 *   <li>Wipe conversation messages and session-related state.</li>
 *   <li>Regenerate the session ID.</li>
 *   <li>Re-point task-output symlinks for surviving local-agent tasks.</li>
 *   <li>Persist mode / worktree state for future {@code --resume}.</li>
 *   <li>Run SessionStart hooks and return any hook-injected messages.</li>
 * </ol>
 */
@Slf4j
@Service
public class ClearConversationService {



    // ---------------------------------------------------------------------------
    // Dependencies
    // ---------------------------------------------------------------------------

    private final ClearCachesService clearCachesService;
    private final HookService hookService;
    private final SessionStorageService sessionStorageService;
    private final BootstrapStateService bootstrapStateService;
    private final SessionStartService sessionStartService;
    private final AnalyticsService analyticsService;
    private final PlanService planService;
    private final TaskOutputDiskService taskOutputDiskService;
    private final WorktreeService worktreeService;

    @Autowired
    public ClearConversationService(ClearCachesService clearCachesService,
                                     HookService hookService,
                                     SessionStorageService sessionStorageService,
                                     BootstrapStateService bootstrapStateService,
                                     SessionStartService sessionStartService,
                                     AnalyticsService analyticsService,
                                     PlanService planService,
                                     TaskOutputDiskService taskOutputDiskService,
                                     WorktreeService worktreeService) {
        this.clearCachesService = clearCachesService;
        this.hookService = hookService;
        this.sessionStorageService = sessionStorageService;
        this.bootstrapStateService = bootstrapStateService;
        this.sessionStartService = sessionStartService;
        this.analyticsService = analyticsService;
        this.planService = planService;
        this.taskOutputDiskService = taskOutputDiskService;
        this.worktreeService = worktreeService;
    }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Execute a full /clear of the current conversation.
     * Mirrors {@code clearConversation()} in conversation.ts.
     *
     * @param request  Parameters that describe the current session state and
     *                 provide callbacks for mutating it.
     * @return A {@link ClearResult} that may contain hook-injected messages to
     *         display at the start of the new empty conversation.
     */
    public CompletableFuture<ClearResult> clearConversation(ClearRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1. Execute SessionEnd hooks before clearing
                long sessionEndTimeoutMs = hookService.getSessionEndHookTimeoutMs();
                hookService.executeSessionEndHooks("clear", sessionEndTimeoutMs);

                // 2. Log a cache-eviction hint so the inference layer can reclaim
                //    the prompt-cache slot for the conversation that is about to
                //    be discarded.
                String lastRequestId = bootstrapStateService.getLastMainRequestId();
                if (lastRequestId != null) {
                    analyticsService.logEvent("tengu_cache_eviction_hint", Map.of(
                        "scope", "conversation_clear",
                        "last_request_id", lastRequestId
                    ));
                }

                // 3. Determine which tasks (and their agent IDs) survive the clear.
                //    A task is preserved unless it has isBackgrounded == false.
                //    LocalAgentTask and InProcessTeammateTask entries carry an agentId.
                Set<String> preservedAgentIds = new HashSet<>();
                List<PreservedLocalAgent> preservedLocalAgents = new ArrayList<>();

                if (request.tasks() != null) {
                    for (Map.Entry<String, TaskInfo> entry : request.tasks().entrySet()) {
                        TaskInfo task = entry.getValue();
                        // Skip foreground tasks (they will be killed)
                        if (Boolean.FALSE.equals(task.isBackgrounded())) continue;

                        if (task.agentId() != null) {
                            preservedAgentIds.add(task.agentId());
                            if (TaskInfo.TaskType.LOCAL_AGENT == task.type()) {
                                preservedLocalAgents.add(
                                    new PreservedLocalAgent(entry.getKey(), task.agentId(), task.status())
                                );
                            }
                        }
                    }
                }

                // 4. Wipe conversation messages
                log.info("Clearing conversation messages");

                // 5. Clear all session-related caches (per-agent state for preserved
                //    background tasks is retained).
                clearCachesService.clearSessionCaches(preservedAgentIds);

                // Restore working directory to original CWD
                bootstrapStateService.resetCwdToOriginal();

                // Clear transient per-session collections provided by the caller
                if (request.discoveredSkillNames() != null) {
                    request.discoveredSkillNames().clear();
                }
                if (request.loadedNestedMemoryPaths() != null) {
                    request.loadedNestedMemoryPaths().clear();
                }

                // 6. Clear plan slug cache so a new plan file is used after /clear
                planService.clearAllPlanSlugs();

                // 7. Clear cached session metadata (title, tag, agent name/color)
                sessionStorageService.clearSessionMetadata();

                // 8. Regenerate session ID; record old session as parent for analytics
                bootstrapStateService.regenerateSessionId(true);
                log.info("Session ID regenerated after /clear");

                // 9. Reset the session file pointer to the new session directory
                sessionStorageService.resetSessionFilePointer();

                // 10. Re-point TaskOutput symlinks for still-running preserved
                //     local-agent tasks so that post-clear transcript writes land
                //     in the correct (new session) directory.
                for (PreservedLocalAgent agent : preservedLocalAgents) {
                    if (!"running".equals(agent.status())) continue;
                    String agentTranscriptPath =
                        sessionStorageService.getAgentTranscriptPath(agent.agentId());
                    taskOutputDiskService.initTaskOutputAsSymlink(
                        agent.taskId(), agentTranscriptPath
                    );
                }

                // 11. Re-persist mode and worktree state for future --resume
                worktreeService.saveCurrentWorktreeStateIfPresent();

                // 12. Execute SessionStart hooks after clearing and collect any
                //     messages they inject.
                List<String> hookMessages =
                    sessionStartService.processSessionStartHooks("clear");

                log.info("Conversation cleared successfully");
                return new ClearResult(hookMessages);

            } catch (Exception e) {
                log.error("Error during conversation clear", e);
                throw new RuntimeException("Failed to clear conversation: " + e.getMessage(), e);
            }
        });
    }

    // ---------------------------------------------------------------------------
    // Supporting types
    // ---------------------------------------------------------------------------

    /**
     * Input parameters for {@link #clearConversation}.
     *
     * @param tasks                  Snapshot of current task states, keyed by task ID.
     * @param discoveredSkillNames   Mutable set of discovered skill names to wipe.
     * @param loadedNestedMemoryPaths Mutable set of loaded nested-memory paths to wipe.
     */
    public record ClearRequest(
        Map<String, TaskInfo> tasks,
        Set<String> discoveredSkillNames,
        Set<String> loadedNestedMemoryPaths
    ) {}

    /**
     * Outcome of a /clear invocation.
     *
     * @param hookMessages Messages injected by SessionStart hooks, possibly empty.
     */
    public record ClearResult(List<String> hookMessages) {
        public boolean hasHookMessages() {
            return hookMessages != null && !hookMessages.isEmpty();
        }
    }

    /**
     * Minimal task descriptor used during the clear computation.
     *
     * @param agentId        Agent ID if the task owns an agent context.
     * @param isBackgrounded {@code null} for tasks that have no backgrounding
     *                       concept; {@code false} for foreground tasks that
     *                       must be killed.
     * @param status         Current execution status string (e.g. "running").
     * @param type           Task category.
     */
    public record TaskInfo(
        String agentId,
        Boolean isBackgrounded,
        String status,
        TaskType type
    ) {
        /** Task categories relevant to the clear computation. */
        public enum TaskType {
            LOCAL_AGENT,
            IN_PROCESS_TEAMMATE,
            OTHER
        }
    }

    /** Internal: a preserved local-agent task that needs its symlink re-pointed. */
    private record PreservedLocalAgent(String taskId, String agentId, String status) {}
}
