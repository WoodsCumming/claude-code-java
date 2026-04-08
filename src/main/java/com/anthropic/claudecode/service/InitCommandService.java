package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * /init command service — CLAUDE.md initialisation.
 * Translated from src/commands/init.ts
 *
 * Generates the prompt that drives the /init command.  Supports two prompt
 * variants:
 * <ol>
 *   <li>{@code OLD_INIT_PROMPT} — classic single-step CLAUDE.md creator.</li>
 *   <li>{@code NEW_INIT_PROMPT} — multi-phase wizard that sets up CLAUDE.md,
 *       CLAUDE.local.md, skills, and hooks.</li>
 * </ol>
 *
 * Which variant to use is controlled by the {@code NEW_INIT} feature flag and
 * the {@code USER_TYPE} / {@code CLAUDE_CODE_NEW_INIT} environment variables,
 * mirroring the logic in the TypeScript source.
 */
@Slf4j
@Service
public class InitCommandService {



    // =========================================================================
    // Prompt constants
    // =========================================================================

    private static final String OLD_INIT_PROMPT = """
Please analyze this codebase and create a CLAUDE.md file, which will be given to future instances of Claude Code to operate in this repository.

What to add:
1. Commands that will be commonly used, such as how to build, lint, and run tests. Include the necessary commands to develop in this codebase, such as how to run a single test.
2. High-level code architecture and structure so that future instances can be productive more quickly. Focus on the "big picture" architecture that requires reading multiple files to understand.

Usage notes:
- If there's already a CLAUDE.md, suggest improvements to it.
- When you make the initial CLAUDE.md, do not repeat yourself and do not include obvious instructions like "Provide helpful error messages to users", "Write unit tests for all new utilities", "Never include sensitive information (API keys, tokens) in code or commits".
- Avoid listing every component or file structure that can be easily discovered.
- Don't include generic development practices.
- If there are Cursor rules (in .cursor/rules/ or .cursorrules) or Copilot rules (in .github/copilot-instructions.md), make sure to include the important parts.
- If there is a README.md, make sure to include the important parts.
- Do not make up information such as "Common Development Tasks", "Tips for Development", "Support and Documentation" unless this is expressly included in other files that you read.
- Be sure to prefix the file with the following text:

```
# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.
```""";

    private static final String NEW_INIT_PROMPT = """
Set up a minimal CLAUDE.md (and optionally skills and hooks) for this repo. CLAUDE.md is loaded into every Claude Code session, so it must be concise — only include what Claude would get wrong without it.

## Phase 1: Ask what to set up

Use AskUserQuestion to find out what the user wants:

- "Which CLAUDE.md files should /init set up?"
  Options: "Project CLAUDE.md" | "Personal CLAUDE.local.md" | "Both project + personal"
  Description for project: "Team-shared instructions checked into source control — architecture, coding standards, common workflows."
  Description for personal: "Your private preferences for this project (gitignored, not shared) — your role, sandbox URLs, preferred test data, workflow quirks."

- "Also set up skills and hooks?"
  Options: "Skills + hooks" | "Skills only" | "Hooks only" | "Neither, just CLAUDE.md"
  Description for skills: "On-demand capabilities you or Claude invoke with `/skill-name` — good for repeatable workflows and reference knowledge."
  Description for hooks: "Deterministic shell commands that run on tool events (e.g., format after every edit). Claude can't skip them."

## Phase 2: Explore the codebase

Launch a subagent to survey the codebase, and ask it to read key files to understand the project: manifest files (package.json, Cargo.toml, pyproject.toml, go.mod, pom.xml, etc.), README, Makefile/build configs, CI config, existing CLAUDE.md, .claude/rules/, AGENTS.md, .cursor/rules or .cursorrules, .github/copilot-instructions.md, .windsurfrules, .clinerules, .mcp.json.

Detect:
- Build, test, and lint commands (especially non-standard ones)
- Languages, frameworks, and package manager
- Project structure (monorepo with workspaces, multi-module, or single project)
- Code style rules that differ from language defaults
- Non-obvious gotchas, required env vars, or workflow quirks
- Existing .claude/skills/ and .claude/rules/ directories
- Formatter configuration (prettier, biome, ruff, black, gofmt, rustfmt, or a unified format script like `npm run format` / `make fmt`)
- Git worktree usage: run `git worktree list` to check if this repo has multiple worktrees (only relevant if the user wants a personal CLAUDE.local.md)

Note what you could NOT figure out from code alone — these become interview questions.

## Phase 3: Fill in the gaps

Use AskUserQuestion to gather what you still need to write good CLAUDE.md files and skills. Ask only things the code can't answer.

If the user chose project CLAUDE.md or both: ask about codebase practices — non-obvious commands, gotchas, branch/PR conventions, required env setup, testing quirks. Skip things already in README or obvious from manifest files. Do not mark any options as "recommended" — this is about how their team works, not best practices.

If the user chose personal CLAUDE.local.md or both: ask about them, not the codebase. Do not mark any options as "recommended" — this is about their personal preferences, not best practices.

## Phase 4: Write CLAUDE.md (if user chose project or both)

Write a minimal CLAUDE.md at the project root. Every line must pass this test: "Would removing this cause Claude to make mistakes?" If no, cut it.

Include:
- Build/test/lint commands Claude can't guess (non-standard scripts, flags, or sequences)
- Code style rules that DIFFER from language defaults (e.g., "prefer type over interface")
- Testing instructions and quirks (e.g., "run single test with: pytest -k 'test_name'")
- Repo etiquette (branch naming, PR conventions, commit style)
- Required env vars or setup steps
- Non-obvious gotchas or architectural decisions
- Important parts from existing AI coding tool configs if they exist

Prefix the file with:

```
# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.
```

If CLAUDE.md already exists: read it, propose specific changes as diffs, and explain why each change improves it. Do not silently overwrite.

## Phase 5: Write CLAUDE.local.md (if user chose personal or both)

Write a minimal CLAUDE.local.md at the project root. After creating it, add `CLAUDE.local.md` to the project's .gitignore so it stays private.

Include:
- The user's role and familiarity with the codebase (so Claude can calibrate explanations)
- Personal sandbox URLs, test accounts, or local setup details
- Personal workflow or communication preferences

## Phase 6: Suggest and create skills (if user chose "Skills + hooks" or "Skills only")

Create skills at `.claude/skills/<skill-name>/SKILL.md` using frontmatter:

```yaml
---
name: <skill-name>
description: <what the skill does and when to use it>
---

<Instructions for Claude>
```

## Phase 7: Suggest additional optimizations

Check environment and ask about each gap (GitHub CLI, linting, hooks). Act on each "yes" before moving on.

## Phase 8: Summary and next steps

Recap what was set up. Tell the user these files are a starting point and they can run `/init` again anytime to re-scan.

Suggest relevant plugins:
- Frontend code: `/plugin install frontend-design@claude-plugins-official`
- `/plugin install skill-creator@claude-plugins-official` (always include)
- Browse official plugins with `/plugin` (always include)""";

