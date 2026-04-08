package com.anthropic.claudecode.service.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * BatchSkill — research and plan a large-scale change, then execute it in
 * parallel across 5–30 isolated worktree agents that each open a PR.
 *
 * <p>Translated from: src/skills/bundled/batch.ts
 */
@Slf4j
@Service
public class BatchSkill {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BatchSkill.class);


    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final int MIN_AGENTS = 5;
    private static final int MAX_AGENTS = 30;

    private static final String SKILL_TOOL_NAME = "Skill";
    private static final String AGENT_TOOL_NAME = "Agent";
    private static final String ENTER_PLAN_MODE_TOOL_NAME = "EnterPlanMode";
    private static final String EXIT_PLAN_MODE_TOOL_NAME = "ExitPlanMode";
    private static final String ASK_USER_QUESTION_TOOL_NAME = "AskUserQuestion";

    private static final String WORKER_INSTRUCTIONS =
            "After you finish implementing the change:\n"
                    + "1. **Simplify** — Invoke the `" + SKILL_TOOL_NAME + "` tool with `skill: \"simplify\"` to review and clean up your changes.\n"
                    + "2. **Run unit tests** — Run the project's test suite (check for package.json scripts, Makefile targets, or common commands like `npm test`, `bun test`, `pytest`, `go test`). If tests fail, fix them.\n"
                    + "3. **Test end-to-end** — Follow the e2e test recipe from the coordinator's prompt (below). If the recipe says to skip e2e for this unit, skip it.\n"
                    + "4. **Commit and push** — Commit all changes with a clear message, push the branch, and create a PR with `gh pr create`. Use a descriptive title. If `gh` is not available or the push fails, note it in your final message.\n"
                    + "5. **Report** — End with a single line: `PR: <url>` so the coordinator can track it. If no PR was created, end with `PR: none — <reason>`.";

    private static final String NOT_A_GIT_REPO_MESSAGE =
            "This is not a git repository. The `/batch` command requires a git repo because it spawns "
                    + "agents in isolated git worktrees and creates PRs from each. Initialize a repo first, or run this from inside an existing one.";

    private static final String MISSING_INSTRUCTION_MESSAGE =
            "Provide an instruction describing the batch change you want to make.\n\n"
                    + "Examples:\n"
                    + "  /batch migrate from react to vue\n"
                    + "  /batch replace all uses of lodash with native equivalents\n"
                    + "  /batch add type annotations to all untyped function parameters";

    // -------------------------------------------------------------------------
    // Skill metadata
    // -------------------------------------------------------------------------

    public static final String NAME = "batch";
    public static final String DESCRIPTION =
            "Research and plan a large-scale change, then execute it in parallel across 5–30 isolated worktree agents that each open a PR.";
    public static final String WHEN_TO_USE =
            "Use when the user wants to make a sweeping, mechanical change across many files (migrations, refactors, bulk renames) "
                    + "that can be decomposed into independent parallel units.";
    public static final String ARGUMENT_HINT = "<instruction>";

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Builds the prompt for the /batch command.
     *
     * @param args the user-supplied instruction (may be blank)
     * @return prompt parts to send to the model
     */
    public CompletableFuture<List<PromptPart>> getPromptForCommand(String args) {
        return CompletableFuture.supplyAsync(() -> {
            String instruction = args == null ? "" : args.trim();

            if (instruction.isEmpty()) {
                return List.of(new PromptPart("text", MISSING_INSTRUCTION_MESSAGE));
            }

            boolean isGit = isGitRepository();
            if (!isGit) {
                return List.of(new PromptPart("text", NOT_A_GIT_REPO_MESSAGE));
            }

            return List.of(new PromptPart("text", buildPrompt(instruction)));
        });
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private String buildPrompt(String instruction) {
        return "# Batch: Parallel Work Orchestration\n\n"
                + "You are orchestrating a large, parallelizable change across this codebase.\n\n"
                + "## User Instruction\n\n"
                + instruction + "\n\n"
                + "## Phase 1: Research and Plan (Plan Mode)\n\n"
                + "Call the `" + ENTER_PLAN_MODE_TOOL_NAME + "` tool now to enter plan mode, then:\n\n"
                + "1. **Understand the scope.** Launch one or more subagents (in the foreground — you need their results) to deeply research what this instruction touches. Find all the files, patterns, and call sites that need to change. Understand the existing conventions so the migration is consistent.\n\n"
                + "2. **Decompose into independent units.** Break the work into " + MIN_AGENTS + "–" + MAX_AGENTS + " self-contained units. Each unit must:\n"
                + "   - Be independently implementable in an isolated git worktree (no shared state with sibling units)\n"
                + "   - Be mergeable on its own without depending on another unit's PR landing first\n"
                + "   - Be roughly uniform in size (split large units, merge trivial ones)\n\n"
                + "   Scale the count to the actual work: few files → closer to " + MIN_AGENTS + "; hundreds of files → closer to " + MAX_AGENTS + ". Prefer per-directory or per-module slicing over arbitrary file lists.\n\n"
                + "3. **Determine the e2e test recipe.** Figure out how a worker can verify its change actually works end-to-end — not just that unit tests pass. Look for:\n"
                + "   - A `claude-in-chrome` skill or browser-automation tool (for UI changes: click through the affected flow, screenshot the result)\n"
                + "   - A `tmux` or CLI-verifier skill (for CLI changes: launch the app interactively, exercise the changed behavior)\n"
                + "   - A dev-server + curl pattern (for API changes: start the server, hit the affected endpoints)\n"
                + "   - An existing e2e/integration test suite the worker can run\n\n"
                + "   If you cannot find a concrete e2e path, use the `" + ASK_USER_QUESTION_TOOL_NAME + "` tool to ask the user how to verify this change end-to-end. Offer 2–3 specific options based on what you found (e.g., \"Screenshot via chrome extension\", \"Run `bun run dev` and curl the endpoint\", \"No e2e — unit tests are sufficient\"). Do not skip this — the workers cannot ask the user themselves.\n\n"
                + "   Write the recipe as a short, concrete set of steps that a worker can execute autonomously. Include any setup (start a dev server, build first) and the exact command/interaction to verify.\n\n"
                + "4. **Write the plan.** In your plan file, include:\n"
                + "   - A summary of what you found during research\n"
                + "   - A numbered list of work units — for each: a short title, the list of files/directories it covers, and a one-line description of the change\n"
                + "   - The e2e test recipe (or \"skip e2e because …\" if the user chose that)\n"
                + "   - The exact worker instructions you will give each agent (the shared template)\n\n"
                + "5. Call `" + EXIT_PLAN_MODE_TOOL_NAME + "` to present the plan for approval.\n\n"
                + "## Phase 2: Spawn Workers (After Plan Approval)\n\n"
                + "Once the plan is approved, spawn one background agent per work unit using the `" + AGENT_TOOL_NAME + "` tool. **All agents must use `isolation: \"worktree\"` and `run_in_background: true`.** Launch them all in a single message block so they run in parallel.\n\n"
                + "For each agent, the prompt must be fully self-contained. Include:\n"
                + "- The overall goal (the user's instruction)\n"
                + "- This unit's specific task (title, file list, change description — copied verbatim from your plan)\n"
                + "- Any codebase conventions you discovered that the worker needs to follow\n"
                + "- The e2e test recipe from your plan (or \"skip e2e because …\")\n"
                + "- The worker instructions below, copied verbatim:\n\n"
                + "```\n" + WORKER_INSTRUCTIONS + "\n```\n\n"
                + "Use `subagent_type: \"general-purpose\"` unless a more specific agent type fits.\n\n"
                + "## Phase 3: Track Progress\n\n"
                + "After launching all workers, render an initial status table:\n\n"
                + "| # | Unit | Status | PR |\n"
                + "|---|------|--------|----|\\n"
                + "| 1 | <title> | running | — |\n"
                + "| 2 | <title> | running | — |\n\n"
                + "As background-agent completion notifications arrive, parse the `PR: <url>` line from each agent's result and re-render the table with updated status (`done` / `failed`) and PR links. Keep a brief failure note for any agent that did not produce a PR.\n\n"
                + "When all agents have reported, render the final table and a one-line summary (e.g., \"22/24 units landed as PRs\").\n";
    }

    /**
     * Checks whether the current working directory is inside a git repository.
     * Equivalent to the TypeScript {@code getIsGit()} utility.
     */
    private boolean isGitRepository() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--is-inside-work-tree");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            log.debug("git check failed", e);
            return false;
        }
    }

    /** Simple record representing a single prompt part. */
    public record PromptPart(String type, String text) {}
}
