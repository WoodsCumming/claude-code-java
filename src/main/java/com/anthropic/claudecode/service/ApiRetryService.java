package com.anthropic.claudecode.service;

import com.anthropic.claudecode.client.AnthropicClient;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * API retry service for handling transient API errors with exponential back-off.
 * Translated from src/services/api/withRetry.ts
 *
 * Key behaviours preserved from the TypeScript original:
 * - Exponential back-off with jitter (getRetryDelay / BASE_DELAY_MS)
 * - Respects Retry-After response headers
 * - Distinguishes foreground vs background 529 retry policy
 * - Per-attempt context (model, thinkingConfig, maxTokensOverride)
 * - CannotRetryError wrapping non-retryable failures
 * - FallbackTriggeredError for Opus → fallback-model transitions
 * - parseMaxTokensContextOverflowError to auto-adjust max_tokens on 400
 */
@Slf4j
@Service
public class ApiRetryService {



    // -----------------------------------------------------------------------
    // Constants  (from withRetry.ts)
    // -----------------------------------------------------------------------

    public static final int DEFAULT_MAX_RETRIES = 10;
    public static final long BASE_DELAY_MS = 500;
    private static final long MAX_DELAY_MS = 32_000;
    private static final int MAX_529_RETRIES = 3;
    private static final int FLOOR_OUTPUT_TOKENS = 3_000;

    // Sources that wait on 529 (user is blocking on result)
    private static final Set<String> FOREGROUND_529_RETRY_SOURCES = Set.of(
        "repl_main_thread",
        "repl_main_thread:outputStyle:custom",
        "repl_main_thread:outputStyle:Explanatory",
        "repl_main_thread:outputStyle:Learning",
        "sdk",
        "agent:custom",
        "agent:default",
        "agent:builtin",
        "compact",
        "hook_agent",
        "hook_prompt",
        "verification_agent",
        "side_question",
        "auto_mode"
    );

    private final ApiErrorUtilsService errorUtils;

    @Autowired
    public ApiRetryService(ApiErrorUtilsService errorUtils) {
        this.errorUtils = errorUtils;
    }

    // -----------------------------------------------------------------------
    // Public types
    // -----------------------------------------------------------------------

    /** Per-attempt context carried through the retry loop. */
    public static class RetryContext {
        private String model;
        private Integer maxTokensOverride;
        private boolean fastMode;

        public RetryContext() {}
        public RetryContext(String model) { this.model = model; }
        public RetryContext(String model, Integer maxTokensOverride, boolean fastMode) {
            this.model = model; this.maxTokensOverride = maxTokensOverride; this.fastMode = fastMode;
        }
        public String getModel() { return model; }
        public void setModel(String v) { model = v; }
        public Integer getMaxTokensOverride() { return maxTokensOverride; }
        public void setMaxTokensOverride(Integer v) { maxTokensOverride = v; }
        public boolean isFastMode() { return fastMode; }
        public void setFastMode(boolean v) { fastMode = v; }
    }

