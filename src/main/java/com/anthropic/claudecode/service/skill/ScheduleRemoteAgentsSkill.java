package com.anthropic.claudecode.service.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ScheduleRemoteAgentsSkill — creates, updates, lists, or runs scheduled
 * remote Claude Code agents (triggers) via the {@code RemoteTrigger} tool.
 *
 * <p>This skill is enabled only when the {@code tengu_surreal_dali} feature
 * flag is active and the {@code allow_remote_sessions} policy is set.
 *
 * <p>Translated from: src/skills/bundled/scheduleRemoteAgents.ts
 */
@Slf4j
@Service
public class ScheduleRemoteAgentsSkill {



    // -------------------------------------------------------------------------
    // Skill metadata
    // -------------------------------------------------------------------------

    public static final String NAME = "schedule";
    public static final String DESCRIPTION =
            "Create, update, list, or run scheduled remote agents (triggers) that execute on a cron schedule.";
    public static final String WHEN_TO_USE =
            "When the user wants to schedule a recurring remote agent, set up automated tasks, "
            + "create a cron job for Claude Code, or manage their scheduled agents/triggers.";

    // -------------------------------------------------------------------------
    // Tool name constants
    // -------------------------------------------------------------------------

    private static final String REMOTE_TRIGGER_TOOL_NAME   = "RemoteTrigger";
    private static final String ASK_USER_QUESTION_TOOL_NAME = "AskUserQuestion";

    // -------------------------------------------------------------------------
    // Base-58 alphabet (Bitcoin-style) used by the tagged ID system
    // -------------------------------------------------------------------------

