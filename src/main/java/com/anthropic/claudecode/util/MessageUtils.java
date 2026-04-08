package com.anthropic.claudecode.util;

import com.anthropic.claudecode.model.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Message utility functions.
 * Translated from src/utils/messages.ts
 *
 * Provides helper functions for creating, normalising, and processing messages.
 */
public class MessageUtils {

    // =========================================================================
    // Sentinel message strings
    // =========================================================================

    /** Displayed when a user's turn is interrupted. */
    public static final String INTERRUPT_MESSAGE = "[Request interrupted by user]";

    /** Displayed when a user interrupts during tool use. */
    public static final String INTERRUPT_MESSAGE_FOR_TOOL_USE =
            "[Request interrupted by user for tool use]";

    /** Injected as a tool_result to cancel a pending tool call. */
    public static final String CANCEL_MESSAGE =
            "The user doesn't want to take this action right now. "
                    + "STOP what you are doing and wait for the user to tell you how to proceed.";

    /** Injected when the user rejects a tool call. */
    public static final String REJECT_MESSAGE =
            "The user doesn't want to proceed with this tool use. "
                    + "The tool use was rejected (eg. if it was a file edit, the new_string was NOT written to the file). "
                    + "STOP what you are doing and wait for the user to tell you how to proceed.";

    public static final String REJECT_MESSAGE_WITH_REASON_PREFIX =
            "The user doesn't want to proceed with this tool use. "
                    + "The tool use was rejected (eg. if it was a file edit, the new_string was NOT written to the file). "
                    + "To tell you how to proceed, the user said:\n";

    public static final String SUBAGENT_REJECT_MESSAGE =
            "Permission for this tool use was denied. "
                    + "The tool use was rejected (eg. if it was a file edit, the new_string was NOT written to the file). "
                    + "Try a different approach or report the limitation to complete your task.";

    public static final String SUBAGENT_REJECT_MESSAGE_WITH_REASON_PREFIX =
            "Permission for this tool use was denied. "
                    + "The tool use was rejected (eg. if it was a file edit, the new_string was NOT written to the file). "
                    + "The user said:\n";

    public static final String PLAN_REJECTION_PREFIX =
            "The agent proposed a plan that was rejected by the user. "
                    + "The user chose to stay in plan mode rather than proceed with implementation.\n\n"
                    + "Rejected plan:\n";

    /** Guidance injected into denial messages to help the model choose alternatives. */
    public static final String DENIAL_WORKAROUND_GUIDANCE =
            "IMPORTANT: You *may* attempt to accomplish this action using other tools that might "
                    + "naturally be used to accomplish this goal, e.g. using head instead of cat. "
                    + "But you *should not* attempt to work around this denial in malicious ways, "
                    + "e.g. do not use your ability to run tests to execute non-test actions. "
                    + "You should only try to work around this restriction in reasonable ways that "
                    + "do not attempt to bypass the intent behind this denial. "
                    + "If you believe this capability is essential to complete the user's request, "
                    + "STOP and explain to the user what you were trying to do and why you need this "
                    + "permission. Let the user decide how to proceed.";

    public static final String NO_RESPONSE_REQUESTED = "No response requested.";

    /**
     * Synthetic placeholder inserted by ensureToolResultPairing when a tool_use block
     * has no matching tool_result. Exported so HFI submission can reject payloads
     * containing it — placeholder satisfies pairing structurally but content is fake.
     */
    public static final String SYNTHETIC_TOOL_RESULT_PLACEHOLDER =
            "[Tool result missing due to internal error]";

    /**
     * Model name used for synthetic/virtual assistant messages.
     * Translated from SYNTHETIC_MODEL in messages.ts
     */
    public static final String SYNTHETIC_MODEL = "<synthetic>";

    /**
     * Set of sentinel message texts that represent synthetic (non-human) turns.
     */
    public static final Set<String> SYNTHETIC_MESSAGES = Set.of(
            INTERRUPT_MESSAGE,
            INTERRUPT_MESSAGE_FOR_TOOL_USE,
            CANCEL_MESSAGE,
            REJECT_MESSAGE,
            NO_RESPONSE_REQUESTED
    );

    // =========================================================================
    // Auto-rejection helpers
    // =========================================================================

