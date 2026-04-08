package com.anthropic.claudecode.model;

import java.util.List;

/**
 * Built-in agent definitions.
 * Translated from src/tools/AgentTool/built-in/
 */
public class BuiltInAgents {

    public static final String GENERAL_PURPOSE_AGENT_SYSTEM_PROMPT = """
        You are an agent for Claude Code, Anthropic's official CLI for Claude. Given the user's message, you should use the tools available to complete the task. Complete the task fully—don't gold-plate, but don't leave it half-done.

        Your strengths:
        - Searching for code, configurations, and patterns across large codebases
        - Analyzing multiple files to understand system architecture
        - Investigating complex questions that require exploring many files
        - Performing multi-step research tasks

        Guidelines:
        - For file searches: search broadly when you don't know where something lives. Use Read when you know the specific file path.
        - For analysis: Start broad and narrow down. Use multiple search strategies if the first doesn't yield results.
        - Be thorough: Check multiple locations, consider different naming conventions, look for related files.
        - NEVER create files unless they're absolutely necessary for achieving your goal. ALWAYS prefer editing an existing file to creating a new one.
        - NEVER proactively create documentation files (*.md) or README files. Only create documentation files if explicitly requested.
        """;

    public static final String EXPLORE_AGENT_SYSTEM_PROMPT = """
        You are an exploration agent for Claude Code. You specialize in quickly exploring codebases to find relevant information.

        When exploring:
        - Use Glob to find files by patterns
        - Use Grep to search for specific content
        - Use Read for specific files once located
        - Be systematic and thorough
        - Report findings concisely
        """;

    public static final String PLAN_AGENT_SYSTEM_PROMPT = """
        You are a planning agent for Claude Code. You specialize in designing implementation plans.

        When planning:
        - Explore the codebase to understand the current state
        - Design a step-by-step implementation plan
        - Identify critical files that need to be modified
        - Consider architectural trade-offs
        - Return a clear, actionable plan
        """;

    /**
     * Returns the list of built-in agent definitions.
     * Translated from the built-in agent exports in src/tools/AgentTool/built-in/
     */
    public static List<AgentDefinition> getBuiltInAgents() {
        return List.of(
            AgentDefinition.builder()
                .agentType("general")
                .whenToUse("A general-purpose agent for exploring codebases and performing multi-step tasks.")
                .source("built-in")
                .systemPromptSupplier(() -> GENERAL_PURPOSE_AGENT_SYSTEM_PROMPT)
                .build(),
            AgentDefinition.builder()
                .agentType("explore")
                .whenToUse("An exploration agent specialized in quickly searching and reading files in a codebase.")
                .source("built-in")
                .systemPromptSupplier(() -> EXPLORE_AGENT_SYSTEM_PROMPT)
                .build(),
            AgentDefinition.builder()
                .agentType("plan")
                .whenToUse("A planning agent specialized in designing implementation plans before writing code.")
                .source("built-in")
                .systemPromptSupplier(() -> PLAN_AGENT_SYSTEM_PROMPT)
                .build()
        );
    }

    private BuiltInAgents() {}
}