    private static final String BASE58 =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    // -------------------------------------------------------------------------
    // Guard
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} when the feature flag and policy are both active.
     * Mirrors the {@code isEnabled} lambda in the TS source.
     */
    public boolean isEnabled() {
        // Simplified flag check — a full implementation would query GrowthBook
        // and the policy-limits service.
        String flag = System.getenv("SCHEDULE_REMOTE_AGENTS_ENABLED");
        return "true".equalsIgnoreCase(flag);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Builds the prompt for the {@code /schedule} command.
     *
     * @param args optional user-supplied intent (e.g. "check deploys every morning")
     * @return a single-element list containing the full prompt text, or an
     *         error/guidance message when prerequisites are not met
     */
    public CompletableFuture<List<PromptPart>> getPromptForCommand(String args) {
        return CompletableFuture.supplyAsync(() -> {
            // Check authentication.
            String accessToken = System.getenv("CLAUDE_AI_ACCESS_TOKEN");
            if (accessToken == null || accessToken.isBlank()) {
                return List.of(new PromptPart("text",
                        "You need to authenticate with a claude.ai account first. "
                        + "API accounts are not supported. Run /login, then try /schedule again."));
            }

            // Obtain the user's local timezone.
            String userTimezone = java.util.TimeZone.getDefault().getID();

            // Build connector/environment stubs (real implementation would call the API).
            String connectorsInfo = "No connected MCP connectors found. The user may need to connect "
                    + "servers at https://claude.ai/settings/connectors";
            String environmentsInfo = "Available environments:\n- default (id: default-env-id, kind: cloud)";
            String gitRepoUrl = detectGitRepoUrl();

            String prompt = buildPrompt(
                    userTimezone,
                    connectorsInfo,
                    gitRepoUrl,
                    environmentsInfo,
                    /* createdEnvironment= */ null,
                    /* setupNotes= */ new ArrayList<>(),
                    /* needsGitHubAccessReminder= */ false,
                    args == null ? "" : args.strip()
            );
            return List.of(new PromptPart("text", prompt));
        });
    }

    // -------------------------------------------------------------------------
    // Prompt builder
    // -------------------------------------------------------------------------

    private String buildPrompt(
            String userTimezone,
            String connectorsInfo,
            String gitRepoUrl,
            String environmentsInfo,
            Object createdEnvironment,        // null or a display name string
            List<String> setupNotes,
            boolean needsGitHubAccessReminder,
            String userArgs
    ) {
        String defaultRepoUrl = gitRepoUrl != null ? gitRepoUrl : "https://github.com/ORG/REPO";

        String setupNotesSection = "";
        if (userArgs != null && !userArgs.isBlank() && !setupNotes.isEmpty()) {
            setupNotesSection = "\n## Setup Notes\n\n" + formatSetupNotes(setupNotes) + "\n";
        }

        String baseQuestion = "What would you like to do with scheduled remote agents?";
        String initialQuestion = setupNotes.isEmpty()
                ? baseQuestion
                : formatSetupNotes(setupNotes) + "\n\n" + baseQuestion;

        String firstStep;
        if (userArgs != null && !userArgs.isBlank()) {
            firstStep = "The user has already told you what they want (see User Request at the bottom). "
                        + "Skip the initial question and go directly to the matching workflow.";
        } else {
            firstStep = "Your FIRST action must be a single " + ASK_USER_QUESTION_TOOL_NAME + " tool call (no preamble). "
                        + "Use this EXACT string for the `question` field — do not paraphrase or shorten it:\n\n"
                        + "\"" + initialQuestion + "\"\n\n"
                        + "Set `header: \"Action\"` and offer the four actions (create/list/update/run) as options. "
                        + "After the user picks, follow the matching workflow below.";
        }

        String userArgsSection = (userArgs != null && !userArgs.isBlank())
                ? "\n\n## User Request\n\nThe user said: \"" + userArgs + "\"\n\n"
                  + "Start by understanding their intent and working through the appropriate workflow above."
                : "";

        return "# Schedule Remote Agents\n\n"
            + "You are helping the user schedule, update, list, or run **remote** Claude Code agents. "
            + "These are NOT local cron jobs — each trigger spawns a fully isolated remote session (CCR) "
            + "in Anthropic's cloud infrastructure on a cron schedule. The agent runs in a sandboxed environment "
            + "with its own git checkout, tools, and optional MCP connections.\n\n"
            + "## First Step\n\n" + firstStep + "\n"
            + setupNotesSection + "\n"
            + "## What You Can Do\n\n"
            + "Use the `" + REMOTE_TRIGGER_TOOL_NAME + "` tool (load it first with "
            + "`ToolSearch select:" + REMOTE_TRIGGER_TOOL_NAME + "`; auth is handled in-process — do not use curl):\n\n"
            + "- `{action: \"list\"}` — list all triggers\n"
            + "- `{action: \"get\", trigger_id: \"...\"}` — fetch one trigger\n"
            + "- `{action: \"create\", body: {...}}` — create a trigger\n"
            + "- `{action: \"update\", trigger_id: \"...\", body: {...}}` — partial update\n"
            + "- `{action: \"run\", trigger_id: \"...\"}` — run a trigger now\n\n"
            + "You CANNOT delete triggers. If the user asks to delete, direct them to: https://claude.ai/code/scheduled\n\n"
            + "## Create body shape\n\n"
            + "```json\n"
            + "{\n"
            + "  \"name\": \"AGENT_NAME\",\n"
            + "  \"cron_expression\": \"CRON_EXPR\",\n"
            + "  \"enabled\": true,\n"
            + "  \"job_config\": {\n"
            + "    \"ccr\": {\n"
            + "      \"environment_id\": \"ENVIRONMENT_ID\",\n"
            + "      \"session_context\": {\n"
            + "        \"model\": \"claude-sonnet-4-6\",\n"
            + "        \"sources\": [\n"
            + "          {\"git_repository\": {\"url\": \"" + defaultRepoUrl + "\"}}\n"
            + "        ],\n"
            + "        \"allowed_tools\": [\"Bash\", \"Read\", \"Write\", \"Edit\", \"Glob\", \"Grep\"]\n"
            + "      },\n"
            + "      \"events\": [\n"
            + "        {\"data\": {\n"
            + "          \"uuid\": \"<lowercase v4 uuid>\",\n"
            + "          \"session_id\": \"\",\n"
            + "          \"type\": \"user\",\n"
            + "          \"parent_tool_use_id\": null,\n"
            + "          \"message\": {\"content\": \"PROMPT_HERE\", \"role\": \"user\"}\n"
            + "        }}\n"
            + "      ]\n"
            + "    }\n"
            + "  }\n"
            + "}\n"
            + "```\n\n"
            + "Generate a fresh lowercase UUID for `events[].data.uuid` yourself.\n\n"
            + "## Available MCP Connectors\n\n"
            + "These are the user's currently connected claude.ai MCP connectors:\n\n"
            + connectorsInfo + "\n\n"
            + "When attaching connectors to a trigger, use the `connector_uuid` and `name` shown above "
            + "(the name is already sanitized to only contain letters, numbers, hyphens, and underscores), "
            + "and the connector's URL. The `name` field in `mcp_connections` must only contain "
            + "`[a-zA-Z0-9_-]` — dots and spaces are NOT allowed.\n\n"
            + "**Important:** Infer what services the agent needs from the user's description. "
            + "Cross-reference against the list above and warn if any required service isn't connected. "
            + "If a needed connector is missing, direct the user to https://claude.ai/settings/connectors to connect it first.\n\n"
            + "## Environments\n\n"
            + "Every trigger requires an `environment_id` in the job config. "
            + "This determines where the remote agent runs. Ask the user which environment to use.\n\n"
            + environmentsInfo + "\n\n"
            + "Use the `id` value as the `environment_id` in `job_config.ccr.environment_id`.\n\n"
            + "## API Field Reference\n\n"
            + "### Create Trigger — Required Fields\n"
            + "- `name` (string) — A descriptive name\n"
            + "- `cron_expression` (string) — 5-field cron. **Minimum interval is 1 hour.**\n"
            + "- `job_config` (object) — Session configuration (see structure above)\n\n"
            + "### Create Trigger — Optional Fields\n"
            + "- `enabled` (boolean, default: true)\n"
            + "- `mcp_connections` (array) — MCP servers to attach:\n"
            + "  ```json\n"
            + "  [{\"connector_uuid\": \"uuid\", \"name\": \"server-name\", \"url\": \"https://...\"}]\n"
            + "  ```\n\n"
            + "### Update Trigger — Optional Fields\n"
            + "All fields optional (partial update):\n"
            + "- `name`, `cron_expression`, `enabled`, `job_config`\n"
            + "- `mcp_connections` — Replace MCP connections\n"
            + "- `clear_mcp_connections` (boolean) — Remove all MCP connections\n\n"
            + "### Cron Expression Examples\n\n"
            + "The user's local timezone is **" + userTimezone + "**. Cron expressions are always in UTC. "
            + "When the user says a local time, convert it to UTC for the cron expression but confirm with them.\n\n"
            + "- `0 9 * * 1-5` — Every weekday at 9am **UTC**\n"
            + "- `0 */2 * * *` — Every 2 hours\n"
            + "- `0 0 * * *` — Daily at midnight **UTC**\n"
            + "- `30 14 * * 1` — Every Monday at 2:30pm **UTC**\n"
            + "- `0 8 1 * *` — First of every month at 8am **UTC**\n\n"
            + "Minimum interval is 1 hour. `*/30 * * * *` will be rejected.\n\n"
            + "## Workflow\n\n"
            + "### CREATE a new trigger:\n\n"
            + "1. **Understand the goal** — Ask what they want the remote agent to do.\n"
            + "2. **Craft the prompt** — Help them write an effective agent prompt.\n"
            + "3. **Set the schedule** — Ask when and how often. The user's timezone is " + userTimezone + ".\n"
            + "4. **Choose the model** — Default to `claude-sonnet-4-6`.\n"
            + "5. **Validate connections** — Infer what services the agent will need.\n"
            + (gitRepoUrl != null
                ? "6. **Choose repo** — The default git repo is already set to `" + gitRepoUrl
                  + "`. Ask the user if this is the right repo or if they need a different one.\n"
                : "6. **Choose repo** — Ask which git repos the remote agent needs cloned into its environment.\n")
            + "7. **Review and confirm** — Show the full configuration before creating.\n"
            + "8. **Create it** — Call `" + REMOTE_TRIGGER_TOOL_NAME + "` with `action: \"create\"` and show the result. "
            + "Always output a link at the end: `https://claude.ai/code/scheduled/{TRIGGER_ID}`\n\n"
            + "### UPDATE a trigger:\n\n"
            + "1. List triggers first so they can pick one\n"
            + "2. Ask what they want to change\n"
            + "3. Show current vs proposed value\n"
            + "4. Confirm and update\n\n"
            + "### LIST triggers:\n\n"
            + "1. Fetch and display in a readable format\n"
            + "2. Show: name, schedule (human-readable), enabled/disabled, next run, repo(s)\n\n"
            + "### RUN NOW:\n\n"
            + "1. List triggers if they haven't specified which one\n"
            + "2. Confirm which trigger\n"
            + "3. Execute and confirm\n\n"
            + "## Important Notes\n\n"
            + "- These are REMOTE agents — they run in Anthropic's cloud, not on the user's machine.\n"
            + "- Always convert cron to human-readable when displaying\n"
            + "- Default to `enabled: true` unless user says otherwise\n"
            + "- The prompt is the most important part — spend time getting it right.\n"
            + "- To delete a trigger, direct users to https://claude.ai/code/scheduled\n"
            + userArgsSection;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Decodes a {@code mcpsrv_}-prefixed tagged ID to a UUID string.
     * Mirrors {@code taggedIdToUUID()} in the TypeScript source.
     *
     * @param taggedId the tagged ID string (e.g. {@code mcpsrv_01ABC…})
     * @return the decoded UUID, or {@code null} if the input is invalid
     */
    String taggedIdToUUID(String taggedId) {
        final String prefix = "mcpsrv_";
        if (!taggedId.startsWith(prefix)) {
            return null;
        }
        // Skip the 2-char version prefix "01".
        String base58Data = taggedId.substring(prefix.length() + 2);

        java.math.BigInteger n = java.math.BigInteger.ZERO;
        java.math.BigInteger base = java.math.BigInteger.valueOf(58);
        for (char c : base58Data.toCharArray()) {
            int idx = BASE58.indexOf(c);
            if (idx == -1) {
                return null;
            }
            n = n.multiply(base).add(java.math.BigInteger.valueOf(idx));
        }

        String hex = n.toString(16);
        // Pad to 32 hex chars.
        hex = "0".repeat(Math.max(0, 32 - hex.length())) + hex;
        return hex.substring(0, 8) + "-"
             + hex.substring(8, 12) + "-"
             + hex.substring(12, 16) + "-"
             + hex.substring(16, 20) + "-"
             + hex.substring(20, 32);
    }

    /** Sanitises a connector display name to {@code [a-zA-Z0-9_-]} only. */
    String sanitizeConnectorName(String name) {
        return name
                .replaceAll("(?i)^claude[.\\s\\-]ai[.\\s\\-]", "")
                .replaceAll("[^a-zA-Z0-9_-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }

    private String formatSetupNotes(List<String> notes) {
        if (notes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("⚠ Heads-up:\n");
        for (String note : notes) {
            sb.append("- ").append(note).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    /** Attempts to detect the HTTPS URL of the current git repository. */
    private String detectGitRepoUrl() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "remote", "get-url", "origin");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes()).strip();
            int exitCode = proc.waitFor();
            if (exitCode != 0 || output.isEmpty()) {
                return null;
            }
            // Normalise SSH → HTTPS and strip .git suffix.
            // e.g. git@github.com:org/repo.git → https://github.com/org/repo
            Pattern sshPattern = Pattern.compile("git@([^:]+):(.+?)(\\.git)?$");
            Matcher m = sshPattern.matcher(output);
            if (m.matches()) {
                return "https://" + m.group(1) + "/" + m.group(2);
            }
            if (output.startsWith("https://")) {
                return output.replaceAll("\\.git$", "");
            }
            return null;
        } catch (Exception e) {
            log.debug("Failed to detect git remote URL: {}", e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Types
    // -------------------------------------------------------------------------

    /** Simple record representing a single prompt part sent to the model. */
    public record PromptPart(String type, String text) {}
}