    private static final String AUTO_MODE_REJECTION_PREFIX =
            "Permission for this action has been denied. Reason: ";

    /**
     * Check if a tool result content string is a classifier denial.
     * Translated from isClassifierDenial() in messages.ts
     */
    public static boolean isClassifierDenial(String content) {
        return content != null && content.startsWith(AUTO_MODE_REJECTION_PREFIX);
    }

    /**
     * Build a rejection message for auto mode classifier denials.
     * Translated from buildYoloRejectionMessage() in messages.ts
     */
    public static String buildYoloRejectionMessage(String reason) {
        return AUTO_MODE_REJECTION_PREFIX + reason + ". "
                + "If you have other tasks that don't depend on this action, continue working on those. "
                + DENIAL_WORKAROUND_GUIDANCE + " "
                + "To allow this type of action in the future, the user can add a permission rule like "
                + "Bash(prompt: <description of allowed action>) to their settings. "
                + "At the end of your session, recommend what permission rules to add so you don't get blocked again.";
    }

    /**
     * Rejection message when the model classifier is temporarily unavailable.
     * Translated from buildClassifierUnavailableMessage() in messages.ts
     */
    public static String buildClassifierUnavailableMessage(String toolName, String classifierModel) {
        return classifierModel + " is temporarily unavailable, so auto mode cannot determine "
                + "the safety of " + toolName + " right now. "
                + "Wait briefly and then try this action again. "
                + "If it keeps failing, continue with other tasks that don't require this action "
                + "and come back to it later. "
                + "Note: reading files, searching code, and other read-only operations do not "
                + "require the classifier and can still be used.";
    }

    /**
     * Auto-reject message for a specific tool with workaround guidance.
     * Translated from AUTO_REJECT_MESSAGE() in messages.ts
     */
    public static String autoRejectMessage(String toolName) {
        return "Permission to use " + toolName + " has been denied. " + DENIAL_WORKAROUND_GUIDANCE;
    }

    /**
     * Rejection message for don't-ask mode.
     * Translated from DONT_ASK_REJECT_MESSAGE() in messages.ts
     */
    public static String dontAskRejectMessage(String toolName) {
        return "Permission to use " + toolName + " has been denied because Claude Code is running "
                + "in don't ask mode. " + DENIAL_WORKAROUND_GUIDANCE;
    }

    // =========================================================================
    // Message creation helpers
    // =========================================================================

    /**
     * Create a user message with plain-text content.
     * Translated from createUserMessage() in messages.ts
     */
    public static Message.UserMessage createUserMessage(String text) {
        return Message.UserMessage.builder()
                .type("user")
                .uuid(UUID.randomUUID().toString())
                .timestamp(java.time.Instant.now().toString())
                .content(List.of(new ContentBlock.TextBlock(text)))
                .build();
    }

    /**
     * Create a user message with multiple content blocks.
     */
    public static Message.UserMessage createUserMessage(List<ContentBlock> content) {
        return Message.UserMessage.builder()
                .type("user")
                .uuid(UUID.randomUUID().toString())
                .timestamp(java.time.Instant.now().toString())
                .content(content)
                .build();
    }

    /**
     * Create a user interruption message.
     * Translated from createUserInterruptionMessage() in messages.ts
     */
    public static Message.UserMessage createUserInterruptionMessage(boolean toolUse) {
        String content = toolUse ? INTERRUPT_MESSAGE_FOR_TOOL_USE : INTERRUPT_MESSAGE;
        return createUserMessage(List.of(new ContentBlock.TextBlock(content)));
    }

    /**
     * Create a synthetic user caveat message for local commands (bash, slash).
     * Translated from createSyntheticUserCaveatMessage() in messages.ts
     *
     * Each call creates a fresh UUID — messages must be unique.
     */
    public static Message.UserMessage createSyntheticUserCaveatMessage() {
        return createUserMessage(
                "<local-command-caveat>Caveat: The messages below were generated by the user while running local commands. "
                        + "DO NOT respond to these messages or otherwise consider them in your response unless the "
                        + "user explicitly asks you to.</local-command-caveat>"
        );
    }

