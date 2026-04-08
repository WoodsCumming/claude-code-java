package com.anthropic.claudecode.util;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Model utility functions.
 * Translated from src/utils/model/model.ts and src/utils/context.ts
 */
public class ModelUtils {

    // Context window sizes
    public static final int MODEL_CONTEXT_WINDOW_DEFAULT = 200_000;
    public static final int COMPACT_MAX_OUTPUT_TOKENS = 20_000;
    public static final int MAX_OUTPUT_TOKENS_DEFAULT = 32_000;
    public static final int CAPPED_DEFAULT_MAX_TOKENS = 8_000;
    public static final int ESCALATED_MAX_TOKENS = 64_000;

    // Model name patterns
    private static final Pattern OPUS_4_PATTERN = Pattern.compile("opus-4", Pattern.CASE_INSENSITIVE);
    private static final Pattern SONNET_4_PATTERN = Pattern.compile("sonnet-4", Pattern.CASE_INSENSITIVE);
    private static final Pattern HAIKU_4_PATTERN = Pattern.compile("haiku-4", Pattern.CASE_INSENSITIVE);

    // Known model IDs
    public static final String DEFAULT_OPUS_MODEL = "claude-opus-4-6";
    public static final String DEFAULT_SONNET_MODEL = "claude-sonnet-4-6";
    public static final String SMALL_FAST_MODEL = "claude-haiku-4-5-20251001";

    /**
     * Get the canonical model name.
     * Translated from getCanonicalName() in model.ts
     */
    public static String getCanonicalName(String model) {
        if (model == null) return DEFAULT_OPUS_MODEL;
        // Remove any version suffixes like "-20241022"
        return model.replaceAll("-\\d{8}$", "");
    }

    /**
     * Get the context window size for a model.
     * Translated from getContextWindowForModel() in context.ts
     */
    public static int getContextWindowForModel(String model) {
        if (model == null) return MODEL_CONTEXT_WINDOW_DEFAULT;
        // All current Claude models support 200k context
        return MODEL_CONTEXT_WINDOW_DEFAULT;
    }

    /**
     * Get max output tokens for a model.
     * Translated from getModelMaxOutputTokens() in context.ts
     */
    public static int getModelMaxOutputTokens(String model) {
        return MAX_OUTPUT_TOKENS_DEFAULT;
    }

    /**
     * Check if model supports 1M context.
     * Translated from modelSupports1M() in context.ts
     */
    public static boolean modelSupports1M(String model) {
        if (model == null) return false;
        String canonical = getCanonicalName(model);
        return canonical.contains("claude-sonnet-4") || canonical.contains("opus-4-6");
    }

    /**
     * Get the default Opus model.
     * Translated from getDefaultOpusModel() in model.ts
     */
    public static String getDefaultOpusModel() {
        return DEFAULT_OPUS_MODEL;
    }

    /**
     * Get the default Sonnet model.
     * Translated from getDefaultSonnetModel() in model.ts
     */
    public static String getDefaultSonnetModel() {
        return DEFAULT_SONNET_MODEL;
    }

    /**
     * Get the small/fast model (Haiku).
     * Translated from getSmallFastModel() in model.ts
     */
    public static String getSmallFastModel() {
        String envModel = System.getenv("ANTHROPIC_SMALL_FAST_MODEL");
        return envModel != null ? envModel : SMALL_FAST_MODEL;
    }

    /**
     * Get the marketing name for a model.
     * Translated from getMarketingNameForModel() in model.ts
     */
    public static String getMarketingName(String model) {
        if (model == null) return "Claude";
        if (model.contains("opus")) return "Claude Opus";
        if (model.contains("sonnet")) return "Claude Sonnet";
        if (model.contains("haiku")) return "Claude Haiku";
        return "Claude";
    }

    /**
     * Estimate cost for a model request.
     * Translated from calculateUSDCost() in modelCost.ts
     */
    public static double calculateUSDCost(
            String model,
            int inputTokens,
            int outputTokens,
            int cacheReadTokens,
            int cacheCreationTokens) {

        // Approximate pricing per million tokens
        double[] prices = getPricesForModel(model);
        double inputPrice = prices[0];
        double outputPrice = prices[1];
        double cacheReadPrice = prices[2];
        double cacheWritePrice = prices[3];

        return (inputTokens * inputPrice
            + outputTokens * outputPrice
            + cacheReadTokens * cacheReadPrice
            + cacheCreationTokens * cacheWritePrice) / 1_000_000.0;
    }

    private static double[] getPricesForModel(String model) {
        if (model == null) return new double[]{15.0, 75.0, 1.5, 18.75};

        if (model.contains("opus")) {
            return new double[]{15.0, 75.0, 1.5, 18.75}; // Opus 4.6
        } else if (model.contains("sonnet")) {
            return new double[]{3.0, 15.0, 0.3, 3.75}; // Sonnet 4.6
        } else if (model.contains("haiku")) {
            return new double[]{0.8, 4.0, 0.08, 1.0}; // Haiku 4.5
        }
        return new double[]{15.0, 75.0, 1.5, 18.75}; // Default to Opus pricing
    }

    private ModelUtils() {}
}
