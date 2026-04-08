package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilities for the {@code @agent-name message} direct-messaging syntax that
 * lets a user send a message to a specific team member without going through
 * the main agent loop.
 *
 * Translated from src/utils/directMemberMessage.ts
 */
@Slf4j
public class DirectMemberMessageUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DirectMemberMessageUtils.class);


    // Pattern: "@word-or-hyphen rest-of-input" — the message part spans multiple lines
    private static final Pattern DIRECT_MESSAGE_PATTERN =
            Pattern.compile("^@([\\w-]+)\\s+(.+)$", Pattern.DOTALL);

    // -----------------------------------------------------------------------
    // Domain types
    // -----------------------------------------------------------------------

    /**
     * Parsed components of a direct-message input string.
     */
    public record ParsedDirectMessage(String recipientName, String message) {}

    /**
     * Sealed result hierarchy for {@link #sendDirectMemberMessage}.
     * Translated from the TypeScript union type {@code DirectMessageResult}.
     */
    public sealed interface DirectMessageResult
            permits DirectMessageResult.Success,
                    DirectMessageResult.Failure {

        record Success(String recipientName) implements DirectMessageResult {}

        record Failure(ErrorType error, String recipientName)
                implements DirectMessageResult {
            /** Use this constructor when the recipient is not relevant to the error. */
            public Failure(ErrorType error) {
                this(error, null);
            }
        }
    }

    /**
     * Possible failure reasons for a direct-message delivery attempt.
     * Translated from the TypeScript union literal type.
     */
    public enum ErrorType {
        NO_TEAM_CONTEXT,
        UNKNOWN_RECIPIENT
    }

    /**
     * A teammate entry that the caller resolves from its own context map.
     */
    public record Teammate(String name) {}

    /**
     * The team context required to resolve recipients.
     * Matches the shape used in {@code AppState.teamContext}.
     */
    public record TeamContext(String teamName, Map<String, Teammate> teammates) {}

    /**
     * A single mailbox message written to the recipient's inbox.
     */
    public record MailboxMessage(String from, String text, String timestamp) {}

    /**
     * Functional interface for the mailbox write operation so callers can
     * inject a real or mock implementation.
     */
    @FunctionalInterface
    public interface WriteToMailboxFn {
        CompletableFuture<Void> write(
                String recipientName, MailboxMessage message, String teamName);
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Parse the {@code @agent-name message} syntax.
     *
     * Returns {@link java.util.Optional#empty()} when the input does not match
     * the expected format (no {@code @} prefix, no space after the name, or
     * the message body is blank after trimming).
     *
     * Translated from {@code parseDirectMemberMessage()} in directMemberMessage.ts
     *
     * @param input raw user input
     * @return parsed recipient and message, or {@code null} if the syntax does
     *         not match
     */
    public static ParsedDirectMessage parseDirectMemberMessage(String input) {
        if (input == null || input.isBlank()) return null;

        Matcher m = DIRECT_MESSAGE_PATTERN.matcher(input);
        if (!m.matches()) return null;

        String recipientName = m.group(1);
        String message = m.group(2);

        if (recipientName == null || recipientName.isBlank()) return null;
        if (message == null) return null;

        String trimmed = message.trim();
        if (trimmed.isBlank()) return null;

        return new ParsedDirectMessage(recipientName, trimmed);
    }

    /**
     * Send a direct message to a named team member, bypassing the model.
     *
     * <p>Fails immediately if {@code teamContext} or {@code writeToMailbox} are
     * {@code null} (no team context).  Also fails if the recipient name cannot
     * be resolved in the team's member map.
     *
     * Translated from {@code sendDirectMemberMessage()} in directMemberMessage.ts
     *
     * @param recipientName  target teammate name (from the parsed {@code @name})
     * @param message        the text to deliver
     * @param teamContext    current team context; pass {@code null} to signal
     *                       "no team context"
     * @param writeToMailbox async function that persists the message; pass
     *                       {@code null} to signal "no team context"
     * @return CompletableFuture with a Success or Failure result
     */
    public static CompletableFuture<DirectMessageResult> sendDirectMemberMessage(
            String recipientName,
            String message,
            TeamContext teamContext,
            WriteToMailboxFn writeToMailbox) {

        if (teamContext == null || writeToMailbox == null) {
            return CompletableFuture.completedFuture(
                    new DirectMessageResult.Failure(ErrorType.NO_TEAM_CONTEXT));
        }

        Map<String, Teammate> teammates = teamContext.teammates();
        if (teammates == null) {
            return CompletableFuture.completedFuture(
                    new DirectMessageResult.Failure(ErrorType.NO_TEAM_CONTEXT));
        }

        // Resolve by display name — teammates map values carry the name field
        boolean recipientFound = teammates.values().stream()
                .anyMatch(t -> recipientName.equals(t.name()));

        if (!recipientFound) {
            return CompletableFuture.completedFuture(
                    new DirectMessageResult.Failure(ErrorType.UNKNOWN_RECIPIENT, recipientName));
        }

        MailboxMessage mailboxMessage = new MailboxMessage(
                "user",
                message,
                Instant.now().toString());

        return writeToMailbox
                .write(recipientName, mailboxMessage, teamContext.teamName())
                .thenApply(v -> (DirectMessageResult)
                        new DirectMessageResult.Success(recipientName))
                .exceptionally(ex -> {
                    log.error("Failed to write to mailbox for {}", recipientName, ex);
                    return new DirectMessageResult.Failure(ErrorType.NO_TEAM_CONTEXT);
                });
    }
}
