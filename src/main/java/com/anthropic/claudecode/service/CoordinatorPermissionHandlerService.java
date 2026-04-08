package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.anthropic.claudecode.model.PermissionResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Handles the coordinator worker permission flow.
 *
 * For coordinator workers, automated checks (hooks and classifier) are
 * awaited sequentially before falling through to the interactive dialog.
 *
 * Returns a PermissionDecision if the automated checks resolved the
 * permission, or null if the caller should fall through to the
 * interactive dialog.
 *
 * Translated from src/hooks/toolPermission/handlers/coordinatorHandler.ts
 */
@Slf4j
@Service
public class CoordinatorPermissionHandlerService {



    /**
     * Parameters for a coordinator permission check.
     * Translated from CoordinatorPermissionParams in coordinatorHandler.ts
     */
    public record CoordinatorPermissionParams(
            PermissionContextService.PermissionContext ctx,
            Object pendingClassifierCheck,           // PendingClassifierCheck | undefined
            Map<String, Object> updatedInput,         // may be null
            List<PermissionResult.PermissionUpdate> suggestions, // may be null
            String permissionMode                     // may be null
    ) {}

    /**
     * Handles the coordinator worker permission flow.
     *
     * 1. Tries permission hooks first (fast, local).
     * 2. Tries classifier (slow, inference — bash only).
     * If both return null or throw, returns null so the caller falls
     * through to the interactive dialog.
     *
     * Translated from handleCoordinatorPermission() in coordinatorHandler.ts
     *
     * @param params coordinator permission parameters
     * @return a future resolving to a PermissionDecision if an automated
     *         check resolved it, or null if the caller should show the dialog
     */
    public CompletableFuture<PermissionContextService.PermissionDecision> handleCoordinatorPermission(
            CoordinatorPermissionParams params) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1. Try permission hooks first (fast, local)
                PermissionContextService.PermissionDecision hookResult =
                        params.ctx().runHooks(
                                params.permissionMode(),
                                params.suggestions(),
                                params.updatedInput())
                        .join();
                if (hookResult != null) {
                    return hookResult;
                }

                // 2. Try classifier (slow, inference — bash only)
                if (params.ctx().tryClassifier() != null) {
                    PermissionContextService.PermissionDecision classifierResult =
                            params.ctx().tryClassifier()
                                    .apply(params.pendingClassifierCheck(), params.updatedInput())
                                    .join();
                    if (classifierResult != null) {
                        return classifierResult;
                    }
                }
            } catch (Exception error) {
                // If automated checks fail unexpectedly, fall through to show
                // the dialog so the user can decide manually.
                log.error("Automated permission check failed: {}", error.getMessage(), error);
            }

            // 3. Neither resolved (or checks failed) -- fall through to dialog.
            return null;
        });
    }
}
