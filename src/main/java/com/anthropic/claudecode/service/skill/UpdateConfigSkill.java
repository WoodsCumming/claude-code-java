package com.anthropic.claudecode.service.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * UpdateConfigSkill — helps the user configure Claude Code by modifying
 * {@code settings.json} files (global, project, or local).
 *
 * <p>Covers permissions, environment variables, hooks, MCP server config,
 * plugins, and all other settings.json fields.  For simple settings like
 * {@code theme} or {@code model}, the Config tool is preferred.
 *
 * <p>Translated from: src/skills/bundled/updateConfig.ts
 */
@Slf4j
@Service
public class UpdateConfigSkill {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UpdateConfigSkill.class);


    // -------------------------------------------------------------------------
    // Skill metadata
    // -------------------------------------------------------------------------

    public static final String NAME = "update-config";
    public static final String DESCRIPTION =
            "Use this skill to configure the Claude Code harness via settings.json. "
            + "Automated behaviors (\"from now on when X\", \"each time X\", \"whenever X\", \"before/after X\") "
            + "require hooks configured in settings.json - the harness executes these, not Claude, so "
            + "memory/preferences cannot fulfill them. Also use for: permissions (\"allow X\", \"add permission\", "
            + "\"move permission to\"), env vars (\"set X=Y\"), hook troubleshooting, or any changes to "
            + "settings.json/settings.local.json files. "
            + "Examples: \"allow npm commands\", \"add bq permission to global settings\", "
            + "\"move permission to user settings\", \"set DEBUG=true\", \"when claude stops show X\". "
            + "For simple settings like theme/model, use Config tool.";

    // -------------------------------------------------------------------------
    // Static prompt sections  (mirrors the TS constants)
    // -------------------------------------------------------------------------

    private static final String SETTINGS_EXAMPLES_DOCS = """
            ## Settings File Locations

            Choose the appropriate file based on scope:

            | File | Scope | Git | Use For |
            |------|-------|-----|---------|
            | `~/.claude/settings.json` | Global | N/A | Personal preferences for all projects |
            | `.claude/settings.json` | Project | Commit | Team-wide hooks, permissions, plugins |
            | `.claude/settings.local.json` | Project | Gitignore | Personal overrides for this project |

            Settings load in order: user → project → local (later overrides earlier).

            ## Settings Schema Reference

            ### Permissions
            ```json
            {
              "permissions": {
                "allow": ["Bash(npm:*)", "Edit(.claude)", "Read"],
                "deny": ["Bash(rm -rf:*)"],
                "ask": ["Write(/etc/*)"],
                "defaultMode": "default" | "plan" | "acceptEdits" | "dontAsk",
                "additionalDirectories": ["/extra/dir"]
              }
            }
            ```

            **Permission Rule Syntax:**
            - Exact match: `"Bash(npm run test)"`
            - Prefix wildcard: `"Bash(git:*)"` - matches `git status`, `git commit`, etc.
            - Tool only: `"Read"` - allows all Read operations

            ### Environment Variables
            ```json
            {
              "env": {
                "DEBUG": "true",
                "MY_API_KEY": "value"
              }
            }
            ```

            ### Model & Agent
            ```json
            {
              "model": "sonnet",  // or "opus", "haiku", full model ID
              "agent": "agent-name",
              "alwaysThinkingEnabled": true
            }
            ```

            ### Attribution (Commits & PRs)
            ```json
            {
              "attribution": {
                "commit": "Custom commit trailer text",
                "pr": "Custom PR description text"
              }
            }
            ```
            Set `commit` or `pr` to empty string `""` to hide that attribution.

            ### MCP Server Management
            ```json
            {
              "enableAllProjectMcpServers": true,
              "enabledMcpjsonServers": ["server1", "server2"],
              "disabledMcpjsonServers": ["blocked-server"]
            }
            ```

            ### Plugins
            ```json
            {
              "enabledPlugins": {
                "formatter@anthropic-tools": true
              }
            }
            ```
            Plugin syntax: `plugin-name@source` where source is `claude-code-marketplace`, `claude-plugins-official`, or `builtin`.

            ### Other Settings
            - `language`: Preferred response language (e.g., "japanese")
            - `cleanupPeriodDays`: Days to keep transcripts (default: 30; 0 disables persistence entirely)
            - `respectGitignore`: Whether to respect .gitignore (default: true)
            - `spinnerTipsEnabled`: Show tips in spinner
            - `spinnerVerbs`: Customize spinner verbs (`{ "mode": "append" | "replace", "verbs": [...] }`)
            - `spinnerTipsOverride`: Override spinner tips (`{ "excludeDefault": true, "tips": ["Custom tip"] }`)
            - `syntaxHighlightingDisabled`: Disable diff highlighting
            """;

    private static final String HOOKS_DOCS = """
            ## Hooks Configuration

            Hooks run commands at specific points in Claude Code's lifecycle.

            ### Hook Structure
            ```json
            {
              "hooks": {
                "EVENT_NAME": [
                  {
                    "matcher": "ToolName|OtherTool",
                    "hooks": [
                      {
                        "type": "command",
                        "command": "your-command-here",
                        "timeout": 60,
                        "statusMessage": "Running..."
                      }
                    ]
                  }
                ]
              }
            }
            ```

            ### Hook Events

            | Event | Matcher | Purpose |
            |-------|---------|---------|
            | PermissionRequest | Tool name | Run before permission prompt |
            | PreToolUse | Tool name | Run before tool, can block |
            | PostToolUse | Tool name | Run after successful tool |
            | PostToolUseFailure | Tool name | Run after tool fails |
            | Notification | Notification type | Run on notifications |
            | Stop | - | Run when Claude stops (including clear, resume, compact) |
            | PreCompact | "manual"/"auto" | Before compaction |
            | PostCompact | "manual"/"auto" | After compaction (receives summary) |
            | UserPromptSubmit | - | When user submits |
            | SessionStart | - | When session starts |

            **Common tool matchers:** `Bash`, `Write`, `Edit`, `Read`, `Glob`, `Grep`

            ### Hook Types

            **1. Command Hook** - Runs a shell command:
            ```json
            { "type": "command", "command": "prettier --write $FILE", "timeout": 30 }
            ```

            **2. Prompt Hook** - Evaluates a condition with LLM:
            ```json
            { "type": "prompt", "prompt": "Is this safe? $ARGUMENTS" }
            ```
            Only available for tool events: PreToolUse, PostToolUse, PermissionRequest.

            **3. Agent Hook** - Runs an agent with tools:
            ```json
            { "type": "agent", "prompt": "Verify tests pass: $ARGUMENTS" }
            ```
            Only available for tool events: PreToolUse, PostToolUse, PermissionRequest.

            ### Hook Input (stdin JSON)
            ```json
            {
              "session_id": "abc123",
              "tool_name": "Write",
              "tool_input": { "file_path": "/path/to/file.txt", "content": "..." },
              "tool_response": { "success": true }  // PostToolUse only
            }
            ```

            ### Hook JSON Output

            Hooks can return JSON to control behavior:

            ```json
            {
              "systemMessage": "Warning shown to user in UI",
              "continue": false,
              "stopReason": "Message shown when blocking",
              "suppressOutput": false,
              "decision": "block",
              "reason": "Explanation for decision",
              "hookSpecificOutput": {
                "hookEventName": "PostToolUse",
                "additionalContext": "Context injected back to model"
              }
            }
            ```

            **Fields:**
            - `systemMessage` - Display a message to the user (all hooks)
            - `continue` - Set to `false` to block/stop (default: true)
            - `stopReason` - Message shown when `continue` is false
            - `suppressOutput` - Hide stdout from transcript (default: false)
            - `decision` - "block" for PostToolUse/Stop/UserPromptSubmit hooks
            - `reason` - Explanation for decision
            - `hookSpecificOutput` - Event-specific output (must include `hookEventName`):
              - `additionalContext` - Text injected into model context
              - `permissionDecision` - "allow", "deny", or "ask" (PreToolUse only)
              - `permissionDecisionReason` - Reason for the permission decision (PreToolUse only)
              - `updatedInput` - Modified tool input (PreToolUse only)
            """;

    private static final String HOOK_VERIFICATION_FLOW = """
            ## Constructing a Hook (with verification)

            Given an event, matcher, target file, and desired behavior, follow this flow. Each step catches a different failure class — a hook that silently does nothing is worse than no hook.

            1. **Dedup check.** Read the target file. If a hook already exists on the same event+matcher, show the existing command and ask: keep it, replace it, or add alongside.

            2. **Construct the command for THIS project — don't assume.** The hook receives JSON on stdin. Build a command that:
               - Extracts any needed payload safely — use `jq -r` into a quoted variable or `{ read -r f; ... "$f"; }`, NOT unquoted `| xargs` (splits on spaces)
               - Invokes the underlying tool the way this project runs it (npx/bunx/yarn/pnpm? Makefile target? globally-installed?)
               - Skips inputs the tool doesn't handle (formatters often have `--ignore-unknown`; if not, guard by extension)
               - Stays RAW for now — no `|| true`, no stderr suppression. You'll wrap it after the pipe-test passes.

            3. **Pipe-test the raw command.** Synthesize the stdin payload the hook will receive and pipe it directly:
               - `Pre|PostToolUse` on `Write|Edit`: `echo '{"tool_name":"Edit","tool_input":{"file_path":"<a real file from this repo>"}}' | <cmd>`
               - `Pre|PostToolUse` on `Bash`: `echo '{"tool_name":"Bash","tool_input":{"command":"ls"}}' | <cmd>`
               - `Stop`/`UserPromptSubmit`/`SessionStart`: most commands don't read stdin, so `echo '{}' | <cmd>` suffices

               Check exit code AND side effect (file actually formatted, test actually ran). If it fails you get a real error — fix (wrong package manager? tool not installed? jq path wrong?) and retest. Once it works, wrap with `2>/dev/null || true` (unless the user wants a blocking check).

            4. **Write the JSON.** Merge into the target file (schema shape in the "Hook Structure" section above). If this creates `.claude/settings.local.json` for the first time, add it to .gitignore — the Write tool doesn't auto-gitignore it.

            5. **Validate syntax + schema in one shot:**

               `jq -e '.hooks.<event>[] | select(.matcher == "<matcher>") | .hooks[] | select(.type == "command") | .command' <target-file>`

               Exit 0 + prints your command = correct. Exit 4 = matcher doesn't match. Exit 5 = malformed JSON or wrong nesting. A broken settings.json silently disables ALL settings from that file — fix any pre-existing malformation too.

            6. **Prove the hook fires** — only for `Pre|PostToolUse` on a matcher you can trigger in-turn (`Write|Edit` via Edit, `Bash` via Bash). `Stop`/`UserPromptSubmit`/`SessionStart` fire outside this turn — skip to step 7.

            7. **Handoff.** Tell the user the hook is live (or needs `/hooks`/restart per the watcher caveat). Point them at `/hooks` to review, edit, or disable it later.
            """;

    private static final String UPDATE_CONFIG_PROMPT = """
            # Update Config Skill

            Modify Claude Code configuration by updating settings.json files.

            ## When Hooks Are Required (Not Memory)

            If the user wants something to happen automatically in response to an EVENT, they need a **hook** configured in settings.json. Memory/preferences cannot trigger automated actions.

            **These require hooks:**
            - "Before compacting, ask me what to preserve" → PreCompact hook
            - "After writing files, run prettier" → PostToolUse hook with Write|Edit matcher
            - "When I run bash commands, log them" → PreToolUse hook with Bash matcher
            - "Always run tests after code changes" → PostToolUse hook

            **Hook events:** PreToolUse, PostToolUse, PreCompact, PostCompact, Stop, Notification, SessionStart

            ## CRITICAL: Read Before Write

            **Always read the existing settings file before making changes.** Merge new settings with existing ones - never replace the entire file.

            ## CRITICAL: Use AskUserQuestion for Ambiguity

            When the user's request is ambiguous, use AskUserQuestion to clarify:
            - Which settings file to modify (user/project/local)
            - Whether to add to existing arrays or replace them
            - Specific values when multiple options exist

            ## Decision: Config Tool vs Direct Edit

            **Use the Config tool** for these simple settings:
            - `theme`, `editorMode`, `verbose`, `model`
            - `language`, `alwaysThinkingEnabled`
            - `permissions.defaultMode`

            **Edit settings.json directly** for:
            - Hooks (PreToolUse, PostToolUse, etc.)
            - Complex permission rules (allow/deny arrays)
            - Environment variables
            - MCP server configuration
            - Plugin configuration

            ## Workflow

            1. **Clarify intent** - Ask if the request is ambiguous
            2. **Read existing file** - Use Read tool on the target settings file
            3. **Merge carefully** - Preserve existing settings, especially arrays
            4. **Edit file** - Use Edit tool (if file doesn't exist, ask user to create it first)
            5. **Confirm** - Tell user what was changed

            ## Merging Arrays (Important!)

            When adding to permission arrays or hook arrays, **merge with existing**, don't replace:

            **WRONG** (replaces existing permissions):
            ```json
            { "permissions": { "allow": ["Bash(npm:*)"] } }
            ```

            **RIGHT** (preserves existing + adds new):
            ```json
            {
              "permissions": {
                "allow": [
                  "Bash(git:*)",      // existing
                  "Edit(.claude)",    // existing
                  "Bash(npm:*)"       // new
                ]
              }
            }
            ```
            """;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Builds the prompt for the {@code /update-config} command.
     *
     * @param args optional user request or {@code [hooks-only]} prefix
     * @return a single-element list containing the full prompt text
     */
    public CompletableFuture<List<PromptPart>> getPromptForCommand(String args) {
        return CompletableFuture.supplyAsync(() -> {
            String safeArgs = args == null ? "" : args.strip();

            // Hooks-only mode: skip the full settings documentation.
            if (safeArgs.startsWith("[hooks-only]")) {
                String req = safeArgs.substring("[hooks-only]".length()).strip();
                StringBuilder prompt = new StringBuilder();
                prompt.append(HOOKS_DOCS).append("\n\n").append(HOOK_VERIFICATION_FLOW);
                if (!req.isEmpty()) {
                    prompt.append("\n\n## Task\n\n").append(req);
                }
                return List.of(new PromptPart("text", prompt.toString()));
            }

            // Full mode: include all settings documentation + dynamically generated schema.
            StringBuilder prompt = new StringBuilder();
            prompt.append(UPDATE_CONFIG_PROMPT)
                  .append("\n\n")
                  .append(SETTINGS_EXAMPLES_DOCS)
                  .append("\n\n")
                  .append(HOOKS_DOCS)
                  .append("\n\n")
                  .append(HOOK_VERIFICATION_FLOW);

            // Append a stub for the full JSON schema (a real implementation would
            // generate this from the settings Zod schema equivalent in Java).
            prompt.append("\n\n## Full Settings JSON Schema\n\n")
                  .append("```json\n")
                  .append("{ \"$schema\": \"https://json-schema.org/draft-07/schema\" }\n")
                  .append("```");

            if (!safeArgs.isEmpty()) {
                prompt.append("\n\n## User Request\n\n").append(safeArgs);
            }

            return List.of(new PromptPart("text", prompt.toString()));
        });
    }

    // -------------------------------------------------------------------------
    // Types
    // -------------------------------------------------------------------------

    /** Simple record representing a single prompt part sent to the model. */
    public record PromptPart(String type, String text) {}
}
