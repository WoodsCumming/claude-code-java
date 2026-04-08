package com.anthropic.claudecode.service;

import com.anthropic.claudecode.client.AnthropicClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.regex.*;

/**
 * API error constants and classification service.
 * Translated from src/services/api/errors.ts
 *
 * Provides user-facing error message constants and helper predicates
 * for classifying API errors (prompt-too-long, media-size, rate-limit, etc.).
 */
@Slf4j
@Service
public class ApiErrorService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApiErrorService.class);


    // -----------------------------------------------------------------------
    // Error message constants  (translated from error-string exports in errors.ts)
    // -----------------------------------------------------------------------

    public static final String API_ERROR_MESSAGE_PREFIX = "API Error";
    public static final String PROMPT_TOO_LONG_ERROR_MESSAGE = "Prompt is too long";
    public static final String CREDIT_BALANCE_TOO_LOW_ERROR_MESSAGE = "Credit balance is too low";
    public static final String INVALID_API_KEY_ERROR_MESSAGE = "Not logged in · Please run /login";
    public static final String INVALID_API_KEY_ERROR_MESSAGE_EXTERNAL = "Invalid API key · Fix external API key";
    public static final String ORG_DISABLED_ERROR_MESSAGE_ENV_KEY_WITH_OAUTH =
        "Your ANTHROPIC_API_KEY belongs to a disabled organization · Unset the environment variable to use your subscription instead";
    public static final String ORG_DISABLED_ERROR_MESSAGE_ENV_KEY =
        "Your ANTHROPIC_API_KEY belongs to a disabled organization · Update or unset the environment variable";
    public static final String TOKEN_REVOKED_ERROR_MESSAGE = "OAuth token revoked · Please run /login";
    public static final String CCR_AUTH_ERROR_MESSAGE =
        "Authentication error · This may be a temporary network issue, please try again";
    public static final String REPEATED_529_ERROR_MESSAGE = "Repeated 529 Overloaded errors";
    public static final String CUSTOM_OFF_SWITCH_MESSAGE =
        "Opus is experiencing high load, please use /model to switch to Sonnet";
    public static final String API_TIMEOUT_ERROR_MESSAGE = "Request timed out";
    public static final String OAUTH_ORG_NOT_ALLOWED_ERROR_MESSAGE =
        "Your account does not have access to Claude Code. Please run /login.";

    private final ApiErrorUtilsService errorUtils;

    @Autowired
    public ApiErrorService(ApiErrorUtilsService errorUtils) {
        this.errorUtils = errorUtils;
    }

    // -----------------------------------------------------------------------
    // startsWithApiErrorPrefix
    // -----------------------------------------------------------------------

    /** Translated from startsWithApiErrorPrefix() in errors.ts */
    public static boolean startsWithApiErrorPrefix(String text) {
        if (text == null) return false;
        return text.startsWith(API_ERROR_MESSAGE_PREFIX)
            || text.startsWith("Please run /login · " + API_ERROR_MESSAGE_PREFIX);
    }

    // -----------------------------------------------------------------------
    // Prompt-too-long helpers
    // -----------------------------------------------------------------------

    /**
     * Parse actual/limit token counts from a raw prompt-too-long error message.
     * Translated from parsePromptTooLongTokenCounts() in errors.ts
     */
    public PromptTooLongTokenCounts parsePromptTooLongTokenCounts(String rawMessage) {
        if (rawMessage == null) return new PromptTooLongTokenCounts(null, null);
        Pattern pattern = Pattern.compile(
            "prompt is too long[^0-9]*(\\d+)\\s*tokens?\\s*>\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE);
        Matcher m = pattern.matcher(rawMessage);
        if (m.find()) {
            try {
                return new PromptTooLongTokenCounts(
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)));
            } catch (NumberFormatException ignored) { /* fall through */ }
        }
        return new PromptTooLongTokenCounts(null, null);
    }

    public record PromptTooLongTokenCounts(Integer actualTokens, Integer limitTokens) {}

    // -----------------------------------------------------------------------
    // Media-size error helpers
    // -----------------------------------------------------------------------

    /**
     * Check whether a raw API error string is a media-size rejection.
     * Translated from isMediaSizeError() in errors.ts
     */
    public static boolean isMediaSizeError(String raw) {
        if (raw == null) return false;
        boolean imageExceedsMax = raw.contains("image exceeds") && raw.contains("maximum");
        boolean imageDimsExceed = raw.contains("image dimensions exceed") && raw.contains("many-image");
        boolean pdfTooManyPages = raw.matches("(?s).*maximum of \\d+ PDF pages.*");
        return imageExceedsMax || imageDimsExceed || pdfTooManyPages;
    }

    // -----------------------------------------------------------------------
    // is529Error
    // -----------------------------------------------------------------------

    /**
     * Check if a throwable represents an overloaded (529) API error.
     * Translated from is529Error() in withRetry.ts
     */
    public static boolean is529Error(Throwable error) {
        if (error instanceof AnthropicClient.ApiException apiEx) {
            if (apiEx.getStatusCode() == 529) return true;
            // The SDK sometimes fails to properly pass 529 during streaming —
            // check the message body directly.
            String body = apiEx.getBody();
            return body != null && body.contains("\"type\":\"overloaded_error\"");
        }
        if (error != null && error.getMessage() != null) {
            return error.getMessage().contains("\"type\":\"overloaded_error\"");
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // isValidAPIMessage
    // -----------------------------------------------------------------------

    /**
     * Check whether a value looks like a valid API message response.
     * Translated from isValidAPIMessage() in errors.ts
     */
    public static boolean isValidApiMessage(Object value) {
        if (!(value instanceof java.util.Map<?, ?> map)) return false;
        return map.containsKey("content")
            && map.containsKey("model")
            && map.containsKey("usage")
            && map.get("content") instanceof java.util.List;
    }

    // -----------------------------------------------------------------------
    // classifyAPIError  (mirrors logging.ts classifyAPIError)
    // -----------------------------------------------------------------------

    /**
     * Classify an API error into a short category string for analytics.
     * Translated from classifyAPIError() in errors.ts / logging.ts
     */
    public String classifyApiError(Throwable error) {
        if (error == null) return "unknown";

        if (error instanceof AnthropicClient.ApiException apiEx) {
            int status = apiEx.getStatusCode();
            String body = apiEx.getBody();
            if (status == 401) return "auth";
            if (status == 403) {
                if (body != null && body.contains("OAuth token has been revoked")) return "token_revoked";
                return "forbidden";
            }
            if (status == 429) return "rate_limit";
            if (status == 529 || (body != null && body.contains("\"type\":\"overloaded_error\"")))
                return "overloaded";
            if (status == 400) {
                if (body != null && body.contains("prompt is too long")) return "prompt_too_long";
                if (body != null && body.contains("image exceeds")) return "media_too_large";
                return "bad_request";
            }
            if (status >= 500) return "server_error";
            return "api_error_" + status;
        }

        String message = error.getMessage();
        if (message == null) message = "";

        if (message.contains("timeout") || message.contains("timed out")) return "timeout";
        if (message.contains("ECONNRESET") || message.contains("Connection reset")) return "connection_reset";
        if (message.contains("SSL") || message.contains("certificate")) return "ssl_error";
        if (message.contains("connection") || message.contains("network")) return "connection_error";

        return "unknown";
    }

    // -----------------------------------------------------------------------
    // getPdfTooLargeErrorMessage  (session-mode-aware)
    // -----------------------------------------------------------------------

    public String getPdfTooLargeErrorMessage(boolean isNonInteractive) {
        String limits = "max 100 pages, 32MB";
        if (isNonInteractive) {
            return "PDF too large (" + limits + "). Try reading the file a different way (e.g., extract text with pdftotext).";
        }
        return "PDF too large (" + limits + "). Double press esc to go back and try again, or use pdftotext to convert to text first.";
    }

    public String getTokenRevokedErrorMessage(boolean isNonInteractive) {
        if (isNonInteractive) {
            return "Your account does not have access to Claude. Please login again or contact your administrator.";
        }
        return TOKEN_REVOKED_ERROR_MESSAGE;
    }

    public String getOauthOrgNotAllowedErrorMessage(boolean isNonInteractive) {
        if (isNonInteractive) {
            return "Your organization does not have access to Claude. Please login again or contact your administrator.";
        }
        return OAUTH_ORG_NOT_ALLOWED_ERROR_MESSAGE;
    }
}
