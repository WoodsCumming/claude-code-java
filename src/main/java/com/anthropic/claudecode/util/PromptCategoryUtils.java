package com.anthropic.claudecode.util;

/**
 * Prompt category utilities for analytics.
 * Translated from src/utils/promptCategory.ts
 *
 * Determines QuerySource strings for tracking different agent and REPL usage patterns.
 */
public class PromptCategoryUtils {

    // Default output style name — mirrors DEFAULT_OUTPUT_STYLE_NAME in outputStyles.ts
    private static final String DEFAULT_OUTPUT_STYLE_NAME = "default";

    /**
     * Determines the query source (prompt category) for agent usage.
     * Used for analytics to track different agent patterns.
     *
     * @param agentType      The type/name of the agent (may be null)
     * @param isBuiltInAgent Whether this is a built-in agent or a custom one
     * @return The agent query source string
     * Translated from getQuerySourceForAgent() in promptCategory.ts
     */
    public static String getQuerySourceForAgent(String agentType, boolean isBuiltInAgent) {
        if (isBuiltInAgent) {
            return agentType != null && !agentType.isBlank()
                    ? "agent:builtin:" + agentType
                    : "agent:default";
        } else {
            return "agent:custom";
        }
    }

    /**
     * Determines the query source based on the active output style setting.
     * Used for analytics to track different output style usage.
     *
     * Logic mirrors getQuerySourceForREPL() in promptCategory.ts:
     * - Default style → "repl_main_thread"
     * - Known built-in style → "repl_main_thread:outputStyle:{style}"
     * - Unknown / custom style → "repl_main_thread:outputStyle:custom"
     *
     * @param outputStyle The current output style name (pass null to use default)
     * @return The REPL query source string
     * Translated from getQuerySourceForREPL() in promptCategory.ts
     */
    public static String getQuerySourceForREPL(String outputStyle) {
        String style = (outputStyle != null && !outputStyle.isBlank())
                ? outputStyle
                : DEFAULT_OUTPUT_STYLE_NAME;

        if (DEFAULT_OUTPUT_STYLE_NAME.equals(style)) {
            return "repl_main_thread";
        }

        // Treat any style name as built-in; the TypeScript side checks OUTPUT_STYLE_CONFIG.
        // In the Java translation we delegate that check to the caller or use a hardcoded set.
        if (isBuiltInOutputStyle(style)) {
            return "repl_main_thread:outputStyle:" + style;
        }

        return "repl_main_thread:outputStyle:custom";
    }

    /**
     * Convenience overload using the default output style.
     */
    public static String getQuerySourceForREPL() {
        return getQuerySourceForREPL(null);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Returns true if {@code style} is a recognised built-in output style.
     * Mirrors the {@code style in OUTPUT_STYLE_CONFIG} check from promptCategory.ts
     */
    private static boolean isBuiltInOutputStyle(String style) {
        // Built-in styles defined in constants/outputStyles.ts
        return switch (style) {
            case "default", "concise", "verbose", "json" -> true;
            default -> false;
        };
    }

    private PromptCategoryUtils() {}
}
