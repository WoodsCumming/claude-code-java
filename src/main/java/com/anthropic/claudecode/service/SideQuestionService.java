package com.anthropic.claudecode.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Side-question ("/btw") feature — lets the user ask a quick question without
 * interrupting the main agent context.
 *
 * Uses a forked agent that shares the parent's prompt cache but is capped at a
 * single turn and has no tool access.
 *
 * Translated from src/utils/sideQuestion.ts
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SideQuestionService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SideQuestionService.class);


    // Pattern that detects "/btw" at the very start of the input (case-insensitive)
    private static final Pattern BTW_PATTERN =
            Pattern.compile("^/btw\\b", Pattern.CASE_INSENSITIVE);

    // -----------------------------------------------------------------------
    // Dependencies
    // -----------------------------------------------------------------------

    private final ForkedAgentService forkedAgentService;

    // -----------------------------------------------------------------------
    // Domain types
    // -----------------------------------------------------------------------

    /**
     * Position of a "/btw" trigger keyword found in the user's input.
     */
    public record BtwTriggerPosition(String word, int start, int end) {}

    /**
     * Result of a side-question query.
     * Translated from the TypeScript type {@code SideQuestionResult}.
     */
    public record SideQuestionResult(String response, UsageSummary usage) {}

    /**
     * Token-usage summary forwarded from the forked agent.
     */
    public record UsageSummary(int inputTokens, int outputTokens, int cacheReadTokens) {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Find all "/btw" keyword positions in {@code text} for UI highlighting.
     * Translated from {@code findBtwTriggerPositions()} in sideQuestion.ts
     *
     * @param text raw user input
     * @return list of positions (may be empty)
     */
    public List<BtwTriggerPosition> findBtwTriggerPositions(String text) {
        List<BtwTriggerPosition> positions = new ArrayList<>();
        if (text == null || text.isBlank()) return positions;

        Matcher m = BTW_PATTERN.matcher(text);
        while (m.find()) {
            positions.add(new BtwTriggerPosition(m.group(), m.start(), m.end()));
        }
        return positions;
    }

    /**
     * Run a side question through a single-turn forked agent.
     *
     * <p>Wraps the question with a system-reminder that instructs the agent:
     * <ul>
     *   <li>No tools available — answer from context only</li>
     *   <li>Single turn — no follow-up</li>
     *   <li>The main agent is NOT interrupted</li>
     * </ul>
     *
     * Translated from {@code runSideQuestion()} in sideQuestion.ts
     *
     * @param question the user's side question (after stripping the "/btw" prefix)
     * @return CompletableFuture with the agent's text response and token usage
     */
    /** Convenience alias that returns the response string directly. */
    public java.util.concurrent.CompletableFuture<String> askSideQuestion(String question) {
        return runSideQuestion(question).thenApply(SideQuestionResult::response);
    }

    public CompletableFuture<SideQuestionResult> runSideQuestion(String question) {
        String wrappedQuestion = buildWrappedQuestion(question);

        return forkedAgentService.runForkedAgent(
                ForkedAgentService.ForkedAgentParams.builder()
                        .initialMessage(wrappedQuestion)
                        .querySource("side_question")
                        .tools(List.of())   // no tools — side questions are answer-only
                        .build(),
                null)   // no parent ToolUseContext needed for side questions
                .thenApply(result -> new SideQuestionResult(
                        extractSideQuestionResponse(result.getMessages()),
                        new UsageSummary(0, 0, 0)));
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Wrap the raw question in a system-reminder that constrains the forked agent.
     */
    private static String buildWrappedQuestion(String question) {
        return """
                <system-reminder>This is a side question from the user. You must answer this question directly in a single response.

                IMPORTANT CONTEXT:
                - You are a separate, lightweight agent spawned to answer this one question
                - The main agent is NOT interrupted - it continues working independently in the background
                - You share the conversation context but are a completely separate instance
                - Do NOT reference being interrupted or what you were "previously doing" - that framing is incorrect

                CRITICAL CONSTRAINTS:
                - You have NO tools available - you cannot read files, run commands, search, or take any actions
                - This is a one-off response - there will be no follow-up turns
                - You can ONLY provide information based on what you already know from the conversation context
                - NEVER say things like "Let me try...", "I'll now...", "Let me check...", or promise to take any action
                - If you don't know the answer, say so - do not offer to look it up or investigate

                Simply answer the question with the information you have.</system-reminder>

                """ + question;
    }

    /**
     * Extract a display string from the messages returned by the forked agent.
     *
     * <p>The forked agent may yield multiple messages — one per content block —
     * when thinking is enabled (thinking block first, text block second).  We
     * therefore scan all assistant messages and concatenate every text block
     * rather than stopping at the first assistant message.
     *
     * Translated from {@code extractSideQuestionResponse()} in sideQuestion.ts
     */
    private static String extractSideQuestionResponse(
            List<com.anthropic.claudecode.model.Message> messages) {

        // Collect all assistant text blocks across all messages
        StringBuilder text = new StringBuilder();
        boolean toolUseAttempted = false;
        String toolUseName = null;

        for (com.anthropic.claudecode.model.Message message : messages) {
            if (!(message instanceof com.anthropic.claudecode.model.Message.AssistantMessage am)) continue;
            if (am.getContent() == null) continue;

            for (com.anthropic.claudecode.model.ContentBlock block : am.getContent()) {
                if (block instanceof com.anthropic.claudecode.model.ContentBlock.TextBlock tb) {
                    String t = tb.getText();
                    if (t != null && !t.isBlank()) {
                        if (!text.isEmpty()) text.append("\n\n");
                        text.append(t.strip());
                    }
                } else if (block instanceof com.anthropic.claudecode.model.ContentBlock.ToolUseBlock tu) {
                    toolUseAttempted = true;
                    toolUseName = tu.getName();
                }
            }
        }

        if (!text.isEmpty()) return text.toString();

        if (toolUseAttempted) {
            String name = toolUseName != null ? toolUseName : "a tool";
            return "(The model tried to call " + name
                    + " instead of answering directly. "
                    + "Try rephrasing or ask in the main conversation.)";
        }

        return null;
    }
}
