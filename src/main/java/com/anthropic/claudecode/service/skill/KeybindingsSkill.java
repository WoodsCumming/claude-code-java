package com.anthropic.claudecode.service.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * KeybindingsSkill — helps the user customise keyboard shortcuts by creating
 * or modifying {@code ~/.claude/keybindings.json}.
 *
 * <p>This skill is enabled only when the keybinding-customisation feature flag
 * is active (see {@link #isEnabled()}).
 *
 * <p>Translated from: src/skills/bundled/keybindings.ts
 */
@Slf4j
@Service
public class KeybindingsSkill {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KeybindingsSkill.class);


    // -------------------------------------------------------------------------
    // Skill metadata
    // -------------------------------------------------------------------------

    public static final String NAME = "keybindings-help";

    public static final String DESCRIPTION =
            "Use when the user wants to customize keyboard shortcuts, rebind keys, add chord bindings, "
            + "or modify ~/.claude/keybindings.json. "
            + "Examples: \"rebind ctrl+s\", \"add a chord shortcut\", \"change the submit key\", \"customize keybindings\".";

    // -------------------------------------------------------------------------
    // Static prompt sections  (mirrors the SECTION_* constants in the TS source)
    // -------------------------------------------------------------------------

    private static final String FILE_FORMAT_EXAMPLE = """
            {
              "$schema": "https://www.schemastore.org/claude-code-keybindings.json",
              "$docs": "https://code.claude.com/docs/en/keybindings",
              "bindings": [
                {
                  "context": "Chat",
                  "bindings": {
                    "ctrl+e": "chat:externalEditor"
                  }
                }
              ]
            }""";

    private static final String UNBIND_EXAMPLE = """
            {
              "context": "Chat",
              "bindings": {
                "ctrl+s": null
              }
            }""";

    private static final String REBIND_EXAMPLE = """
            {
              "context": "Chat",
              "bindings": {
                "ctrl+g": null,
                "ctrl+e": "chat:externalEditor"
              }
            }""";

    private static final String CHORD_EXAMPLE = """
            {
              "context": "Global",
              "bindings": {
                "ctrl+k ctrl+t": "app:toggleTodos"
              }
            }""";

    private static final String SECTION_INTRO = """
            # Keybindings Skill

            Create or modify `~/.claude/keybindings.json` to customize keyboard shortcuts.

            ## CRITICAL: Read Before Write

            **Always read `~/.claude/keybindings.json` first** (it may not exist yet). Merge changes with existing bindings — never replace the entire file.

            - Use **Edit** tool for modifications to existing files
            - Use **Write** tool only if the file does not exist yet""";

    private static final String SECTION_FILE_FORMAT =
            "## File Format\n\n```json\n" + FILE_FORMAT_EXAMPLE + "\n```\n\nAlways include the `$schema` and `$docs` fields.";

    private static final String SECTION_KEYSTROKE_SYNTAX = """
            ## Keystroke Syntax

            **Modifiers** (combine with `+`):
            - `ctrl` (alias: `control`)
            - `alt` (aliases: `opt`, `option`) — note: `alt` and `meta` are identical in terminals
            - `shift`
            - `meta` (aliases: `cmd`, `command`)

            **Special keys**: `escape`/`esc`, `enter`/`return`, `tab`, `space`, `backspace`, `delete`, `up`, `down`, `left`, `right`

            **Chords**: Space-separated keystrokes, e.g. `ctrl+k ctrl+s` (1-second timeout between keystrokes)

            **Examples**: `ctrl+shift+p`, `alt+enter`, `ctrl+k ctrl+n`""";

    private static final String SECTION_UNBINDING =
            "## Unbinding Default Shortcuts\n\nSet a key to `null` to remove its default binding:\n\n"
            + "```json\n" + UNBIND_EXAMPLE + "\n```";

    private static final String SECTION_INTERACTION = """
            ## How User Bindings Interact with Defaults

            - User bindings are **additive** — they are appended after the default bindings
            - To **move** a binding to a different key: unbind the old key (`null`) AND add the new binding
            - A context only needs to appear in the user's file if they want to change something in that context""";

    private static final String SECTION_COMMON_PATTERNS =
            "## Common Patterns\n\n"
            + "### Rebind a key\nTo change the external editor shortcut from `ctrl+g` to `ctrl+e`:\n"
            + "```json\n" + REBIND_EXAMPLE + "\n```\n\n"
            + "### Add a chord binding\n"
            + "```json\n" + CHORD_EXAMPLE + "\n```";

    private static final String SECTION_BEHAVIORAL_RULES = """
            ## Behavioral Rules

            1. Only include contexts the user wants to change (minimal overrides)
            2. Validate that actions and contexts are from the known lists below
            3. Warn the user proactively if they choose a key that conflicts with reserved shortcuts or common tools like tmux (`ctrl+b`) and screen (`ctrl+a`)
            4. When adding a new binding for an existing action, the new binding is additive (existing default still works unless explicitly unbound)
            5. To fully replace a default binding, unbind the old key AND add the new one""";

    private static final String SECTION_DOCTOR = """
            ## Validation with /doctor

            The `/doctor` command includes a "Keybinding Configuration Issues" section that validates `~/.claude/keybindings.json`.

            ### Common Issues and Fixes

            | Issue | Cause | Fix |
            | --- | --- | --- |
            | `keybindings.json must have a "bindings" array` | Missing wrapper object | Wrap bindings in `{ "bindings": [...] }` |
            | `"bindings" must be an array` | `bindings` is not an array | Set `"bindings"` to an array: `[{ context: ..., bindings: ... }]` |
            | `Unknown context "X"` | Typo or invalid context name | Use exact context names from the Available Contexts table |
            | `Duplicate key "X" in Y bindings` | Same key defined twice in one context | Remove the duplicate; JSON uses only the last value |
            | `"X" may not work: ...` | Key conflicts with terminal/OS reserved shortcut | Choose a different key (see Reserved Shortcuts section) |
            | `Could not parse keystroke "X"` | Invalid key syntax | Check syntax: use `+` between modifiers, valid key names |
            | `Invalid action for "X"` | Action value is not a string or null | Actions must be strings like `"app:help"` or `null` to unbind |

            ### Example /doctor Output

            ```
            Keybinding Configuration Issues
            Location: ~/.claude/keybindings.json
              └ [Error] Unknown context "chat"
                → Valid contexts: Global, Chat, Autocomplete, ...
              └ [Warning] "ctrl+c" may not work: Terminal interrupt (SIGINT)
            ```

            **Errors** prevent bindings from working and must be fixed. **Warnings** indicate potential conflicts but the binding may still work.""";

    // -------------------------------------------------------------------------
    // Keybinding metadata tables
    // (derived from DEFAULT_BINDINGS / KEYBINDING_ACTIONS / reserved-shortcuts
    //  in the TS source — reproduced here as static data)
    // -------------------------------------------------------------------------

    /** All valid context names, in the order declared by KEYBINDING_CONTEXTS. */
    private static final List<String> KEYBINDING_CONTEXTS = List.of(
            "Global", "Chat", "Autocomplete", "Confirmation", "Tabs",
            "Transcript", "HistorySearch", "Task", "ThemePicker", "Help",
            "Attachments", "Footer", "MessageSelector", "DiffDialog",
            "ModelPicker", "Select"
    );

    private static final Map<String, String> CONTEXT_DESCRIPTIONS = Map.ofEntries(
            Map.entry("Global",          "Active in all contexts"),
            Map.entry("Chat",            "Active while composing a message"),
            Map.entry("Autocomplete",    "Active while the autocomplete dropdown is open"),
            Map.entry("Confirmation",    "Active on permission/confirmation prompts"),
            Map.entry("Tabs",            "Active when the tab bar is focused"),
            Map.entry("Transcript",      "Active while scrolling the conversation transcript"),
            Map.entry("HistorySearch",   "Active while the history search panel is open"),
            Map.entry("Task",            "Active during task/plan mode"),
            Map.entry("ThemePicker",     "Active while the theme picker is open"),
            Map.entry("Help",            "Active while the help overlay is open"),
            Map.entry("Attachments",     "Active while the attachment picker is open"),
            Map.entry("Footer",          "Active when the footer bar is focused"),
            Map.entry("MessageSelector", "Active while selecting messages"),
            Map.entry("DiffDialog",      "Active while the diff dialog is open"),
            Map.entry("ModelPicker",     "Active while the model picker is open"),
            Map.entry("Select",          "Active while a selection list is focused")
    );

    // -------------------------------------------------------------------------
    // Guard
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} when the keybinding-customisation feature is enabled.
     * Mirrors {@code isKeybindingCustomizationEnabled()} from the TS source.
     */
    public boolean isEnabled() {
        String flag = System.getenv("KEYBINDING_CUSTOMIZATION_ENABLED");
        return !"false".equalsIgnoreCase(flag);   // enabled by default
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Builds the prompt for the {@code /keybindings-help} command.
     *
     * @param args optional user-supplied request (e.g. "rebind ctrl+s to save")
     * @return a single-element list containing the full prompt text
     */
    public CompletableFuture<List<PromptPart>> getPromptForCommand(String args) {
        return CompletableFuture.supplyAsync(() -> {
            String contextsTable   = generateContextsTable();
            String reservedSection = generateReservedShortcuts();

            List<String> sections = new ArrayList<>();
            sections.add(SECTION_INTRO);
            sections.add(SECTION_FILE_FORMAT);
            sections.add(SECTION_KEYSTROKE_SYNTAX);
            sections.add(SECTION_UNBINDING);
            sections.add(SECTION_INTERACTION);
            sections.add(SECTION_COMMON_PATTERNS);
            sections.add(SECTION_BEHAVIORAL_RULES);
            sections.add(SECTION_DOCTOR);
            sections.add("## Reserved Shortcuts\n\n" + reservedSection);
            sections.add("## Available Contexts\n\n" + contextsTable);

            if (args != null && !args.isBlank()) {
                sections.add("## User Request\n\n" + args.strip());
            }

            return List.of(new PromptPart("text", String.join("\n\n", sections)));
        });
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** Builds a markdown table of all valid keybinding contexts. */
    private String generateContextsTable() {
        List<String[]> rows = KEYBINDING_CONTEXTS.stream()
                .map(ctx -> new String[]{"`" + ctx + "`", CONTEXT_DESCRIPTIONS.getOrDefault(ctx, "")})
                .toList();
        return markdownTable(new String[]{"Context", "Description"}, rows);
    }

    /**
     * Builds the reserved-shortcuts section.
     * The exact shortcut lists mirror reservedShortcuts.ts from the TS source.
     */
    private String generateReservedShortcuts() {
        return """
                ### Non-rebindable (errors)
                - `ctrl+c` — Terminal interrupt (SIGINT)
                - `ctrl+d` — Terminal EOF / exit

                ### Terminal reserved (errors/warnings)
                - `ctrl+z` — Suspend process (SIGTSTP) (will not work)
                - `ctrl+s` — Terminal flow control (XOFF) (may conflict)
                - `ctrl+q` — Terminal flow control (XON) (may conflict)

                ### macOS reserved (errors)
                - `cmd+space` — Spotlight
                - `cmd+tab` — Application switcher""";
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private static String markdownTable(String[] headers, List<String[]> rows) {
        String header    = "| " + String.join(" | ", headers) + " |";
        String separator = "| " + String.join(" | ", java.util.Arrays.stream(headers).map(h -> "---").toArray(String[]::new)) + " |";
        List<String> lines = new ArrayList<>();
        lines.add(header);
        lines.add(separator);
        for (String[] row : rows) {
            lines.add("| " + String.join(" | ", row) + " |");
        }
        return String.join("\n", lines);
    }

    // -------------------------------------------------------------------------
    // Types
    // -------------------------------------------------------------------------

    /** Simple record representing a single prompt part sent to the model. */
    public record PromptPart(String type, String text) {}
}
