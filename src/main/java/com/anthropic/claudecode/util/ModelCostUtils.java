package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.util.*;

/**
 * Model cost calculation utilities.
 * Translated from src/utils/modelCost.ts
 *
 * Provides pricing information and cost calculations for Claude models.
 * Costs are in USD per million tokens (Mtok).
 *
 * @see <a href="https://platform.claude.com/docs/en/about-claude/pricing">Anthropic Pricing</a>
 */
@Slf4j
public class ModelCostUtils {



    // =========================================================================
    // Pricing tiers (USD per million tokens)
    // =========================================================================

    /** Standard pricing tier for Sonnet models: $3 input / $15 output per Mtok */
    public static final ModelCosts COST_TIER_3_15 =
            new ModelCosts(3.0, 15.0, 3.75, 0.3, 0.01);

    /** Pricing tier for Opus 4 / 4.1: $15 input / $75 output per Mtok */
    public static final ModelCosts COST_TIER_15_75 =
            new ModelCosts(15.0, 75.0, 18.75, 1.5, 0.01);

    /** Pricing tier for Opus 4.5: $5 input / $25 output per Mtok */
    public static final ModelCosts COST_TIER_5_25 =
            new ModelCosts(5.0, 25.0, 6.25, 0.5, 0.01);

    /** Fast mode pricing for Opus 4.6: $30 input / $150 output per Mtok */
    public static final ModelCosts COST_TIER_30_150 =
            new ModelCosts(30.0, 150.0, 37.5, 3.0, 0.01);

    /** Pricing for Haiku 3.5: $0.80 input / $4 output per Mtok */
    public static final ModelCosts COST_HAIKU_35 =
            new ModelCosts(0.8, 4.0, 1.0, 0.08, 0.01);

    /** Pricing for Haiku 4.5: $1 input / $5 output per Mtok */
    public static final ModelCosts COST_HAIKU_45 =
            new ModelCosts(1.0, 5.0, 1.25, 0.1, 0.01);

    /** Default fallback when the model is unknown. */
    private static final ModelCosts DEFAULT_UNKNOWN_MODEL_COST = COST_TIER_5_25;

    // =========================================================================
    // Per-model cost map
    // NOTE: add new models here on model launch. Keys are canonical short names.
    // =========================================================================
    private static final Map<String, ModelCosts> MODEL_COSTS;

