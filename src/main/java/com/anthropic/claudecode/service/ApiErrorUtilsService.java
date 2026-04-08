package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import javax.net.ssl.SSLException;

/**
 * API error utilities service.
 * Translated from src/services/api/errorUtils.ts
 *
 * Provides utilities for extracting and classifying connection/SSL errors,
 * sanitizing HTML error responses, and formatting API errors for display.
 */
@Slf4j
@Service
public class ApiErrorUtilsService {



    // SSL/TLS error codes from OpenSSL (mirrored from errorUtils.ts)
    private static final Set<String> SSL_ERROR_CODES = Set.of(
        // Certificate verification errors
        "UNABLE_TO_VERIFY_LEAF_SIGNATURE",
        "UNABLE_TO_GET_ISSUER_CERT",
        "UNABLE_TO_GET_ISSUER_CERT_LOCALLY",
        "CERT_SIGNATURE_FAILURE",
        "CERT_NOT_YET_VALID",
        "CERT_HAS_EXPIRED",
        "CERT_REVOKED",
        "CERT_REJECTED",
        "CERT_UNTRUSTED",
        // Self-signed certificate errors
        "DEPTH_ZERO_SELF_SIGNED_CERT",
        "SELF_SIGNED_CERT_IN_CHAIN",
        // Chain errors
        "CERT_CHAIN_TOO_LONG",
        "PATH_LENGTH_EXCEEDED",
        // Hostname/altname errors
        "ERR_TLS_CERT_ALTNAME_INVALID",
        "HOSTNAME_MISMATCH",
        // TLS handshake errors
        "ERR_TLS_HANDSHAKE_TIMEOUT",
        "ERR_SSL_WRONG_VERSION_NUMBER",
        "ERR_SSL_DECRYPTION_FAILED_OR_BAD_RECORD_MAC"
    );

    /**
     * Connection error details extracted from the cause chain.
     * Translated from ConnectionErrorDetails in errorUtils.ts
     */
    public static class ConnectionErrorDetails {
        private final String code;
        private final String message;
        private final boolean sslError;
        public ConnectionErrorDetails(String code, String message, boolean sslError) {
            this.code = code;
            this.message = message;
            this.sslError = sslError;
        }
        public String getCode() { return code; }
        public String getMessage() { return message; }
        public boolean isSslError() { return sslError; }
    }

    /**
     * Extracts connection error details from the exception cause chain.
     * Walks up to 5 levels deep to find the root error code/message.
     * Translated from extractConnectionErrorDetails() in errorUtils.ts
     */
    public Optional<ConnectionErrorDetails> extractConnectionErrorDetails(Throwable error) {
        if (error == null) return Optional.empty();

        Throwable current = error;
        int maxDepth = 5;
        int depth = 0;

        while (current != null && depth < maxDepth) {
            // Check for SSL exceptions
            if (current instanceof SSLException sslEx) {
                String message = sslEx.getMessage() != null ? sslEx.getMessage() : "";
                String code = detectSslCode(message);
                if (code != null) {
                    return Optional.of(new ConnectionErrorDetails(code, message, true));
                }
                return Optional.of(new ConnectionErrorDetails("SSL_ERROR", message, true));
            }

            // Check message for known SSL error codes
            String message = current.getMessage();
            if (message != null) {
                for (String sslCode : SSL_ERROR_CODES) {
                    if (message.contains(sslCode)) {
                        return Optional.of(new ConnectionErrorDetails(sslCode, message, true));
                    }
                }

                // Check for network-level codes
                if (message.contains("ECONNRESET") || message.contains("Connection reset")) {
                    return Optional.of(new ConnectionErrorDetails("ECONNRESET", message, false));
                }
                if (message.contains("EPIPE") || message.contains("Broken pipe")) {
                    return Optional.of(new ConnectionErrorDetails("EPIPE", message, false));
                }
                if (message.contains("ETIMEDOUT") || message.contains("timed out")) {
                    return Optional.of(new ConnectionErrorDetails("ETIMEDOUT", message, false));
                }
                if (message.contains("ECONNREFUSED") || current instanceof java.net.ConnectException) {
                    return Optional.of(new ConnectionErrorDetails("ECONNREFUSED", message, false));
                }
            }

            Throwable cause = current.getCause();
            if (cause == null || cause == current) break;
            current = cause;
            depth++;
        }

        return Optional.empty();
    }

