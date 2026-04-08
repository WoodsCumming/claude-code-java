package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Handles accept-once, accept-session, and reject actions for file permission dialogs.
 * Translated from src/components/permissions/FilePermissionDialog/usePermissionHandler.ts
 *
 * The TypeScript module exports a PERMISSION_HANDLERS map of three functions.
 * This service exposes the same three actions via a single dispatch method and
 * individual named methods, keeping the internal structure parallel to the source.
 */
@Slf4j
@Service
public class PermissionHandlerService {



    /**
     * Pattern granting session-level access to all files inside a project's
     * .claude/ directory. Mirrors CLAUDE_FOLDER_PERMISSION_PATTERN.
     */
    public static final String CLAUDE_FOLDER_PERMISSION_PATTERN = ".claude/**";

    /**
     * Pattern granting session-level access to all .claude/ files globally.
     * Mirrors GLOBAL_CLAUDE_FOLDER_PERMISSION_PATTERN.
     */
    public static final String GLOBAL_CLAUDE_FOLDER_PERMISSION_PATTERN = "**/.claude/**";

    /** Tool name used for file-edit permission rules. */
    public static final String FILE_EDIT_TOOL_NAME = "str_replace_editor";

    private final AnalyticsService analyticsService;
    private final UnaryLoggingService unaryLoggingService;
    private final FilesystemPermissionService filesystemPermissionService;

    public PermissionHandlerService(AnalyticsService analyticsService,
                                    UnaryLoggingService unaryLoggingService,
                                    FilesystemPermissionService filesystemPermissionService) {
        this.analyticsService = analyticsService;
        this.unaryLoggingService = unaryLoggingService;
        this.filesystemPermissionService = filesystemPermissionService;
    }

    // -------------------------------------------------------------------------
    // Public dispatch – mirrors PERMISSION_HANDLERS map in TypeScript
    // -------------------------------------------------------------------------

    /**
     * Dispatches to the correct handler based on the option type string.
     * Mirrors PERMISSION_HANDLERS[option.type](params, options) in the source.
     *
     * @param optionType "accept-once" | "accept-session" | "reject"
     * @param params     handler parameters
     * @param options    optional handler options (feedback, scope, …)
     * @param onAllow    callback for the allow path (receives input, updates, feedback)
     */
    public void handle(String optionType,
                       HandlerParams params,
                       HandlerOptions options,
                       AllowCallback onAllow) {
        switch (optionType) {
            case "accept-once"    -> handleAcceptOnce(params, options, onAllow);
            case "accept-session" -> handleAcceptSession(params, options, onAllow);
            case "reject"         -> handleReject(params, options);
            default -> log.warn("[PermissionHandler] Unknown option type: {}", optionType);
        }
    }

    // -------------------------------------------------------------------------
    // Individual handlers
    // -------------------------------------------------------------------------

    /**
     * Handles an accept-once decision: logs the event and calls onAllow with
     * no permission updates (single-use approval).
     * Translated from handleAcceptOnce() in usePermissionHandler.ts
     */
    public void handleAcceptOnce(HandlerParams params,
                                  HandlerOptions options,
                                  AllowCallback onAllow) {
        logPermissionEvent("accept", params.completionType(), params.languageName(),
                params.messageId(), options != null && options.hasFeedback());

        analyticsService.logEvent("tengu_accept_submitted", Map.of(
                "toolName", params.toolName(),
                "isMcp", params.isMcp(),
                "has_instructions", options != null && options.feedback() != null,
                "instructions_length", options != null && options.feedback() != null
                        ? options.feedback().length() : 0,
                "entered_feedback_mode", options != null && Boolean.TRUE.equals(options.enteredFeedbackMode())));

        params.onDone().run();
        onAllow.accept(params.rawInput(), List.of(),
                options != null ? options.feedback() : null);
    }

