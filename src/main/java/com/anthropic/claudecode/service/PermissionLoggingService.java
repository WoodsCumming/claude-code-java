package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Centralized analytics/telemetry logging for tool permission decisions.
 *
 * All permission approve/reject events flow through {@link #logPermissionDecision},
 * which fans out to analytics events, OTel telemetry, and code-edit metrics.
 *
 * Translated from src/hooks/toolPermission/permissionLogging.ts
 */
@Slf4j
@Service
public class PermissionLoggingService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PermissionLoggingService.class);


    private final AnalyticsService analyticsService;

    public PermissionLoggingService(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    // =========================================================================
    // Domain types
    // =========================================================================

    /**
     * Logging context for a single permission decision.
     * Translated from PermissionLogContext in permissionLogging.ts
     */
    public record PermissionLogContext(
            String toolName,
            Object input,
            String messageId,
            String toolUseId
    ) {}

    /**
     * Discriminated union: 'accept' pairs with approval sources,
     * 'reject' pairs with rejection sources.
     * Translated from PermissionDecisionArgs in permissionLogging.ts
     */
    public record PermissionDecisionArgs(
            String decision,   // "accept" | "reject"
            Object source      // PermissionApprovalSource | PermissionRejectionSource | "config"
    ) {}

    // --- Source discriminants (mirrors PermissionContext.ts sealed interfaces) ---

    /** Translated from { type: 'hook'; permanent?: boolean } approval source. */
    public record HookApprovalDecision(boolean permanent) {}

    /** Translated from { type: 'user'; permanent: boolean } approval source. */
    public record UserApprovalDecision(boolean permanent) {}

    /** Translated from { type: 'classifier' } approval source. */
    public record ClassifierApprovalDecision() {}

    /** Config auto-approval (allow-listed in settings). */
    public record ConfigSource() {}

    /** Translated from { type: 'hook' } rejection source. */
    public record HookRejectionDecision() {}

    /** Translated from { type: 'user_abort' } rejection source. */
    public record UserAbortSource() {}

    /** Translated from { type: 'user_reject'; hasFeedback: boolean } rejection source. */
    public record UserRejectSource(boolean hasFeedback) {}

    // --- Additional types used by callers ---

    /** Approval source (mirrors PermissionApprovalSource in PermissionContext.ts) */
    public record UserSource(boolean permanent) {}

    // =========================================================================
    // Constants
    // =========================================================================

    private static final Set<String> CODE_EDITING_TOOLS = Set.of("Edit", "Write", "NotebookEdit");

    public static boolean isCodeEditingTool(String toolName) {
        return CODE_EDITING_TOOLS.contains(toolName);
    }

    // =========================================================================
    // Core logging method
    // =========================================================================

    /**
     * Single entry point for all permission decision logging.
     * Called by permission handlers after every approve/reject.
     * Fans out to: analytics events, OTel telemetry, code-edit OTel counters,
     * and toolUseContext decision storage.
     *
     * Translated from logPermissionDecision() in permissionLogging.ts
     *
     * @param ctx                        permission log context
     * @param args                       decision with source
     * @param permissionPromptStartTimeMs epoch ms when the prompt appeared (null = auto-approved)
     */
    public void logPermissionDecision(
            PermissionLogContext ctx,
            PermissionDecisionArgs args,
            Long permissionPromptStartTimeMs) {

        Long waitMs = permissionPromptStartTimeMs != null
                ? Instant.now().toEpochMilli() - permissionPromptStartTimeMs
                : null;

        if ("accept".equals(args.decision())) {
            logApprovalEvent(ctx.toolName(), ctx.messageId(), args.source(), waitMs);
        } else {
            logRejectionEvent(ctx.toolName(), ctx.messageId(), args.source(), waitMs);
        }

        String sourceString = sourceToString(args.source());

        // Track code-editing tool metrics
        if (isCodeEditingTool(ctx.toolName())) {
            log.debug("[permission] Code-edit decision: tool={} decision={} source={}",
                    ctx.toolName(), args.decision(), sourceString);
        }

        log.debug("[permission] Decision logged: tool={} decision={} source={} waitMs={}",
                ctx.toolName(), args.decision(), sourceString, waitMs);
    }

    // =========================================================================
    // Approval event fan-out (logApprovalEvent in permissionLogging.ts)
    // =========================================================================

    /**
     * Emits a distinct analytics event name per approval source for funnel analysis.
     * Translated from logApprovalEvent() in permissionLogging.ts
     */
    private void logApprovalEvent(String toolName, String messageId,
                                   Object source, Long waitMs) {
        Map<String, Object> base = buildBaseMetadata(messageId, toolName, waitMs);

        if (source instanceof ConfigSource) {
            analyticsService.logEvent("tengu_tool_use_granted_in_config", base);
            return;
        }
        if (source instanceof ClassifierApprovalDecision) {
            analyticsService.logEvent("tengu_tool_use_granted_by_classifier", base);
            return;
        }
        if (source instanceof UserApprovalDecision u) {
            analyticsService.logEvent(
                    u.permanent()
                            ? "tengu_tool_use_granted_in_prompt_permanent"
                            : "tengu_tool_use_granted_in_prompt_temporary",
                    base);
            return;
        }
        if (source instanceof UserSource u) {
            analyticsService.logEvent(
                    u.permanent()
                            ? "tengu_tool_use_granted_in_prompt_permanent"
                            : "tengu_tool_use_granted_in_prompt_temporary",
                    base);
            return;
        }
        if (source instanceof HookApprovalDecision h) {
            Map<String, Object> hookMeta = new java.util.HashMap<>(base);
            hookMeta.put("permanent", h.permanent());
            analyticsService.logEvent("tengu_tool_use_granted_by_permission_hook", hookMeta);
        }
    }

    // =========================================================================
    // Rejection event fan-out (logRejectionEvent in permissionLogging.ts)
    // =========================================================================

    /**
     * Rejections share a single event name, differentiated by metadata fields.
     * Translated from logRejectionEvent() in permissionLogging.ts
     */
    private void logRejectionEvent(String toolName, String messageId,
                                    Object source, Long waitMs) {
        Map<String, Object> base = buildBaseMetadata(messageId, toolName, waitMs);

        if (source instanceof ConfigSource) {
            analyticsService.logEvent("tengu_tool_use_denied_in_config", base);
            return;
        }

        Map<String, Object> meta = new java.util.HashMap<>(base);
        if (source instanceof HookRejectionDecision) {
            meta.put("isHook", true);
        } else if (source instanceof UserRejectSource u) {
            meta.put("hasFeedback", u.hasFeedback());
        } else if (source instanceof UserAbortSource) {
            meta.put("hasFeedback", false);
        }
        analyticsService.logEvent("tengu_tool_use_rejected_in_prompt", meta);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Builds base analytics metadata shared by all events.
     * Translated from baseMetadata() in permissionLogging.ts
     */
    private Map<String, Object> buildBaseMetadata(String messageId, String toolName, Long waitMs) {
        Map<String, Object> meta = new java.util.HashMap<>();
        meta.put("messageID", messageId);
        meta.put("toolName", toolName);
        if (waitMs != null) {
            meta.put("waiting_for_user_permission_ms", waitMs);
        }
        return meta;
    }

    /**
     * Flattens a structured source into a string label for analytics/OTel events.
     * Translated from sourceToString() in permissionLogging.ts
     */
    public static String sourceToString(Object source) {
        if (source instanceof ConfigSource) return "config";
        if (source instanceof ClassifierApprovalDecision) return "classifier";
        if (source instanceof UserApprovalDecision u)
            return u.permanent() ? "user_permanent" : "user_temporary";
        if (source instanceof UserSource u)
            return u.permanent() ? "user_permanent" : "user_temporary";
        if (source instanceof HookApprovalDecision) return "hook";
        if (source instanceof HookRejectionDecision) return "hook";
        if (source instanceof UserAbortSource) return "user_abort";
        if (source instanceof UserRejectSource) return "user_reject";
        return "unknown";
    }

    /**
     * Builds OTel counter attributes for code-editing tools.
     * Translated from buildCodeEditToolAttributes() in permissionLogging.ts
     *
     * @param toolName name of the tool
     * @param decision "accept" or "reject"
     * @param source   resolved source string
     * @return attribute map for the OTel counter
     */
    public Map<String, String> buildCodeEditToolAttributes(
            String toolName, String decision, String source) {
        Map<String, String> attrs = new java.util.HashMap<>();
        attrs.put("decision", decision);
        attrs.put("source", source);
        attrs.put("tool_name", toolName);
        return attrs;
    }
}
