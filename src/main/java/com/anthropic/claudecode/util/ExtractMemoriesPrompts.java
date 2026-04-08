package com.anthropic.claudecode.util;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Prompt templates for the background memory extraction agent.
 * Translated from src/services/extractMemories/prompts.ts
 *
 * The extraction agent runs as a perfect fork of the main conversation.
 * When the main agent writes memories itself, extraction is skipped.
 * This prompt fires only when the main agent didn't write memories.
 */
public final class ExtractMemoriesPrompts {

    private ExtractMemoriesPrompts() {}

    // =========================================================================
    // Tool name constants (mirrors TS imports)
    // =========================================================================
    private static final String FILE_READ_TOOL_NAME = "Read";
    private static final String GREP_TOOL_NAME = "Grep";
    private static final String GLOB_TOOL_NAME = "Glob";
    private static final String BASH_TOOL_NAME = "Bash";
    private static final String FILE_EDIT_TOOL_NAME = "Edit";
    private static final String FILE_WRITE_TOOL_NAME = "Write";

    // =========================================================================
    // Memory frontmatter example
    // =========================================================================
    private static final List<String> MEMORY_FRONTMATTER_EXAMPLE = List.of(
        "```",
        "---",
        "title: Short descriptive title",
        "tags: [tag1, tag2]",
        "---",
        "",
        "Memory content here.",
        "```"
    );

    // =========================================================================
    // Shared opener
    // =========================================================================

    /**
     * Shared opener for both extract-prompt variants.
     * Translated from opener() in prompts.ts
     */
    private static String opener(int newMessageCount, String existingMemories) {
        String manifest = (existingMemories != null && !existingMemories.isBlank())
            ? "\n\n## Existing memory files\n\n" + existingMemories
              + "\n\nCheck this list before writing — update an existing file rather than creating a duplicate."
            : "";

        return String.join("\n", List.of(
            "You are now acting as the memory extraction subagent. Analyze the most recent ~"
                + newMessageCount + " messages above and use them to update your persistent memory systems.",
            "",
            "Available tools: " + FILE_READ_TOOL_NAME + ", " + GREP_TOOL_NAME + ", " + GLOB_TOOL_NAME
                + ", read-only " + BASH_TOOL_NAME
                + " (ls/find/cat/stat/wc/head/tail and similar), and "
                + FILE_EDIT_TOOL_NAME + "/" + FILE_WRITE_TOOL_NAME
                + " for paths inside the memory directory only. "
                + BASH_TOOL_NAME + " rm is not permitted. All other tools — MCP, Agent, write-capable "
                + BASH_TOOL_NAME + ", etc — will be denied.",
            "",
            FILE_EDIT_TOOL_NAME + " requires a prior " + FILE_READ_TOOL_NAME
                + " of the same file, so the efficient strategy is: turn 1 — issue all "
                + FILE_READ_TOOL_NAME + " calls in parallel for every file you might update; turn 2 — issue all "
                + FILE_WRITE_TOOL_NAME + "/" + FILE_EDIT_TOOL_NAME
                + " calls in parallel. Do not interleave reads and writes across multiple turns.",
            "",
            "You MUST only use content from the last ~" + newMessageCount
                + " messages to update your persistent memories. "
                + "Do not waste any turns attempting to investigate or verify that content further — "
                + "no grepping source files, no reading code to confirm a pattern exists, no git commands."
                + manifest
        ));
    }

    // =========================================================================
    // Types sections (mirrors TS TYPES_SECTION_INDIVIDUAL / _COMBINED)
    // =========================================================================

    private static final List<String> TYPES_SECTION_INDIVIDUAL = List.of(
        "## What to save",
        "",
        "Save memories from the following four categories:",
        "",
        "**1. Explicit user preferences** — things the user directly told you to remember, "
            + "such as preferred coding style, communication tone, or workflow habits.",
        "",
        "**2. Discovered project facts** — architectural decisions, naming conventions, build commands, "
            + "deployment processes, and similar persistent truths about the project.",
        "",
        "**3. Recurring patterns** — things the user asked you to do multiple times, "
            + "suggesting they want it done automatically in the future.",
        "",
        "**4. Correction patterns** — mistakes you made that the user corrected, "
            + "so you don't repeat them."
    );

