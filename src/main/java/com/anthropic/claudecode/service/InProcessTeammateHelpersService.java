package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Helper functions for in-process teammate integration.
 * Provides utilities to:
 * - Find task ID by agent name
 * - Handle plan approval responses
 * - Update awaitingPlanApproval state
 * - Detect permission-related messages
 * Translated from src/utils/inProcessTeammateHelpers.ts
 */
@Slf4j
@Service
public class InProcessTeammateHelpersService {



    private final TaskFrameworkService taskFrameworkService;
    private final TeammateMailboxService teammateMailboxService;

    @Autowired
    public InProcessTeammateHelpersService(
            TaskFrameworkService taskFrameworkService,
            TeammateMailboxService teammateMailboxService) {
        this.taskFrameworkService = taskFrameworkService;
        this.teammateMailboxService = teammateMailboxService;
    }

    // =========================================================================
    // Task lookup
    // =========================================================================

    /**
     * Find the task ID for an in-process teammate by agent name.
     * Translated from findInProcessTeammateTaskId() in inProcessTeammateHelpers.ts
     *
     * @param agentName The agent name (e.g. "researcher")
     * @return Task ID if found, empty otherwise
     */
    public Optional<String> findInProcessTeammateTaskId(String agentName) {
        return taskFrameworkService.listTasks().stream()
                .filter(t -> "in_process_teammate".equals(t.getType()))
                .filter(t -> {
                    Object identity = t.getMetadata().get("identity");
                    if (identity instanceof java.util.Map<?, ?> map) {
                        return agentName.equals(map.get("agentName"));
                    }
                    return false;
                })
                .map(TaskFrameworkService.TaskState::getId)
                .findFirst();
    }

    // =========================================================================
    // Plan approval
    // =========================================================================

    /**
     * Set awaitingPlanApproval state for an in-process teammate.
     * Translated from setAwaitingPlanApproval() in inProcessTeammateHelpers.ts
     *
     * @param taskId   Task ID of the in-process teammate
     * @param awaiting Whether the teammate is awaiting plan approval
     */
    public void setAwaitingPlanApproval(String taskId, boolean awaiting) {
        taskFrameworkService.updateTaskMetadata(taskId, meta -> {
            meta.put("awaitingPlanApproval", awaiting);
            return meta;
        });
        log.debug("[in-process] Task {} awaitingPlanApproval = {}", taskId, awaiting);
    }

    /**
     * Handle a plan approval response for an in-process teammate.
     * Resets awaitingPlanApproval to false.
     * Translated from handlePlanApprovalResponse() in inProcessTeammateHelpers.ts
     *
     * @param taskId   Task ID of the in-process teammate
     * @param response The plan approval response message (reserved for future use)
     */
    public void handlePlanApprovalResponse(String taskId, Object response) {
        setAwaitingPlanApproval(taskId, false);
    }

    // =========================================================================
    // Permission delegation
    // =========================================================================

    /**
     * Check if a message is a permission-related response.
     * Handles both tool permissions and sandbox (network host) permissions.
     * Translated from isPermissionRelatedResponse() in inProcessTeammateHelpers.ts
     *
     * @param messageText The raw message text to check
     * @return {@code true} if the message is a permission response
     */
    public boolean isPermissionRelatedResponse(String messageText) {
        return teammateMailboxService.isPermissionResponse(messageText)
                || teammateMailboxService.isSandboxPermissionResponse(messageText);
    }
}
