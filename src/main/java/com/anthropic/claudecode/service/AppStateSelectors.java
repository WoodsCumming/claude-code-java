package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.Optional;

/**
 * Selectors for deriving computed state from AppState.
 * Translated from src/state/selectors.ts
 *
 * Keep selectors pure — just data extraction, no side effects.
 */
@Slf4j
@Service
public class AppStateSelectors {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AppStateSelectors.class);


    private final AppState appState;

    @Autowired
    public AppStateSelectors(AppState appState) {
        this.appState = appState;
    }

    // =========================================================================
    // ActiveAgentForInput — discriminated union
    // Translated from ActiveAgentForInput type in selectors.ts
    // =========================================================================

    /**
     * Sealed interface that mirrors the TypeScript discriminated union:
     *   { type: 'leader' } | { type: 'viewed'; task } | { type: 'named_agent'; task }
     */
    public sealed interface ActiveAgentForInput
            permits ActiveAgentForInput.Leader,
                    ActiveAgentForInput.Viewed,
                    ActiveAgentForInput.NamedAgent {

        /** Input routes to the leader / main session. */
        record Leader() implements ActiveAgentForInput {}

        /** User is viewing an in-process teammate; input goes to that teammate. */
        record Viewed(Map<String, Object> task) implements ActiveAgentForInput {}

        /** User is viewing a named LocalAgentTask. */
        record NamedAgent(Map<String, Object> task) implements ActiveAgentForInput {}
    }

    // =========================================================================
    // getViewedTeammateTask
    // =========================================================================

    /**
     * Get the currently viewed teammate task, if any.
     * Translated from getViewedTeammateTask() in selectors.ts
     *
     * Returns empty when:
     * - No teammate is being viewed (viewingAgentTaskId is null)
     * - The task ID doesn't exist in tasks
     * - The task is not an in-process teammate task
     */
    public Optional<Map<String, Object>> getViewedTeammateTask() {
        return getViewedTeammateTask(appState);
    }

    /**
     * Stateless overload that accepts an explicit AppState snapshot.
     * Use this inside update functions where you have a local prev state.
     */
    @SuppressWarnings("unchecked")
    public static Optional<Map<String, Object>> getViewedTeammateTask(AppState state) {
        String viewingId = state.getViewingAgentTaskId();
        if (viewingId == null) {
            return Optional.empty();
        }

        Object taskObj = state.getTasks().get(viewingId);
        if (taskObj == null) {
            return Optional.empty();
        }

        // Verify it's an in-process teammate task
        if (taskObj instanceof Map<?, ?> taskMap) {
            if ("in_process_teammate".equals(taskMap.get("type"))) {
                return Optional.of((Map<String, Object>) taskMap);
            }
        }
        return Optional.empty();
    }

    // =========================================================================
    // getActiveAgentForInput
    // =========================================================================

    /**
     * Determine where user input should be routed.
     * Translated from getActiveAgentForInput() in selectors.ts
     *
     * Returns:
     *   Leader      — not viewing a teammate (input goes to leader)
     *   Viewed      — viewing an in-process teammate (input goes to that agent)
     *   NamedAgent  — viewing a local agent task
     */
    public ActiveAgentForInput getActiveAgentForInput() {
        return getActiveAgentForInput(appState);
    }

    /**
     * Stateless overload that accepts an explicit AppState snapshot.
     */
    @SuppressWarnings("unchecked")
    public static ActiveAgentForInput getActiveAgentForInput(AppState state) {
        // Check for viewed in-process teammate first
        Optional<Map<String, Object>> viewedTask = getViewedTeammateTask(state);
        if (viewedTask.isPresent()) {
            return new ActiveAgentForInput.Viewed(viewedTask.get());
        }

        // Check for a named local agent being viewed
        String viewingId = state.getViewingAgentTaskId();
        if (viewingId != null) {
            Object taskObj = state.getTasks().get(viewingId);
            if (taskObj instanceof Map<?, ?> taskMap) {
                if ("local_agent".equals(taskMap.get("type"))) {
                    return new ActiveAgentForInput.NamedAgent((Map<String, Object>) taskMap);
                }
            }
        }

        return new ActiveAgentForInput.Leader();
    }

    // =========================================================================
    // Convenience accessors (unchanged from previous version)
    // =========================================================================

    public String getSessionId() {
        // Session ID lives on the bootstrap state, not on AppStateStore;
        // return a placeholder or wire to the real session service.
        return "session";
    }

    public boolean isInteractive() {
        return !appState.isVerbose(); // approximate; wire to real flag if needed
    }
}
