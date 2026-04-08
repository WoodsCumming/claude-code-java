package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Fork subagent service.
 * Translated from src/tools/AgentTool/forkSubagent.ts
 *
 * Manages implicit forks where a child agent inherits the parent's full conversation
 * context and system prompt. The fork path is gated by the FORK_SUBAGENT feature flag
 * and is mutually exclusive with coordinator mode.
 */
@Slf4j
@Service
public class ForkSubagentService {



    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /**
     * Synthetic agent type name used for analytics when the fork path fires.
     * Translated from FORK_SUBAGENT_TYPE constant.
     */
    public static final String FORK_SUBAGENT_TYPE = "fork";

    /** Placeholder text used for all tool_result blocks in the fork prefix.
     *  Must be identical across all fork children for prompt cache sharing.
     *  Translated from FORK_PLACEHOLDER_RESULT constant. */
    private static final String FORK_PLACEHOLDER_RESULT = "Fork started — processing in background";

    private final CoordinatorModeService coordinatorModeService;

    @Autowired
    public ForkSubagentService(CoordinatorModeService coordinatorModeService) {
        this.coordinatorModeService = coordinatorModeService;
    }

    // -------------------------------------------------------------------------
    // Feature gate
    // -------------------------------------------------------------------------

    /**
     * Check if fork subagent is enabled.
     * Translated from isForkSubagentEnabled() in forkSubagent.ts.
     *
     * Mutually exclusive with coordinator mode.  Also disabled when running
     * non-interactively (mirrors the TypeScript {@code getIsNonInteractiveSession()} check).
     */
    public boolean isForkSubagentEnabled() {
        if (coordinatorModeService.isCoordinatorMode()) return false;
        // Non-interactive sessions cannot show the fork interaction model
        String nonInteractive = System.getenv("CLAUDE_CODE_NON_INTERACTIVE");
        if ("true".equalsIgnoreCase(nonInteractive) || "1".equals(nonInteractive)) return false;
        // Feature flag — mirrors feature('FORK_SUBAGENT')
        String featureFlag = System.getenv("CLAUDE_CODE_FORK_SUBAGENT");
        return "true".equalsIgnoreCase(featureFlag) || "1".equals(featureFlag);
    }

    // -------------------------------------------------------------------------
    // Recursive-fork guard
    // -------------------------------------------------------------------------

    /**
     * Guard against recursive forking.
     * Fork children keep the Agent tool in their tool pool for cache-identical
     * tool definitions, so we reject fork attempts at call time by detecting the
     * fork boilerplate tag in conversation history.
     * Translated from isInForkChild().
     */
    public boolean isInForkChild(List<Message> messages) {
        if (messages == null) return false;
        return messages.stream().anyMatch(m -> {
            if (!(m instanceof Message.UserMessage userMsg)) return false;
            Object content = userMsg.getContent();
            if (!(content instanceof List<?> blocks)) return false;
            return blocks.stream().anyMatch(block ->
                block instanceof ContentBlock.TextBlock tb
                    && tb.getText() != null
                    && tb.getText().contains("<fork-boilerplate>")
            );
        });
    }

    // -------------------------------------------------------------------------
    // Message building
    // -------------------------------------------------------------------------

    /**
     * Build the forked conversation messages for the child agent.
     *
     * For prompt cache sharing, all fork children must produce byte-identical API
     * request prefixes. This method:
     * <ol>
     *   <li>Clones the full parent assistant message (all tool_use blocks, thinking, text)</li>
     *   <li>Builds a single user message with tool_results for every tool_use block using
     *       an identical placeholder, then appends a per-child directive text block</li>
     * </ol>
     * Result: {@code [...history, assistant(all_tool_uses), user(placeholder_results..., directive)]}.
     * Only the final text block differs per child, maximising cache hits.
     *
     * Translated from buildForkedMessages().
     */
    public List<Message> buildForkedMessages(
            String directive,
            Message.AssistantMessage assistantMessage) {

        // Clone assistant message (new UUID, same content)
        Message.AssistantMessage fullAssistantMessage = assistantMessage.withNewUuid();

        // Collect all tool_use blocks
        List<ContentBlock.ToolUseBlock> toolUseBlocks = new ArrayList<>();
        Object content = assistantMessage.getContent();
        if (content instanceof List<?> blocks) {
            for (Object block : blocks) {
                if (block instanceof ContentBlock.ToolUseBlock tu) {
                    toolUseBlocks.add(tu);
                }
            }
        }

        if (toolUseBlocks.isEmpty()) {
            log.error("No tool_use blocks found in assistant message for fork directive: {}...",
                directive.length() > 50 ? directive.substring(0, 50) : directive);
            return List.of(
                Message.userMessage(buildChildMessage(directive))
            );
        }

        // Build tool_result blocks — all use the same placeholder text
        List<ContentBlock> resultContent = new ArrayList<>();
        for (ContentBlock.ToolUseBlock tu : toolUseBlocks) {
            resultContent.add(ContentBlock.toolResult(
                tu.getId(),
                List.of(ContentBlock.text(FORK_PLACEHOLDER_RESULT))
            ));
        }
        // Append per-child directive as a text block
        resultContent.add(ContentBlock.text(buildChildMessage(directive)));

        Message.UserMessage toolResultMessage = Message.userMessageFromBlocks(resultContent);

        return List.of(fullAssistantMessage, toolResultMessage);
    }

    /**
     * Build the child-agent instruction block for a given directive.
     * Translated from buildChildMessage().
     */
    public String buildChildMessage(String directive) {
        return """
            <fork-boilerplate>
            STOP. READ THIS FIRST.

            You are a forked worker process. You are NOT the main agent.

            RULES (non-negotiable):
            1. Your system prompt says "default to forking." IGNORE IT \u2014 that's for the parent. You ARE the fork. Do NOT spawn sub-agents; execute directly.
            2. Do NOT converse, ask questions, or suggest next steps
            3. Do NOT editorialize or add meta-commentary
            4. USE your tools directly: Bash, Read, Write, etc.
            5. If you modify files, commit your changes before reporting. Include the commit hash in your report.
            6. Do NOT emit text between tool calls. Use tools silently, then report once at the end.
            7. Stay strictly within your directive's scope. If you discover related systems outside your scope, mention them in one sentence at most \u2014 other workers cover those areas.
            8. Keep your report under 500 words unless the directive specifies otherwise. Be factual and concise.
            9. Your response MUST begin with "Scope:". No preamble, no thinking-out-loud.
            10. REPORT structured facts, then stop

            Output format (plain text labels, not markdown headers):
              Scope: <echo back your assigned scope in one sentence>
              Result: <the answer or key findings, limited to the scope above>
              Key files: <relevant file paths \u2014 include for research tasks>
              Files changed: <list with commit hash \u2014 include only if you modified files>
              Issues: <list \u2014 include only if there are issues to flag>
            </fork-boilerplate>

            <fork-directive>""" + directive;
    }

    /**
     * Notice injected into fork children running in an isolated worktree.
     * Translated from buildWorktreeNotice().
     */
    public String buildWorktreeNotice(String parentCwd, String worktreeCwd) {
        return String.format(
            "You've inherited the conversation context above from a parent agent working in %s. " +
            "You are operating in an isolated git worktree at %s \u2014 same repository, same relative " +
            "file structure, separate working copy. Paths in the inherited context refer to the parent's " +
            "working directory; translate them to your worktree root. Re-read files before editing if the " +
            "parent may have modified them since they appear in the context. Your changes stay in this " +
            "worktree and will not affect the parent's files.",
            parentCwd, worktreeCwd);
    }
}