    private static final List<String> TYPES_SECTION_COMBINED = List.of(
        "## What to save",
        "",
        "Save memories from the following four categories. Each type has a <scope> annotation "
            + "indicating whether to save in your private directory or the shared team directory.",
        "",
        "**1. Explicit user preferences** <scope: private> — things the user directly told you to remember.",
        "",
        "**2. Discovered project facts** <scope: team> — architectural decisions, naming conventions, "
            + "build commands, deployment processes, and similar persistent truths about the project.",
        "",
        "**3. Recurring patterns** <scope: team if project-relevant, private otherwise> — "
            + "things the user asked you to do multiple times.",
        "",
        "**4. Correction patterns** <scope: private> — mistakes you made that the user corrected."
    );

    private static final List<String> WHAT_NOT_TO_SAVE_SECTION = List.of(
        "",
        "## What NOT to save",
        "",
        "- Transient state: current task status, what's being worked on right now",
        "- Redundant info: things already in the system prompt or CLAUDE.md",
        "- Sensitive data: API keys, passwords, personal information",
        "- Speculation: things you inferred but the user didn't confirm",
        "- One-off requests: tasks done once with no expectation of recurrence"
    );

    // =========================================================================
    // Public API — Auto-only prompt
    // =========================================================================

    /**
     * Build the extraction prompt for auto-only memory (no team memory).
     * Translated from buildExtractAutoOnlyPrompt() in prompts.ts
     *
     * @param newMessageCount number of new messages to analyze
     * @param existingMemories formatted manifest of existing memory files
     * @param skipIndex        when true, skip the MEMORY.md index step
     * @return the full prompt string
     */
    public static String buildExtractAutoOnlyPrompt(
            int newMessageCount,
            String existingMemories,
            boolean skipIndex) {

        List<String> howToSave;
        if (skipIndex) {
            howToSave = Stream.concat(
                List.of(
                    "## How to save memories",
                    "",
                    "Write each memory to its own file (e.g., `user_role.md`, `feedback_testing.md`) "
                        + "using this frontmatter format:",
                    ""
                ).stream(),
                Stream.concat(
                    MEMORY_FRONTMATTER_EXAMPLE.stream(),
                    List.of(
                        "",
                        "- Organize memory semantically by topic, not chronologically",
                        "- Update or remove memories that turn out to be wrong or outdated",
                        "- Do not write duplicate memories. First check if there is an existing memory "
                            + "you can update before writing a new one."
                    ).stream()
                )
            ).collect(Collectors.toList());
        } else {
            howToSave = Stream.concat(
                List.of(
                    "## How to save memories",
                    "",
                    "Saving a memory is a two-step process:",
                    "",
                    "**Step 1** — write the memory to its own file (e.g., `user_role.md`, `feedback_testing.md`) "
                        + "using this frontmatter format:",
                    ""
                ).stream(),
                Stream.concat(
                    MEMORY_FRONTMATTER_EXAMPLE.stream(),
                    List.of(
                        "",
                        "**Step 2** — add a pointer to that file in `MEMORY.md`. `MEMORY.md` is an index, not a memory — "
                            + "each entry should be one line, under ~150 characters: `- [Title](file.md) — one-line hook`. "
                            + "It has no frontmatter. Never write memory content directly into `MEMORY.md`.",
                        "",
                        "- `MEMORY.md` is always loaded into your system prompt — lines after 200 will be truncated, "
                            + "so keep the index concise",
                        "- Organize memory semantically by topic, not chronologically",
                        "- Update or remove memories that turn out to be wrong or outdated",
                        "- Do not write duplicate memories. First check if there is an existing memory "
                            + "you can update before writing a new one."
                    ).stream()
                )
            ).collect(Collectors.toList());
        }

        return Stream.of(
            List.of(opener(newMessageCount, existingMemories), ""),
            List.of(
                "If the user explicitly asks you to remember something, save it immediately as whichever type fits best. "
                    + "If they ask you to forget something, find and remove the relevant entry.",
                ""
            ),
            TYPES_SECTION_INDIVIDUAL,
            WHAT_NOT_TO_SAVE_SECTION,
            List.of(""),
            howToSave
        ).flatMap(List::stream).collect(Collectors.joining("\n"));
    }

