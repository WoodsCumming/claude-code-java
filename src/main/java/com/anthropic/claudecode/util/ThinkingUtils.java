package com.anthropic.claudecode.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extended-thinking ("ultrathink") utilities.
 * Translated from src/utils/thinking.ts
 *
 * Manages thinking configuration types, keyword detection, and model-capability
 * checks for extended thinking support.
 */
public final class ThinkingUtils {

    // -------------------------------------------------------------------------
    // Types
    // -------------------------------------------------------------------------

    /**
     * Thinking configuration variants.
     * Translated from the ThinkingConfig union type in thinking.ts.
     */
    public sealed interface ThinkingConfig permits
            ThinkingConfig.Adaptive,
            ThinkingConfig.Enabled,
            ThinkingConfig.Disabled {

        record Adaptive() implements ThinkingConfig {}

        record Enabled(int budgetTokens) implements ThinkingConfig {}

        record Disabled() implements ThinkingConfig {}
    }

    /**
     * Position of a thinking keyword within text.
     * Translated from the return type of findThinkingTriggerPositions() in thinking.ts.
     */
    public record KeywordPosition(String word, int start, int end) {}

    // -------------------------------------------------------------------------
    // Keyword detection
    // -------------------------------------------------------------------------

    private static final Pattern ULTRATHINK_PATTERN =
        Pattern.compile("\\bultrathink\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Check if the "ultrathink" keyword is present in text.
     * Translated from hasUltrathinkKeyword() in thinking.ts
     */
    public static boolean hasUltrathinkKeyword(String text) {
        if (text == null) return false;
        return ULTRATHINK_PATTERN.matcher(text).find();
    }

    /**
     * Find all positions of the "ultrathink" keyword in text (for UI highlighting).
     * A fresh Matcher is created each call to avoid shared lastIndex state.
     * Translated from findThinkingTriggerPositions() in thinking.ts
     */
    public static List<KeywordPosition> findThinkingTriggerPositions(String text) {
        if (text == null) return List.of();

        List<KeywordPosition> positions = new ArrayList<>();
        // Use a fresh pattern instance to avoid shared-lastIndex bugs, matching
        // the TS comment about creating a fresh /g literal each call.
        Matcher matcher = Pattern.compile("\\bultrathink\\b", Pattern.CASE_INSENSITIVE)
            .matcher(text);

        while (matcher.find()) {
            positions.add(new KeywordPosition(matcher.group(), matcher.start(), matcher.end()));
        }
        return positions;
    }

    // -------------------------------------------------------------------------
    // Feature flags
    // -------------------------------------------------------------------------

    /**
     * Check if ultrathink is enabled.
     * The TypeScript source gates on a build-time feature flag and a GrowthBook
     * runtime flag. In Java we approximate with an environment variable.
     * Translated from isUltrathinkEnabled() in thinking.ts
     */
    public static boolean isUltrathinkEnabled() {
        return EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_ENABLE_ULTRATHINK"));
    }

    // -------------------------------------------------------------------------
    // Model capability checks
    // -------------------------------------------------------------------------

    /**
     * Check whether a model supports extended thinking.
     * Translated from modelSupportsThinking() in thinking.ts
     *
     * Rules:
     * - 1P / Foundry: all Claude 4+ models support thinking (claude-3-* do not).
     * - 3P (Bedrock/Vertex): only Sonnet 4+ and Opus 4+.
     */
    public static boolean modelSupportsThinking(String model) {
        if (model == null) return false;
        String canonical = getCanonicalName(model);
        String provider  = getApiProvider();

        if ("foundry".equals(provider) || "firstParty".equals(provider)) {
            return !canonical.contains("claude-3-");
        }
        // 3P: only Sonnet 4+ and Opus 4+
        return canonical.contains("sonnet-4") || canonical.contains("opus-4");
    }

    /**
     * Check whether a model supports adaptive thinking.
     * Translated from modelSupportsAdaptiveThinking() in thinking.ts
     */
    public static boolean modelSupportsAdaptiveThinking(String model) {
        if (model == null) return false;
        String canonical = getCanonicalName(model);

        // Supported by a subset of Claude 4 models (4-6 variants)
        if (canonical.contains("opus-4-6") || canonical.contains("sonnet-4-6")) {
            return true;
        }

        // Exclude other known legacy model families
        if (canonical.contains("opus") || canonical.contains("sonnet")
                || canonical.contains("haiku")) {
            return false;
        }

        // Newer (4.6+) models: default to true on 1P and Foundry
        String provider = getApiProvider();
        return "firstParty".equals(provider) || "foundry".equals(provider);
    }

    /**
     * Decide whether thinking should be enabled by default.
     * Translated from shouldEnableThinkingByDefault() in thinking.ts
     */
    public static boolean shouldEnableThinkingByDefault() {
        String maxThinkingTokens = System.getenv("MAX_THINKING_TOKENS");
        if (maxThinkingTokens != null && !maxThinkingTokens.isBlank()) {
            try {
                return Integer.parseInt(maxThinkingTokens.trim()) > 0;
            } catch (NumberFormatException ignored) {}
        }
        // Enable thinking by default unless explicitly disabled via settings.
        // The Java side cannot read alwaysThinkingEnabled from settings here;
        // callers that have settings access should check that flag themselves.
        return true;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Derive a canonical model name for capability comparisons.
     * Lowercases and strips known vendor prefixes (e.g. "anthropic.", "claude.").
     */
    static String getCanonicalName(String model) {
        if (model == null) return "";
        return model.toLowerCase()
            .replaceFirst("^anthropic\\.", "")
            .replaceFirst("^claude\\.", "");
    }

    /**
     * Determine the current API provider from the environment.
     * Returns "firstParty", "bedrock", "vertex", or "foundry".
     */
    static String getApiProvider() {
        String provider = System.getenv("CLAUDE_CODE_API_PROVIDER");
        if (provider != null && !provider.isBlank()) return provider.toLowerCase();
        // Infer from endpoint variables
        if (System.getenv("ANTHROPIC_BEDROCK_BASE_URL") != null
                || "aws".equalsIgnoreCase(System.getenv("ANTHROPIC_API_PLATFORM"))) {
            return "bedrock";
        }
        if (System.getenv("ANTHROPIC_VERTEX_PROJECT_ID") != null
                || "gcp".equalsIgnoreCase(System.getenv("ANTHROPIC_API_PLATFORM"))) {
            return "vertex";
        }
        return "firstParty";
    }

    private ThinkingUtils() {}
}
