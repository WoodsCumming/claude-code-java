package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

/**
 * Utility helpers for permission UI components.
 *
 * <p>Translated from TypeScript {@code src/components/permissions/utils.ts}.
 *
 * <p>The TypeScript module contained a single function ({@code logUnaryPermissionEvent})
 * that fired an analytics event for tool-use permission accept/reject actions.
 * In the Java port the actual analytics call is delegated to an injectable
 * {@link UnaryEventLogger} functional interface so the utility stays testable
 * without a live analytics back-end.
 */
@Slf4j
public final class PermissionComponentUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PermissionComponentUtils.class);


    private PermissionComponentUtils() {}

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Logs a unary permission event (accept or reject).
     *
     * <p>TypeScript signature:
     * <pre>
     * export function logUnaryPermissionEvent(
     *   completion_type: CompletionType,
     *   { assistantMessage: { message: { id: message_id } } }: ToolUseConfirm,
     *   event: 'accept' | 'reject',
     *   hasFeedback?: boolean,
     * ): void
     * </pre>
     *
     * @param completionType identifies the type of completion (maps to {@code CompletionType})
     * @param messageId      the assistant message ID extracted from the tool-use confirmation
     * @param event          {@code "accept"} or {@code "reject"}
     * @param hasFeedback    whether user feedback was supplied (defaults to {@code false})
     * @param logger         strategy that performs the actual event dispatch
     */
    public static void logUnaryPermissionEvent(
            String completionType,
            String messageId,
            PermissionEvent event,
            boolean hasFeedback,
            UnaryEventLogger logger
    ) {
        UnaryEventPayload payload = new UnaryEventPayload(
                completionType,
                event,
                messageId,
                getHostPlatform(),
                hasFeedback
        );

        // Fire-and-forget — mirrors the TypeScript "void logUnaryEvent(...)" call
        try {
            logger.log(payload);
        } catch (Exception e) {
            log.warn("Failed to log unary permission event: {}", e.getMessage(), e);
        }
    }

    /**
     * Convenience overload that defaults {@code hasFeedback} to {@code false}.
     */
    public static void logUnaryPermissionEvent(
            String completionType,
            String messageId,
            PermissionEvent event,
            UnaryEventLogger logger
    ) {
        logUnaryPermissionEvent(completionType, messageId, event, false, logger);
    }

    // ---------------------------------------------------------------------------
    // Types
    // ---------------------------------------------------------------------------

    /**
     * The two event values from the TypeScript union {@code 'accept' | 'reject'}.
     */
    public enum PermissionEvent {
        ACCEPT,
        REJECT;

        /** Returns the lowercase string value used in analytics payloads. */
        public String value() {
            return name().toLowerCase();
        }
    }

    /**
     * Immutable payload passed to the {@link UnaryEventLogger}.
     *
     * <p>Mirrors the anonymous metadata object from the TS implementation.
     */
    public record UnaryEventPayload(
            String completionType,
            PermissionEvent event,
            String messageId,
            String platform,
            boolean hasFeedback
    ) {}

    /**
     * Strategy interface for dispatching a unary event to the analytics back-end.
     *
     * <p>Implementations can be provided as lambdas or Spring beans.
     */
    @FunctionalInterface
    public interface UnaryEventLogger {
        void log(UnaryEventPayload payload);
    }

    // ---------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------

    /**
     * Returns a string identifying the host platform for analytics.
     *
     * <p>Mirrors {@code getHostPlatformForAnalytics()} from the TS source.
     */
    private static String getHostPlatform() {
        String os = System.getProperty("os.name", "unknown").toLowerCase();
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "mac";
        }
        if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
            return "linux";
        }
        return "unknown";
    }
}