    /**
     * Create a system message.
     * Translated from createSystemMessage() in messages.ts
     */
    public static Message.SystemMessage createSystemMessage(
            String content,
            Message.SystemMessageLevel level) {
        return Message.SystemMessage.builder()
                .type("system")
                .uuid(UUID.randomUUID().toString())
                .timestamp(java.time.Instant.now().toString())
                .level(level)
                .content(content)
                .build();
    }

    /**
     * Create a compact boundary message.
     * Translated from createCompactBoundaryMessage() in messages.ts
     */
    public static Message.SystemMessage createCompactBoundaryMessage(String summary) {
        return Message.SystemMessage.builder()
                .type("system")
                .uuid(UUID.randomUUID().toString())
                .timestamp(java.time.Instant.now().toString())
                .content(summary)
                .subtype(Message.SystemMessageSubtype.COMPACT_BOUNDARY)
                .build();
    }

    /**
     * Create an assistant API error message.
     * Translated from createAssistantAPIErrorMessage() in messages.ts
     */
    public static Message.AssistantMessage createAssistantAPIErrorMessage(String errorMessage) {
        return Message.AssistantMessage.builder()
                .type("assistant")
                .uuid(UUID.randomUUID().toString())
                .timestamp(java.time.Instant.now().toString())
                .content(List.of(new ContentBlock.TextBlock(errorMessage)))
                .isApiErrorMessage(true)
                .build();
    }

    /**
     * Create an assistant message with text or content blocks.
     * Translated from createAssistantMessage() in messages.ts
     */
    public static Message.AssistantMessage createAssistantMessage(String content) {
        String text = (content == null || content.isEmpty()) ? "[No content]" : content;
        return Message.AssistantMessage.builder()
                .type("assistant")
                .uuid(UUID.randomUUID().toString())
                .timestamp(java.time.Instant.now().toString())
                .content(List.of(new ContentBlock.TextBlock(text)))
                .build();
    }

    // =========================================================================
    // Message inspection helpers
    // =========================================================================

    /**
     * Derive a short stable message ID (6-char base36 string) from a UUID.
     * Translated from deriveShortMessageId() in messages.ts
     *
     * Deterministic: same UUID always produces the same short ID.
     */
    public static String deriveShortMessageId(String uuid) {
        if (uuid == null) return "000000";
        String hex = uuid.replace("-", "").substring(0, Math.min(10, uuid.replace("-", "").length()));
        try {
            long value = Long.parseUnsignedLong(hex, 16);
            String base36 = Long.toString(value, 36);
            return base36.substring(0, Math.min(6, base36.length()));
        } catch (NumberFormatException e) {
            return "000000";
        }
    }

