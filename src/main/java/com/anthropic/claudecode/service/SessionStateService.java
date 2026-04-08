package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Manages the lifecycle state of a Claude Code session and notifies registered
 * listeners when state or metadata changes.
 *
 * Three orthogonal event channels:
 *   1. Session state   (idle / running / requires_action)
 *   2. Session metadata (permission_mode, model, pending_action, etc.)
 *   3. Permission-mode changes
 *
 * Translated from src/utils/sessionState.ts
 */
@Slf4j
@Service
public class SessionStateService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SessionStateService.class);


    // -----------------------------------------------------------------------
    // Domain types
    // -----------------------------------------------------------------------

    /**
     * The three high-level states a session can be in.
     * Translated from the TypeScript union type {@code SessionState}.
     */
    public enum SessionState {
        IDLE,
        RUNNING,
        REQUIRES_ACTION
    }

    /**
     * Contextual details attached to a {@link SessionState#REQUIRES_ACTION}
     * transition so downstream surfaces (sidebar, push notifications) can show
     * what the session is blocked on.
     * Translated from the TypeScript type {@code RequiresActionDetails}.
     */
    public record RequiresActionDetails(
            String toolName,
            /** Human-readable summary, e.g. "Editing src/foo.ts" */
            String actionDescription,
            String toolUseId,
            String requestId,
            /** Raw tool input — the frontend reads this from external_metadata.pending_action.input */
            Map<String, Object> input
    ) {}

    /**
     * Key-value bag that is merged into the session's external_metadata on every
     * state or metadata change.
     * Translated from the TypeScript type {@code SessionExternalMetadata}.
     */
    public record SessionExternalMetadata(
            String permissionMode,
            Boolean isUltraplanMode,
            String model,
            RequiresActionDetails pendingAction,
            Object postTurnSummary,
            String taskSummary
    ) {
        /** Convenience factory: just the pending-action field. */
        public static SessionExternalMetadata pendingAction(RequiresActionDetails action) {
            return new SessionExternalMetadata(null, null, null, action, null, null);
        }

        /** Convenience factory: clear the pending-action field. */
        public static SessionExternalMetadata clearPendingAction() {
            return new SessionExternalMetadata(null, null, null, null, null, null);
        }

        /** Convenience factory: clear the task-summary field. */
        public static SessionExternalMetadata clearTaskSummary() {
            return new SessionExternalMetadata(null, null, null, null, null, null);
        }
    }

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private final AtomicReference<SessionState> currentState =
            new AtomicReference<>(SessionState.IDLE);
    private final AtomicBoolean hasPendingAction = new AtomicBoolean(false);

    private volatile Consumer<SessionStateChanged> stateListener = null;
    private volatile Consumer<SessionExternalMetadata> metadataListener = null;
    private volatile Consumer<String> permissionModeListener = null;

    // -----------------------------------------------------------------------
    // Listener registration
    // -----------------------------------------------------------------------

    /**
     * Register a callback for session-state transitions.
     * Pass {@code null} to remove the listener.
     * Translated from {@code setSessionStateChangedListener()} in sessionState.ts
     */
    public void setSessionStateChangedListener(Consumer<SessionStateChanged> cb) {
        this.stateListener = cb;
    }

    /**
     * Register a callback for external-metadata changes.
     * Pass {@code null} to remove the listener.
     * Translated from {@code setSessionMetadataChangedListener()} in sessionState.ts
     */
    public void setSessionMetadataChangedListener(Consumer<SessionExternalMetadata> cb) {
        this.metadataListener = cb;
    }

    /**
     * Register a callback for permission-mode changes.
     * Pass {@code null} to remove the listener.
     * Translated from {@code setPermissionModeChangedListener()} in sessionState.ts
     */
    public void setPermissionModeChangedListener(Consumer<String> cb) {
        this.permissionModeListener = cb;
    }

    // -----------------------------------------------------------------------
    // State accessors and mutators
    // -----------------------------------------------------------------------

    /**
     * Returns the current session state.
     * Translated from {@code getSessionState()} in sessionState.ts
     */
    public SessionState getSessionState() {
        return currentState.get();
    }

    /**
     * Transition to {@code state}, notify the state listener, and mirror
     * {@link RequiresActionDetails} (or its absence) into external_metadata.
     *
     * <p>When transitioning to {@link SessionState#REQUIRES_ACTION} the details
     * are pushed to metadata via {@code pending_action}.  Any other transition
     * clears the pending action.  {@link SessionState#IDLE} additionally clears
     * {@code task_summary} so the previous turn's progress line is not shown
     * at the start of the next turn.
     *
     * Translated from {@code notifySessionStateChanged()} in sessionState.ts
     *
     * @param state   the new session state
     * @param details optional details (only meaningful for REQUIRES_ACTION)
     */
    public void notifySessionStateChanged(SessionState state, RequiresActionDetails details) {
        currentState.set(state);

        Consumer<SessionStateChanged> sl = stateListener;
        if (sl != null) {
            sl.accept(new SessionStateChanged(state, details));
        }

        // Mirror pending_action into external_metadata
        if (state == SessionState.REQUIRES_ACTION && details != null) {
            hasPendingAction.set(true);
            fireMetadata(SessionExternalMetadata.pendingAction(details));
        } else if (hasPendingAction.getAndSet(false)) {
            fireMetadata(SessionExternalMetadata.clearPendingAction());
        }

        // Clear task_summary when going idle
        if (state == SessionState.IDLE) {
            fireMetadata(SessionExternalMetadata.clearTaskSummary());
        }

        log.debug("Session state changed to {}", state);
    }

    /**
     * Convenience overload without action details.
     */
    public void notifySessionStateChanged(SessionState state) {
        notifySessionStateChanged(state, null);
    }

    /**
     * Push an arbitrary metadata update without changing the session state.
     * Translated from {@code notifySessionMetadataChanged()} in sessionState.ts
     */
    public void notifySessionMetadataChanged(SessionExternalMetadata metadata) {
        fireMetadata(metadata);
    }

    /**
     * Notify listeners that the active permission mode has changed.
     * Translated from {@code notifyPermissionModeChanged()} in sessionState.ts
     *
     * @param mode the new permission mode identifier (e.g. "default", "auto", "plan")
     */
    public void notifyPermissionModeChanged(String mode) {
        Consumer<String> pml = permissionModeListener;
        if (pml != null) {
            pml.accept(mode);
        }
    }

    // -----------------------------------------------------------------------
    // Session identity helpers (used by SdkEventQueueService)
    // -----------------------------------------------------------------------

    private String sessionId;
    private boolean nonInteractiveSession;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public boolean isNonInteractiveSession() { return nonInteractiveSession; }
    public void setNonInteractiveSession(boolean nonInteractiveSession) {
        this.nonInteractiveSession = nonInteractiveSession;
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private void fireMetadata(SessionExternalMetadata metadata) {
        Consumer<SessionExternalMetadata> ml = metadataListener;
        if (ml != null) {
            ml.accept(metadata);
        }
    }

    // -----------------------------------------------------------------------
    // Event payload record
    // -----------------------------------------------------------------------

    /**
     * Payload delivered to the session-state listener.
     */
    public record SessionStateChanged(SessionState state, RequiresActionDetails details) {}
}