    /**
     * Convenience overload with skipIndex defaulting to false.
     */
    public static String buildExtractAutoOnlyPrompt(int newMessageCount, String existingMemories) {
        return buildExtractAutoOnlyPrompt(newMessageCount, existingMemories, false);
    }

    // =========================================================================
    // Public API — Combined auto + team prompt
    // =========================================================================

    /**
     * Build the extraction prompt for combined auto + team memory.
     * Four-type taxonomy with per-type scope guidance.
     * Translated from buildExtractCombinedPrompt() in prompts.ts
     *
     * When team memory feature is disabled, falls back to the auto-only prompt.
     *
     * @param newMessageCount   number of new messages to analyze
     * @param existingMemories  formatted manifest of existing memory files
     * @param skipIndex         when true, skip the MEMORY.md index step
     * @param teamMemEnabled    whether team memory feature is enabled
     * @return the full prompt string
     */
    public static String buildExtractCombinedPrompt(
            int newMessageCount,
            String existingMemories,
            boolean skipIndex,
            boolean teamMemEnabled) {

        if (!teamMemEnabled) {
            return buildExtractAutoOnlyPrompt(newMessageCount, existingMemories, skipIndex);
        }

        List<String> howToSave;
        if (skipIndex) {
            howToSave = Stream.concat(
                List.of(
                    "## How to save memories",
                    "",
                    "Write each memory to its own file in the chosen directory (private or team, "
                        + "per the type's scope guidance) using this frontmatter format:",
                    ""
                ).stream(),
                Stream.concat(
                    MEMORY_FRONTMATTER_EXAMPLE.stream(),
                    List.of(
                        "",
                        "- Organize memory semantically by topic, not chronologically",
                        "- Update or remove memories that turn out to be wrong or outdated",
                        "- Do not write duplicate memories. First check if there is an existing memory "
                            + "you can update before writing a new one."
                    ).stream()
                )
            ).collect(Collectors.toList());
        } else {
            howToSave = Stream.concat(
                List.of(
                    "## How to save memories",
                    "",
                    "Saving a memory is a two-step process:",
                    "",
                    "**Step 1** — write the memory to its own file in the chosen directory (private or team, "
                        + "per the type's scope guidance) using this frontmatter format:",
                    ""
                ).stream(),
                Stream.concat(
                    MEMORY_FRONTMATTER_EXAMPLE.stream(),
                    List.of(
                        "",
                        "**Step 2** — add a pointer to that file in the same directory's `MEMORY.md`. "
                            + "Each directory (private and team) has its own `MEMORY.md` index — each entry should be "
                            + "one line, under ~150 characters: `- [Title](file.md) — one-line hook`. "
                            + "They have no frontmatter. Never write memory content directly into a `MEMORY.md`.",
                        "",
                        "- Both `MEMORY.md` indexes are loaded into your system prompt — lines after 200 will be "
                            + "truncated, so keep them concise",
                        "- Organize memory semantically by topic, not chronologically",
                        "- Update or remove memories that turn out to be wrong or outdated",
                        "- Do not write duplicate memories. First check if there is an existing memory "
                            + "you can update before writing a new one."
                    ).stream()
                )
            ).collect(Collectors.toList());
        }

        return Stream.of(
            List.of(opener(newMessageCount, existingMemories), ""),
            List.of(
                "If the user explicitly asks you to remember something, save it immediately as whichever type fits best. "
                    + "If they ask you to forget something, find and remove the relevant entry.",
                ""
            ),
            TYPES_SECTION_COMBINED,
            WHAT_NOT_TO_SAVE_SECTION,
            List.of("- You MUST avoid saving sensitive data within shared team memories. "
                + "For example, never save API keys or user credentials."),
            List.of(""),
            howToSave
        ).flatMap(List::stream).collect(Collectors.joining("\n"));
    }

    /**
     * Convenience overload with skipIndex defaulting to false.
     */
    public static String buildExtractCombinedPrompt(
            int newMessageCount,
            String existingMemories,
            boolean teamMemEnabled) {
        return buildExtractCombinedPrompt(newMessageCount, existingMemories, false, teamMemEnabled);
    }
}
