package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.Message;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Conversation recovery service for restoring sessions from persisted transcripts.
 * Translated from src/utils/conversationRecovery.ts
 *
 * Handles loading, deserialising and normalising conversation history so that a
 * Claude Code session can be resumed cleanly. Key behaviours mirrored from
 * the TypeScript source:
 *
 *   - Strip invalid permissionMode values from deserialized user messages.
 *   - Filter unresolved tool_uses (filterUnresolvedToolUses).
 *   - Filter orphaned thinking-only assistant messages (filterOrphanedThinkingOnlyMessages).
 *   - Filter whitespace-only assistant messages (filterWhitespaceOnlyAssistantMessages).
 *   - Detect turn interruption (none / interrupted_prompt / interrupted_turn).
 *   - Append a synthetic "Continue from where you left off." message when
 *     the session was interrupted mid-turn.
 *   - Append a synthetic NO_RESPONSE_REQUESTED assistant sentinel when the
 *     last relevant message is from the user.
 */
@Slf4j
@Service
public class ConversationRecoveryService {



    // -------------------------------------------------------------------------
    // Types
    // -------------------------------------------------------------------------

    /**
     * Mirrors TurnInterruptionState from conversationRecovery.ts.
     * Sealed so callers must handle all variants.
     */
    public sealed interface TurnInterruptionState permits
            TurnInterruptionState.None,
            TurnInterruptionState.InterruptedPrompt {

        record None() implements TurnInterruptionState {}

        record InterruptedPrompt(Message message) implements TurnInterruptionState {}
    }

    /** Result of deserialising a conversation. */
    public record DeserializeResult(
            List<Message> messages,
            TurnInterruptionState turnInterruptionState
    ) {}

    /**
     * Full result returned by {@link #loadConversationForResume}.
     * Mirrors the anonymous object returned by loadConversationForResume() in
     * conversationRecovery.ts.
     */
    @Builder
    public record ConversationResumeResult(
            List<Message> messages,
            TurnInterruptionState turnInterruptionState,
            String sessionId,
            // Session metadata for restoring agent context
            String agentName,
            String agentColor,
            String agentSetting,
            String customTitle,
            String tag,
            String mode,          // "coordinator" | "normal"
            Integer prNumber,
            String prUrl,
            String prRepository,
            String fullPath       // Full path to the session file (cross-directory resume)
    ) {}

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final SessionStorageService sessionStorageService;

