package com.anthropic.claudecode.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Output style constants and built-in style configurations.
 * Translated from src/constants/outputStyles.ts
 *
 * Defines the built-in output styles (default, Explanatory, Learning) and
 * helper utilities for working with the output style registry.
 */
public final class OutputStylesConstants {

    // -------------------------------------------------------------------------
    // Bullet character (mirrors the `figures` npm package used in TypeScript)
    // -------------------------------------------------------------------------

    /** Unicode star character used in Explanatory/Learning style prompts. */
    public static final String FIGURES_STAR = "\u2605";

    /** Unicode bullet character used in Learning style prompts. */
    public static final String FIGURES_BULLET = "\u2022";

    // -------------------------------------------------------------------------
    // Shared prompt fragment
    // -------------------------------------------------------------------------

    /**
     * Shared educational insight prompt block used in both Explanatory and
     * Learning output styles.
     */
    public static final String EXPLANATORY_FEATURE_PROMPT =
            "\n## Insights\n" +
            "In order to encourage learning, before and after writing code, always provide brief " +
            "educational explanations about implementation choices using (with backticks):\n" +
            "\"`" + FIGURES_STAR + " Insight \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500`\n" +
            "[2-3 key educational points]\n" +
            "`\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\"`\n\n" +
            "These insights should be included in the conversation, not in the codebase. " +
            "You should generally focus on interesting insights that are specific to the codebase " +
            "or the code you just wrote, rather than general programming concepts.";

    // -------------------------------------------------------------------------
    // Style name constants
    // -------------------------------------------------------------------------

    /** Name of the default (no-op) output style. */
    public static final String DEFAULT_OUTPUT_STYLE_NAME = "default";

    /** Name of the Explanatory built-in output style. */
    public static final String EXPLANATORY_STYLE_NAME = "Explanatory";

    /** Name of the Learning built-in output style. */
    public static final String LEARNING_STYLE_NAME = "Learning";

    // -------------------------------------------------------------------------
    // OutputStyleConfig value type
    // -------------------------------------------------------------------------

    /**
     * Configuration for a single output style.
     * Corresponds to TypeScript: type OutputStyleConfig
     */
    public record OutputStyleConfig(
            String name,
            String description,
            String prompt,
            String source,          // "built-in" | "plugin" | SettingSource values
            Boolean keepCodingInstructions,
            Boolean forceForPlugin  // only relevant for plugin output styles
    ) {
        /** Convenience constructor for built-in styles without forceForPlugin. */
        public OutputStyleConfig(String name, String description, String prompt, String source,
                                 Boolean keepCodingInstructions) {
            this(name, description, prompt, source, keepCodingInstructions, null);
        }
    }

    // -------------------------------------------------------------------------
    // Explanatory style prompt
    // -------------------------------------------------------------------------

    private static final String EXPLANATORY_PROMPT =
            "You are an interactive CLI tool that helps users with software engineering tasks. " +
            "In addition to software engineering tasks, you should provide educational insights " +
            "about the codebase along the way.\n\n" +
            "You should be clear and educational, providing helpful explanations while remaining " +
            "focused on the task. Balance educational content with task completion. When providing " +
            "insights, you may exceed typical length constraints, but remain focused and relevant.\n\n" +
            "# Explanatory Style Active\n" +
            EXPLANATORY_FEATURE_PROMPT;

    // -------------------------------------------------------------------------
    // Learning style prompt
    // -------------------------------------------------------------------------

    private static final String LEARNING_PROMPT =
            "You are an interactive CLI tool that helps users with software engineering tasks. " +
            "In addition to software engineering tasks, you should help users learn more about " +
            "the codebase through hands-on practice and educational insights.\n\n" +
            "You should be collaborative and encouraging. Balance task completion with learning " +
            "by requesting user input for meaningful design decisions while handling routine " +
            "implementation yourself.   \n\n" +
            "# Learning Style Active\n" +
            "## Requesting Human Contributions\n" +
            "In order to encourage learning, ask the human to contribute 2-10 line code pieces " +
            "when generating 20+ lines involving:\n" +
            "- Design decisions (error handling, data structures)\n" +
            "- Business logic with multiple valid approaches  \n" +
            "- Key algorithms or interface definitions\n\n" +
            "**TodoList Integration**: If using a TodoList for the overall task, include a specific " +
            "todo item like \"Request human input on [specific decision]\" when planning to request " +
            "human input. This ensures proper task tracking. Note: TodoList is not required for all tasks.\n\n" +
            "Example TodoList flow:\n" +
            "   \u2713 \"Set up component structure with placeholder for logic\"\n" +
            "   \u2713 \"Request human collaboration on decision logic implementation\"\n" +
            "   \u2713 \"Integrate contribution and complete feature\"\n\n" +
            "### Request Format\n" +
            "```\n" +
            FIGURES_BULLET + " **Learn by Doing**\n" +
            "**Context:** [what's built and why this decision matters]\n" +
            "**Your Task:** [specific function/section in file, mention file and TODO(human) but do not include line numbers]\n" +
            "**Guidance:** [trade-offs and constraints to consider]\n" +
            "```\n\n" +
            "### Key Guidelines\n" +
            "- Frame contributions as valuable design decisions, not busy work\n" +
            "- You must first add a TODO(human) section into the codebase with your editing tools " +
            "before making the Learn by Doing request      \n" +
            "- Make sure there is one and only one TODO(human) section in the code\n" +
            "- Don't take any action or output anything after the Learn by Doing request. " +
            "Wait for human implementation before proceeding.\n\n" +
            "### After Contributions\n" +
            "Share one insight connecting their code to broader patterns or system effects. " +
            "Avoid praise or repetition.\n\n" +
            "## Insights\n" +
            EXPLANATORY_FEATURE_PROMPT;

    // -------------------------------------------------------------------------
    // Built-in OUTPUT_STYLE_CONFIG registry
    // -------------------------------------------------------------------------

    /**
     * The canonical built-in output style registry.
     * null entry for DEFAULT_OUTPUT_STYLE_NAME means "no custom prompt".
     * Corresponds to TypeScript: OUTPUT_STYLE_CONFIG
     */
    public static final Map<String, OutputStyleConfig> OUTPUT_STYLE_CONFIG;

    static {
        Map<String, OutputStyleConfig> map = new LinkedHashMap<>();
        map.put(DEFAULT_OUTPUT_STYLE_NAME, null);
        map.put(EXPLANATORY_STYLE_NAME, new OutputStyleConfig(
                EXPLANATORY_STYLE_NAME,
                "Claude explains its implementation choices and codebase patterns",
                EXPLANATORY_PROMPT,
                "built-in",
                true
        ));
        map.put(LEARNING_STYLE_NAME, new OutputStyleConfig(
                LEARNING_STYLE_NAME,
                "Claude pauses and asks you to write small pieces of code for hands-on practice",
                LEARNING_PROMPT,
                "built-in",
                true
        ));
        OUTPUT_STYLE_CONFIG = Collections.unmodifiableMap(map);
    }

    private OutputStylesConstants() {}
}