    /**
     * Returns an actionable hint for SSL/TLS errors.
     * Intended for contexts outside the main API client (OAuth token exchange, preflight checks).
     * Translated from getSSLErrorHint() in errorUtils.ts
     */
    public String getSSLErrorHint(Throwable error) {
        Optional<ConnectionErrorDetails> details = extractConnectionErrorDetails(error);
        if (details.isEmpty() || !details.get().isSslError()) {
            return null;
        }
        String code = details.get().getCode();
        return "SSL certificate error (" + code + "). If you are behind a corporate proxy or "
            + "TLS-intercepting firewall, configure your JVM truststore or set the appropriate "
            + "CA certificates, or ask IT to allowlist *.anthropic.com.";
    }

    /**
     * Strips HTML content from a message string (e.g., CloudFlare error pages).
     * Returns a user-friendly title or empty string if HTML is detected.
     * Translated from sanitizeMessageHTML() in errorUtils.ts
     */
    public String sanitizeMessageHtml(String message) {
        if (message == null) return "";
        if (message.contains("<!DOCTYPE html") || message.contains("<html")) {
            // Extract <title> tag if present
            java.util.regex.Pattern titlePattern =
                java.util.regex.Pattern.compile("<title>([^<]+)</title>",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher m = titlePattern.matcher(message);
            if (m.find()) {
                return m.group(1).trim();
            }
            return "";
        }
        return message;
    }

    /**
     * Sanitizes an API error message, stripping any HTML content.
     * Translated from sanitizeAPIError() in errorUtils.ts
     */
    public String sanitizeApiError(com.anthropic.claudecode.client.AnthropicClient.ApiException apiError) {
        if (apiError == null) return "";
        String message = apiError.getMessage();
        if (message == null) return "";
        return sanitizeMessageHtml(message);
    }

    /**
     * Formats an API error into a user-friendly message.
     * Handles SSL errors, timeouts, connection errors, and status-code-based messages.
     * Translated from formatAPIError() in errorUtils.ts
     */
    public String formatApiError(Throwable error) {
        if (error == null) return "Unknown error";

        Optional<ConnectionErrorDetails> connectionDetails = extractConnectionErrorDetails(error);

        if (connectionDetails.isPresent()) {
            ConnectionErrorDetails details = connectionDetails.get();
            String code = details.getCode();

            // Handle timeout errors
            if ("ETIMEDOUT".equals(code)) {
                return "Request timed out. Check your internet connection and proxy settings";
            }

            // Handle SSL/TLS errors with specific messages
            if (details.isSslError()) {
                return switch (code) {
                    case "UNABLE_TO_VERIFY_LEAF_SIGNATURE",
                         "UNABLE_TO_GET_ISSUER_CERT",
                         "UNABLE_TO_GET_ISSUER_CERT_LOCALLY" ->
                        "Unable to connect to API: SSL certificate verification failed. "
                        + "Check your proxy or corporate SSL certificates";
                    case "CERT_HAS_EXPIRED" ->
                        "Unable to connect to API: SSL certificate has expired";
                    case "CERT_REVOKED" ->
                        "Unable to connect to API: SSL certificate has been revoked";
                    case "DEPTH_ZERO_SELF_SIGNED_CERT",
                         "SELF_SIGNED_CERT_IN_CHAIN" ->
                        "Unable to connect to API: Self-signed certificate detected. "
                        + "Check your proxy or corporate SSL certificates";
                    case "ERR_TLS_CERT_ALTNAME_INVALID",
                         "HOSTNAME_MISMATCH" ->
                        "Unable to connect to API: SSL certificate hostname mismatch";
                    case "CERT_NOT_YET_VALID" ->
                        "Unable to connect to API: SSL certificate is not yet valid";
                    default ->
                        "Unable to connect to API: SSL error (" + code + ")";
                };
            }
        }

        String message = error.getMessage();

        if (message != null && message.contains("Connection error")) {
            if (connectionDetails.isPresent()) {
                return "Unable to connect to API (" + connectionDetails.get().getCode() + ")";
            }
            return "Unable to connect to API. Check your internet connection";
        }

        if (error instanceof com.anthropic.claudecode.client.AnthropicClient.ApiException apiEx) {
            if (message == null || message.isBlank()) {
                return "API error (status " + apiEx.getStatusCode() + ")";
            }
            String sanitized = sanitizeMessageHtml(message);
            return (!sanitized.equals(message) && !sanitized.isEmpty()) ? sanitized : message;
        }

        if (message == null) return "Unknown API error";
        return sanitizeMessageHtml(message);
    }

    // --- private helpers ---

    private String detectSslCode(String message) {
        for (String code : SSL_ERROR_CODES) {
            if (message.contains(code)) return code;
        }
        return null;
    }
}