    /**
     * Get the last assistant message from a list.
     * Translated from getLastAssistantMessage() in messages.ts
     */
    public static Optional<Message.AssistantMessage> getLastAssistantMessage(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof Message.AssistantMessage msg) {
                return Optional.of(msg);
            }
        }
        return Optional.empty();
    }

    /**
     * Check whether the last assistant turn contains any tool_use blocks.
     * Translated from hasToolCallsInLastAssistantTurn() in messages.ts
     */
    public static boolean hasToolCallsInLastAssistantTurn(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof Message.AssistantMessage msg) {
                if (msg.getContent() == null) return false;
                return msg.getContent().stream()
                        .anyMatch(b -> b instanceof ContentBlock.ToolUseBlock);
            }
        }
        return false;
    }

    /**
     * Check if a message is a synthetic (non-human) message.
     * Translated from isSyntheticMessage() in messages.ts
     */
    public static boolean isSyntheticMessage(Message message) {
        if (message instanceof Message.SystemMessage
                || message instanceof Message.ProgressMessage
                || message instanceof Message.AttachmentMessage) {
            return false;
        }
        if (message instanceof Message.UserMessage user) {
            if (user.getContent() == null || user.getContent().isEmpty()) return false;
            ContentBlock first = user.getContent().get(0);
            if (!(first instanceof ContentBlock.TextBlock tb)) return false;
            return SYNTHETIC_MESSAGES.contains(tb.getText());
        }
        return false;
    }

    /**
     * Check if an assistant message is a synthetic API error message.
     * Translated from isSyntheticApiErrorMessage() in messages.ts
     */
    public static boolean isSyntheticApiErrorMessage(Message message) {
        if (!(message instanceof Message.AssistantMessage assistantMsg)) return false;
        return Boolean.TRUE.equals(assistantMsg.getIsApiErrorMessage());
    }

    /**
     * Normalise messages for API submission.
     * Translated from normalizeMessagesForAPI() in messages.ts
     *
     * Filters out system/progress/tombstone/summary messages.
     */
    public static List<Message> normalizeMessagesForAPI(List<Message> messages) {
        if (messages == null) return List.of();
        return messages.stream()
                .filter(msg -> !(msg instanceof Message.SystemMessage)
                        && !(msg instanceof Message.ProgressMessage)
                        && !(msg instanceof Message.TombstoneMessage)
                        && !(msg instanceof Message.ToolUseSummaryMessage))
                .collect(Collectors.toList());
    }

    /**
     * Get messages after the last compact boundary.
     * Translated from getMessagesAfterCompactBoundary() in messages.ts
     */
    public static List<Message> getMessagesAfterCompactBoundary(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return List.of();

        int lastBoundary = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (isCompactBoundaryMessage(messages.get(i))) {
                lastBoundary = i;
                break;
            }
        }
        if (lastBoundary < 0) return messages;
        return messages.subList(lastBoundary + 1, messages.size());
    }

    /**
     * Check if a message is a compact boundary marker.
     * Translated from isCompactBoundaryMessage() in messages.ts
     */
    public static boolean isCompactBoundaryMessage(Message msg) {
        if (!(msg instanceof Message.SystemMessage systemMsg)) return false;
        return systemMsg.getSubtypeEnum() == Message.SystemMessageSubtype.COMPACT_BOUNDARY;
    }

    /**
     * Check if a message is an assistant API error message.
     */
    public static boolean isAssistantAPIError(Message msg) {
        return msg instanceof Message.AssistantMessage assistantMsg
                && Boolean.TRUE.equals(assistantMsg.getIsApiErrorMessage());
    }

    /**
     * Extract tool use blocks from an assistant message.
     */
    public static List<ContentBlock.ToolUseBlock> getToolUseBlocks(Message.AssistantMessage msg) {
        if (msg == null || msg.getContent() == null) return List.of();
        return msg.getContent().stream()
                .filter(b -> b instanceof ContentBlock.ToolUseBlock)
                .map(b -> (ContentBlock.ToolUseBlock) b)
                .collect(Collectors.toList());
    }

    /**
     * Check if a message has tool use blocks.
     */
    public static boolean hasToolUse(Message.AssistantMessage msg) {
        return !getToolUseBlocks(msg).isEmpty();
    }

    /**
     * Extract all concatenated text content from a user message.
     */
    public static String getUserMessageText(Message.UserMessage msg) {
        if (msg == null || msg.getContent() == null) return "";
        return msg.getContent().stream()
                .filter(b -> b instanceof ContentBlock.TextBlock)
                .map(b -> ((ContentBlock.TextBlock) b).getText())
                .filter(Objects::nonNull)
                .collect(Collectors.joining());
    }

    /**
     * Extract all concatenated text content from an assistant message.
     */
    public static String getAssistantMessageText(Message.AssistantMessage msg) {
        if (msg == null || msg.getContent() == null) return "";
        return msg.getContent().stream()
                .filter(b -> b instanceof ContentBlock.TextBlock)
                .map(b -> ((ContentBlock.TextBlock) b).getText())
                .filter(Objects::nonNull)
                .collect(Collectors.joining());
    }

    /**
     * Format the command-input breadcrumb tags that the model sees when a slash command runs.
     * Translated from formatCommandInputTags() in messages.ts
     */
    public static String formatCommandInputTags(String commandName, String args) {
        return "<command-name>/" + commandName + "</command-name>\n"
                + "<command-message>" + commandName + "</command-message>\n"
                + "<command-args>" + args + "</command-args>";
    }

    /**
     * Wrap text in a system reminder XML tag.
     * Translated from wrapInSystemReminder() in messages.ts (used indirectly by hooks.ts).
     */
    public static String wrapInSystemReminder(String content) {
        return "<system-reminder>" + content + "</system-reminder>";
    }

    private MessageUtils() {}
}