    /** Options for a retry-wrapped call. */
    @Data
    @lombok.NoArgsConstructor(force = true)
    @lombok.AllArgsConstructor
    public static class RetryOptions {
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private final String model;
        private String fallbackModel;
        private String querySource;
        private Integer initialConsecutive529Errors;
        /** Set to cancel in-flight waits. */
        private volatile boolean aborted = false;

        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int v) { maxRetries = v; }
        public String getFallbackModel() { return fallbackModel; }
        public void setFallbackModel(String v) { fallbackModel = v; }
        public String getQuerySource() { return querySource; }
        public void setQuerySource(String v) { querySource = v; }
        public Integer getInitialConsecutive529Errors() { return initialConsecutive529Errors; }
        public void setInitialConsecutive529Errors(Integer v) { initialConsecutive529Errors = v; }
        public boolean getAborted() { return aborted; }
        public boolean isAborted() { return aborted; }
        public void setAborted(boolean v) { aborted = v; }
    }

    // -----------------------------------------------------------------------
    // Exception types
    // -----------------------------------------------------------------------

    /** Thrown when a call cannot be retried further. */
    public static class CannotRetryError extends RuntimeException {
        private final Throwable originalError;
        private final RetryContext retryContext;

        public CannotRetryError(Throwable originalError, RetryContext retryContext) {
            super(originalError != null ? originalError.getMessage() : "Cannot retry", originalError);
            this.originalError = originalError;
            this.retryContext = retryContext;
        }

        public Throwable getOriginalError() { return originalError; }
        public RetryContext getRetryContext() { return retryContext; }
    }

    /** Thrown when the 529 threshold is hit and a fallback model should be used. */
    public static class FallbackTriggeredError extends RuntimeException {
        private final String originalModel;
        private final String fallbackModel;

        public FallbackTriggeredError(String originalModel, String fallbackModel) {
            super("Model fallback triggered: " + originalModel + " -> " + fallbackModel);
            this.originalModel = originalModel;
            this.fallbackModel = fallbackModel;
        }

        public String getOriginalModel() { return originalModel; }
    }

    // -----------------------------------------------------------------------
    // Result types for parseMaxTokensContextOverflowError
    // -----------------------------------------------------------------------

    public record MaxTokensOverflowInfo(int inputTokens, int maxTokens, int contextLimit) {}

    // -----------------------------------------------------------------------
    // Core retry logic
    // -----------------------------------------------------------------------

    /**
     * Execute an operation with retry logic, back-off, and context mutation.
     * Translated from withRetry() in withRetry.ts
     *
     * @param clientSupplier  Supplier for a fresh API client (called on first attempt and after auth errors).
     * @param operation       (client, attempt, context) -> result
     * @param options         Retry parameters.
     * @param <T>             Result type.
     * @return CompletableFuture with the operation result.
     */
    public <T> CompletableFuture<T> withRetry(
            Supplier<CompletableFuture<AnthropicClient>> clientSupplier,
            TriFunction<AnthropicClient, Integer, RetryContext, CompletableFuture<T>> operation,
            RetryOptions options) {

        return CompletableFuture.supplyAsync(() -> {
            int maxRetries = options.getMaxRetries();
            RetryContext context = new RetryContext(options.getModel());
            AnthropicClient client = null;
            int consecutive529Errors = options.getInitialConsecutive529Errors() != null
                ? options.getInitialConsecutive529Errors() : 0;
            Throwable lastError = null;

            for (int attempt = 1; attempt <= maxRetries + 1; attempt++) {
                if (options.isAborted()) {
                    throw new RuntimeException("Request aborted");
                }

                try {
                    // Get a fresh client on first attempt or after auth errors
                    if (client == null || isAuthError(lastError)) {
                        try {
                            client = clientSupplier.get().get();
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to get API client", e);
                        }
                    }

                    return operation.apply(client, attempt, context).get();

                } catch (java.util.concurrent.ExecutionException ee) {
                    lastError = ee.getCause() != null ? ee.getCause() : ee;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry", ie);
                } catch (Exception e) {
                    lastError = e;
                }

                log.debug("API error (attempt {}/{}): {}", attempt, maxRetries + 1,
                    lastError != null ? lastError.getMessage() : "unknown");

                // Handle 529 consecutive tracking / fallback
                if (ApiErrorService.is529Error(lastError)) {
                    if (!shouldRetry529(options.getQuerySource())) {
                        throw new CannotRetryError(lastError, context);
                    }
                    consecutive529Errors++;
                    if (consecutive529Errors >= MAX_529_RETRIES && options.getFallbackModel() != null) {
                        throw new FallbackTriggeredError(options.getModel(), options.getFallbackModel());
                    }
                    if (consecutive529Errors >= MAX_529_RETRIES) {
                        String userType = System.getenv("USER_TYPE");
                        if ("external".equals(userType)) {
                            throw new CannotRetryError(
                                new RuntimeException(ApiErrorService.REPEATED_529_ERROR_MESSAGE), context);
                        }
                    }
                }

                // Check if we've exhausted retries
                if (attempt > maxRetries) {
                    throw new CannotRetryError(lastError, context);
                }

                // Check if error is retryable
                if (!shouldRetry(lastError)) {
                    throw new CannotRetryError(lastError, context);
                }

                // Handle max_tokens context overflow: adjust for next attempt
                if (lastError instanceof AnthropicClient.ApiException apiEx) {
                    Optional<MaxTokensOverflowInfo> overflow = parseMaxTokensContextOverflowError(apiEx);
                    if (overflow.isPresent()) {
                        MaxTokensOverflowInfo info = overflow.get();
                        int safetyBuffer = 1_000;
                        int availableContext = Math.max(0, info.contextLimit() - info.inputTokens() - safetyBuffer);
                        if (availableContext < FLOOR_OUTPUT_TOKENS) {
                            throw new CannotRetryError(lastError, context);
                        }
                        context.setMaxTokensOverride(Math.max(FLOOR_OUTPUT_TOKENS, availableContext));
                        continue;
                    }
                }

                // Compute back-off delay
                String retryAfter = getRetryAfterHeader(lastError);
                long delayMs = getRetryDelay(attempt, retryAfter, MAX_DELAY_MS);

                log.debug("Retrying in {}ms (attempt {}/{})", delayMs, attempt, maxRetries + 1);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry sleep", ie);
                }
            }

            throw new CannotRetryError(lastError, context);
        });
    }

    // -----------------------------------------------------------------------
    // getRetryDelay  (exported, matches TypeScript implementation exactly)
    // -----------------------------------------------------------------------

    /**
     * Calculate retry delay with exponential back-off and optional jitter.
     * Translated from getRetryDelay() in withRetry.ts
     */
    public static long getRetryDelay(int attempt, String retryAfterHeader, long maxDelayMs) {
        if (retryAfterHeader != null && !retryAfterHeader.isBlank()) {
            try {
                long seconds = Long.parseLong(retryAfterHeader.trim());
                return seconds * 1_000;
            } catch (NumberFormatException ignored) { /* fall through */ }
        }
        long baseDelay = Math.min(BASE_DELAY_MS * (1L << (attempt - 1)), maxDelayMs);
        long jitter = (long) (Math.random() * 0.25 * baseDelay);
        return baseDelay + jitter;
    }

    public static long getRetryDelay(int attempt, String retryAfterHeader) {
        return getRetryDelay(attempt, retryAfterHeader, MAX_DELAY_MS);
    }

    // -----------------------------------------------------------------------
    // getDefaultMaxRetries
    // -----------------------------------------------------------------------

    public static int getDefaultMaxRetries() {
        String envVal = System.getenv("CLAUDE_CODE_MAX_RETRIES");
        if (envVal != null && !envVal.isBlank()) {
            try { return Integer.parseInt(envVal.trim()); }
            catch (NumberFormatException ignored) { /* fall through */ }
        }
        return DEFAULT_MAX_RETRIES;
    }

    // -----------------------------------------------------------------------
    // parseMaxTokensContextOverflowError
    // -----------------------------------------------------------------------

    /**
     * Parse inputTokens/maxTokens/contextLimit from a 400 "exceed context limit" error.
     * Translated from parseMaxTokensContextOverflowError() in withRetry.ts
     *
     * Example message: "input length and `max_tokens` exceed context limit: 188059 + 20000 > 200000"
     */
    public Optional<MaxTokensOverflowInfo> parseMaxTokensContextOverflowError(
            AnthropicClient.ApiException error) {
        if (error == null || error.getStatusCode() != 400) return Optional.empty();

        String message = error.getMessage();
        if (message == null || !message.contains("input length and `max_tokens` exceed context limit")) {
            return Optional.empty();
        }

        java.util.regex.Pattern regex = java.util.regex.Pattern.compile(
            "input length and `max_tokens` exceed context limit: (\\d+) \\+ (\\d+) > (\\d+)");
        java.util.regex.Matcher m = regex.matcher(message);
        if (!m.find()) return Optional.empty();

        try {
            int inputTokens   = Integer.parseInt(m.group(1));
            int maxTokens     = Integer.parseInt(m.group(2));
            int contextLimit  = Integer.parseInt(m.group(3));
            return Optional.of(new MaxTokensOverflowInfo(inputTokens, maxTokens, contextLimit));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private boolean shouldRetry529(String querySource) {
        if (querySource == null) return true; // conservative default
        return FOREGROUND_529_RETRY_SOURCES.contains(querySource)
            || FOREGROUND_529_RETRY_SOURCES.stream().anyMatch(querySource::startsWith);
    }

    private boolean shouldRetry(Throwable error) {
        if (error == null) return false;

        if (error instanceof AnthropicClient.ApiException apiEx) {
            int status = apiEx.getStatusCode();
            // Always retry overloaded errors
            if (ApiErrorService.is529Error(apiEx)) return true;
            // Retry rate limits
            if (status == 429) return true;
            // Retry auth errors (client will refresh tokens)
            if (status == 401 || status == 403) return true;
            // Retry request/lock timeouts
            if (status == 408 || status == 409) return true;
            // Retry server errors
            if (status >= 500) return true;
            // Retry context overflow (handled via maxTokensOverride)
            if (parseMaxTokensContextOverflowError(apiEx).isPresent()) return true;
            return false;
        }

        // Connection / network errors are always retryable
        if (error instanceof java.net.SocketTimeoutException
            || error instanceof java.net.ConnectException
            || error instanceof java.io.IOException) {
            return true;
        }

        return false;
    }

    private boolean isAuthError(Throwable error) {
        if (error instanceof AnthropicClient.ApiException apiEx) {
            int status = apiEx.getStatusCode();
            return status == 401 || status == 403;
        }
        return false;
    }

    private String getRetryAfterHeader(Throwable error) {
        if (error instanceof AnthropicClient.ApiException apiEx) {
            return apiEx.getResponseHeader("retry-after");
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Functional interface (TriFunction not in standard Java)
    // -----------------------------------------------------------------------

    @FunctionalInterface
    public interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }
}