    // =========================================================================
    // Dependencies
    // =========================================================================

    private final ProjectOnboardingService projectOnboardingService;
    private final FeatureFlagService featureFlagService;

    @Autowired
    public InitCommandService(ProjectOnboardingService projectOnboardingService,
                               FeatureFlagService featureFlagService) {
        this.projectOnboardingService = projectOnboardingService;
        this.featureFlagService = featureFlagService;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Returns the command metadata for the /init slash command.
     * Mirrors the {@code command} export in init.ts.
     */
    public InitCommandDefinition getCommandDefinition() {
        boolean useNewInit = isNewInitEnabled();
        return new InitCommandDefinition(
            "init",
            useNewInit
                ? "Initialize new CLAUDE.md file(s) and optional skills/hooks with codebase documentation"
                : "Initialize a new CLAUDE.md file with codebase documentation",
            "analyzing your codebase"
        );
    }

    /**
     * Builds the prompt for the /init command, marking project onboarding as
     * complete as a side effect.
     * Mirrors {@code getPromptForCommand()} in init.ts.
     *
     * @return A single-element list containing the text prompt.
     */
    public CompletableFuture<List<Map<String, String>>> getPromptForCommand() {
        return CompletableFuture.supplyAsync(() -> {
            projectOnboardingService.maybeMarkProjectOnboardingComplete();

            String promptText = isNewInitEnabled() ? NEW_INIT_PROMPT : OLD_INIT_PROMPT;

            return List.of(Map.of("type", "text", "text", promptText));
        });
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Returns {@code true} when the new multi-phase /init flow should be used.
     * Mirrors the feature-flag + env-var check in init.ts.
     */
    boolean isNewInitEnabled() {
        if (!featureFlagService.isEnabled("NEW_INIT")) {
            return false;
        }
        String userType = System.getenv("USER_TYPE");
        String envFlag  = System.getenv("CLAUDE_CODE_NEW_INIT");
        return "ant".equals(userType) || isEnvTruthy(envFlag);
    }

    private static boolean isEnvTruthy(String value) {
        if (value == null) return false;
        return "1".equals(value) || "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value);
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /**
     * Immutable descriptor for the /init command.
     */
    public record InitCommandDefinition(
        String name,
        String description,
        String progressMessage
    ) {}
}
