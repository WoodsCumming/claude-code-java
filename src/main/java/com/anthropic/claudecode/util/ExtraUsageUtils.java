package com.anthropic.claudecode.util;

/**
 * Extra usage / billing utilities.
 * Translated from src/utils/extraUsage.ts
 *
 * Determines whether a given model invocation is billed as "extra usage"
 * for Claude.ai subscribers (e.g. 1M-context Opus / Sonnet usage).
 */
public class ExtraUsageUtils {

    /**
     * Check whether this model invocation is billed as extra usage for a
     * Claude.ai subscriber.
     * Translated from isBilledAsExtraUsage() in extraUsage.ts
     *
     * @param model            model identifier string (may be null)
     * @param isClaudeAISubscriber true if the user is a Claude.ai subscriber
     * @param isFastMode       true if fast (Haiku-tier) mode is active
     * @param has1mContext     true if the model supports 1M-token context
     * @param isOpus1mMerged   true if opus-4-6 1M usage has been merged/included
     *                         (no extra charge for opus in that billing period)
     * @return true if the invocation counts as extra usage
     */
    public static boolean isBilledAsExtraUsage(
            String model,
            boolean isClaudeAISubscriber,
            boolean isFastMode,
            boolean has1mContext,
            boolean isOpus1mMerged) {

        if (!isClaudeAISubscriber) return false;
        if (isFastMode) return true;
        if (model == null || !has1mContext) return false;

        // Normalize: strip trailing [1m] tag and lowercase
        String m = model.toLowerCase()
                .replaceAll("\\[1m]$", "")
                .trim();

        boolean isOpus46 = m.equals("opus") || m.contains("opus-4-6");
        boolean isSonnet46 = m.equals("sonnet") || m.contains("sonnet-4-6");

        // Opus 1M is included in certain subscription tiers
        if (isOpus46 && isOpus1mMerged) return false;

        return isOpus46 || isSonnet46;
    }

    private ExtraUsageUtils() {}
}
