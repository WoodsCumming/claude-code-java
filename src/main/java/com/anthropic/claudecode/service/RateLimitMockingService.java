package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Facade for rate limit header processing.
 * Translated from src/services/rateLimitMocking.ts
 *
 * Isolates mock logic from production code. Only active when the
 * /mock-limits command has been invoked by an Ant employee.
 */
@Slf4j
@Service
public class RateLimitMockingService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RateLimitMockingService.class);


    // -------------------------------------------------------------------------
    // Mock 429 error representation
    // -------------------------------------------------------------------------

    /**
     * A simulated 429 rate-limit error.
     * Replaces APIError from the Anthropic SDK.
     */
    public record MockRateLimitError(
        int status,
        String type,
        String message,
        Map<String, String> headers) {

        public static MockRateLimitError rateLimitExceeded(Map<String, String> headers) {
            return new MockRateLimitError(429, "rate_limit_error", "Rate limit exceeded",
                                         headers != null ? headers : Map.of());
        }

        public static MockRateLimitError withMessage(String message) {
            return new MockRateLimitError(429, "rate_limit_error", message, Map.of());
        }
    }

    // -------------------------------------------------------------------------
    // Collaborators
    // -------------------------------------------------------------------------

    private final MockRateLimitsService mockRateLimitsService;

    @Autowired
    public RateLimitMockingService(MockRateLimitsService mockRateLimitsService) {
        this.mockRateLimitsService = mockRateLimitsService;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Process headers, applying mocks if /mock-limits command is active.
     * Translated from processRateLimitHeaders() in rateLimitMocking.ts
     *
     * @param headers incoming HTTP response headers
     * @return headers with mocks applied (if active), or original headers
     */
    public Map<String, String> processRateLimitHeaders(Map<String, String> headers) {
        if (mockRateLimitsService.shouldProcessMockLimits()) {
            return mockRateLimitsService.applyMockHeaders(
                headers != null ? headers : new HashMap<>());
        }
        return headers != null ? headers : Map.of();
    }

    /**
     * Check if rate limits should be processed (real subscriber or /mock-limits active).
     * Translated from shouldProcessRateLimits() in rateLimitMocking.ts
     */
    public boolean shouldProcessRateLimits(boolean isSubscriber) {
        return isSubscriber || mockRateLimitsService.shouldProcessMockLimits();
    }

    /**
     * Check if mock rate limits should throw a 429 error.
     * Returns the error to throw, or empty if no error should be thrown.
     * Translated from checkMockRateLimitError() in rateLimitMocking.ts
     *
     * @param currentModel     the model being used for the current request
     * @param isFastModeActive whether fast mode is currently active
     * @return Optional containing the mock error, or empty if none
     */
    public Optional<MockRateLimitError> checkMockRateLimitError(
            String currentModel,
            boolean isFastModeActive) {

        if (!mockRateLimitsService.shouldProcessMockLimits()) {
            return Optional.empty();
        }

        // Check for headerless 429 message first
        String headerlessMessage = mockRateLimitsService.getMockHeaderless429Message();
        if (headerlessMessage != null && !headerlessMessage.isBlank()) {
            return Optional.of(MockRateLimitError.withMessage(headerlessMessage));
        }

        Map<String, String> mockHeaders = mockRateLimitsService.getMockHeaders();
        if (mockHeaders == null) {
            return Optional.empty();
        }

        String status = mockHeaders.get("anthropic-ratelimit-unified-status");
        String overageStatus = mockHeaders.get("anthropic-ratelimit-unified-overage-status");
        String rateLimitType = mockHeaders.get("anthropic-ratelimit-unified-representative-claim");

        // Opus-specific limit: only throw 429 if actually using Opus
        boolean isOpusLimit = "seven_day_opus".equals(rateLimitType);
        boolean isUsingOpus = currentModel != null && currentModel.contains("opus");
        if (isOpusLimit && !isUsingOpus) {
            return Optional.empty();
        }

        // Check mock fast-mode rate limits
        if (mockRateLimitsService.isMockFastModeRateLimitScenario()) {
            Map<String, String> fastModeHeaders =
                mockRateLimitsService.checkMockFastModeRateLimit(isFastModeActive);
            if (fastModeHeaders == null) {
                return Optional.empty();
            }
            return Optional.of(MockRateLimitError.rateLimitExceeded(fastModeHeaders));
        }

        // Standard 429: rejected AND overage also rejected (or absent)
        boolean shouldThrow429 = "rejected".equals(status) &&
            (overageStatus == null || "rejected".equals(overageStatus));

        if (shouldThrow429) {
            return Optional.of(MockRateLimitError.rateLimitExceeded(new HashMap<>(mockHeaders)));
        }

        return Optional.empty();
    }

    /**
     * Check if this is a mock 429 error that should not be retried.
     * Translated from isMockRateLimitError() in rateLimitMocking.ts
     */
    public boolean isMockRateLimitError(int httpStatus) {
        return mockRateLimitsService.shouldProcessMockLimits() && httpStatus == 429;
    }

    /**
     * Check if /mock-limits command is currently active (for UI purposes).
     * Translated from shouldProcessMockLimits export in rateLimitMocking.ts
     */
    public boolean shouldProcessMockLimits() {
        return mockRateLimitsService.shouldProcessMockLimits();
    }
}
