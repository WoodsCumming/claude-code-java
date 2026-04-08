package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.anthropic.claudecode.model.PermissionResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Handles the swarm worker permission flow.
 *
 * When running as a swarm worker:
 * 1. Tries classifier auto-approval for bash commands.
 * 2. Forwards the permission request to the leader via mailbox.
 * 3. Registers callbacks for when the leader responds.
 * 4. Sets the pending indicator while waiting.
 *
 * Returns a PermissionDecision if the classifier auto-approves,
 * or a CompletableFuture that resolves when the leader responds.
 * Returns null if swarms are not enabled or this is not a swarm worker,
 * so the caller can fall through to interactive handling.
 *
 * Translated from src/hooks/toolPermission/handlers/swarmWorkerHandler.ts
 */
@Slf4j
@Service
public class SwarmWorkerPermissionHandlerService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SwarmWorkerPermissionHandlerService.class);


    private final SwarmPermissionSyncService swarmPermissionSyncService;
    private final SwarmBackendDetectionService swarmBackendDetectionService;

    @Autowired
    public SwarmWorkerPermissionHandlerService(
            SwarmPermissionSyncService swarmPermissionSyncService,
            SwarmBackendDetectionService swarmBackendDetectionService) {
        this.swarmPermissionSyncService = swarmPermissionSyncService;
        this.swarmBackendDetectionService = swarmBackendDetectionService;
    }

    /**
     * Parameters for a swarm worker permission check.
     * Translated from SwarmWorkerPermissionParams in swarmWorkerHandler.ts
     */
    public record SwarmWorkerPermissionParams(
            PermissionContextService.PermissionContext ctx,
            String description,
            Object pendingClassifierCheck,            // PendingClassifierCheck | null
            Map<String, Object> updatedInput,          // may be null
            List<PermissionResult.PermissionUpdate> suggestions  // may be null
    ) {}

    /**
     * Handles the swarm worker permission flow.
     *
     * Translated from handleSwarmWorkerPermission() in swarmWorkerHandler.ts
     *
     * @param params swarm worker permission parameters
     * @return a future resolving to a PermissionDecision, or null if swarms
     *         are not enabled / this is not a swarm worker
     */
    public CompletableFuture<PermissionContextService.PermissionDecision> handleSwarmWorkerPermission(
            SwarmWorkerPermissionParams params) {

        if (!swarmBackendDetectionService.isAgentSwarmsEnabled()
                || !swarmPermissionSyncService.isSwarmWorker()) {
            return CompletableFuture.completedFuture(null);
        }

        final PermissionContextService.PermissionContext ctx = params.ctx();
        final String description = params.description();
        final Map<String, Object> updatedInput = params.updatedInput();
        final List<PermissionResult.PermissionUpdate> suggestions = params.suggestions();

        // For bash commands, try classifier auto-approval before forwarding to
        // the leader. Agents await the classifier result (rather than racing it
        // against user interaction like the main agent).
        CompletableFuture<PermissionContextService.PermissionDecision> classifierFuture;
        if (ctx.tryClassifier() != null) {
            classifierFuture = ctx.tryClassifier()
                    .apply(params.pendingClassifierCheck(), updatedInput);
        } else {
            classifierFuture = CompletableFuture.completedFuture(null);
        }

        return classifierFuture.thenCompose(classifierResult -> {
            if (classifierResult != null) {
                return CompletableFuture.completedFuture(classifierResult);
            }

            // Forward permission request to the leader via mailbox
            return forwardToLeader(ctx, description, updatedInput, suggestions)
                    .exceptionally(error -> {
                        // If swarm permission submission fails, fall back to local handling
                        log.error("Swarm permission forwarding failed: {}", error.getMessage(), error);
                        return null;
                    });
        });
    }

    /**
     * Forwards the permission request to the swarm leader and awaits a response.
     * Translated from the Promise block in handleSwarmWorkerPermission() in swarmWorkerHandler.ts
     */
    private CompletableFuture<PermissionContextService.PermissionDecision> forwardToLeader(
            PermissionContextService.PermissionContext ctx,
            String description,
            Map<String, Object> updatedInput,
            List<PermissionResult.PermissionUpdate> suggestions) {

        CompletableFuture<PermissionContextService.PermissionDecision> outer =
                new CompletableFuture<>();
        AtomicBoolean claimed = new AtomicBoolean(false);

        // Create the permission request
        SwarmPermissionSyncService.PermissionRequest request =
                swarmPermissionSyncService.createPermissionRequest(
                        ctx.getToolName(),
                        ctx.getToolUseId(),
                        ctx.getInput(),
                        description,
                        suggestions);

        // Register callback BEFORE sending the request to avoid a race condition
        // where the leader responds before the callback is registered.
        swarmPermissionSyncService.registerPermissionCallback(
                request.id(),
                ctx.getToolUseId(),
                new SwarmPermissionSyncService.PermissionCallback() {
                    @Override
                    public void onAllow(Map<String, Object> allowedInput,
                                        List<PermissionResult.PermissionUpdate> permissionUpdates,
                                        String feedback) {
                        if (!claimed.compareAndSet(false, true)) return;
                        clearPendingRequest(ctx);

                        // Merge the updated input with the original input
                        Map<String, Object> finalInput =
                                (allowedInput != null && !allowedInput.isEmpty())
                                        ? allowedInput
                                        : ctx.getInput();

                        ctx.handleUserAllow(finalInput, permissionUpdates, feedback,
                                null, null, null)
                           .thenAccept(outer::complete);
                    }

                    @Override
                    public void onReject(String feedback) {
                        if (!claimed.compareAndSet(false, true)) return;
                        clearPendingRequest(ctx);

                        ctx.logDecision(
                                new PermissionLoggingService.PermissionDecisionArgs(
                                        "reject",
                                        new PermissionLoggingService.UserRejectSource(
                                                feedback != null && !feedback.isBlank())),
                                null);
                        outer.complete(ctx.cancelAndAbort(feedback, null, null));
                    }
                });

        // Now that the callback is registered, send the request to the leader
        swarmPermissionSyncService.sendPermissionRequestViaMailbox(request);

        // Show visual indicator that we're waiting for leader approval
        ctx.setPendingWorkerRequest(
                new PermissionContextService.PendingWorkerRequest(
                        ctx.getToolName(), ctx.getToolUseId(), description));

        // If the abort signal fires while waiting, resolve with cancel so it
        // does not hang.
        ctx.onAbortSignal(() -> {
            if (!claimed.compareAndSet(false, true)) return;
            clearPendingRequest(ctx);
            ctx.logCancelled();
            outer.complete(ctx.cancelAndAbort(null, true, null));
        });

        return outer;
    }

    private void clearPendingRequest(PermissionContextService.PermissionContext ctx) {
        ctx.setPendingWorkerRequest(null);
    }
}
