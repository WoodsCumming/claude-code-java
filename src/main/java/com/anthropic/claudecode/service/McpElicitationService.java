package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * MCP elicitation handler service.
 * Translated from src/services/mcp/elicitationHandler.ts
 *
 * Handles MCP elicitation requests from servers — both form mode (user fills out
 * structured fields) and URL mode (server provides a URL for the user to visit).
 *
 * In URL mode, a completion notification arrives when the server confirms the
 * flow is done; this updates the queued event's `completed` flag so the UI can react.
 */
@Slf4j
@Service
public class McpElicitationService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(McpElicitationService.class);


    // ── Elicitation types ────────────────────────────────────────────────────

    /**
     * Configuration for the waiting state shown after the user opens a URL.
     * Translated from ElicitationWaitingState in elicitationHandler.ts
     */
    @Data
    @lombok.NoArgsConstructor(force = true)
    @lombok.AllArgsConstructor
    public static class ElicitationWaitingState {
        /** Button label, e.g. "Retry now" or "Skip confirmation". */
        private String actionLabel;
        /** Whether to show a visible Cancel button (for error-based retry flow). */
        private Boolean showCancel;

        public String getActionLabel() { return actionLabel; }
        public void setActionLabel(String v) { actionLabel = v; }
        public boolean isShowCancel() { return showCancel; }
        public void setShowCancel(Boolean v) { showCancel = v; }
    }

    /** Mode of an elicitation request. */
    public enum ElicitationMode {
        FORM, URL
    }

    /**
     * Result of an elicitation — user's response.
     * Translated from ElicitResult in @modelcontextprotocol/sdk/types.js
     */
    @Data
    @lombok.NoArgsConstructor(force = true)
    @lombok.AllArgsConstructor
    public static class ElicitResult {
        /** Action chosen by the user. */
        private String action;   // "accept" | "decline" | "cancel"
        /** For form mode: the structured data the user submitted. */
        private Map<String, Object> content;

        public static ElicitResult cancel() {
            return new ElicitResult("cancel", null);
        }

        public static ElicitResult decline() {
            return new ElicitResult("decline", null);
        }

        public String getAction() { return action; }
        public void setAction(String v) { action = v; }
        public Map<String, Object> getContent() { return content; }
        public void setContent(Map<String, Object> v) { content = v; }
    }

    /**
     * A queued elicitation request event.
     * Translated from ElicitationRequestEvent in elicitationHandler.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ElicitationRequestEvent {
        private String serverName;
        /** The JSON-RPC request ID, unique per server connection. */
        private String requestId;
        /** The elicitation parameters from the server. */
        private ElicitRequestParams params;
        /** Resolves the elicitation with the user's response. */
        private Consumer<ElicitResult> respond;
        /** For URL elicitations: shown after user opens the browser. */
        private ElicitationWaitingState waitingState;
        /** Called when phase 2 (waiting) is dismissed by user action or completion. */
        private Consumer<String> onWaitingDismiss;  // "dismiss" | "retry" | "cancel"
        /** Set to true by the completion notification handler when server confirms completion. */
        private volatile boolean completed;

        public String getServerName() { return serverName; }
        public void setServerName(String v) { serverName = v; }
        public String getRequestId() { return requestId; }
        public void setRequestId(String v) { requestId = v; }
        public ElicitRequestParams getParams() { return params; }
        public void setParams(ElicitRequestParams v) { params = v; }
        public Consumer<ElicitResult> getRespond() { return respond; }
        public void setRespond(Consumer<ElicitResult> v) { respond = v; }
        public ElicitationWaitingState getWaitingState() { return waitingState; }
        public void setWaitingState(ElicitationWaitingState v) { waitingState = v; }
        public Consumer<String> getOnWaitingDismiss() { return onWaitingDismiss; }
        public void setOnWaitingDismiss(Consumer<String> v) { onWaitingDismiss = v; }
        public boolean getCompleted() { return completed; }
        public void setCompleted(boolean v) { completed = v; }
    }

    /**
     * Elicitation request parameters.
     * Simplified from ElicitRequestParams in @modelcontextprotocol/sdk
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ElicitRequestParams {
        private String message;
        private String mode;            // "form" | "url"
        private String url;             // present in url mode
        private String elicitationId;   // present in url mode for completion tracking
        private Map<String, Object> requestedSchema;  // present in form mode

        public String getMessage() { return message; }
        public void setMessage(String v) { message = v; }
        public String getMode() { return mode; }
        public void setMode(String v) { mode = v; }
        public String getUrl() { return url; }
        public void setUrl(String v) { url = v; }
        public String getElicitationId() { return elicitationId; }
        public void setElicitationId(String v) { elicitationId = v; }
        public Map<String, Object> getRequestedSchema() { return requestedSchema; }
        public void setRequestedSchema(Map<String, Object> v) { requestedSchema = v; }
    }

    // ── Elicitation queue ────────────────────────────────────────────────────

    private final List<ElicitationRequestEvent> queue = new java.util.concurrent.CopyOnWriteArrayList<>();
    private Function<ElicitRequestParams, CompletableFuture<ElicitResult>> hookRunner;

    /**
     * Register a hook runner that can programmatically resolve elicitations
     * (e.g. from PreElicitation hooks). If the hook returns a non-null result,
     * the user is not shown the elicitation dialog.
     */
    public void setHookRunner(Function<ElicitRequestParams, CompletableFuture<ElicitResult>> hookRunner) {
        this.hookRunner = hookRunner;
    }

    // ── Request handling ─────────────────────────────────────────────────────

    /**
     * Handle an elicitation request from an MCP server.
     * Translated from the setRequestHandler(ElicitRequestSchema, ...) closure
     * in registerElicitationHandler() in elicitationHandler.ts
     *
     * @param serverName the MCP server name
     * @param requestId  the JSON-RPC request ID
     * @param params     the elicitation parameters
     * @return CompletableFuture that resolves when the user (or a hook) responds
     */
    public CompletableFuture<ElicitResult> handleElicitationRequest(
            String serverName,
            String requestId,
            ElicitRequestParams params) {

        ElicitationMode mode = getElicitationMode(params);
        log.debug("[MCP {}] Received elicitation request (mode={})", serverName, mode);

        // Log analytics event — elicitation shown
        // logEvent("tengu_mcp_elicitation_shown", Map.of("mode", mode.name().toLowerCase()))

        // Try hooks first — they can provide a programmatic response
        if (hookRunner != null) {
            return hookRunner.apply(params).thenCompose(hookResult -> {
                if (hookResult != null) {
                    log.debug("[MCP {}] Elicitation resolved by hook: action={}", serverName, hookResult.getAction());
                    return CompletableFuture.completedFuture(hookResult);
                }
                return enqueueElicitation(serverName, requestId, params, mode);
            }).exceptionally(e -> {
                log.error("[MCP {}] Elicitation hook error: {}", serverName, e.getMessage());
                return ElicitResult.cancel();
            });
        }

        return enqueueElicitation(serverName, requestId, params, mode);
    }

    /**
     * Enqueue an elicitation request for user interaction.
     * Translated from the Promise<ElicitResult> construction in handleElicitationRequest.
     */
    private CompletableFuture<ElicitResult> enqueueElicitation(
            String serverName,
            String requestId,
            ElicitRequestParams params,
            ElicitationMode mode) {

        String elicitationId = (mode == ElicitationMode.URL) ? params.getElicitationId() : null;

        ElicitationWaitingState waitingState = (elicitationId != null)
            ? new ElicitationWaitingState("Skip confirmation", null)
            : null;

        CompletableFuture<ElicitResult> future = new CompletableFuture<>();

        ElicitationRequestEvent event = new ElicitationRequestEvent();
        event.setServerName(serverName);
        event.setRequestId(requestId);
        event.setParams(params);
        event.setRespond(result -> {
            log.debug("[MCP {}] Elicitation response: action={}", serverName, result.getAction());
            future.complete(result);
        });
        event.setWaitingState(waitingState);

        queue.add(event);
        log.debug("[MCP {}] Elicitation queued (queue size={})", serverName, queue.size());

        return future.thenCompose(rawResult ->
            runElicitationResultHooks(serverName, rawResult, mode, elicitationId)
        ).exceptionally(e -> {
            log.error("[MCP {}] Elicitation error: {}", serverName, e.getMessage());
            return ElicitResult.cancel();
        });
    }

    /**
     * Handle an elicitation completion notification from the server (URL mode).
     * Sets completed=true on the matching queued event so the UI can react.
     * Translated from the setNotificationHandler(ElicitationCompleteNotificationSchema, ...)
     * in registerElicitationHandler() in elicitationHandler.ts
     *
     * @param serverName    the MCP server name
     * @param elicitationId the ID of the completed elicitation
     */
    public void handleElicitationCompleteNotification(String serverName, String elicitationId) {
        log.debug("[MCP {}] Received elicitation completion notification: {}", serverName, elicitationId);

        boolean found = false;
        for (ElicitationRequestEvent event : queue) {
            if (event.getServerName().equals(serverName)
                    && event.getParams().getMode() != null
                    && "url".equals(event.getParams().getMode())
                    && elicitationId.equals(event.getParams().getElicitationId())) {
                event.setCompleted(true);
                found = true;
                break;
            }
        }

        if (!found) {
            log.debug("[MCP {}] Ignoring completion notification for unknown elicitation: {}",
                serverName, elicitationId);
        }
    }

    // ── Hook runners ─────────────────────────────────────────────────────────

    /**
     * Run pre-elicitation hooks that may provide a programmatic response.
     * Translated from runElicitationHooks() in elicitationHandler.ts
     */
    public CompletableFuture<ElicitResult> runElicitationHooks(
            String serverName,
            ElicitRequestParams params) {
        // In a full implementation, this would call executeElicitationHooks() from utils/hooks.ts
        // and return the hook's response if present, or null to show the UI.
        log.debug("[MCP {}] Running pre-elicitation hooks", serverName);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Run post-elicitation hooks after the user has responded.
     * Translated from runElicitationResultHooks() in elicitationHandler.ts
     *
     * Returns a potentially modified ElicitResult — hooks may override action/content
     * or block the response by returning "decline".
     */
    public CompletableFuture<ElicitResult> runElicitationResultHooks(
            String serverName,
            ElicitResult result,
            ElicitationMode mode,
            String elicitationId) {
        // In a full implementation, this would call executeElicitationResultHooks() from utils/hooks.ts
        // A blocking hook error → decline. Otherwise return the hook's modified result or the original.
        log.debug("[MCP {}] Running post-elicitation hooks (action={})", serverName, result.getAction());
        return CompletableFuture.completedFuture(result);
    }

    // ── Queue accessors ───────────────────────────────────────────────────────

    /** Get the current elicitation queue (unmodifiable view). */
    public List<ElicitationRequestEvent> getQueue() {
        return Collections.unmodifiableList(queue);
    }

    /** Remove a resolved elicitation event from the queue. */
    public void removeFromQueue(ElicitationRequestEvent event) {
        queue.remove(event);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Determine the elicitation mode from the params.
     * Translated from getElicitationMode() in elicitationHandler.ts
     */
    private static ElicitationMode getElicitationMode(ElicitRequestParams params) {
        return "url".equals(params.getMode()) ? ElicitationMode.URL : ElicitationMode.FORM;
    }
}
