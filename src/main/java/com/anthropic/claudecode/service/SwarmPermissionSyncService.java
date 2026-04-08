package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.PermissionResult;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;

/**
 * Swarm permission sync service.
 * Translated from src/utils/swarm/permissionSync.ts
 *
 * Coordinates permission prompts across multiple agents in a swarm.
 */
@Slf4j
@Service
public class SwarmPermissionSyncService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SwarmPermissionSyncService.class);


    private final TeammateMailboxService teammateMailboxService;

    // Pending permission responses
    private final Map<String, CompletableFuture<PermissionResult>> pendingResponses
        = new ConcurrentHashMap<>();

    @Autowired
    public SwarmPermissionSyncService(TeammateMailboxService teammateMailboxService) {
        this.teammateMailboxService = teammateMailboxService;
    }

    /**
     * Create a permission request for the team leader.
     * Translated from createPermissionRequest() in permissionSync.ts
     */
    public PermissionRequest createPermissionRequest(
            String toolName,
            Map<String, Object> toolInput,
            String toolUseId,
            String description) {

        String requestId = UUID.randomUUID().toString();
        return new PermissionRequest(requestId, toolName, toolInput, toolUseId, description);
    }

    /**
     * Send a permission request to the leader via mailbox.
     * Translated from sendPermissionRequestViaMailbox() in permissionSync.ts
     */
    public CompletableFuture<PermissionResult> sendPermissionRequestViaMailbox(
            PermissionRequest request) {
        return sendPermissionRequestViaMailbox(request, null, null);
    }

    public CompletableFuture<PermissionResult> sendPermissionRequestViaMailbox(
            PermissionRequest request,
            String teamName,
            String agentName) {

        CompletableFuture<PermissionResult> future = new CompletableFuture<>();
        pendingResponses.put(request.getRequestId(), future);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "permission_request");
        message.put("request_id", request.getRequestId());
        message.put("tool_name", request.getToolName());
        message.put("tool_input", request.getToolInput());
        message.put("tool_use_id", request.getToolUseId());
        message.put("description", request.getDescription());
        message.put("from_agent", agentName);

        teammateMailboxService.writeToMailbox(
            "team-lead",
            new TeammateMailboxService.MailboxEntry(
                request.getDescription(),
                agentName,
                null,
                java.time.Instant.now().toString()
            ),
            teamName
        );

        // Set timeout
        future.orTimeout(5 * 60, TimeUnit.SECONDS)
            .whenComplete((result, ex) -> {
                pendingResponses.remove(request.getRequestId());
                if (ex != null) {
                    log.debug("Permission request {} timed out", request.getRequestId());
                }
            });

        return future;
    }

    /**
     * Handle a permission response from the leader.
     */
    public void handlePermissionResponse(String requestId, boolean allow, String message) {
        CompletableFuture<PermissionResult> future = pendingResponses.remove(requestId);
        if (future != null) {
            PermissionResult result = allow
                ? PermissionResult.AllowDecision.builder().build()
                : PermissionResult.DenyDecision.builder().message(message).build();
            future.complete(result);
        }
    }

    /**
     * Check if this is a swarm worker.
     * Translated from isSwarmWorker() in permissionSync.ts
     */
    public boolean isSwarmWorker() {
        return com.anthropic.claudecode.util.TeammateContext.isTeammate();
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PermissionRequest {
        private String requestId;
        private String toolName;
        private Map<String, Object> toolInput;
        private String toolUseId;
        private String description;

        /** Convenience accessor (same as requestId). */
        public String id() { return requestId; }

        public String getRequestId() { return requestId; }
        public void setRequestId(String v) { requestId = v; }
        public String getToolName() { return toolName; }
        public void setToolName(String v) { toolName = v; }
        public Map<String, Object> getToolInput() { return toolInput; }
        public void setToolInput(Map<String, Object> v) { toolInput = v; }
        public String getToolUseId() { return toolUseId; }
        public void setToolUseId(String v) { toolUseId = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
    
    }

    /** Overload accepting toolUseId first (matches call site in SwarmWorkerPermissionHandlerService). */
    public PermissionRequest createPermissionRequest(
            String toolName,
            String toolUseId,
            Map<String, Object> toolInput,
            String description,
            Object suggestions) {
        return createPermissionRequest(toolName, toolInput, toolUseId, description);
    }

    /** Register a callback for a permission request. */
    public void registerPermissionCallback(String requestId, String toolUseId,
            PermissionCallback callback) {
        // In a full implementation, store callback for later invocation when response arrives.
        log.debug("registerPermissionCallback: requestId={}", requestId);
    }

    /** Callback interface for permission request responses. */
    public interface PermissionCallback {
        void onAllow(Map<String, Object> allowedInput,
                     java.util.List<com.anthropic.claudecode.model.PermissionResult.PermissionUpdate> permissionUpdates,
                     String feedback);
        void onReject(String feedback);
    }
}
