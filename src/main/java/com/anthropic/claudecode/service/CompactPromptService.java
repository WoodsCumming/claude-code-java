package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Compact prompt service.
 * Translated from src/services/compact/prompt.ts
 *
 * Provides all prompt strings and formatting utilities used by the compaction
 * pipeline (full compact, partial compact, summary formatting).
 */
@Slf4j
@Service
public class CompactPromptService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CompactPromptService.class);


    // =========================================================================
    // Direction enum for partial compact (mirrors PartialCompactDirection TS type)
    // =========================================================================

    /**
     * Direction for partial compact prompts.
     * Matches the {@code PartialCompactDirection} union type in message.ts.
     */
    public enum PartialCompactDirection {
        FROM, UP_TO
    }

    // =========================================================================
    // Static prompt fragments — translated verbatim from prompt.ts
    // =========================================================================

    /**
     * Aggressive no-tools preamble added before every compact prompt.
     * Prevents adaptive-thinking models from attempting tool calls on the
     * single summarisation turn.
     */
    private static final String NO_TOOLS_PREAMBLE =
            "CRITICAL: Respond with TEXT ONLY. Do NOT call any tools.\n\n" +
            "- Do NOT use Read, Bash, Grep, Glob, Edit, Write, or ANY other tool.\n" +
            "- You already have all the context you need in the conversation above.\n" +
            "- Tool calls will be REJECTED and will waste your only turn — you will fail the task.\n" +
            "- Your entire response must be plain text: an <analysis> block followed by a <summary> block.\n\n";

    private static final String NO_TOOLS_TRAILER =
            "\n\nREMINDER: Do NOT call any tools. Respond with plain text only — " +
            "an <analysis> block followed by a <summary> block. " +
            "Tool calls will be rejected and you will fail the task.";

    private static final String DETAILED_ANALYSIS_INSTRUCTION_BASE =
            "Before providing your final summary, wrap your analysis in <analysis> tags to organize " +
            "your thoughts and ensure you've covered all necessary points. In your analysis process:\n\n" +
            "1. Chronologically analyze each message and section of the conversation. For each section " +
            "thoroughly identify:\n" +
            "   - The user's explicit requests and intents\n" +
            "   - Your approach to addressing the user's requests\n" +
            "   - Key decisions, technical concepts and code patterns\n" +
            "   - Specific details like:\n" +
            "     - file names\n" +
            "     - full code snippets\n" +
            "     - function signatures\n" +
            "     - file edits\n" +
            "   - Errors that you ran into and how you fixed them\n" +
            "   - Pay special attention to specific user feedback that you received, especially if the " +
            "user told you to do something differently.\n" +
            "2. Double-check for technical accuracy and completeness, addressing each required element " +
            "thoroughly.";

    private static final String DETAILED_ANALYSIS_INSTRUCTION_PARTIAL =
            "Before providing your final summary, wrap your analysis in <analysis> tags to organize " +
            "your thoughts and ensure you've covered all necessary points. In your analysis process:\n\n" +
            "1. Analyze the recent messages chronologically. For each section thoroughly identify:\n" +
            "   - The user's explicit requests and intents\n" +
            "   - Your approach to addressing the user's requests\n" +
            "   - Key decisions, technical concepts and code patterns\n" +
            "   - Specific details like:\n" +
            "     - file names\n" +
            "     - full code snippets\n" +
            "     - function signatures\n" +
            "     - file edits\n" +
            "   - Errors that you ran into and how you fixed them\n" +
            "   - Pay special attention to specific user feedback that you received, especially if the " +
            "user told you to do something differently.\n" +
            "2. Double-check for technical accuracy and completeness, addressing each required element " +
            "thoroughly.";

    private static final String BASE_COMPACT_PROMPT =
            "Your task is to create a detailed summary of the conversation so far, paying close " +
            "attention to the user's explicit requests and your previous actions.\n" +
            "This summary should be thorough in capturing technical details, code patterns, and " +
            "architectural decisions that would be essential for continuing development work without " +
            "losing context.\n\n" +
            DETAILED_ANALYSIS_INSTRUCTION_BASE + "\n\n" +
            "Your summary should include the following sections:\n\n" +
            "1. Primary Request and Intent: Capture all of the user's explicit requests and intents in detail\n" +
            "2. Key Technical Concepts: List all important technical concepts, technologies, and frameworks discussed.\n" +
            "3. Files and Code Sections: Enumerate specific files and code sections examined, modified, or created. " +
            "Pay special attention to the most recent messages and include full code snippets where applicable and " +
            "include a summary of why this file read or edit is important.\n" +
            "4. Errors and fixes: List all errors that you ran into, and how you fixed them. Pay special attention " +
            "to specific user feedback that you received, especially if the user told you to do something differently.\n" +
            "5. Problem Solving: Document problems solved and any ongoing troubleshooting efforts.\n" +
            "6. All user messages: List ALL user messages that are not tool results. These are critical for " +
            "understanding the users' feedback and changing intent.\n" +
            "7. Pending Tasks: Outline any pending tasks that you have explicitly been asked to work on.\n" +
            "8. Current Work: Describe in detail precisely what was being worked on immediately before this summary " +
            "request, paying special attention to the most recent messages from both user and assistant. Include file " +
            "names and code snippets where applicable.\n" +
            "9. Optional Next Step: List the next step that you will take that is related to the most recent work " +
            "you were doing. IMPORTANT: ensure that this step is DIRECTLY in line with the user's most recent " +
            "explicit requests, and the task you were working on immediately before this summary request. If your " +
            "last task was concluded, then only list next steps if they are explicitly in line with the users request. " +
            "Do not start on tangential requests or really old requests that were already completed without confirming " +
            "with the user first.\n" +
            "                       If there is a next step, include direct quotes from the most recent conversation " +
            "showing exactly what task you were working on and where you left off. This should be verbatim to ensure " +
            "there's no drift in task interpretation.\n\n" +
            "Here's an example of how your output should be structured:\n\n" +
            "<example>\n" +
            "<analysis>\n" +
            "[Your thought process, ensuring all points are covered thoroughly and accurately]\n" +
            "</analysis>\n\n" +
            "<summary>\n" +
            "1. Primary Request and Intent:\n" +
            "   [Detailed description]\n\n" +
            "2. Key Technical Concepts:\n" +
            "   - [Concept 1]\n" +
            "   - [Concept 2]\n" +
            "   - [...]\n\n" +
            "3. Files and Code Sections:\n" +
            "   - [File Name 1]\n" +
            "      - [Summary of why this file is important]\n" +
            "      - [Summary of the changes made to this file, if any]\n" +
            "      - [Important Code Snippet]\n" +
            "   - [File Name 2]\n" +
            "      - [Important Code Snippet]\n" +
            "   - [...]\n\n" +
            "4. Errors and fixes:\n" +
            "    - [Detailed description of error 1]:\n" +
            "      - [How you fixed the error]\n" +
            "      - [User feedback on the error if any]\n" +
            "    - [...]\n\n" +
            "5. Problem Solving:\n" +
            "   [Description of solved problems and ongoing troubleshooting]\n\n" +
            "6. All user messages: \n" +
            "    - [Detailed non tool use user message]\n" +
            "    - [...]\n\n" +
            "7. Pending Tasks:\n" +
            "   - [Task 1]\n" +
            "   - [Task 2]\n" +
            "   - [...]\n\n" +
            "8. Current Work:\n" +
            "   [Precise description of current work]\n\n" +
            "9. Optional Next Step:\n" +
            "   [Optional Next step to take]\n\n" +
            "</summary>\n" +
            "</example>\n\n" +
            "Please provide your summary based on the conversation so far, following this structure and ensuring " +
            "precision and thoroughness in your response. \n\n" +
            "There may be additional summarization instructions provided in the included context. If so, remember " +
            "to follow these instructions when creating the above summary. Examples of instructions include:\n" +
            "<example>\n" +
            "## Compact Instructions\n" +
            "When summarizing the conversation focus on typescript code changes and also remember the mistakes you " +
            "made and how you fixed them.\n" +
            "</example>\n\n" +
            "<example>\n" +
            "# Summary instructions\n" +
            "When you are using compact - please focus on test output and code changes. Include file reads verbatim.\n" +
            "</example>\n";

    private static final String PARTIAL_COMPACT_PROMPT =
            "Your task is to create a detailed summary of the RECENT portion of the conversation — " +
            "the messages that follow earlier retained context. The earlier messages are being kept intact " +
            "and do NOT need to be summarized. Focus your summary on what was discussed, learned, and " +
            "accomplished in the recent messages only.\n\n" +
            DETAILED_ANALYSIS_INSTRUCTION_PARTIAL + "\n\n" +
            "Your summary should include the following sections:\n\n" +
            "1. Primary Request and Intent: Capture the user's explicit requests and intents from the recent messages\n" +
            "2. Key Technical Concepts: List important technical concepts, technologies, and frameworks discussed recently.\n" +
            "3. Files and Code Sections: Enumerate specific files and code sections examined, modified, or created. " +
            "Include full code snippets where applicable and include a summary of why this file read or edit is important.\n" +
            "4. Errors and fixes: List errors encountered and how they were fixed.\n" +
            "5. Problem Solving: Document problems solved and any ongoing troubleshooting efforts.\n" +
            "6. All user messages: List ALL user messages from the recent portion that are not tool results.\n" +
            "7. Pending Tasks: Outline any pending tasks from the recent messages.\n" +
            "8. Current Work: Describe precisely what was being worked on immediately before this summary request.\n" +
            "9. Optional Next Step: List the next step related to the most recent work. Include direct quotes from " +
            "the most recent conversation.\n\n" +
            "Please provide your summary based on the RECENT messages only (after the retained earlier context), " +
            "following this structure and ensuring precision and thoroughness in your response.\n";

    private static final String PARTIAL_COMPACT_UP_TO_PROMPT =
            "Your task is to create a detailed summary of this conversation. This summary will be placed at " +
            "the start of a continuing session; newer messages that build on this context will follow after " +
            "your summary (you do not see them here). Summarize thoroughly so that someone reading only your " +
            "summary and then the newer messages can fully understand what happened and continue the work.\n\n" +
            DETAILED_ANALYSIS_INSTRUCTION_BASE + "\n\n" +
            "Your summary should include the following sections:\n\n" +
            "1. Primary Request and Intent: Capture the user's explicit requests and intents in detail\n" +
            "2. Key Technical Concepts: List important technical concepts, technologies, and frameworks discussed.\n" +
            "3. Files and Code Sections: Enumerate specific files and code sections examined, modified, or created. " +
            "Include full code snippets where applicable and include a summary of why this file read or edit is important.\n" +
            "4. Errors and fixes: List errors encountered and how they were fixed.\n" +
            "5. Problem Solving: Document problems solved and any ongoing troubleshooting efforts.\n" +
            "6. All user messages: List ALL user messages that are not tool results.\n" +
            "7. Pending Tasks: Outline any pending tasks.\n" +
            "8. Work Completed: Describe what was accomplished by the end of this portion.\n" +
            "9. Context for Continuing Work: Summarize any context, decisions, or state that would be needed " +
            "to understand and continue the work in subsequent messages.\n\n" +
            "Please provide your summary following this structure, ensuring precision and thoroughness in your response.\n";

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Get the full base compact prompt (optionally with custom instructions).
     * Translated from getCompactPrompt() in prompt.ts
     *
     * @param customInstructions Optional additional instructions; appended after the base prompt.
     * @return Complete prompt string ready to be sent to the model.
     */
    public String getCompactPrompt(String customInstructions) {
        StringBuilder sb = new StringBuilder(NO_TOOLS_PREAMBLE).append(BASE_COMPACT_PROMPT);
        appendCustomInstructions(sb, customInstructions);
        sb.append(NO_TOOLS_TRAILER);
        return sb.toString();
    }

    /**
     * Convenience overload with no custom instructions.
     */
    public String getCompactPrompt() {
        return getCompactPrompt(null);
    }

    /**
     * Get the partial compact prompt for summarizing a recent message segment.
     * Translated from getPartialCompactPrompt() in prompt.ts
     *
     * @param customInstructions Optional additional instructions.
     * @param direction          {@code FROM} summarises recent messages only;
     *                           {@code UP_TO} summarises an earlier prefix that will be
     *                           followed by preserved newer messages.
     * @return Complete partial-compact prompt string.
     */
    public String getPartialCompactPrompt(String customInstructions, PartialCompactDirection direction) {
        if (direction == null) {
            direction = PartialCompactDirection.FROM;
        }
        String template = (direction == PartialCompactDirection.UP_TO)
                ? PARTIAL_COMPACT_UP_TO_PROMPT
                : PARTIAL_COMPACT_PROMPT;

        StringBuilder sb = new StringBuilder(NO_TOOLS_PREAMBLE).append(template);
        appendCustomInstructions(sb, customInstructions);
        sb.append(NO_TOOLS_TRAILER);
        return sb.toString();
    }

    /**
     * Convenience overload with default direction ({@code FROM}) and no custom instructions.
     */
    public String getPartialCompactPrompt() {
        return getPartialCompactPrompt(null, PartialCompactDirection.FROM);
    }

    /**
     * Format the compact summary for display: strip the {@code <analysis>} scratchpad
     * and replace {@code <summary>} XML tags with a readable section header.
     * Translated from formatCompactSummary() in prompt.ts
     *
     * @param summary Raw summary string potentially containing {@code <analysis>} and
     *                {@code <summary>} XML tags.
     * @return Formatted summary with analysis stripped and summary tags replaced.
     */
    public String formatCompactSummary(String summary) {
        if (summary == null || summary.isEmpty()) {
            return "";
        }

        String result = summary;

        // Strip analysis section — it's a drafting scratchpad that improves summary
        // quality but has no informational value once the summary is written.
        result = result.replaceAll("(?s)<analysis>[\\s\\S]*?</analysis>", "");

        // Extract and format summary section
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?s)<summary>([\\s\\S]*?)</summary>")
                .matcher(result);
        if (m.find()) {
            String content = m.group(1) != null ? m.group(1).strip() : "";
            result = m.replaceFirst("Summary:\n" + java.util.regex.Matcher.quoteReplacement(content));
        }

        // Clean up extra whitespace between sections
        result = result.replaceAll("\n{3,}", "\n\n");

        return result.strip();
    }

    /**
     * Build the user-facing summary message that is prepended to the post-compact conversation.
     * Translated from getCompactUserSummaryMessage() in prompt.ts
     *
     * @param summary                  Raw summary string (may contain {@code <analysis>}
     *                                 and {@code <summary>} tags).
     * @param suppressFollowUpQuestions When {@code true}, instruct the model to resume work
     *                                  without asking the user any questions.
     * @param transcriptPath           Optional path to the full transcript for reference.
     * @param recentMessagesPreserved  When {@code true}, note that recent messages are kept verbatim.
     * @return Formatted user-facing message string.
     */
    public String getCompactUserSummaryMessage(
            String summary,
            boolean suppressFollowUpQuestions,
            String transcriptPath,
            boolean recentMessagesPreserved) {

        String formattedSummary = formatCompactSummary(summary);

        StringBuilder sb = new StringBuilder();
        sb.append("This session is being continued from a previous conversation that ran out of context. " +
                  "The summary below covers the earlier portion of the conversation.\n\n");
        sb.append(formattedSummary);

        if (transcriptPath != null && !transcriptPath.isEmpty()) {
            sb.append("\n\nIf you need specific details from before compaction (like exact code snippets, " +
                      "error messages, or content you generated), read the full transcript at: ")
              .append(transcriptPath);
        }

        if (recentMessagesPreserved) {
            sb.append("\n\nRecent messages are preserved verbatim.");
        }

        if (suppressFollowUpQuestions) {
            sb.append("\nContinue the conversation from where it left off without asking the user any " +
                      "further questions. Resume directly — do not acknowledge the summary, do not recap " +
                      "what was happening, do not preface with \"I'll continue\" or similar. Pick up the " +
                      "last task as if the break never happened.");
        }

        return sb.toString();
    }

    /**
     * Convenience overload with no transcript path and no preserved-messages note.
     */
    public String getCompactUserSummaryMessage(String summary, boolean suppressFollowUpQuestions) {
        return getCompactUserSummaryMessage(summary, suppressFollowUpQuestions, null, false);
    }

    /**
     * Get the compact system prompt (kept for backward-compat with callers).
     */
    public String getCompactSystemPrompt() {
        return "You are a conversation summarizer. Your task is to create a concise but comprehensive " +
               "summary of the conversation that preserves all important technical details.";
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void appendCustomInstructions(StringBuilder sb, String customInstructions) {
        if (customInstructions != null && !customInstructions.isBlank()) {
            sb.append("\n\nAdditional Instructions:\n").append(customInstructions);
        }
    }
}