    static {
        Map<String, ModelCosts> m = new LinkedHashMap<>();
        // Haiku
        m.put("claude-3-5-haiku",       COST_HAIKU_35);
        m.put("claude-haiku-4-5",       COST_HAIKU_45);
        // Sonnet
        m.put("claude-3-5-sonnet-v2",   COST_TIER_3_15);
        m.put("claude-3-7-sonnet",      COST_TIER_3_15);
        m.put("claude-sonnet-4",        COST_TIER_3_15);
        m.put("claude-sonnet-4-5",      COST_TIER_3_15);
        m.put("claude-sonnet-4-6",      COST_TIER_3_15);
        // Opus
        m.put("claude-opus-4",          COST_TIER_15_75);
        m.put("claude-opus-4-1",        COST_TIER_15_75);
        m.put("claude-opus-4-5",        COST_TIER_5_25);
        // Opus 4.6 base tier (fast-mode tier is resolved at call time)
        m.put("claude-opus-4-6",        COST_TIER_5_25);
        MODEL_COSTS = Collections.unmodifiableMap(m);
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Get the cost tier for Opus 4.6 based on fast-mode flag.
     * Translated from getOpus46CostTier() in modelCost.ts
     */
    public static ModelCosts getOpus46CostTier(boolean fastMode) {
        if (fastMode) {
            return COST_TIER_30_150;
        }
        return COST_TIER_5_25;
    }

    /**
     * Get the {@link ModelCosts} for a model, accounting for Opus 4.6 fast mode.
     * Translated from getModelCosts() in modelCost.ts
     *
     * @param model      Full or canonical model name
     * @param isFastMode Whether the request is in fast mode (only matters for Opus 4.6)
     * @return The applicable cost tier
     */
    public static ModelCosts getModelCosts(String model, boolean isFastMode) {
        String shortName = ModelUtils.getCanonicalName(model);

        // Opus 4.6 has a dynamic cost tier depending on fast mode
        if ("claude-opus-4-6".equals(shortName)) {
            return getOpus46CostTier(isFastMode);
        }

        ModelCosts costs = MODEL_COSTS.get(shortName);
        if (costs == null) {
            log.warn("Unknown model cost for model='{}' shortName='{}', using default", model, shortName);
            return DEFAULT_UNKNOWN_MODEL_COST;
        }
        return costs;
    }

    /**
     * Get model costs without fast-mode consideration.
     * Convenience overload for callers that don't track fast mode.
     */
    public static ModelCosts getModelCosts(String model) {
        return getModelCosts(model, false);
    }

    /**
     * Calculate USD cost from a full token-usage breakdown.
     * Translated from calculateUSDCost() in modelCost.ts
     *
     * @param resolvedModel         Full or canonical model name
     * @param inputTokens           Regular input tokens
     * @param outputTokens          Output tokens
     * @param cacheReadInputTokens  Prompt-cache read tokens (cheaper)
     * @param cacheCreationTokens   Prompt-cache write tokens (slightly more expensive)
     * @param webSearchRequests     Number of server-side web searches
     * @param isFastMode            Whether this request is in fast mode
     * @return Cost in USD
     */
    public static double calculateUSDCost(
            String resolvedModel,
            long inputTokens,
            long outputTokens,
            long cacheReadInputTokens,
            long cacheCreationTokens,
            long webSearchRequests,
            boolean isFastMode) {

        ModelCosts costs = getModelCosts(resolvedModel, isFastMode);
        return tokensToUSDCost(costs, inputTokens, outputTokens,
                cacheReadInputTokens, cacheCreationTokens, webSearchRequests);
    }

    /**
     * Convenience overload without fast-mode or web-search parameters.
     */
    public static double calculateUSDCost(
            String resolvedModel,
            long inputTokens,
            long outputTokens,
            long cacheReadInputTokens,
            long cacheCreationTokens) {

        return calculateUSDCost(resolvedModel, inputTokens, outputTokens,
                cacheReadInputTokens, cacheCreationTokens, 0, false);
    }

    /**
     * Calculate cost from raw token counts (no BetaUsage object required).
     * Translated from calculateCostFromTokens() in modelCost.ts
     *
     * Useful for side queries (e.g. classifier) that track token counts independently.
     */
    public static double calculateCostFromTokens(
            String model,
            long inputTokens,
            long outputTokens,
            long cacheReadInputTokens,
            long cacheCreationInputTokens) {

        return calculateUSDCost(model, inputTokens, outputTokens,
                cacheReadInputTokens, cacheCreationInputTokens, 0, false);
    }

    /**
     * Format model costs as a pricing string for display.
     * Translated from formatModelPricing() in modelCost.ts
     *
     * Example: "$3/$15 per Mtok"
     */
    public static String formatModelPricing(ModelCosts costs) {
        return formatPrice(costs.inputTokens()) + "/" + formatPrice(costs.outputTokens()) + " per Mtok";
    }

    /**
     * Get a formatted pricing string for a model, or null if the model is unknown.
     * Translated from getModelPricingString() in modelCost.ts
     */
    public static Optional<String> getModelPricingString(String model) {
        String shortName = ModelUtils.getCanonicalName(model);
        ModelCosts costs = MODEL_COSTS.get(shortName);
        if (costs == null) return Optional.empty();
        return Optional.of(formatModelPricing(costs));
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /**
     * Immutable pricing record for a model tier.
     * All values are in USD per million tokens (Mtok), except webSearchRequests
     * which is per-request.
     * Translated from ModelCosts in modelCost.ts
     */
    public record ModelCosts(
            double inputTokens,
            double outputTokens,
            double promptCacheWriteTokens,
            double promptCacheReadTokens,
            double webSearchRequests
    ) {}

    // =========================================================================
    // Private helpers
    // =========================================================================

    private static double tokensToUSDCost(
            ModelCosts costs,
            long inputTokens,
            long outputTokens,
            long cacheReadInputTokens,
            long cacheCreationTokens,
            long webSearchRequests) {

        return (inputTokens / 1_000_000.0) * costs.inputTokens()
                + (outputTokens / 1_000_000.0) * costs.outputTokens()
                + (cacheReadInputTokens / 1_000_000.0) * costs.promptCacheReadTokens()
                + (cacheCreationTokens / 1_000_000.0) * costs.promptCacheWriteTokens()
                + webSearchRequests * costs.webSearchRequests();
    }

    /**
     * Format a price value for display.
     * Integers are shown without decimals; others with 2 decimal places.
     * e.g. 3 → "$3", 0.8 → "$0.80", 22.5 → "$22.50"
     * Translated from formatPrice() in modelCost.ts
     */
    private static String formatPrice(double price) {
        if (price == Math.floor(price) && !Double.isInfinite(price)) {
            return "$" + (int) price;
        }
        return String.format("$%.2f", price);
    }

    private ModelCostUtils() {}
}
