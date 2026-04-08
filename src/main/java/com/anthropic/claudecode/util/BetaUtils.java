package com.anthropic.claudecode.util;

import java.util.*;

/**
 * Beta feature utilities.
 * Translated from src/utils/betas.ts
 *
 * Manages beta feature headers for the Anthropic API.
 */
public class BetaUtils {

    // =========================================================================
    // Beta header constants (from src/constants/betas.ts)
    // =========================================================================
    public static final String PROMPT_CACHING_BETA = "prompt-caching-2024-07-31";
    public static final String COMPUTER_USE_BETA = "computer-use-2024-10-22";
    public static final String CONTEXT_1M_BETA = "context-1m-2025-08-07";
    public static final String WEB_SEARCH_BETA = "web-search-2025-03-05";
    public static final String STRUCTURED_OUTPUTS_BETA = "structured-outputs-2025-12-15";
    public static final String TOKEN_EFFICIENT_TOOLS_BETA = "token-efficient-tools-2026-03-28";
    public static final String INTERLEAVED_THINKING_BETA = "interleaved-thinking-2025-05-14";
    public static final String CLAUDE_CODE_20250219_BETA = "claude-code-20250219";
    /** Backwards-compat alias */
    public static final String CLAUDE_CODE_BETA = CLAUDE_CODE_20250219_BETA;
    public static final String CONTEXT_MANAGEMENT_BETA = "context-management-2025-06-27";
    public static final String TOOL_SEARCH_BETA_1P = "advanced-tool-use-2025-11-20";
    public static final String TOOL_SEARCH_BETA_3P = "tool-search-tool-2025-10-19";
    public static final String EFFORT_BETA = "effort-2025-11-24";
    public static final String TASK_BUDGETS_BETA = "task-budgets-2026-03-13";
    public static final String PROMPT_CACHING_SCOPE_BETA = "prompt-caching-scope-2026-01-05";
    public static final String FAST_MODE_BETA = "fast-mode-2026-02-01";
    public static final String REDACT_THINKING_BETA = "redact-thinking-2026-02-12";

    /** Beta headers that Bedrock sends via extra_body rather than the standard betas list. */
    public static final Set<String> BEDROCK_EXTRA_PARAMS_HEADERS = Set.of(
            INTERLEAVED_THINKING_BETA,
            REDACT_THINKING_BETA,
            CONTEXT_MANAGEMENT_BETA,
            STRUCTURED_OUTPUTS_BETA,
            TOKEN_EFFICIENT_TOOLS_BETA,
            PROMPT_CACHING_SCOPE_BETA
    );

    /** SDK-provided betas that are allowed for API-key users. */
    private static final List<String> ALLOWED_SDK_BETAS = List.of(CONTEXT_1M_BETA);

    // =========================================================================
    // Model capability checks
    // =========================================================================

    /**
     * Check whether a model supports interleaved thinking (ISP).
     * Translated from modelSupportsISP() in betas.ts
     */
    public static boolean modelSupportsISP(String model) {
        if (model == null) return false;
        String canonical = ModelUtils.getCanonicalName(model);
        ApiProviderUtils.ApiProvider provider = ApiProviderUtils.getAPIProvider();
        if (provider == ApiProviderUtils.ApiProvider.FOUNDRY) return true;
        if (provider == ApiProviderUtils.ApiProvider.FIRST_PARTY) return !canonical.contains("claude-3-");
        return canonical.contains("claude-opus-4") || canonical.contains("claude-sonnet-4");
    }

    /**
     * Check whether a model supports context management (tool clearing / thinking preservation).
     * Translated from modelSupportsContextManagement() in betas.ts
     */
    public static boolean modelSupportsContextManagement(String model) {
        if (model == null) return false;
        String canonical = ModelUtils.getCanonicalName(model);
        ApiProviderUtils.ApiProvider provider = ApiProviderUtils.getAPIProvider();
        if (provider == ApiProviderUtils.ApiProvider.FOUNDRY) return true;
        if (provider == ApiProviderUtils.ApiProvider.FIRST_PARTY) return !canonical.contains("claude-3-");
        return canonical.contains("claude-opus-4")
                || canonical.contains("claude-sonnet-4")
                || canonical.contains("claude-haiku-4");
    }

