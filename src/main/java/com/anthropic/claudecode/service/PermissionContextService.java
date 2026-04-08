package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.PermissionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * Factory and context for tool permission decisions.
 *
 * Provides {@link PermissionContext} — an immutable value object that holds
 * all the state and helper methods needed throughout a single permission
 * request lifecycle.
 *
 * Also provides {@link ResolveOnce} — an atomic check-and-mark guard used
 * by interactive/swarm handlers to prevent multiple concurrent resolutions.
 *
 * Translated from src/hooks/toolPermission/PermissionContext.ts
 */
@Slf4j
@Service
public class PermissionContextService {


    // =========================================================================
    // ResolveOnce  (createResolveOnce in PermissionContext.ts)
    // =========================================================================

    /**
     * Atomic check-and-mark guard for resolving a CompletableFuture exactly once.
     *
     * Translated from createResolveOnce() in PermissionContext.ts
     */
    public static class ResolveOnce<T> {
        private volatile boolean claimed = false;
        private volatile boolean delivered = false;
        private final CompletableFuture<T> future;

        public ResolveOnce(CompletableFuture<T> future) {
            this.future = future;
        }

        /**
         * Resolve the future if not yet delivered.
         * Translated from ResolveOnce.resolve() in PermissionContext.ts
         */
        public synchronized void resolve(T value) {
            if (delivered) return;
            delivered = true;
            claimed = true;
            future.complete(value);
        }

        /**
         * Returns true if this instance has already been resolved.
         * Translated from ResolveOnce.isResolved() in PermissionContext.ts
         */
        public boolean isResolved() {
            return claimed;
        }

        /**
         * Atomically check-and-mark as resolved.
         * Returns true if this caller won the race, false otherwise.
         * Translated from ResolveOnce.claim() in PermissionContext.ts
         */
        public synchronized boolean claim() {
            if (claimed) return false;
            claimed = true;
            return true;
        }
    }

    // =========================================================================
    // PermissionQueueOps  (PermissionQueueOps in PermissionContext.ts)
    // =========================================================================

    /**
     * Generic interface for permission queue operations, decoupled from any UI framework.
     * Translated from PermissionQueueOps in PermissionContext.ts
     */
    public interface PermissionQueueOps {
        void push(ToolUseConfirm item);
        void remove(String toolUseId);
        void update(String toolUseId, Map<String, Object> patch);
    }

    // =========================================================================
    // Shared domain records
    // =========================================================================

    /**
     * Permission decision result returned by a handler.
     * Translated from PermissionDecision in PermissionResult.ts
     */
    public record PermissionDecision(
            String behavior,                      // "allow" | "ask" | "deny"
            Map<String, Object> updatedInput,     // may be null
            boolean userModified,
            Object decisionReason,                // PermissionDecisionReason | null
            String message,                       // for "ask"/"deny"
            List<Object> contentBlocks,           // may be null
            Object pendingClassifierCheck,        // may be null
            List<PermissionResult.PermissionUpdate> suggestions, // may be null
            String blockedPath                    // may be null
    ) {}

    /**
     * Approval source discriminants.
     * Translated from PermissionApprovalSource in PermissionContext.ts
     */
    public sealed interface PermissionApprovalSource permits
            PermissionContextService.HookApprovalSource,
            PermissionContextService.UserApprovalSource,
            PermissionContextService.ClassifierApprovalSource {}

    public record HookApprovalSource(boolean permanent) implements PermissionApprovalSource {}
    public record UserApprovalSource(boolean permanent) implements PermissionApprovalSource {}
    public record ClassifierApprovalSource() implements PermissionApprovalSource {}

    /**
     * Rejection source discriminants.
     * Translated from PermissionRejectionSource in PermissionContext.ts
     */
    public sealed interface PermissionRejectionSource permits
            PermissionContextService.HookRejectionSource,
            PermissionContextService.UserAbortSource,
            PermissionContextService.UserRejectSource {}

    public record HookRejectionSource() implements PermissionRejectionSource {}
    public record UserAbortSource() implements PermissionRejectionSource {}
    public record UserRejectSource(boolean hasFeedback) implements PermissionRejectionSource {}

    /** Pending worker request indicator (used by swarm workers). */
    public record PendingWorkerRequest(String toolName, String toolUseId, String description) {}

    /** Minimal ToolUseConfirm snapshot pushed to the confirm queue. */
    public record ToolUseConfirm(
            String toolUseId,
            String toolName,
            String messageId,
            boolean isMcp,
            String decisionReasonType,
            boolean sandboxEnabled
    ) {}

    // =========================================================================
    // PermissionContext  (createPermissionContext in PermissionContext.ts)
    // =========================================================================

