package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Compact slash-command handler.
 * Translated from src/commands/compact/compact.ts
 *
 * Orchestrates context-window compaction when the user runs the /compact
 * slash command.  Tries session-memory compaction first (if no custom
 * instructions are provided), falls back to microcompact + full compaction.
 */
@Slf4j
@Service
public class CompactCommandService {



    // ---------------------------------------------------------------------------
    // Error messages (mirror constants from the TS source)
    // ---------------------------------------------------------------------------

    public static final String ERROR_MESSAGE_NOT_ENOUGH_MESSAGES =
        "Not enough messages to compact";
    public static final String ERROR_MESSAGE_INCOMPLETE_RESPONSE =
        "Incomplete response from model during compaction";
    public static final String ERROR_MESSAGE_USER_ABORT =
        "Compaction canceled.";

    // ---------------------------------------------------------------------------
    // Dependencies
    // ---------------------------------------------------------------------------

    private final CompactService compactService;
    private final MicroCompactService microCompactService;
    private final SessionMemoryCompactService sessionMemoryCompactService;
    private final PostCompactCleanupService postCompactCleanupService;
    private final CompactWarningService compactWarningService;
    private final ContextAnalysisService contextAnalysisService;

    @Autowired
    public CompactCommandService(CompactService compactService,
                                  MicroCompactService microCompactService,
                                  SessionMemoryCompactService sessionMemoryCompactService,
                                  PostCompactCleanupService postCompactCleanupService,
                                  CompactWarningService compactWarningService,
                                  ContextAnalysisService contextAnalysisService) {
        this.compactService = compactService;
        this.microCompactService = microCompactService;
        this.sessionMemoryCompactService = sessionMemoryCompactService;
        this.postCompactCleanupService = postCompactCleanupService;
        this.compactWarningService = compactWarningService;
        this.contextAnalysisService = contextAnalysisService;
    }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Run the /compact command.
     * Mirrors call() in compact.ts.
     *
     * @param args      Raw arguments typed after /compact (optional custom instructions).
     * @param messages  Current conversation messages visible to the model.
     * @param agentId   Identifier of the active agent session.
     * @param model     Model name used for the summarization request.
     * @return A {@link CompactCommandResult} containing the updated messages and display text.
     */
    public CompletableFuture<CompactCommandResult> call(String args,
                                                         List<Message> messages,
                                                         String agentId,
                                                         String model) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Project messages after the compact boundary so previously-snipped
                // content is not re-summarised.
                List<Message> effectiveMessages = contextAnalysisService
                    .getMessagesAfterCompactBoundary(messages);

                if (effectiveMessages.isEmpty()) {
                    throw new RuntimeException(ERROR_MESSAGE_NOT_ENOUGH_MESSAGES);
                }

                String customInstructions = (args != null) ? args.trim() : "";

                // 1. Try session-memory compaction first (no custom instructions needed).
                if (customInstructions.isEmpty()) {
                    SessionMemoryCompactService.SessionMemoryCompactionResult smResult =
                        sessionMemoryCompactService.trySessionMemoryCompaction(
                            effectiveMessages, agentId).join();

                    if (smResult != null) {
                        postCompactCleanupService.runPostCompactCleanup();
                        compactWarningService.suppressCompactWarning();

                        log.info("Session-memory compaction succeeded for agent {}", agentId);
                        return CompactCommandResult.of(
                            smResult.compactedMessages(),
                            buildDisplayText(smResult.userDisplayMessage(), false)
                        );
                    }
                }

                // 2. Microcompact to reduce tokens, then full compaction.
                MicroCompactService.MicrocompactResult microResult =
                    microCompactService.microcompactMessages(effectiveMessages);
                List<Message> messagesForCompact = microResult.getMessages();

                List<Message> compacted = compactService
                    .compact(messagesForCompact, model)
                    .join();

                compactWarningService.suppressCompactWarning();
                postCompactCleanupService.runPostCompactCleanup();

                log.info("Full compaction complete: {} -> {} messages",
                    messages.size(), compacted.size());

                return CompactCommandResult.of(
                    compacted,
                    buildDisplayText(microResult.userDisplayMessage(), false)
                );

            } catch (RuntimeException e) {
                rethrowKnownCompactionError(e);
                throw e;
            }
        });
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Builds the human-readable display text shown after compaction.
     * Mirrors buildDisplayText() in compact.ts.
     */
    private String buildDisplayText(String userDisplayMessage, boolean verbose) {
        StringBuilder sb = new StringBuilder("Compacted");
        if (!verbose) {
            sb.append(" (ctrl+o to see full summary)");
        }
        if (userDisplayMessage != null && !userDisplayMessage.isBlank()) {
            sb.append("\n").append(userDisplayMessage);
        }
        return sb.toString();
    }

    /**
     * Re-throws well-known compaction errors with clean messages, mirroring the
     * catch-block logic in compact.ts.
     */
    private void rethrowKnownCompactionError(RuntimeException e) {
        String msg = e.getMessage();
        if (msg == null) return;
        if (msg.contains(ERROR_MESSAGE_NOT_ENOUGH_MESSAGES)) {
            throw new RuntimeException(ERROR_MESSAGE_NOT_ENOUGH_MESSAGES, e);
        }
        if (msg.contains(ERROR_MESSAGE_INCOMPLETE_RESPONSE)) {
            throw new RuntimeException(ERROR_MESSAGE_INCOMPLETE_RESPONSE, e);
        }
        if (msg.contains(ERROR_MESSAGE_USER_ABORT)) {
            throw new RuntimeException(ERROR_MESSAGE_USER_ABORT, e);
        }
        log.error("Error during compaction: {}", msg, e);
        throw new RuntimeException("Error during compaction: " + msg, e);
    }

    // ---------------------------------------------------------------------------
    // Result type
    // ---------------------------------------------------------------------------

    /**
     * Holds the outcome of a /compact invocation.
     */
    public record CompactCommandResult(
        List<Message> compactedMessages,
        String displayText
    ) {
        public static CompactCommandResult of(List<Message> messages, String text) {
            return new CompactCommandResult(messages, text);
        }
    }
}
