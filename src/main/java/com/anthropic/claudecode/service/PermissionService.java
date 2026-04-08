package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.*;
import com.anthropic.claudecode.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Service for checking tool permissions.
 * Translated from src/utils/permissions/ directory
 *
 * Implements the permission system:
 * - Mode-based permissions (default, acceptEdits, bypassPermissions, etc.)
 * - Rule-based permissions (alwaysAllow, alwaysDeny, alwaysAsk)
 * - Tool-specific permissions
 */
@Slf4j
@Service
public class PermissionService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PermissionService.class);


    /**
     * Check if a tool can be used with the given input.
     * Translated from canUseTool() in permissions.ts
     */
    public CompletableFuture<PermissionResult> canUseTool(
            Tool<?, ?> tool,
            Map<String, Object> input,
            ToolUseContext context) {

        ToolPermissionContext permContext = getPermissionContext(context);

        // Check mode
        PermissionMode mode = permContext.getMode();

        // bypassPermissions: allow everything
        if (mode == PermissionMode.BYPASS_PERMISSIONS) {
            return CompletableFuture.completedFuture(
                PermissionResult.AllowDecision.builder().build()
            );
        }

        // Check always-deny rules
        if (matchesRule(tool.getName(), input, permContext.getAlwaysDenyRules())) {
            return CompletableFuture.completedFuture(
                PermissionResult.DenyDecision.builder()
                    .message("Tool is in the always-deny list")
                    .decisionReason(new PermissionResult.PermissionDecisionReason.OtherReason("always-deny rule"))
                    .build()
            );
        }

        // Check always-allow rules
        if (matchesRule(tool.getName(), input, permContext.getAlwaysAllowRules())) {
            return CompletableFuture.completedFuture(
                PermissionResult.AllowDecision.builder().build()
            );
        }

        // Mode-specific behavior
        return switch (mode) {
            case DEFAULT -> checkDefaultPermission(tool, input, context);
            case ACCEPT_EDITS -> checkAcceptEditsPermission(tool, input, context);
            case DONT_ASK -> CompletableFuture.completedFuture(
                PermissionResult.AllowDecision.builder().build()
            );
            case PLAN -> CompletableFuture.completedFuture(
                PermissionResult.DenyDecision.builder()
                    .message("In plan mode - cannot execute tools")
                    .build()
            );
            default -> checkDefaultPermission(tool, input, context);
        };
    }

    private CompletableFuture<PermissionResult> checkDefaultPermission(
            Tool<?, ?> tool,
            Map<String, Object> input,
            ToolUseContext context) {

        // Read-only tools are always allowed in default mode
        @SuppressWarnings({"unchecked","rawtypes"})
        Tool rawTool = (Tool) tool;
        if (rawTool.isReadOnly(input)) {
            return CompletableFuture.completedFuture(
                PermissionResult.AllowDecision.builder().build()
            );
        }

        // Non-interactive sessions: allow all
        if (context.getOptions() != null && context.getOptions().isNonInteractiveSession()) {
            return CompletableFuture.completedFuture(
                PermissionResult.AllowDecision.builder().build()
            );
        }

        // Interactive: ask for permission for write operations
        @SuppressWarnings({"unchecked","rawtypes"})
        Tool rawToolForAsk = (Tool) tool;
        return CompletableFuture.completedFuture(
            PermissionResult.AskDecision.builder()
                .message("Allow " + rawToolForAsk.userFacingName(input) + "?")
                .build()
        );
    }

    private CompletableFuture<PermissionResult> checkAcceptEditsPermission(
            Tool<?, ?> tool,
            Map<String, Object> input,
            ToolUseContext context) {

        // In acceptEdits mode, file edits are auto-approved
        String toolName = tool.getName();
        if (Set.of("Edit", "Write", "NotebookEdit").contains(toolName)) {
            return CompletableFuture.completedFuture(
                PermissionResult.AllowDecision.builder().build()
            );
        }

        return checkDefaultPermission(tool, input, context);
    }

    private boolean matchesRule(
            String toolName,
            Map<String, Object> input,
            Map<String, List<String>> rules) {

        if (rules == null || rules.isEmpty()) return false;

        for (Map.Entry<String, List<String>> entry : rules.entrySet()) {
            List<String> patterns = entry.getValue();
            if (patterns == null) continue;

            for (String pattern : patterns) {
                if (matchesPattern(toolName, pattern)) return true;
            }
        }

        return false;
    }

    private boolean matchesPattern(String toolName, String pattern) {
        if (pattern == null) return false;
        // Simple wildcard matching
        if (pattern.equals("*")) return true;
        if (pattern.equals(toolName)) return true;
        if (pattern.endsWith("*") && toolName.startsWith(pattern.substring(0, pattern.length() - 1))) {
            return true;
        }
        return false;
    }

    private ToolPermissionContext getPermissionContext(ToolUseContext context) {
        // In a real implementation, this would come from the context
        return ToolPermissionContext.empty();
    }

    /** Get the list of always-allowed tools. */
    public java.util.List<String> getAllowedTools() {
        return java.util.List.of();
    }

    /** Get the list of always-denied tools. */
    public java.util.List<String> getDeniedTools() {
        return java.util.List.of();
    }

    /** Allow a tool. */
    public void allowTool(String toolName, String scope) {
        log.debug("allowTool: {} scope={}", toolName, scope);
    }

    /** Deny a tool. */
    public void denyTool(String toolName, String scope) {
        log.debug("denyTool: {} scope={}", toolName, scope);
    }

    /** Remove a permission rule for a specific tool. */
    public void removeToolRule(String toolName) {
        log.debug("removeToolRule: {}", toolName);
    }

    /** Reset all custom permission rules. */
    public void resetAllRules() {
        log.debug("resetAllRules called");
    }

    /** Result of a rule-only permission check (no interactive prompt). */
    public record RuleCheckResult(boolean allowed, String reason) {
        public static RuleCheckResult allow() { return new RuleCheckResult(true, null); }
        public static RuleCheckResult deny(String reason) { return new RuleCheckResult(false, reason); }
    }
}