    /**
     * Handles an accept-session decision: generates permission update suggestions
     * and calls onAllow.
     * Translated from handleAcceptSession() in usePermissionHandler.ts
     */
    public void handleAcceptSession(HandlerParams params,
                                     HandlerOptions options,
                                     AllowCallback onAllow) {
        logPermissionEvent("accept", params.completionType(), params.languageName(),
                params.messageId(), false);

        // For claude-folder scope grant session-level access to all .claude/ files
        if (options != null && ("claude-folder".equals(options.scope())
                || "global-claude-folder".equals(options.scope()))) {

            String pattern = "global-claude-folder".equals(options.scope())
                    ? GLOBAL_CLAUDE_FOLDER_PERMISSION_PATTERN
                    : CLAUDE_FOLDER_PERMISSION_PATTERN;

            List<Object> suggestions = List.of(Map.of(
                    "type", "addRules",
                    "rules", List.of(Map.of(
                            "toolName", FILE_EDIT_TOOL_NAME,
                            "ruleContent", pattern)),
                    "behavior", "allow",
                    "destination", "session"));

            params.onDone().run();
            onAllow.accept(params.rawInput(), suggestions, null);
            return;
        }

        // Generate permission updates based on the file path and operation type
        List<Object> suggestions = params.path() != null
                ? filesystemPermissionService.generateSuggestions(
                        params.path(), params.operationType(), params.toolPermissionContext())
                : List.of();

        params.onDone().run();
        onAllow.accept(params.rawInput(), suggestions, null);
    }

    /**
     * Handles a reject decision: logs analytics and calls onReject/onDone.
     * Translated from handleReject() in usePermissionHandler.ts
     */
    public void handleReject(HandlerParams params, HandlerOptions options) {
        boolean hasFeedback = options != null && options.hasFeedback();
        logPermissionEvent("reject", params.completionType(), params.languageName(),
                params.messageId(), hasFeedback);

        analyticsService.logEvent("tengu_reject_submitted", Map.of(
                "toolName", params.toolName(),
                "isMcp", params.isMcp(),
                "has_instructions", options != null && options.feedback() != null,
                "instructions_length", options != null && options.feedback() != null
                        ? options.feedback().length() : 0,
                "entered_feedback_mode", options != null && Boolean.TRUE.equals(options.enteredFeedbackMode())));

        params.onDone().run();
        params.onReject().run();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void logPermissionEvent(String event,
                                     String completionType,
                                     String languageName,
                                     String messageId,
                                     boolean hasFeedback) {
        unaryLoggingService.logUnaryEvent(completionType, event, languageName, messageId);
        log.debug("[PermissionHandler] event={} completionType={} messageId={} hasFeedback={}",
                event, completionType, messageId, hasFeedback);
    }

    // -------------------------------------------------------------------------
    // Supporting types
    // -------------------------------------------------------------------------

    /**
     * All parameters needed by every handler function.
     * Translated from PermissionHandlerParams in usePermissionHandler.ts
     */
    public record HandlerParams(
            String messageId,
            String path,
            Object toolUseConfirm,
            Object toolPermissionContext,
            Runnable onDone,
            Runnable onReject,
            String completionType,
            String languageName,
            String operationType,
            // Convenience fields extracted from toolUseConfirm for analytics
            Object rawInput,
            String toolName,
            boolean isMcp) {

        /** Compact constructor – rawInput, toolName and isMcp default to safe values. */
        public HandlerParams(String messageId, String path, Object toolUseConfirm,
                             Object toolPermissionContext, Runnable onDone, Runnable onReject,
                             String completionType, String languageName, String operationType) {
            this(messageId, path, toolUseConfirm, toolPermissionContext,
                    onDone, onReject, completionType, languageName, operationType,
                    null, "unknown", false);
        }
    }

    /**
     * Optional extras supplied per handler invocation.
     * Translated from PermissionHandlerOptions in usePermissionHandler.ts
     */
    public record HandlerOptions(
            boolean hasFeedback,
            String feedback,
            Boolean enteredFeedbackMode,
            /** "claude-folder" | "global-claude-folder" | null */
            String scope) {

        public HandlerOptions(boolean hasFeedback, String feedback) {
            this(hasFeedback, feedback, null, null);
        }
    }

    /**
     * Callback for the allow path: receives the (possibly modified) input,
     * the list of permission updates, and optional feedback text.
     * Translates the (input, permissionUpdates, feedback?) → void signature
     * from toolUseConfirm.onAllow().
     */
    @FunctionalInterface
    public interface AllowCallback {
        void accept(Object input, List<Object> permissionUpdates, String feedback);
    }
}
