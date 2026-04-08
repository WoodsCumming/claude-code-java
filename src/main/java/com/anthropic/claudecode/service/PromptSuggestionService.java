package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Prompt suggestion service.
 * Translated from src/services/PromptSuggestion/promptSuggestion.ts
 *
 * Generates next-turn prompt suggestions for the user based on recent conversation.
 * Uses a forked agent call that piggybacks on the main thread's prompt cache.
 */
@Slf4j
@Service
public class PromptSuggestionService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PromptSuggestionService.class);


    private static final int MAX_PARENT_UNCACHED_TOKENS = 10_000;

    /** Suggestion prompt injected as a user message to the forked agent. */
    private static final String SUGGESTION_PROMPT =
        "[SUGGESTION MODE: Suggest what the user might naturally type next into Claude Code.]\n\n" +
        "FIRST: Look at the user's recent messages and original request.\n\n" +
        "Your job is to predict what THEY would type - not what you think they should do.\n\n" +
        "THE TEST: Would they think \"I was just about to type that\"?\n\n" +
        "EXAMPLES:\n" +
        "User asked \"fix the bug and run tests\", bug is fixed → \"run the tests\"\n" +
        "After code written → \"try it out\"\n" +
        "Claude offers options → suggest the one the user would likely pick, based on conversation\n" +
        "Claude asks to continue → \"yes\" or \"go ahead\"\n" +
        "Task complete, obvious follow-up → \"commit this\" or \"push it\"\n" +
        "After error or misunderstanding → silence (let them assess/correct)\n\n" +
        "Be specific: \"run the tests\" beats \"continue\".\n\n" +
        "NEVER SUGGEST:\n" +
        "- Evaluative (\"looks good\", \"thanks\")\n" +
        "- Questions (\"what about...?\")\n" +
        "- Claude-voice (\"Let me...\", \"I'll...\", \"Here's...\")\n" +
        "- New ideas they didn't ask about\n" +
        "- Multiple sentences\n\n" +
        "Stay silent if the next step isn't obvious from what the user said.\n\n" +
        "Format: 2-12 words, match the user's style. Or nothing.\n\n" +
        "Reply with ONLY the suggestion, no quotes or explanation.";

    private static final Set<String> ALLOWED_SINGLE_WORDS = Set.of(
        "yes", "yeah", "yep", "yea", "yup", "sure", "ok", "okay",
        "push", "commit", "deploy", "stop", "continue", "check", "exit", "quit", "no"
    );

    private final AtomicReference<String> currentAbortRef = new AtomicReference<>();

    private final SettingsService settingsService;
    private final AnalyticsService analyticsService;

    @Autowired
    public PromptSuggestionService(SettingsService settingsService, AnalyticsService analyticsService) {
        this.settingsService = settingsService;
        this.analyticsService = analyticsService;
    }

    /**
     * The prompt variant type.
     * Translated from PromptVariant in promptSuggestion.ts
     */
    public enum PromptVariant {
        USER_INTENT("user_intent"),
        STATED_INTENT("stated_intent");

        private final String value;
        PromptVariant(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    /**
     * Get the current prompt variant (always user_intent currently).
     * Translated from getPromptVariant() in promptSuggestion.ts
     */
    public PromptVariant getPromptVariant() {
        return PromptVariant.USER_INTENT;
    }

    /**
     * Determine whether prompt suggestion is enabled.
     * Checks env override, feature flag, non-interactive mode, and settings.
     * Translated from shouldEnablePromptSuggestion() in promptSuggestion.ts
     */
    public boolean shouldEnablePromptSuggestion() {
        String envOverride = System.getenv("CLAUDE_CODE_ENABLE_PROMPT_SUGGESTION");
        if (isEnvDefinedFalsy(envOverride)) {
            logEvent("tengu_prompt_suggestion_init", Map.of("enabled", false, "source", "env"));
            return false;
        }
        if (isEnvTruthy(envOverride)) {
            logEvent("tengu_prompt_suggestion_init", Map.of("enabled", true, "source", "env"));
            return true;
        }

        // Check user setting (default enabled)
        boolean enabled = !Boolean.FALSE.equals(settingsService.getPromptSuggestionEnabled());
        logEvent("tengu_prompt_suggestion_init", Map.of("enabled", enabled, "source", "setting"));
        return enabled;
    }

    /**
     * Abort any in-progress suggestion generation.
     * Translated from abortPromptSuggestion() in promptSuggestion.ts
     */
    public void abortPromptSuggestion() {
        currentAbortRef.set("aborted");
    }

    /**
     * Filter out suggestions that should not be shown to the user.
     * Returns true if the suggestion should be filtered (suppressed).
     * Translated from shouldFilterSuggestion() in promptSuggestion.ts
     */
    public boolean shouldFilterSuggestion(String suggestion, PromptVariant promptId, String source) {
        if (suggestion == null || suggestion.isBlank()) {
            logSuggestionSuppressed("empty", null, promptId, source);
            return true;
        }

        String lower = suggestion.toLowerCase().trim();
        String[] words = suggestion.trim().split("\\s+");
        int wordCount = words.length;

        // done
        if ("done".equals(lower)) {
            logSuggestionSuppressed("done", suggestion, promptId, source);
            return true;
        }

        // meta text — model outputting instructions instead of suggestion
        if ("nothing found".equals(lower) || "nothing found.".equals(lower)
                || lower.startsWith("nothing to suggest")
                || lower.startsWith("no suggestion")
                || Pattern.compile("\\bsilence is\\b|\\bstay(s|ing)? silent\\b").matcher(lower).find()
                || Pattern.compile("^\\W*silence\\W*$").matcher(lower).matches()) {
            logSuggestionSuppressed("meta_text", suggestion, promptId, source);
            return true;
        }

        // meta wrapped in parens/brackets
        if (Pattern.compile("^\\(.*\\)$|^\\[.*\\]$").matcher(suggestion).matches()) {
            logSuggestionSuppressed("meta_wrapped", suggestion, promptId, source);
            return true;
        }

        // error messages
        if (lower.startsWith("api error:") || lower.startsWith("prompt is too long")
                || lower.startsWith("request timed out") || lower.startsWith("invalid api key")
                || lower.startsWith("image was too large")) {
            logSuggestionSuppressed("error_message", suggestion, promptId, source);
            return true;
        }

        // prefixed label like "Suggestion: ..."
        if (Pattern.compile("^\\w+:\\s").matcher(suggestion).find()) {
            logSuggestionSuppressed("prefixed_label", suggestion, promptId, source);
            return true;
        }

        // too few words
        if (wordCount < 2) {
            if (!suggestion.startsWith("/") && !ALLOWED_SINGLE_WORDS.contains(lower)) {
                logSuggestionSuppressed("too_few_words", suggestion, promptId, source);
                return true;
            }
        }

        // too many words
        if (wordCount > 12) {
            logSuggestionSuppressed("too_many_words", suggestion, promptId, source);
            return true;
        }

        // too long
        if (suggestion.length() >= 100) {
            logSuggestionSuppressed("too_long", suggestion, promptId, source);
            return true;
        }

        // multiple sentences
        if (Pattern.compile("[.!?]\\s+[A-Z]").matcher(suggestion).find()) {
            logSuggestionSuppressed("multiple_sentences", suggestion, promptId, source);
            return true;
        }

        // has markdown formatting
        if (Pattern.compile("[\n*]|\\*\\*").matcher(suggestion).find()) {
            logSuggestionSuppressed("has_formatting", suggestion, promptId, source);
            return true;
        }

        // evaluative responses
        if (Pattern.compile("thanks|thank you|looks good|sounds good|that works|that worked|" +
                "that's all|nice|great|perfect|makes sense|awesome|excellent").matcher(lower).find()) {
            logSuggestionSuppressed("evaluative", suggestion, promptId, source);
            return true;
        }

        // claude voice (model speaking as itself, not predicting user input)
        if (Pattern.compile("^(let me|i'll|i've|i'm|i can|i would|i think|i notice|here's|here is|" +
                "here are|that's|this is|this will|you can|you should|you could|sure,|of course|certainly)",
                Pattern.CASE_INSENSITIVE).matcher(suggestion).find()) {
            logSuggestionSuppressed("claude_voice", suggestion, promptId, source);
            return true;
        }

        return false;
    }

    /**
     * Check whether the parent message's cache state should suppress suggestions.
     * Returns a suppression reason string, or null if generation is allowed.
     * Translated from getParentCacheSuppressReason() in promptSuggestion.ts
     */
    public Optional<String> getParentCacheSuppressReason(
            Integer inputTokens, Integer cacheWriteTokens, Integer outputTokens) {
        int total = (inputTokens != null ? inputTokens : 0)
            + (cacheWriteTokens != null ? cacheWriteTokens : 0)
            + (outputTokens != null ? outputTokens : 0);
        return total > MAX_PARENT_UNCACHED_TOKENS ? Optional.of("cache_cold") : Optional.empty();
    }

    /**
     * Log the outcome of a suggestion (accepted or ignored).
     * Translated from logSuggestionOutcome() in promptSuggestion.ts
     */
    public void logSuggestionOutcome(
            String suggestion, String userInput, long emittedAt,
            PromptVariant promptId, String generationRequestId) {
        double similarity = Math.round((userInput.length() / (double) Math.max(suggestion.length(), 1)) * 100.0) / 100.0;
        boolean wasAccepted = userInput.equals(suggestion);
        long timeMs = Math.max(0, System.currentTimeMillis() - emittedAt);

        Map<String, Object> props = new java.util.HashMap<>();
        props.put("source", "sdk");
        props.put("outcome", wasAccepted ? "accepted" : "ignored");
        props.put("prompt_id", promptId.getValue());
        if (generationRequestId != null) props.put("generationRequestId", generationRequestId);
        if (wasAccepted) props.put("timeToAcceptMs", timeMs);
        else props.put("timeToIgnoreMs", timeMs);
        props.put("similarity", similarity);

        logEvent("tengu_prompt_suggestion", props);
    }

    /**
     * Log a suppressed suggestion event.
     * Translated from logSuggestionSuppressed() in promptSuggestion.ts
     */
    public void logSuggestionSuppressed(String reason, String suggestion, PromptVariant promptId, String source) {
        PromptVariant resolvedPromptId = promptId != null ? promptId : getPromptVariant();

        Map<String, Object> props = new java.util.HashMap<>();
        if (source != null) props.put("source", source);
        props.put("outcome", "suppressed");
        props.put("reason", reason);
        props.put("prompt_id", resolvedPromptId.getValue());
        if (suggestion != null && "ant".equals(System.getenv("USER_TYPE"))) {
            props.put("suggestion", suggestion);
        }

        logEvent("tengu_prompt_suggestion", props);
    }

    // ─── Internal helpers ─────────────────────────────────────

    private void logEvent(String eventName, Map<String, Object> properties) {
        log.debug("Analytics: {} {}", eventName, properties);
        analyticsService.logEvent(eventName, properties);
    }

    private boolean isEnvDefinedFalsy(String value) {
        return value != null && (value.isBlank() || "false".equalsIgnoreCase(value)
            || "0".equals(value) || "no".equalsIgnoreCase(value));
    }

    private boolean isEnvTruthy(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    /**
     * Fire-and-forget async prompt suggestion execution.
     * Called by StopHookService after each turn.
     */
    public void executePromptSuggestionAsync(
            Object systemPrompt,
            java.util.Map<String, String> userContext,
            java.util.Map<String, String> systemContext,
            Object toolUseContext,
            String querySource) {
        if (!shouldEnablePromptSuggestion()) return;
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                log.debug("[PromptSuggestion] executePromptSuggestionAsync querySource={}", querySource);
                // Full implementation would generate a prompt suggestion here
            } catch (Exception e) {
                log.debug("[PromptSuggestion] Error in async execution: {}", e.getMessage());
            }
        });
    }

    /**
     * Result from generating a suggestion.
     */
    @Data
    @lombok.NoArgsConstructor(force = true)
    @lombok.AllArgsConstructor
    public static class SuggestionResult {
        private final String suggestion;
        private final PromptVariant promptId;
        private final String generationRequestId;

        public String getSuggestion() { return suggestion; }
        public PromptVariant getPromptId() { return promptId; }
        public String getGenerationRequestId() { return generationRequestId; }
    }
}