    /**
     * Check whether a model supports structured outputs (JSON schema strict mode).
     * Translated from modelSupportsStructuredOutputs() in betas.ts
     */
    public static boolean modelSupportsStructuredOutputs(String model) {
        if (model == null) return false;
        ApiProviderUtils.ApiProvider provider = ApiProviderUtils.getAPIProvider();
        // Structured outputs only supported on firstParty and Foundry
        if (provider != ApiProviderUtils.ApiProvider.FIRST_PARTY
                && provider != ApiProviderUtils.ApiProvider.FOUNDRY) return false;
        String canonical = ModelUtils.getCanonicalName(model);
        return canonical.contains("claude-sonnet-4-6")
                || canonical.contains("claude-sonnet-4-5")
                || canonical.contains("claude-opus-4-1")
                || canonical.contains("claude-opus-4-5")
                || canonical.contains("claude-opus-4-6")
                || canonical.contains("claude-haiku-4-5");
    }

    /**
     * Get the correct tool-search beta header for the current API provider.
     * - Claude API / Foundry: advanced-tool-use-2025-11-20
     * - Vertex AI / Bedrock: tool-search-tool-2025-10-19
     * Translated from getToolSearchBetaHeader() in betas.ts
     */
    public static String getToolSearchBetaHeader() {
        ApiProviderUtils.ApiProvider provider = ApiProviderUtils.getAPIProvider();
        if (provider == ApiProviderUtils.ApiProvider.VERTEX
                || provider == ApiProviderUtils.ApiProvider.BEDROCK) {
            return TOOL_SEARCH_BETA_3P;
        }
        return TOOL_SEARCH_BETA_1P;
    }

    /**
     * Check if experimental (first-party-only) betas should be included.
     * Translated from shouldIncludeFirstPartyOnlyBetas() in betas.ts
     */
    public static boolean shouldIncludeFirstPartyOnlyBetas() {
        ApiProviderUtils.ApiProvider provider = ApiProviderUtils.getAPIProvider();
        return (provider == ApiProviderUtils.ApiProvider.FIRST_PARTY
                    || provider == ApiProviderUtils.ApiProvider.FOUNDRY)
                && !EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS"));
    }

    /**
     * Check if global-scope prompt caching should be used.
     * Global caching is firstParty only (Foundry excluded from rollout data).
     * Translated from shouldUseGlobalCacheScope() in betas.ts
     */
    public static boolean shouldUseGlobalCacheScope() {
        return ApiProviderUtils.getAPIProvider() == ApiProviderUtils.ApiProvider.FIRST_PARTY
                && !EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS"));
    }

    // =========================================================================
    // Beta list builders
    // =========================================================================

    /**
     * Get all beta headers for a model, including feature-gated ones.
     * Translated from getAllModelBetas() in betas.ts
     */
    public static List<String> getAllModelBetas(String model) {
        List<String> betas = new ArrayList<>();
        if (model == null) return betas;

        String canonical = ModelUtils.getCanonicalName(model);
        boolean isHaiku = canonical.contains("haiku");
        ApiProviderUtils.ApiProvider provider = ApiProviderUtils.getAPIProvider();
        boolean includeFirstPartyOnly = shouldIncludeFirstPartyOnlyBetas();

        if (!isHaiku) {
            betas.add(CLAUDE_CODE_20250219_BETA);
        }

        if (has1mContext(model)) {
            betas.add(CONTEXT_1M_BETA);
        }

        if (!EnvUtils.isEnvTruthy(System.getenv("DISABLE_INTERLEAVED_THINKING"))
                && modelSupportsISP(model)) {
            betas.add(INTERLEAVED_THINKING_BETA);
        }

        // Add context management beta for thinking preservation
        if (includeFirstPartyOnly && modelSupportsContextManagement(model)) {
            betas.add(CONTEXT_MANAGEMENT_BETA);
        }

        // Add web search beta for Vertex Claude 4.0+ models
        if (provider == ApiProviderUtils.ApiProvider.VERTEX && vertexModelSupportsWebSearch(canonical)) {
            betas.add(WEB_SEARCH_BETA);
        }
        // Foundry always gets web search
        if (provider == ApiProviderUtils.ApiProvider.FOUNDRY) {
            betas.add(WEB_SEARCH_BETA);
        }

        // Always send prompt caching scope header for 1P (no-op without a scope field)
        if (includeFirstPartyOnly) {
            betas.add(PROMPT_CACHING_SCOPE_BETA);
        }

        // User-provided betas via ANTHROPIC_BETAS env var
        String anthropicBetas = System.getenv("ANTHROPIC_BETAS");
        if (anthropicBetas != null && !anthropicBetas.isBlank()) {
            for (String b : anthropicBetas.split(",")) {
                String trimmed = b.trim();
                if (!trimmed.isEmpty()) betas.add(trimmed);
            }
        }

        return betas;
    }

