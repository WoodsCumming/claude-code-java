package com.anthropic.claudecode.util;

import java.net.URI;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Session URL / identifier parsing utilities.
 * Translated from src/utils/sessionUrl.ts
 *
 * A session resume identifier can be:
 *   - A JSONL file path (ends with .jsonl)
 *   - A plain UUID
 *   - A URL containing the full ingress endpoint
 */
public final class SessionUrlUtils {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE);

    private SessionUrlUtils() {}

    /**
     * Parsed result of a session resume identifier.
     * Translated from ParsedSessionUrl in sessionUrl.ts — uses a Java 21 record.
     *
     * @param sessionId   The resolved or newly-generated session UUID.
     * @param ingressUrl  The full ingress URL when the identifier was a URL, otherwise null.
     * @param isUrl       True when the identifier was a URL.
     * @param jsonlFile   The JSONL file path when the identifier was a file path, otherwise null.
     * @param isJsonlFile True when the identifier was a JSONL file path.
     */
    public record ParsedSessionUrl(
            String sessionId,
            String ingressUrl,
            boolean isUrl,
            String jsonlFile,
            boolean isJsonlFile) {}

    /**
     * Parse a session resume identifier which can be either:
     * <ul>
     *   <li>A JSONL file path (ends with {@code .jsonl})</li>
     *   <li>A plain session UUID</li>
     *   <li>A URL containing the full ingress endpoint</li>
     * </ul>
     *
     * Returns null when the identifier cannot be parsed.
     * Translated from parseSessionIdentifier() in sessionUrl.ts
     *
     * @param resumeIdentifier The URL, UUID, or file path to parse.
     * @return Parsed session information, or {@code null} if invalid.
     */
    public static ParsedSessionUrl parseSessionIdentifier(String resumeIdentifier) {
        if (resumeIdentifier == null || resumeIdentifier.isBlank()) {
            return null;
        }

        // Check for JSONL file path before URL parsing.
        // Windows absolute paths (C:\path\file.jsonl) would be mis-parsed as URLs
        // with "C:" as the protocol, so we check for the extension first.
        if (resumeIdentifier.toLowerCase().endsWith(".jsonl")) {
            return new ParsedSessionUrl(
                    UUID.randomUUID().toString(),
                    null,
                    false,
                    resumeIdentifier,
                    true);
        }

        // Check if it is a plain UUID
        if (isValidUuid(resumeIdentifier)) {
            return new ParsedSessionUrl(
                    resumeIdentifier,
                    null,
                    false,
                    null,
                    false);
        }

        // Try to parse as a URL (http / https only)
        try {
            URI uri = URI.create(resumeIdentifier);
            String scheme = uri.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                // Use the entire URL as the ingress URL and generate a fresh session ID
                return new ParsedSessionUrl(
                        UUID.randomUUID().toString(),
                        resumeIdentifier,
                        true,
                        null,
                        false);
            }
        } catch (Exception ignored) {
            // Not a valid URI — fall through
        }

        return null;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Returns true when {@code value} is a syntactically valid UUID string. */
    private static boolean isValidUuid(String value) {
        return value != null && UUID_PATTERN.matcher(value).matches();
    }
}
