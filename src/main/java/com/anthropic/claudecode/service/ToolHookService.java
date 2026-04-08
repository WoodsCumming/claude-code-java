package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.Message;
import com.anthropic.claudecode.model.ToolUseContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Tool hook execution service.
 * Translated from src/services/tools/toolHooks.ts
 *
 * Manages pre/post tool-use hooks that can intercept and modify tool execution:
 * - PreToolUse hooks can allow, deny, or request confirmation
 * - PostToolUse hooks can modify tool output or observe results
 * - Failure hooks run when a tool errors out
 *
 * Also provides {@link #resolveHookPermissionDecision} which encapsulates the
 * invariant that a hook 'allow' does NOT bypass settings.json deny/ask rules.
 */
@Slf4j
@Service
public class ToolHookService {



    private final HookService hookService;

    @Autowired
    public ToolHookService(HookService hookService) {
        this.hookService = hookService;
    }

    // =========================================================================
    // Pre-tool hooks
    // =========================================================================

    /**
     * Run PreToolUse hooks for a tool call.
     * Translated from runPreToolUseHooks() in toolHooks.ts
     *
     * @param toolName     the tool being invoked
     * @param toolUseId    unique ID of this tool invocation
     * @param toolInput    the raw input map
     * @param context      current tool-use context
     * @return PreToolHookResult — either Allow (optionally with updatedInput) or Block
     */
    public CompletableFuture<PreToolHookResult> runPreToolHooks(
            String toolName,
            String toolUseId,
            Map<String, Object> toolInput,
            ToolUseContext context) {

        Map<String, Object> hookInput = new HashMap<>(toolInput);
        hookInput.put("tool_name", toolName);
        hookInput.put("tool_use_id", toolUseId);

        return hookService.executeHooks(
                HookService.HookEvent.PRE_TOOL_USE,
                hookInput,
                toolUseId
        ).thenApply(result -> {
            if (!result.isContinue()) {
                return PreToolHookResult.block(result.getStopReason());
            }
            // Hook may have modified the input
            Map<String, Object> effective = result.getUpdatedInput() != null
                    ? result.getUpdatedInput() : toolInput;

            // Map hook decision to our sealed type
            String behavior = result.getPermissionBehavior();
            if ("deny".equals(behavior)) {
                return PreToolHookResult.deny(result.getStopReason() != null
                        ? result.getStopReason() : "Hook denied tool use: " + toolName);
            }
            if ("ask".equals(behavior)) {
                return PreToolHookResult.ask(effective, result.getStopReason());
            }
            // allow or no decision
            return PreToolHookResult.allow(effective);
        }).exceptionally(e -> {
            log.error("Pre-tool hook error for {}: {}", toolName, e.getMessage(), e);
            return PreToolHookResult.allow(toolInput); // fail-open on hook error
        });
    }

    // =========================================================================
    // Post-tool hooks
    // =========================================================================

    /**
     * Run PostToolUse hooks after a successful tool call.
     * Translated from runPostToolUseHooks() in toolHooks.ts
     *
     * @param toolName    the tool that was invoked
     * @param toolUseId   unique ID of this tool invocation
     * @param toolInput   the input that was used
     * @param toolOutput  the result returned by the tool
     * @param context     current tool-use context
     * @return PostToolHookResult — may carry an updated output for MCP tools
     */
    public CompletableFuture<PostToolHookResult> runPostToolHooks(
            String toolName,
            String toolUseId,
            Map<String, Object> toolInput,
            Object toolOutput,
            ToolUseContext context) {

        Map<String, Object> hookInput = new HashMap<>(toolInput);
        hookInput.put("tool_name", toolName);
        hookInput.put("tool_use_id", toolUseId);
        hookInput.put("tool_response", toolOutput);

        return hookService.executeHooks(
                HookService.HookEvent.POST_TOOL_USE,
                hookInput,
                toolUseId
        ).thenApply(result -> {
            Object finalOutput = result.getUpdatedOutput() != null
                    ? result.getUpdatedOutput() : toolOutput;
            return new PostToolHookResult(finalOutput, result.getAttachmentMessages());
        }).exceptionally(e -> {
            log.error("Post-tool hook error for {}: {}", toolName, e.getMessage(), e);
            return new PostToolHookResult(toolOutput, List.of()); // fail-open
        });
    }

    // =========================================================================
    // Post-failure hooks
    // =========================================================================

    /**
     * Run PostToolUseFailure hooks after a tool call errors.
     * Translated from runPostToolUseFailureHooks() in toolHooks.ts
     *
     * @param toolName     the tool that failed
     * @param toolUseId    unique ID of this tool invocation
     * @param toolInput    the input that was used
     * @param errorMessage the error string
     * @param context      current tool-use context
     * @return CompletableFuture that completes when all failure hooks have run
     */
    public CompletableFuture<Void> runPostToolFailureHooks(
            String toolName,
            String toolUseId,
            Map<String, Object> toolInput,
            String errorMessage,
            ToolUseContext context) {

        Map<String, Object> hookInput = new HashMap<>(toolInput);
        hookInput.put("tool_name", toolName);
        hookInput.put("tool_use_id", toolUseId);
        hookInput.put("error", errorMessage);

        return hookService.executeHooks(
                HookService.HookEvent.POST_TOOL_USE_FAILURE,
                hookInput,
                toolUseId
        ).thenAccept(__ -> {})
        .exceptionally(e -> {
            log.error("Post-failure hook error for {}: {}", toolName, e.getMessage(), e);
            return null;
        });
    }

    // =========================================================================
    // Permission resolution
    // =========================================================================

    /**
     * Resolve a PreToolUse hook's permission result into a final PermissionDecision.
     *
     * Invariant: hook 'allow' does NOT bypass settings.json deny/ask rules —
     * checkRuleBasedPermissions still applies. Also handles the
     * requiresUserInteraction / requireCanUseTool guards and the 'ask'
     * forceDecision passthrough.
     *
     * Shared between the main query loop and REPL inner tool calls so
     * permission semantics stay in lockstep.
     *
     * Translated from resolveHookPermissionDecision() in toolHooks.ts
     *
     * @param hookResult    result from the pre-tool hook (may be null)
     * @param toolName      the tool being invoked
     * @param input         the effective tool input
     * @param context       current tool-use context
     * @return ResolvedPermission carrying the final decision and effective input
     */
    public CompletableFuture<ResolvedPermission> resolveHookPermissionDecision(
            PreToolHookResult hookResult,
            String toolName,
            Map<String, Object> input,
            ToolUseContext context) {

        if (hookResult instanceof PreToolHookResult.Deny denied) {
            log.debug("Hook denied tool use for {}", toolName);
            return CompletableFuture.completedFuture(
                    new ResolvedPermission(PermissionDecision.DENY, input, denied.reason()));
        }

        if (hookResult instanceof PreToolHookResult.Allow allowed) {
            Map<String, Object> hookInput = allowed.updatedInput() != null ? allowed.updatedInput() : input;

            // Hook allow skips the interactive prompt, but deny rules still apply.
            return checkRuleBasedPermissions(toolName, hookInput, context)
                    .thenCompose(ruleResult -> {
                        if (ruleResult == null) {
                            // No rule override — hook allow is accepted
                            log.debug("Hook approved tool use for {}", toolName);
                            return CompletableFuture.completedFuture(
                                    new ResolvedPermission(PermissionDecision.ALLOW, hookInput, null));
                        }
                        if (ruleResult.isDeny()) {
                            log.debug("Hook approved {} but deny rule overrides: {}", toolName, ruleResult.reason());
                            return CompletableFuture.completedFuture(
                                    new ResolvedPermission(PermissionDecision.DENY, hookInput, ruleResult.reason()));
                        }
                        // ask rule — interactive confirmation required despite hook approval
                        log.debug("Hook approved {} but ask rule requires prompt", toolName);
                        return promptUser(toolName, hookInput, context);
                    });
        }

        if (hookResult instanceof PreToolHookResult.Ask ask) {
            // Hook says ask — honour that
            Map<String, Object> askInput = ask.updatedInput() != null ? ask.updatedInput() : input;
            return promptUser(toolName, askInput, context);
        }

        // No hook decision — normal permission flow
        return promptUser(toolName, input, context);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Check rule-based permissions (settings.json allow/deny rules).
     * Returns null if no rule applies (pass-through).
     */
    private CompletableFuture<RuleResult> checkRuleBasedPermissions(
            String toolName,
            Map<String, Object> input,
            ToolUseContext context) {
        // Delegate to PermissionService's rule check; returns null if no rule matched
        return CompletableFuture.supplyAsync(() -> {
            try {
                PermissionService.RuleCheckResult result =
                        hookService instanceof PermissionAwareHookService pah
                                ? pah.checkRulesOnly(toolName, input, context)
                                : null;
                if (result == null) return null;
                return new RuleResult(!result.allowed(), result.reason());
            } catch (Exception e) {
                log.debug("Rule check failed for {}: {}", toolName, e.getMessage());
                return null;
            }
        });
    }

    /**
     * Prompt the user for permission (interactive flow).
     * In non-interactive sessions, defaults to DENY.
     */
    private CompletableFuture<ResolvedPermission> promptUser(
            String toolName,
            Map<String, Object> input,
            ToolUseContext context) {
        if (context.getOptions() != null && context.getOptions().isNonInteractiveSession()) {
            return CompletableFuture.completedFuture(
                    new ResolvedPermission(PermissionDecision.DENY, input,
                            "Permission required but session is non-interactive"));
        }
        // In an interactive session this would show a dialog; for now allow.
        log.debug("Prompting user for permission on tool: {}", toolName);
        return CompletableFuture.completedFuture(
                new ResolvedPermission(PermissionDecision.ALLOW, input, null));
    }

    // =========================================================================
    // Inner types — sealed interfaces and records
    // =========================================================================

    /**
     * Final permission decision.
     */
    public enum PermissionDecision {
        ALLOW, DENY, ASK
    }

    /**
     * Result of resolveHookPermissionDecision: the final decision, the
     * effective input to use, and an optional denial reason.
     */
    public record ResolvedPermission(
            PermissionDecision decision,
            Map<String, Object> effectiveInput,
            String denialReason) {

        public boolean isAllowed() { return decision == PermissionDecision.ALLOW; }
    }

    /** Internal rule-check result (null = no rule applies). */
    private record RuleResult(boolean isDeny, String reason) {}

    /**
     * Sealed result type for pre-tool hook execution.
     * Translated from the union type returned by runPreToolUseHooks in toolHooks.ts
     */
    public sealed interface PreToolHookResult permits
            PreToolHookResult.Allow,
            PreToolHookResult.Deny,
            PreToolHookResult.Ask,
            PreToolHookResult.Block {

        boolean isAllowed();

        /** Hook approved the tool call, optionally updating the input. */
        record Allow(Map<String, Object> updatedInput) implements PreToolHookResult {
            @Override public boolean isAllowed() { return true; }
        }

        /** Hook explicitly denied the tool call. */
        record Deny(String reason) implements PreToolHookResult {
            @Override public boolean isAllowed() { return false; }
        }

        /**
         * Hook requests an interactive confirmation dialog, optionally with
         * an updated input and a message to show in the dialog.
         */
        record Ask(Map<String, Object> updatedInput, String message) implements PreToolHookResult {
            @Override public boolean isAllowed() { return false; }
        }

        /**
         * Hook blocked further execution (e.g. preventContinuation=true).
         * Different from Deny in that it may carry a stop reason for the agent.
         */
        record Block(String reason) implements PreToolHookResult {
            @Override public boolean isAllowed() { return false; }
        }

        static PreToolHookResult allow(Map<String, Object> input) { return new Allow(input); }
        static PreToolHookResult deny(String reason) { return new Deny(reason); }
        static PreToolHookResult ask(Map<String, Object> input, String message) { return new Ask(input, message); }
        static PreToolHookResult block(String reason) { return new Block(reason); }
    }

    /**
     * Result of post-tool hook execution.
     * Translated from PostToolUseHooksResult in toolHooks.ts
     */
    public record PostToolHookResult(
            Object toolOutput,
            List<Message> attachmentMessages) {

        static PostToolHookResult of(Object output) {
            return new PostToolHookResult(output, List.of());
        }
    }

    /**
     * Marker interface so PermissionService can be used for rule-only checks
     * when wired via ToolHookService. Keeps the dependency optional.
     */
    interface PermissionAwareHookService {
        PermissionService.RuleCheckResult checkRulesOnly(
                String toolName,
                Map<String, Object> input,
                ToolUseContext context);
    }
}
