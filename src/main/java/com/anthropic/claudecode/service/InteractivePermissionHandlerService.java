package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.anthropic.claudecode.model.PermissionResult;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Handles the interactive (main-agent) permission flow.
 *
 * Pushes a ToolUseConfirm entry to the confirm queue with callbacks:
 * onAbort, onAllow, onReject, recheckPermission, onUserInteraction.
 *
 * Runs permission hooks and bash classifier checks asynchronously in the
 * background, racing them against user interaction. Uses a resolve-once
 * guard and {@code userInteracted} flag to prevent multiple resolutions.
 *
 * This service does NOT block the calling thread — it sets up callbacks
 * that eventually call {@code resolve} to complete the outer
 * CompletableFuture owned by the caller.
 *
 * Translated from src/hooks/toolPermission/handlers/interactiveHandler.ts
 */
@Slf4j
@Service
public class InteractivePermissionHandlerService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InteractivePermissionHandlerService.class);


    // =========================================================================
    // Domain types
    // =========================================================================

    /**
     * Parameters for interactive permission handling.
     * Translated from InteractivePermissionParams in interactiveHandler.ts
     */
    public record InteractivePermissionParams(
            PermissionContextService.PermissionContext ctx,
            String description,
            /** Must be a PermissionDecision whose behavior is "ask". */
            PermissionContextService.PermissionDecision result,
            /** When true, hooks were already awaited in the coordinator branch. */
            boolean awaitAutomatedChecksBeforeDialog,
            BridgePermissionCallbacks bridgeCallbacks,   // may be null
            ChannelPermissionCallbacks channelCallbacks  // may be null
    ) {}

    /**
     * Minimal bridge-callback interface used by the handler.
     * Translated from BridgePermissionCallbacks in bridgePermissionCallbacks.ts
     */
    public interface BridgePermissionCallbacks {
        void sendRequest(String requestId, String toolName,
                         Map<String, Object> displayInput, String toolUseId,
                         String description,
                         List<PermissionResult.PermissionUpdate> suggestions,
                         String blockedPath);
        /** Returns an unsubscribe runnable. */
        Runnable onResponse(String requestId,
                            Consumer<BridgePermissionResponse> handler);
        void cancelRequest(String requestId);
        void sendResponse(String requestId, BridgePermissionResponse response);
    }

    /**
     * Minimal channel-callback interface.
     * Translated from ChannelPermissionCallbacks in channelPermissions.ts
     */
    public interface ChannelPermissionCallbacks {
        /** Returns an unsubscribe runnable. */
        Runnable onResponse(String channelRequestId,
                            Consumer<ChannelPermissionResponse> handler);
    }

    /** Response from the bridge (web app). */
    public record BridgePermissionResponse(
            String behavior,                          // "allow" | "deny"
            Map<String, Object> updatedInput,         // may be null
            List<PermissionResult.PermissionUpdate> updatedPermissions, // may be null
            String message                            // may be null
    ) {}

    /** Response relayed from a channel (Telegram, iMessage, etc.). */
    public record ChannelPermissionResponse(
            String behavior,   // "allow" | "deny"
            String fromServer  // channel server name
    ) {}

    // =========================================================================
    // Resolve-once guard (mirrors createResolveOnce in PermissionContext.ts)
    // =========================================================================

    private static class ResolveOnce<T> {
        private final AtomicBoolean claimed = new AtomicBoolean(false);
        private final CompletableFuture<T> future;

        ResolveOnce(CompletableFuture<T> future) {
            this.future = future;
        }

        /** Atomically claim the right to resolve. Returns true if this caller wins. */
        boolean claim() {
            return claimed.compareAndSet(false, true);
        }

        boolean isResolved() {
            return claimed.get();
        }

        void resolve(T value) {
            if (claimed.compareAndSet(false, true)) {
                future.complete(value);
            }
        }
    }

    // =========================================================================
    // Queue item — mirrors ToolUseConfirm shape used in the TypeScript
    // =========================================================================

    @Data
    public static class QueueItem {
        private final String toolUseId;
        private final String toolName;
        private final String description;
        private final Map<String, Object> input;
        private final PermissionContextService.PermissionDecision permissionResult;
        private final long permissionPromptStartTimeMs;
        private volatile boolean classifierCheckInProgress;
        private volatile boolean classifierAutoApproved;
        private volatile String classifierMatchedRule;

        // Callbacks
        private Runnable onAbort;
        private BiConsumer<Map<String, Object>, List<PermissionResult.PermissionUpdate>> onAllow;
        private Consumer<String> onReject;
        private Runnable onUserInteraction;
        private Runnable recheckPermission;
        private Runnable onDismissCheckmark;

        public QueueItem() {
            this.toolUseId = null; this.toolName = null; this.description = null;
            this.input = null; this.permissionResult = null; this.permissionPromptStartTimeMs = 0;
        }
        public QueueItem(String toolUseId, String toolName, String description,
                         Map<String, Object> input,
                         PermissionContextService.PermissionDecision permissionResult,
                         long permissionPromptStartTimeMs) {
            this.toolUseId = toolUseId; this.toolName = toolName; this.description = description;
            this.input = input; this.permissionResult = permissionResult;
            this.permissionPromptStartTimeMs = permissionPromptStartTimeMs;
        }

        public String getToolUseId() { return toolUseId; }
        public String getToolName() { return toolName; }
        public String getDescription() { return description; }
        public Map<String, Object> getInput() { return input; }
        public long getPermissionPromptStartTimeMs() { return permissionPromptStartTimeMs; }
        public boolean getClassifierCheckInProgress() { return classifierCheckInProgress; }
        public void setClassifierCheckInProgress(boolean v) { classifierCheckInProgress = v; }
        public boolean getClassifierAutoApproved() { return classifierAutoApproved; }
        public void setClassifierAutoApproved(boolean v) { classifierAutoApproved = v; }
        public String getClassifierMatchedRule() { return classifierMatchedRule; }
        public void setClassifierMatchedRule(String v) { classifierMatchedRule = v; }
        public BiConsumer<Map<String, Object>, List<PermissionResult.PermissionUpdate>> getOnAllow() { return onAllow; }
        public void setOnAllow(BiConsumer<Map<String, Object>, List<PermissionResult.PermissionUpdate>> v) { onAllow = v; }
        public Runnable getOnAbort() { return onAbort; }
        public void setOnAbort(Runnable v) { onAbort = v; }
        public Consumer<String> getOnReject() { return onReject; }
        public void setOnReject(Consumer<String> v) { onReject = v; }
        public Runnable getOnUserInteraction() { return onUserInteraction; }
        public void setOnUserInteraction(Runnable v) { onUserInteraction = v; }
        public Runnable getRecheckPermission() { return recheckPermission; }
        public void setRecheckPermission(Runnable v) { recheckPermission = v; }
        public Runnable getOnDismissCheckmark() { return onDismissCheckmark; }
        public void setOnDismissCheckmark(Runnable v) { onDismissCheckmark = v; }
    }

    // =========================================================================
    // Queue
    // =========================================================================

    private final List<QueueItem> queue = new CopyOnWriteArrayList<>();
    private final List<Consumer<QueueItem>> queueListeners = new CopyOnWriteArrayList<>();

    /** Subscribe to queue additions. Returns an unsubscribe runnable. */
    public Runnable subscribeToQueue(Consumer<QueueItem> listener) {
        queueListeners.add(listener);
        return () -> queueListeners.remove(listener);
    }

    /** Returns an unmodifiable view of the current queue. */
    public List<QueueItem> getQueue() {
        return Collections.unmodifiableList(queue);
    }

    // =========================================================================
    // Main handler
    // =========================================================================

    /**
     * Set up the interactive permission dialog for one tool-use request.
     *
     * Returns a CompletableFuture that resolves when the user (or an
     * automated racer) makes a decision.
     *
     * Translated from handleInteractivePermission() in interactiveHandler.ts
     *
     * @param params handler parameters
     * @return future that resolves to the final PermissionDecision
     */
    public CompletableFuture<PermissionContextService.PermissionDecision> handleInteractivePermission(
            InteractivePermissionParams params) {

        CompletableFuture<PermissionContextService.PermissionDecision> outer = new CompletableFuture<>();
        ResolveOnce<PermissionContextService.PermissionDecision> resolveOnce = new ResolveOnce<>(outer);

        final PermissionContextService.PermissionContext ctx = params.ctx();
        final PermissionContextService.PermissionDecision result = params.result();
        final String description = params.description();
        final BridgePermissionCallbacks bridgeCallbacks = params.bridgeCallbacks();
        final ChannelPermissionCallbacks channelCallbacks = params.channelCallbacks();

        final String bridgeRequestId = bridgeCallbacks != null
                ? UUID.randomUUID().toString() : null;
        final long permissionPromptStartTimeMs = Instant.now().toEpochMilli();
        final Map<String, Object> displayInput =
                (result.updatedInput() != null) ? result.updatedInput() : ctx.getInput();

        final AtomicBoolean userInteracted = new AtomicBoolean(false);
        final long[] lastCheckmarkTimerRef = {0};
        final Runnable[] channelUnsubscribeRef = {null};

        // --- Build and push the queue item ---
        QueueItem item = new QueueItem(
                ctx.getToolUseId(),
                ctx.getToolName(),
                description,
                displayInput,
                result,
                permissionPromptStartTimeMs
        );
        item.setClassifierCheckInProgress(
                result.pendingClassifierCheck() != null && !params.awaitAutomatedChecksBeforeDialog()
        );

        // onUserInteraction: hide classifier indicator when user starts interacting
        final long GRACE_PERIOD_MS = 200;
        item.setOnUserInteraction(() -> {
            if (Instant.now().toEpochMilli() - permissionPromptStartTimeMs < GRACE_PERIOD_MS) return;
            userInteracted.set(true);
            item.setClassifierCheckInProgress(false);
        });

        // onAbort
        item.setOnAbort(() -> {
            if (!resolveOnce.claim()) return;
            if (bridgeCallbacks != null && bridgeRequestId != null) {
                bridgeCallbacks.sendResponse(bridgeRequestId,
                        new BridgePermissionResponse("deny", null, null, "User aborted"));
                bridgeCallbacks.cancelRequest(bridgeRequestId);
            }
            if (channelUnsubscribeRef[0] != null) channelUnsubscribeRef[0].run();
            ctx.logCancelled();
            resolveOnce.resolve(ctx.cancelAndAbort(null, true, null));
        });

        // onAllow
        item.setOnAllow((updatedInput, permissionUpdates) -> {
            if (!resolveOnce.claim()) return;
            if (bridgeCallbacks != null && bridgeRequestId != null) {
                bridgeCallbacks.sendResponse(bridgeRequestId,
                        new BridgePermissionResponse("allow", updatedInput, permissionUpdates, null));
                bridgeCallbacks.cancelRequest(bridgeRequestId);
            }
            if (channelUnsubscribeRef[0] != null) channelUnsubscribeRef[0].run();
            ctx.handleUserAllow(updatedInput, permissionUpdates, null,
                    permissionPromptStartTimeMs, null, null)
                .thenAccept(resolveOnce::resolve);
        });

        // onReject
        item.setOnReject(feedback -> {
            if (!resolveOnce.claim()) return;
            if (bridgeCallbacks != null && bridgeRequestId != null) {
                String msg = feedback != null ? feedback : "User denied permission";
                bridgeCallbacks.sendResponse(bridgeRequestId,
                        new BridgePermissionResponse("deny", null, null, msg));
                bridgeCallbacks.cancelRequest(bridgeRequestId);
            }
            if (channelUnsubscribeRef[0] != null) channelUnsubscribeRef[0].run();
            ctx.logDecision(new PermissionLoggingService.PermissionDecisionArgs(
                    "reject",
                    new PermissionLoggingService.UserRejectSource(feedback != null && !feedback.isBlank())),
                    permissionPromptStartTimeMs);
            resolveOnce.resolve(ctx.cancelAndAbort(feedback, null, null));
        });

        // recheckPermission
        item.setRecheckPermission(() -> {
            if (resolveOnce.isResolved()) return;
            ctx.recheckPermission().thenAccept(freshResult -> {
                if (freshResult != null && "allow".equals(freshResult.behavior())) {
                    if (!resolveOnce.claim()) return;
                    if (bridgeCallbacks != null && bridgeRequestId != null) {
                        bridgeCallbacks.cancelRequest(bridgeRequestId);
                    }
                    if (channelUnsubscribeRef[0] != null) channelUnsubscribeRef[0].run();
                    queue.remove(item);
                    ctx.logDecision(new PermissionLoggingService.PermissionDecisionArgs(
                            "accept", new PermissionLoggingService.ConfigSource()), null);
                    resolveOnce.resolve(ctx.buildAllow(
                            freshResult.updatedInput() != null ? freshResult.updatedInput() : ctx.getInput(),
                            null));
                }
            });
        });

        queue.add(item);
        queueListeners.forEach(l -> {
            try { l.accept(item); } catch (Exception e) {
                log.warn("Queue listener threw: {}", e.getMessage(), e);
            }
        });

        // --- Race: Bridge (claude.ai web app) ---
        if (bridgeCallbacks != null && bridgeRequestId != null) {
            bridgeCallbacks.sendRequest(
                    bridgeRequestId, ctx.getToolName(), displayInput, ctx.getToolUseId(),
                    description, result.suggestions(), result.blockedPath());

            Runnable[] bridgeUnsub = {null};
            bridgeUnsub[0] = bridgeCallbacks.onResponse(bridgeRequestId, response -> {
                if (!resolveOnce.claim()) return;
                item.setClassifierCheckInProgress(false);
                queue.remove(item);
                if (channelUnsubscribeRef[0] != null) channelUnsubscribeRef[0].run();

                if ("allow".equals(response.behavior())) {
                    if (response.updatedPermissions() != null && !response.updatedPermissions().isEmpty()) {
                        ctx.persistPermissions(response.updatedPermissions());
                    }
                    ctx.logDecision(new PermissionLoggingService.PermissionDecisionArgs(
                            "accept", new PermissionLoggingService.UserSource(
                                    response.updatedPermissions() != null
                                            && !response.updatedPermissions().isEmpty())),
                            permissionPromptStartTimeMs);
                    resolveOnce.resolve(ctx.buildAllow(
                            response.updatedInput() != null ? response.updatedInput() : displayInput,
                            null));
                } else {
                    ctx.logDecision(new PermissionLoggingService.PermissionDecisionArgs(
                            "reject", new PermissionLoggingService.UserRejectSource(
                                    response.message() != null && !response.message().isBlank())),
                            permissionPromptStartTimeMs);
                    resolveOnce.resolve(ctx.cancelAndAbort(response.message(), null, null));
                }
            });
        }

        // --- Race: Background hooks (unless already awaited in coordinator branch) ---
        if (!params.awaitAutomatedChecksBeforeDialog()) {
            CompletableFuture.runAsync(() -> {
                if (resolveOnce.isResolved()) return;
                ctx.runHooks(ctx.getPermissionMode(), result.suggestions(), result.updatedInput(),
                        permissionPromptStartTimeMs)
                    .thenAccept(hookDecision -> {
                        if (hookDecision == null || !resolveOnce.claim()) return;
                        if (bridgeCallbacks != null && bridgeRequestId != null) {
                            bridgeCallbacks.cancelRequest(bridgeRequestId);
                        }
                        if (channelUnsubscribeRef[0] != null) channelUnsubscribeRef[0].run();
                        queue.remove(item);
                        resolveOnce.resolve(hookDecision);
                    });
            });
        }

        // Clean up on abort signal
        ctx.onAbortSignal(() -> {
            if (!resolveOnce.claim()) return;
            if (channelUnsubscribeRef[0] != null) channelUnsubscribeRef[0].run();
            queue.remove(item);
            ctx.logCancelled();
            resolveOnce.resolve(ctx.cancelAndAbort(null, true, null));
        });

        return outer;
    }
}
