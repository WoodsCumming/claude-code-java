package com.anthropic.claudecode.service.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * LoopSkill — schedules a prompt or slash command to run on a recurring cron
 * interval via the {@code CronCreate} tool.
 *
 * <p>This skill is enabled only when the Kairos cron feature flag is active
 * (see {@link #isEnabled()}).
 *
 * <p>Translated from: src/skills/bundled/loop.ts
 */
@Slf4j
@Service
public class LoopSkill {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LoopSkill.class);


    // -------------------------------------------------------------------------
    // Skill metadata
    // -------------------------------------------------------------------------

    public static final String NAME = "loop";
    public static final String DESCRIPTION =
            "Run a prompt or slash command on a recurring interval (e.g. /loop 5m /foo, defaults to 10m)";
    public static final String WHEN_TO_USE =
            "When the user wants to set up a recurring task, poll for status, or run something repeatedly "
            + "on an interval (e.g. \"check the deploy every 5 minutes\", \"keep running /babysit-prs\"). "
            + "Do NOT invoke for one-off tasks.";
    public static final String ARGUMENT_HINT = "[interval] <prompt>";

    // -------------------------------------------------------------------------
    // Tool name constants (mirrors the imports in the TS source)
    // -------------------------------------------------------------------------

    private static final String CRON_CREATE_TOOL_NAME  = "CronCreate";
    private static final String CRON_DELETE_TOOL_NAME  = "CronDelete";
    private static final int    DEFAULT_MAX_AGE_DAYS   = 7;
    private static final String DEFAULT_INTERVAL       = "10m";

    // -------------------------------------------------------------------------
    // Static prompt content
    // -------------------------------------------------------------------------

    private static final String USAGE_MESSAGE =
            "Usage: /loop [interval] <prompt>\n\n"
            + "Run a prompt or slash command on a recurring interval.\n\n"
            + "Intervals: Ns, Nm, Nh, Nd (e.g. 5m, 30m, 2h, 1d). Minimum granularity is 1 minute.\n"
            + "If no interval is specified, defaults to " + DEFAULT_INTERVAL + ".\n\n"
            + "Examples:\n"
            + "  /loop 5m /babysit-prs\n"
            + "  /loop 30m check the deploy\n"
            + "  /loop 1h /standup 1\n"
            + "  /loop check the deploy          (defaults to " + DEFAULT_INTERVAL + ")\n"
            + "  /loop check the deploy every 20m";

    // -------------------------------------------------------------------------
    // Guard
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} when the Kairos cron feature is enabled.
     * Mirrors {@code isKairosCronEnabled()} from the TS source.
     */
    public boolean isEnabled() {
        String flag = System.getenv("KAIROS_CRON_ENABLED");
        return "true".equalsIgnoreCase(flag);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Builds the prompt for the {@code /loop} command.
     *
     * @param args the raw user-supplied arguments (may be blank)
     * @return a single-element list containing the full prompt text
     */
    public CompletableFuture<List<PromptPart>> getPromptForCommand(String args) {
        return CompletableFuture.supplyAsync(() -> {
            String trimmed = args == null ? "" : args.strip();
            if (trimmed.isEmpty()) {
                return List.of(new PromptPart("text", USAGE_MESSAGE));
            }
            return List.of(new PromptPart("text", buildPrompt(trimmed)));
        });
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private String buildPrompt(String args) {
        return "# /loop — schedule a recurring prompt\n\n"
            + "Parse the input below into `[interval] <prompt…>` and schedule it with " + CRON_CREATE_TOOL_NAME + ".\n\n"
            + "## Parsing (in priority order)\n\n"
            + "1. **Leading token**: if the first whitespace-delimited token matches `^\\d+[smhd]$` "
            + "(e.g. `5m`, `2h`), that's the interval; the rest is the prompt.\n"
            + "2. **Trailing \"every\" clause**: otherwise, if the input ends with `every <N><unit>` or "
            + "`every <N> <unit-word>` (e.g. `every 20m`, `every 5 minutes`, `every 2 hours`), extract "
            + "that as the interval and strip it from the prompt. Only match when what follows \"every\" "
            + "is a time expression — `check every PR` has no interval.\n"
            + "3. **Default**: otherwise, interval is `" + DEFAULT_INTERVAL + "` and the entire input is the prompt.\n\n"
            + "If the resulting prompt is empty, show usage `/loop [interval] <prompt>` and stop — do not call "
            + CRON_CREATE_TOOL_NAME + ".\n\n"
            + "Examples:\n"
            + "- `5m /babysit-prs` → interval `5m`, prompt `/babysit-prs` (rule 1)\n"
            + "- `check the deploy every 20m` → interval `20m`, prompt `check the deploy` (rule 2)\n"
            + "- `run tests every 5 minutes` → interval `5m`, prompt `run tests` (rule 2)\n"
            + "- `check the deploy` → interval `" + DEFAULT_INTERVAL + "`, prompt `check the deploy` (rule 3)\n"
            + "- `check every PR` → interval `" + DEFAULT_INTERVAL + "`, prompt `check every PR` (rule 3 — \"every\" not followed by time)\n"
            + "- `5m` → empty prompt → show usage\n\n"
            + "## Interval → cron\n\n"
            + "Supported suffixes: `s` (seconds, rounded up to nearest minute, min 1), `m` (minutes), `h` (hours), `d` (days). Convert:\n\n"
            + "| Interval pattern      | Cron expression     | Notes                                    |\n"
            + "|-----------------------|---------------------|------------------------------------------|\n"
            + "| `Nm` where N ≤ 59   | `*/N * * * *`     | every N minutes                          |\n"
            + "| `Nm` where N ≥ 60   | `0 */H * * *`     | round to hours (H = N/60, must divide 24)|\n"
            + "| `Nh` where N ≤ 23   | `0 */N * * *`     | every N hours                            |\n"
            + "| `Nd`                | `0 0 */N * *`     | every N days at midnight local           |\n"
            + "| `Ns`                | treat as `ceil(N/60)m` | cron minimum granularity is 1 minute  |\n\n"
            + "**If the interval doesn't cleanly divide its unit** (e.g. `7m` → `*/7 * * * *` gives uneven gaps at "
            + ":56→:00; `90m` → 1.5h which cron can't express), pick the nearest clean interval and tell the user "
            + "what you rounded to before scheduling.\n\n"
            + "## Action\n\n"
            + "1. Call " + CRON_CREATE_TOOL_NAME + " with:\n"
            + "   - `cron`: the expression from the table above\n"
            + "   - `prompt`: the parsed prompt from above, verbatim (slash commands are passed through unchanged)\n"
            + "   - `recurring`: `true`\n"
            + "2. Briefly confirm: what's scheduled, the cron expression, the human-readable cadence, "
            + "that recurring tasks auto-expire after " + DEFAULT_MAX_AGE_DAYS + " days, "
            + "and that they can cancel sooner with " + CRON_DELETE_TOOL_NAME + " (include the job ID).\n"
            + "3. **Then immediately execute the parsed prompt now** — don't wait for the first cron fire. "
            + "If it's a slash command, invoke it via the Skill tool; otherwise act on it directly.\n\n"
            + "## Input\n\n"
            + args;
    }

    // -------------------------------------------------------------------------
    // Types
    // -------------------------------------------------------------------------

    /** Simple record representing a single prompt part sent to the model. */
    public record PromptPart(String type, String text) {}
}
