package com.anthropic.claudecode.model;

import com.anthropic.claudecode.model.KeybindingSchema.KeybindingBlock;
import java.util.List;
import java.util.Map;

/**
 * Default keybindings that match current Claude Code behavior.
 * These are loaded first, then user keybindings.json overrides them.
 * Translated from src/keybindings/defaultBindings.ts
 *
 * Platform-specific notes:
 * - IMAGE_PASTE_KEY: Windows uses "alt+v", other platforms use "ctrl+v"
 * - MODE_CYCLE_KEY: Windows without VT mode uses "meta+m", others use "shift+tab"
 * - Feature-gated bindings (QUICK_SEARCH, TERMINAL_PANEL, MESSAGE_ACTIONS,
 *   VOICE_MODE, KAIROS, KAIROS_BRIEF) are included here as the full superset;
 *   runtime feature gating is handled at the application level.
 */
public class KeybindingDefaults {

    /**
     * The full list of default keybinding blocks, ordered by context priority.
     * Translated from DEFAULT_BINDINGS in defaultBindings.ts
     */
    public static final List<KeybindingBlock> DEFAULT_BINDINGS = List.of(

        new KeybindingBlock("Global", Map.ofEntries(
            // ctrl+c and ctrl+d use special time-based double-press handling.
            // They ARE defined here so the resolver can find them, but they
            // CANNOT be rebound by users.
            Map.entry("ctrl+c", "app:interrupt"),
            Map.entry("ctrl+d", "app:exit"),
            Map.entry("ctrl+l", "app:redraw"),
            Map.entry("ctrl+t", "app:toggleTodos"),
            Map.entry("ctrl+o", "app:toggleTranscript"),
            // ctrl+shift+b: app:toggleBrief (KAIROS/KAIROS_BRIEF feature gate)
            Map.entry("ctrl+shift+b", "app:toggleBrief"),
            Map.entry("ctrl+shift+o", "app:toggleTeammatePreview"),
            Map.entry("ctrl+r", "history:search"),
            // File navigation (QUICK_SEARCH feature gate)
            // cmd+ bindings only fire on kitty-protocol terminals
            Map.entry("ctrl+shift+f", "app:globalSearch"),
            Map.entry("cmd+shift+f", "app:globalSearch"),
            Map.entry("ctrl+shift+p", "app:quickOpen"),
            Map.entry("cmd+shift+p", "app:quickOpen"),
            // TERMINAL_PANEL feature gate
            Map.entry("meta+j", "app:toggleTerminal")
        )),

        new KeybindingBlock("Chat", Map.ofEntries(
            Map.entry("escape", "chat:cancel"),
            // ctrl+x chord prefix avoids shadowing readline editing keys
            Map.entry("ctrl+x ctrl+k", "chat:killAgents"),
            // MODE_CYCLE_KEY: "shift+tab" (non-Windows) or "meta+m" (Windows without VT mode)
            Map.entry("shift+tab", "chat:cycleMode"),
            Map.entry("meta+p", "chat:modelPicker"),
            Map.entry("meta+o", "chat:fastMode"),
            Map.entry("meta+t", "chat:thinkingToggle"),
            Map.entry("enter", "chat:submit"),
            Map.entry("up", "history:previous"),
            Map.entry("down", "history:next"),
            // Undo — two bindings for different terminal behaviors:
            // ctrl+_ for legacy terminals (\x1f); ctrl+shift+- for Kitty protocol
            Map.entry("ctrl+_", "chat:undo"),
            Map.entry("ctrl+shift+-", "chat:undo"),
            // ctrl+x ctrl+e is the readline-native edit-and-execute-command binding
            Map.entry("ctrl+x ctrl+e", "chat:externalEditor"),
            Map.entry("ctrl+g", "chat:externalEditor"),
            Map.entry("ctrl+s", "chat:stash"),
            // Image paste (platform-specific: ctrl+v on non-Windows, alt+v on Windows)
            Map.entry("ctrl+v", "chat:imagePaste"),
            // MESSAGE_ACTIONS feature gate
            Map.entry("shift+up", "chat:messageActions"),
            // VOICE_MODE feature gate: hold-to-talk
            Map.entry("space", "voice:pushToTalk")
        )),

        new KeybindingBlock("Autocomplete", Map.of(
            "tab", "autocomplete:accept",
            "escape", "autocomplete:dismiss",
            "up", "autocomplete:previous",
            "down", "autocomplete:next"
        )),

        new KeybindingBlock("Settings", Map.ofEntries(
            // Settings menu uses escape only (not 'n') to dismiss
            Map.entry("escape", "confirm:no"),
            // Config panel list navigation (reuses Select actions)
            Map.entry("up", "select:previous"),
            Map.entry("down", "select:next"),
            Map.entry("k", "select:previous"),
            Map.entry("j", "select:next"),
            Map.entry("ctrl+p", "select:previous"),
            Map.entry("ctrl+n", "select:next"),
            // Toggle/activate the selected setting (space only — enter saves & closes)
            Map.entry("space", "select:accept"),
            // Save and close the config panel
            Map.entry("enter", "settings:close"),
            // Enter search mode
            Map.entry("/", "settings:search"),
            // Retry loading usage data (only active on error)
            Map.entry("r", "settings:retry")
        )),

        new KeybindingBlock("Confirmation", Map.ofEntries(
            Map.entry("y", "confirm:yes"),
            Map.entry("n", "confirm:no"),
            Map.entry("enter", "confirm:yes"),
            Map.entry("escape", "confirm:no"),
            // Navigation for dialogs with lists
            Map.entry("up", "confirm:previous"),
            Map.entry("down", "confirm:next"),
            Map.entry("tab", "confirm:nextField"),
            Map.entry("space", "confirm:toggle"),
            // Cycle modes (used in file permission dialogs and teams dialog)
            Map.entry("shift+tab", "confirm:cycleMode"),
            // Toggle permission explanation in permission dialogs
            Map.entry("ctrl+e", "confirm:toggleExplanation"),
            // Toggle permission debug info
            Map.entry("ctrl+d", "permission:toggleDebug")
        )),

        new KeybindingBlock("Tabs", Map.ofEntries(
            // Tab cycling navigation
            Map.entry("tab", "tabs:next"),
            Map.entry("shift+tab", "tabs:previous"),
            Map.entry("right", "tabs:next"),
            Map.entry("left", "tabs:previous")
        )),

        new KeybindingBlock("Transcript", Map.ofEntries(
            Map.entry("ctrl+e", "transcript:toggleShowAll"),
            Map.entry("ctrl+c", "transcript:exit"),
            Map.entry("escape", "transcript:exit"),
            // q — pager convention (less, tmux copy-mode)
            Map.entry("q", "transcript:exit")
        )),

        new KeybindingBlock("HistorySearch", Map.ofEntries(
            Map.entry("ctrl+r", "historySearch:next"),
            Map.entry("escape", "historySearch:accept"),
            Map.entry("tab", "historySearch:accept"),
            Map.entry("ctrl+c", "historySearch:cancel"),
            Map.entry("enter", "historySearch:execute")
        )),

        new KeybindingBlock("Task", Map.of(
            // Background running foreground tasks (bash commands, agents)
            // In tmux, users must press ctrl+b twice (tmux prefix escape)
            "ctrl+b", "task:background"
        )),

        new KeybindingBlock("ThemePicker", Map.of(
            "ctrl+t", "theme:toggleSyntaxHighlighting"
        )),

        new KeybindingBlock("Scroll", Map.ofEntries(
            Map.entry("pageup", "scroll:pageUp"),
            Map.entry("pagedown", "scroll:pageDown"),
            Map.entry("wheelup", "scroll:lineUp"),
            Map.entry("wheeldown", "scroll:lineDown"),
            Map.entry("ctrl+home", "scroll:top"),
            Map.entry("ctrl+end", "scroll:bottom"),
            // Selection copy. ctrl+shift+c is standard terminal copy.
            // cmd+c only fires on kitty-protocol terminals.
            Map.entry("ctrl+shift+c", "selection:copy"),
            Map.entry("cmd+c", "selection:copy")
        )),

        new KeybindingBlock("Help", Map.of(
            "escape", "help:dismiss"
        )),

        // Attachment navigation (select dialog image attachments)
        new KeybindingBlock("Attachments", Map.ofEntries(
            Map.entry("right", "attachments:next"),
            Map.entry("left", "attachments:previous"),
            Map.entry("backspace", "attachments:remove"),
            Map.entry("delete", "attachments:remove"),
            Map.entry("down", "attachments:exit"),
            Map.entry("escape", "attachments:exit")
        )),

        // Footer indicator navigation (tasks, teams, diff, loop)
        new KeybindingBlock("Footer", Map.ofEntries(
            Map.entry("up", "footer:up"),
            Map.entry("ctrl+p", "footer:up"),
            Map.entry("down", "footer:down"),
            Map.entry("ctrl+n", "footer:down"),
            Map.entry("right", "footer:next"),
            Map.entry("left", "footer:previous"),
            Map.entry("enter", "footer:openSelected"),
            Map.entry("escape", "footer:clearSelection")
        )),

        // Message selector (rewind dialog) navigation
        new KeybindingBlock("MessageSelector", Map.ofEntries(
            Map.entry("up", "messageSelector:up"),
            Map.entry("down", "messageSelector:down"),
            Map.entry("k", "messageSelector:up"),
            Map.entry("j", "messageSelector:down"),
            Map.entry("ctrl+p", "messageSelector:up"),
            Map.entry("ctrl+n", "messageSelector:down"),
            Map.entry("ctrl+up", "messageSelector:top"),
            Map.entry("shift+up", "messageSelector:top"),
            Map.entry("meta+up", "messageSelector:top"),
            Map.entry("shift+k", "messageSelector:top"),
            Map.entry("ctrl+down", "messageSelector:bottom"),
            Map.entry("shift+down", "messageSelector:bottom"),
            Map.entry("meta+down", "messageSelector:bottom"),
            Map.entry("shift+j", "messageSelector:bottom"),
            Map.entry("enter", "messageSelector:select")
        )),

        // Message actions overlay (MESSAGE_ACTIONS feature gate)
        // PromptInput unmounts while cursor active — no key conflict.
        new KeybindingBlock("MessageActions", Map.ofEntries(
            Map.entry("up", "messageActions:prev"),
            Map.entry("down", "messageActions:next"),
            Map.entry("k", "messageActions:prev"),
            Map.entry("j", "messageActions:next"),
            // meta = cmd on macOS; super for kitty keyboard-protocol — bind both
            Map.entry("meta+up", "messageActions:top"),
            Map.entry("meta+down", "messageActions:bottom"),
            Map.entry("super+up", "messageActions:top"),
            Map.entry("super+down", "messageActions:bottom"),
            // Mouse selection extends on shift+arrow when present
            Map.entry("shift+up", "messageActions:prevUser"),
            Map.entry("shift+down", "messageActions:nextUser"),
            Map.entry("escape", "messageActions:escape"),
            Map.entry("ctrl+c", "messageActions:ctrlc"),
            Map.entry("enter", "messageActions:enter"),
            Map.entry("c", "messageActions:c"),
            Map.entry("p", "messageActions:p")
        )),

        // Diff dialog navigation
        new KeybindingBlock("DiffDialog", Map.ofEntries(
            Map.entry("escape", "diff:dismiss"),
            Map.entry("left", "diff:previousSource"),
            Map.entry("right", "diff:nextSource"),
            Map.entry("up", "diff:previousFile"),
            Map.entry("down", "diff:nextFile"),
            Map.entry("enter", "diff:viewDetails")
        )),

        // Model picker effort cycling (ant-only)
        new KeybindingBlock("ModelPicker", Map.of(
            "left", "modelPicker:decreaseEffort",
            "right", "modelPicker:increaseEffort"
        )),

        // Select component navigation (used by /model, /resume, permission prompts, etc.)
        new KeybindingBlock("Select", Map.ofEntries(
            Map.entry("up", "select:previous"),
            Map.entry("down", "select:next"),
            Map.entry("j", "select:next"),
            Map.entry("k", "select:previous"),
            Map.entry("ctrl+n", "select:next"),
            Map.entry("ctrl+p", "select:previous"),
            Map.entry("enter", "select:accept"),
            Map.entry("escape", "select:cancel")
        )),

        // Plugin dialog actions (manage, browse, discover plugins)
        // Navigation (select:*) uses the Select context above
        new KeybindingBlock("Plugin", Map.of(
            "space", "plugin:toggle",
            "i", "plugin:install"
        ))
    );

    private KeybindingDefaults() {}
}