    /**
     * Get model betas, filtering out Bedrock extra-params headers for the standard list.
     * Translated from getModelBetas() in betas.ts
     */
    public static List<String> getModelBetas(String model) {
        List<String> all = getAllModelBetas(model);
        if ("bedrock".equals(ApiProviderUtils.getAPIProvider())) {
            return all.stream().filter(b -> !BEDROCK_EXTRA_PARAMS_HEADERS.contains(b)).toList();
        }
        return all;
    }

    /**
     * Get betas that Bedrock sends via extra_body (not the standard header).
     * Translated from getBedrockExtraBodyParamsBetas() in betas.ts
     */
    public static List<String> getBedrockExtraBodyParamsBetas(String model) {
        return getAllModelBetas(model).stream()
                .filter(BEDROCK_EXTRA_PARAMS_HEADERS::contains)
                .toList();
    }

    /**
     * Merge SDK-provided betas with auto-detected model betas.
     * Translated from getMergedBetas() in betas.ts
     *
     * @param model          current model
     * @param sdkBetas       pre-filtered SDK betas (may be null/empty)
     * @param isAgenticQuery when true, ensures claude-code beta header is present
     * @return merged list without duplicates
     */
    public static List<String> getMergedBetas(String model, List<String> sdkBetas, boolean isAgenticQuery) {
        List<String> base = new ArrayList<>(getModelBetas(model));

        if (isAgenticQuery && !base.contains(CLAUDE_CODE_20250219_BETA)) {
            base.add(CLAUDE_CODE_20250219_BETA);
        }

        if (sdkBetas == null || sdkBetas.isEmpty()) {
            return base;
        }

        // Merge without duplicates
        for (String b : sdkBetas) {
            if (!base.contains(b)) base.add(b);
        }
        return base;
    }

    /** Convenience overload without isAgenticQuery. */
    public static List<String> getMergedBetas(String model, List<String> sdkBetas) {
        return getMergedBetas(model, sdkBetas, false);
    }

    // =========================================================================
    // SDK beta filtering
    // =========================================================================

    /**
     * Filter SDK-provided betas to only include those in the allowlist.
     * Translated from filterAllowedSdkBetas() in betas.ts
     *
     * @param sdkBetas raw list from SDK options
     * @param isSubscriber whether the user is a Claude.ai subscriber
     * @return filtered list, or null if nothing remains
     */
    public static List<String> filterAllowedSdkBetas(List<String> sdkBetas, boolean isSubscriber) {
        if (sdkBetas == null || sdkBetas.isEmpty()) return null;

        if (isSubscriber) {
            System.err.println("Warning: Custom betas are only available for API key users. Ignoring provided betas.");
            return null;
        }

        List<String> allowed = new ArrayList<>();
        for (String beta : sdkBetas) {
            if (ALLOWED_SDK_BETAS.contains(beta)) {
                allowed.add(beta);
            } else {
                System.err.printf("Warning: Beta header '%s' is not allowed. Only the following betas are supported: %s%n",
                        beta, String.join(", ", ALLOWED_SDK_BETAS));
            }
        }
        return allowed.isEmpty() ? null : allowed;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private static boolean has1mContext(String model) {
        String canonical = ModelUtils.getCanonicalName(model);
        return canonical.contains("claude-opus-4") || canonical.contains("claude-sonnet-4");
    }

    private static boolean vertexModelSupportsWebSearch(String canonical) {
        return canonical.contains("claude-opus-4")
                || canonical.contains("claude-sonnet-4")
                || canonical.contains("claude-haiku-4");
    }

    private BetaUtils() {}
}