    /**
     * Context object for a single permission request lifecycle.
     *
     * Holds all state and helper methods used by permission handlers.
     * Translated from the object returned by createPermissionContext() in
     * PermissionContext.ts
     */
    public interface PermissionContext {

        // --- Identity ---
        String getToolName();
        String getToolUseId();
        String getMessageId();
        Map<String, Object> getInput();
        String getPermissionMode();

        // --- Permission hooks ---
        CompletableFuture<PermissionDecision> runHooks(
                String permissionMode,
                List<PermissionResult.PermissionUpdate> suggestions,
                Map<String, Object> updatedInput);

        CompletableFuture<PermissionDecision> runHooks(
                String permissionMode,
                List<PermissionResult.PermissionUpdate> suggestions,
                Map<String, Object> updatedInput,
                Long permissionPromptStartTimeMs);

        // --- Classifier (null when BASH_CLASSIFIER feature is off) ---
        /**
         * Returns a function that awaits classifier auto-approval, or null when
         * the feature flag is disabled.
         * Translated from ctx.tryClassifier in PermissionContext.ts
         */
        BiFunction<Object, Map<String, Object>, CompletableFuture<PermissionDecision>> tryClassifier();

        // --- Decision helpers ---
        PermissionDecision buildAllow(
                Map<String, Object> updatedInput,
                Object opts);

        PermissionDecision buildDeny(String message, Object decisionReason);

        PermissionDecision cancelAndAbort(String feedback, Boolean isAbort, List<Object> contentBlocks);

        // --- Allow handlers ---
        CompletableFuture<PermissionDecision> handleUserAllow(
                Map<String, Object> updatedInput,
                List<PermissionResult.PermissionUpdate> permissionUpdates,
                String feedback,
                Long permissionPromptStartTimeMs,
                List<Object> contentBlocks,
                Object decisionReason);

        // --- Persistence ---
        CompletableFuture<Boolean> persistPermissions(
                List<PermissionResult.PermissionUpdate> updates);

        // --- Abort / signal ---
        boolean resolveIfAborted(java.util.function.Consumer<PermissionDecision> resolve);
        void onAbortSignal(Runnable handler);

        // --- Queue operations ---
        void pushToQueue(ToolUseConfirm item);
        void removeFromQueue();
        void updateQueueItem(Map<String, Object> patch);

        // --- Logging ---
        void logDecision(PermissionLoggingService.PermissionDecisionArgs args,
                         Long permissionPromptStartTimeMs);
        void logCancelled();

        // --- Recheck ---
        CompletableFuture<PermissionDecision> recheckPermission();

        // --- Swarm ---
        void setPendingWorkerRequest(PendingWorkerRequest request);
    }

    // =========================================================================
    // Factory (createPermissionContext in PermissionContext.ts)
    // =========================================================================

