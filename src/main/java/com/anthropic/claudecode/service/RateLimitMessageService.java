package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import lombok.Data;

/**
 * Rate limit message service.
 * Translated from src/services/rateLimitMessages.ts
 *
 * Generates user-friendly messages for rate limit errors.
 */
@Slf4j
@Service
public class RateLimitMessageService {



    public static final List<String> RATE_LIMIT_ERROR_PREFIXES = List.of(
        "You've hit your",
        "You've used",
        "You're now using extra usage",
        "You're close to",
        "You're out of extra usage"
    );

    /**
     * Check if a message is a rate limit error.
     * Translated from isRateLimitErrorMessage() in rateLimitMessages.ts
     */
    public boolean isRateLimitErrorMessage(String text) {
        if (text == null) return false;
        return RATE_LIMIT_ERROR_PREFIXES.stream()
            .anyMatch(text::startsWith);
    }

    /**
     * Get the rate limit message for display.
     * Translated from getRateLimitErrorMessage() in errors.ts
     */
    public String getRateLimitErrorMessage(int statusCode, String model) {
        return switch (statusCode) {
            case 429 -> "You've hit your rate limit for model " + model + ". Please wait before retrying.";
            case 529 -> "Claude's API is temporarily overloaded. Please try again in a few minutes.";
            default -> "Rate limit exceeded. Please wait before retrying.";
        };
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class RateLimitMessage {
        private String message;
        private String severity; // "error" | "warning"

        public String getMessage() { return message; }
        public void setMessage(String v) { message = v; }
        public String getSeverity() { return severity; }
        public void setSeverity(String v) { severity = v; }
    }
}
