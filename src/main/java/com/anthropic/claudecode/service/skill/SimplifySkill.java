package com.anthropic.claudecode.service.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * SimplifySkill — review changed code for reuse, quality, and efficiency,
 * then fix any issues found.
 *
 * <p>Translated from: src/skills/bundled/simplify.ts
 */
@Slf4j
@Service
public class SimplifySkill {



    private static final String AGENT_TOOL_NAME = "Agent";

    private static final String SIMPLIFY_PROMPT =
            "# Simplify: Code Review and Cleanup\n\n" +
            "Review all changed files for reuse, quality, and efficiency. Fix any issues found.\n\n" +
            "## Phase 1: Identify Changes\n\n" +
            "Run `git diff` (or `git diff HEAD` if there are staged changes) to see what changed." +
            " If there are no git changes, review the most recently modified files that the user mentioned" +
            " or that you edited earlier in this conversation.\n\n" +
            "## Phase 2: Launch Three Review Agents in Parallel\n\n" +
            "Use the " + AGENT_TOOL_NAME + " tool to launch all three agents concurrently in a single message." +
            " Pass each agent the full diff so it has the complete context.\n\n" +
            "### Agent 1: Code Reuse Review\n\n" +
            "For each change:\n\n" +
            "1. **Search for existing utilities and helpers** that could replace newly written code.\n" +
            "2. **Flag any new function that duplicates existing functionality.**\n" +
            "3. **Flag any inline logic that could use an existing utility**\n\n" +
            "### Agent 2: Code Quality Review\n\n" +
            "Review the same changes for hacky patterns:\n\n" +
            "1. **Redundant state**: state that duplicates existing state\n" +
            "2. **Parameter sprawl**: adding new parameters instead of restructuring\n" +
            "3. **Copy-paste with slight variation**: near-duplicate code blocks\n" +
            "4. **Leaky abstractions**: exposing internal details\n" +
            "5. **Stringly-typed code**: using raw strings where enums already exist\n" +
            "6. **Unnecessary comments**: comments explaining WHAT the code does\n\n" +
            "### Agent 3: Efficiency Review\n\n" +
            "Review the same changes for efficiency:\n\n" +
            "1. **Unnecessary work**: redundant computations, repeated file reads\n" +
            "2. **Missed concurrency**: independent operations run sequentially\n" +
            "3. **Hot-path bloat**: new blocking work added to startup\n" +
            "4. **Memory**: unbounded data structures, missing cleanup\n\n" +
            "## Phase 3: Fix Issues\n\n" +
            "Wait for all three agents to complete. Aggregate their findings and fix each issue directly." +
            " If a finding is a false positive or not worth addressing, note it and move on.\n\n" +
            "When done, briefly summarize what was fixed (or confirm the code was already clean).\n";

    public static final String NAME = "simplify";
    public static final String DESCRIPTION =
            "Review changed code for reuse, quality, and efficiency, then fix any issues found.";

    public CompletableFuture<List<PromptPart>> getPromptForCommand(String args) {
        return CompletableFuture.supplyAsync(() -> {
            String prompt = SIMPLIFY_PROMPT;
            if (args != null && !args.isBlank()) {
                prompt += "\n\n## Additional Focus\n\n" + args;
            }
            return List.of(new PromptPart("text", prompt));
        });
    }

    public record PromptPart(String type, String text) {}
}