    /**
     * Create a PermissionContext for a single tool-use permission request.
     *
     * Translated from createPermissionContext() in PermissionContext.ts
     *
     * In the Java implementation callers must supply concrete implementations
     * of the functional dependencies (hooks runner, classifier, queue ops,
     * state updater) because there is no React state machinery.
     *
     * @param toolName             tool being checked
     * @param toolUseId            tool_use block identifier
     * @param messageId            assistant message identifier
     * @param input                raw tool input
     * @param permissionMode       current permission mode string
     * @param hooksRunner          runs PermissionRequest hooks
     * @param classifierRunner     runs bash classifier (null when feature off)
     * @param queueOps             queue push/remove/update operations (null = no-op)
     * @param permissionLogging    logging service
     * @return frozen PermissionContext
     */
    public PermissionContext createPermissionContext(
            String toolName,
            String toolUseId,
            String messageId,
            Map<String, Object> input,
            String permissionMode,
            HooksRunner hooksRunner,
            BiFunction<Object, Map<String, Object>, CompletableFuture<PermissionDecision>> classifierRunner,
            PermissionQueueOps queueOps,
            PermissionLoggingService permissionLogging,
            java.util.function.Supplier<CompletableFuture<PermissionDecision>> recheckFn,
            java.util.function.Consumer<PendingWorkerRequest> setPendingWorkerRequestFn,
            java.util.function.Consumer<List<PermissionResult.PermissionUpdate>> persistFn,
            Runnable abortFn,
            java.util.function.Consumer<Runnable> onAbortSignalFn) {

        return new PermissionContext() {

            private volatile PendingWorkerRequest pendingWorkerRequest;

            @Override public String getToolName() { return toolName; }
            @Override public String getToolUseId() { return toolUseId; }
            @Override public String getMessageId() { return messageId; }
            @Override public Map<String, Object> getInput() { return input; }
            @Override public String getPermissionMode() { return permissionMode; }

            @Override
            public CompletableFuture<PermissionDecision> runHooks(
                    String mode,
                    List<PermissionResult.PermissionUpdate> suggestions,
                    Map<String, Object> updatedInput) {
                return hooksRunner.run(toolName, toolUseId, input, mode, suggestions, updatedInput, null)
                        .thenCompose(hookResult -> {
                            if (hookResult == null) return CompletableFuture.completedFuture(null);
                            if ("allow".equals(hookResult.behavior())) {
                                Map<String, Object> finalInput = hookResult.updatedInput() != null
                                        ? hookResult.updatedInput()
                                        : updatedInput != null ? updatedInput : input;
                                return handleHookAllow(finalInput,
                                        hookResult.updatedPermissions() != null
                                                ? hookResult.updatedPermissions()
                                                : List.of(),
                                        null);
                            } else if ("deny".equals(hookResult.behavior())) {
                                logDecision(new PermissionLoggingService.PermissionDecisionArgs(
                                        "reject", new PermissionLoggingService.HookRejectionDecision()), null);
                                return CompletableFuture.completedFuture(
                                        buildDeny(hookResult.message() != null
                                                        ? hookResult.message()
                                                        : "Permission denied by hook",
                                                null));
                            }
                            return CompletableFuture.completedFuture(null);
                        });
            }

            @Override
            public CompletableFuture<PermissionDecision> runHooks(
                    String mode,
                    List<PermissionResult.PermissionUpdate> suggestions,
                    Map<String, Object> updatedInput,
                    Long permissionPromptStartTimeMs) {
                return hooksRunner.run(toolName, toolUseId, input, mode, suggestions, updatedInput,
                        permissionPromptStartTimeMs)
                        .thenCompose(hookResult -> {
                            if (hookResult == null) return CompletableFuture.completedFuture(null);
                            if ("allow".equals(hookResult.behavior())) {
                                Map<String, Object> finalInput = hookResult.updatedInput() != null
                                        ? hookResult.updatedInput()
                                        : updatedInput != null ? updatedInput : input;
                                return handleHookAllow(finalInput,
                                        hookResult.updatedPermissions() != null
                                                ? hookResult.updatedPermissions()
                                                : List.of(),
                                        permissionPromptStartTimeMs);
                            } else if ("deny".equals(hookResult.behavior())) {
                                logDecision(new PermissionLoggingService.PermissionDecisionArgs(
                                        "reject", new PermissionLoggingService.HookRejectionDecision()),
                                        permissionPromptStartTimeMs);
                                return CompletableFuture.completedFuture(
                                        buildDeny(hookResult.message() != null
                                                        ? hookResult.message()
                                                        : "Permission denied by hook",
                                                null));
                            }
                            return CompletableFuture.completedFuture(null);
                        });
            }

            @Override
            public BiFunction<Object, Map<String, Object>, CompletableFuture<PermissionDecision>>
                    tryClassifier() {
                return classifierRunner;
            }

            @Override
            public PermissionDecision buildAllow(Map<String, Object> updatedInput, Object opts) {
                return new PermissionDecision(
                        "allow", updatedInput, false, null, null, null, null, null, null);
            }

            @Override
            public PermissionDecision buildDeny(String message, Object decisionReason) {
                return new PermissionDecision(
                        "deny", null, false, decisionReason, message, null, null, null, null);
            }

            @Override
            public PermissionDecision cancelAndAbort(String feedback, Boolean isAbort,
                                                      List<Object> contentBlocks) {
                if (Boolean.TRUE.equals(isAbort) || (feedback == null && contentBlocks == null)) {
                    log.debug("[permission] Aborting: tool={} isAbort={} hasFeedback={}",
                            toolName, isAbort, feedback != null);
                    if (abortFn != null) abortFn.run();
                }
                String message = feedback != null ? "Rejected: " + feedback : "Rejected";
                return new PermissionDecision(
                        "ask", null, false, null, message, contentBlocks, null, null, null);
            }

            @Override
            public CompletableFuture<PermissionDecision> handleUserAllow(
                    Map<String, Object> updatedInput,
                    List<PermissionResult.PermissionUpdate> permissionUpdates,
                    String feedback,
                    Long permissionPromptStartTimeMs,
                    List<Object> contentBlocks,
                    Object decisionReason) {
                return persistPermissions(permissionUpdates).thenApply(acceptedPermanent -> {
                    logDecision(new PermissionLoggingService.PermissionDecisionArgs(
                            "accept", new PermissionLoggingService.UserApprovalDecision(acceptedPermanent)),
                            permissionPromptStartTimeMs);
                    String trimmedFeedback = feedback != null ? feedback.trim() : null;
                    return new PermissionDecision(
                            "allow", updatedInput, !input.equals(updatedInput),
                            decisionReason,
                            trimmedFeedback != null && !trimmedFeedback.isEmpty() ? trimmedFeedback : null,
                            contentBlocks, null, null, null);
                });
            }

            private CompletableFuture<PermissionDecision> handleHookAllow(
                    Map<String, Object> finalInput,
                    List<PermissionResult.PermissionUpdate> permissionUpdates,
                    Long permissionPromptStartTimeMs) {
                return persistPermissions(permissionUpdates).thenApply(acceptedPermanent -> {
                    logDecision(new PermissionLoggingService.PermissionDecisionArgs(
                            "accept", new PermissionLoggingService.HookApprovalDecision(acceptedPermanent)),
                            permissionPromptStartTimeMs);
                    return buildAllow(finalInput, null);
                });
            }

            @Override
            public CompletableFuture<Boolean> persistPermissions(
                    List<PermissionResult.PermissionUpdate> updates) {
                if (updates == null || updates.isEmpty()) {
                    return CompletableFuture.completedFuture(false);
                }
                if (persistFn != null) persistFn.accept(updates);
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public boolean resolveIfAborted(
                    java.util.function.Consumer<PermissionDecision> resolve) {
                // Caller checks abort signal; if aborted, deliver cancel decision
                return false;
            }

            @Override
            public void onAbortSignal(Runnable handler) {
                if (onAbortSignalFn != null) onAbortSignalFn.accept(handler);
            }

            @Override
            public void pushToQueue(ToolUseConfirm item) {
                if (queueOps != null) queueOps.push(item);
            }

            @Override
            public void removeFromQueue() {
                if (queueOps != null) queueOps.remove(toolUseId);
            }

            @Override
            public void updateQueueItem(Map<String, Object> patch) {
                if (queueOps != null) queueOps.update(toolUseId, patch);
            }

            @Override
            public void logDecision(PermissionLoggingService.PermissionDecisionArgs args,
                                    Long permissionPromptStartTimeMs) {
                if (permissionLogging != null) {
                    permissionLogging.logPermissionDecision(
                            new PermissionLoggingService.PermissionLogContext(
                                    toolName, input, messageId, toolUseId),
                            args,
                            permissionPromptStartTimeMs);
                }
            }

            @Override
            public void logCancelled() {
                log.debug("[permission] Cancelled: tool={} toolUseId={}", toolName, toolUseId);
            }

            @Override
            public CompletableFuture<PermissionDecision> recheckPermission() {
                if (recheckFn != null) return recheckFn.get();
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void setPendingWorkerRequest(PendingWorkerRequest request) {
                this.pendingWorkerRequest = request;
                if (setPendingWorkerRequestFn != null) setPendingWorkerRequestFn.accept(request);
            }
        };
    }

    // =========================================================================
    // Supporting interfaces
    // =========================================================================

    /**
     * Functional interface for running PermissionRequest hooks.
     * Translated from executePermissionRequestHooks() in hooks.ts
     */
    @FunctionalInterface
    public interface HooksRunner {
        /**
         * Execute hooks for the given tool and return the first decisive result,
         * or null if all hooks abstained.
         */
        CompletableFuture<HookDecisionResult> run(
                String toolName,
                String toolUseId,
                Map<String, Object> input,
                String permissionMode,
                List<PermissionResult.PermissionUpdate> suggestions,
                Map<String, Object> updatedInput,
                Long permissionPromptStartTimeMs);
    }

    /**
     * Partial hook decision (allow/deny with optional overrides).
     * Translated from the permissionRequestResult shape in hooks.ts
     */
    public record HookDecisionResult(
            String behavior,                           // "allow" | "deny"
            String message,
            Map<String, Object> updatedInput,          // may be null
            List<PermissionResult.PermissionUpdate> updatedPermissions, // may be null
            boolean interrupt
    ) {}

    /** Returns current MCP tools for doctor/context-warning diagnostics. */
    public java.util.List<McpService.McpToolInfo> getMcpTools() {
        return java.util.List.of();
    }

    /** Detect permission rules that can never be reached (shadowed by broader rules). */
    public java.util.List<UnreachableRule> detectUnreachableRules() {
        return java.util.List.of();
    }

    /** Describes a permission rule that is unreachable due to a broader rule above it. */
    public record UnreachableRule(String ruleValue, String reason, String fix) {}
}