    @Autowired
    public ConversationRecoveryService(SessionStorageService sessionStorageService) {
        this.sessionStorageService = sessionStorageService;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Deserializes messages from a log file into the format expected by the REPL.
     * Filters unresolved tool uses, orphaned thinking messages, and appends a
     * synthetic assistant sentinel when the last message is from the user.
     *
     * Translated from deserializeMessages() in conversationRecovery.ts.
     *
     * @param serializedMessages raw messages from a transcript
     * @return filtered and normalized message list
     */
    public List<Message> deserializeMessages(List<Message> serializedMessages) {
        return deserializeMessagesWithInterruptDetection(serializedMessages).messages();
    }

    /**
     * Like {@link #deserializeMessages}, but also detects whether the session
     * was interrupted mid-turn.
     *
     * Translated from deserializeMessagesWithInterruptDetection() in conversationRecovery.ts.
     */
    public DeserializeResult deserializeMessagesWithInterruptDetection(List<Message> serializedMessages) {
        try {
            // 1. Filter unresolved tool_uses (assistant tool_use with no matching user tool_result)
            List<Message> filtered = filterUnresolvedToolUses(serializedMessages);

            // 2. Filter orphaned thinking-only assistant messages
            filtered = filterOrphanedThinkingOnlyMessages(filtered);

            // 3. Filter whitespace-only assistant messages
            filtered = filterWhitespaceOnlyAssistantMessages(filtered);

            // 4. Detect turn interruption
            InternalState internalState = detectTurnInterruption(filtered);

            TurnInterruptionState turnInterruptionState;
            if (internalState instanceof InterruptedTurnState) {
                // Transform mid-turn interruption to interrupted_prompt with a
                // synthetic continuation message — mirrors TypeScript behaviour.
                Message continuationMessage = createContinuationMessage();
                filtered = new ArrayList<>(filtered);
                filtered.add(continuationMessage);
                turnInterruptionState = new TurnInterruptionState.InterruptedPrompt(continuationMessage);
            } else if (internalState instanceof InterruptedPromptState ips) {
                turnInterruptionState = new TurnInterruptionState.InterruptedPrompt(ips.message());
            } else {
                turnInterruptionState = new TurnInterruptionState.None();
            }

            // 5. Append a synthetic NO_RESPONSE_REQUESTED assistant sentinel after the
            //    last user message so the conversation is API-valid if no resume action is taken.
            int lastRelevantIdx = findLastRelevantIndex(filtered);
            if (lastRelevantIdx != -1 && isUserMessage(filtered.get(lastRelevantIdx))) {
                filtered = new ArrayList<>(filtered);
                filtered.add(lastRelevantIdx + 1, createNoResponseRequestedMessage());
            }

            return new DeserializeResult(filtered, turnInterruptionState);

        } catch (Exception e) {
            log.error("Error deserializing messages", e);
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }

    /**
     * Load a conversation for resume.
     *
     * Translated from loadConversationForResume() in conversationRecovery.ts.
     *
     * @param sessionId optional session ID to load; null → most recent session
     * @return future resolving to the resume result, or null if no session found
     */
    public CompletableFuture<ConversationResumeResult> loadConversationForResume(String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<Message> raw;
                String resolvedSessionId = sessionId;

                if (sessionId == null) {
                    // --continue: most recent session
                    // SessionStorageService.loadTranscript(null) returns the most recent session
                    raw = sessionStorageService.loadTranscript(null);
                } else {
                    raw = sessionStorageService.loadTranscript(sessionId);
                }

                if (raw == null || raw.isEmpty()) {
                    return null;
                }

                DeserializeResult deserialized = deserializeMessagesWithInterruptDetection(raw);

                return ConversationResumeResult.builder()
                        .messages(deserialized.messages())
                        .turnInterruptionState(deserialized.turnInterruptionState())
                        .sessionId(resolvedSessionId)
                        .build();

            } catch (Exception e) {
                log.error("Failed to load conversation for resume", e);
                throw e instanceof RuntimeException re ? re : new RuntimeException(e);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Removes assistant messages whose tool_use blocks have no matching
     * tool_result in subsequent user messages.
     * Corresponds to filterUnresolvedToolUses() in messages.ts.
     */
    private List<Message> filterUnresolvedToolUses(List<Message> messages) {
        // Collect all tool_result IDs from user messages
        Set<String> resolvedIds = new HashSet<>();
        for (Message msg : messages) {
            if (msg instanceof Message.UserMessage user && user.getContent() != null) {
                for (var block : user.getContent()) {
                    if (block instanceof com.anthropic.claudecode.model.ContentBlock.ToolResultBlock tr) {
                        resolvedIds.add(tr.getToolUseId());
                    }
                }
            }
        }

        List<Message> result = new ArrayList<>();
        for (Message msg : messages) {
            if (msg instanceof Message.AssistantMessage assistant && assistant.getContent() != null) {
                boolean hasUnresolved = assistant.getContent().stream()
                        .filter(b -> b instanceof com.anthropic.claudecode.model.ContentBlock.ToolUseBlock)
                        .map(b -> (com.anthropic.claudecode.model.ContentBlock.ToolUseBlock) b)
                        .anyMatch(tu -> !resolvedIds.contains(tu.getId()));
                if (hasUnresolved) continue; // Drop this assistant message
            }
            result.add(msg);
        }
        return result;
    }

    /**
     * Removes assistant messages that contain only thinking blocks (no text or
     * tool_use blocks). These can cause API errors on resume.
     * Corresponds to filterOrphanedThinkingOnlyMessages() in messages.ts.
     */
    private List<Message> filterOrphanedThinkingOnlyMessages(List<Message> messages) {
        List<Message> result = new ArrayList<>();
        for (Message msg : messages) {
            if (msg instanceof Message.AssistantMessage assistant && assistant.getContent() != null) {
                boolean onlyThinking = assistant.getContent().stream()
                        .allMatch(b -> b instanceof com.anthropic.claudecode.model.ContentBlock.ThinkingBlock);
                if (onlyThinking && !assistant.getContent().isEmpty()) continue;
            }
            result.add(msg);
        }
        return result;
    }

    /**
     * Removes assistant messages whose only text content is whitespace.
     * Corresponds to filterWhitespaceOnlyAssistantMessages() in messages.ts.
     */
    private List<Message> filterWhitespaceOnlyAssistantMessages(List<Message> messages) {
        List<Message> result = new ArrayList<>();
        for (Message msg : messages) {
            if (msg instanceof Message.AssistantMessage assistant && assistant.getContent() != null) {
                boolean onlyWhitespace = assistant.getContent().stream()
                        .filter(b -> b instanceof com.anthropic.claudecode.model.ContentBlock.TextBlock)
                        .map(b -> (com.anthropic.claudecode.model.ContentBlock.TextBlock) b)
                        .allMatch(tb -> tb.getText() == null || tb.getText().isBlank());
                boolean noNonText = assistant.getContent().stream()
                        .noneMatch(b -> b instanceof com.anthropic.claudecode.model.ContentBlock.ToolUseBlock
                                     || b instanceof com.anthropic.claudecode.model.ContentBlock.ThinkingBlock);
                if (onlyWhitespace && noNonText && !assistant.getContent().isEmpty()) continue;
            }
            result.add(msg);
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Turn interruption detection
    // -----------------------------------------------------------------------

    /**
     * Internal 3-way state (before transforming interrupted_turn to interrupted_prompt).
     * Mirrors InternalInterruptionState in conversationRecovery.ts.
     */
    private sealed interface InternalState
            permits NoneState, InterruptedPromptState, InterruptedTurnState {}
    private record NoneState() implements InternalState {}
    private record InterruptedPromptState(Message message) implements InternalState {}
    private record InterruptedTurnState() implements InternalState {}

    private static final InternalState INTERRUPTED_TURN = new InterruptedTurnState();

    private InternalState detectTurnInterruption(List<Message> messages) {
        if (messages.isEmpty()) return new NoneState();

        // Find the last turn-relevant message (skip system/progress/api-error assistants)
        Message lastMessage = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (isSystemOrProgress(m)) continue;
            if (isApiErrorAssistant(m)) continue;
            lastMessage = m;
            break;
        }

        if (lastMessage == null) return new NoneState();

        if (lastMessage instanceof Message.AssistantMessage) {
            // Turn completed normally
            return new NoneState();
        }

        if (lastMessage instanceof Message.UserMessage user) {
            // Meta / compactSummary messages don't indicate an interruption
            if (Boolean.TRUE.equals(user.getUserModified())) return new NoneState();

            // Plain tool_result — could be mid-turn
            if (isToolUseResultMessage(user)) {
                return INTERRUPTED_TURN;
            }
            // Plain text prompt — Claude hadn't started responding yet
            return new InterruptedPromptState(lastMessage);
        }

        if (lastMessage instanceof Message.AttachmentMessage) {
            // Attachment at the end means the user provided context but got no response
            return INTERRUPTED_TURN;
        }

        return new NoneState();
    }

    private boolean isSystemOrProgress(Message m) {
        return m instanceof Message.SystemMessage || m instanceof Message.ProgressMessage;
    }

    private boolean isApiErrorAssistant(Message m) {
        return m instanceof Message.AssistantMessage a
                && Boolean.TRUE.equals(a.getIsApiErrorMessage());
    }

    private boolean isUserMessage(Message m) {
        return m instanceof Message.UserMessage;
    }

    private boolean isToolUseResultMessage(Message.UserMessage user) {
        if (user.getContent() == null || user.getContent().isEmpty()) return false;
        return user.getContent().get(0) instanceof
                com.anthropic.claudecode.model.ContentBlock.ToolResultBlock;
    }

    private int findLastRelevantIndex(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (!isSystemOrProgress(m)) return i;
        }
        return -1;
    }

    // -----------------------------------------------------------------------
    // Synthetic message factories
    // -----------------------------------------------------------------------

    private Message createContinuationMessage() {
        return Message.UserMessage.builder()
                .type("user")
                .uuid(UUID.randomUUID().toString())
                .content(List.of(new com.anthropic.claudecode.model.ContentBlock.TextBlock(
                        "Continue from where you left off.")))
                .build();
    }

    /** Sentinel used when the conversation is API-valid but no response is needed yet. */
    private Message createNoResponseRequestedMessage() {
        return Message.AssistantMessage.builder()
                .type("assistant")
                .uuid(UUID.randomUUID().toString())
                .content(List.of(new com.anthropic.claudecode.model.ContentBlock.TextBlock(
                        "<NO_RESPONSE_REQUESTED>")))
                .build();
    }
}
