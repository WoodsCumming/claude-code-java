package com.anthropic.claudecode.model;

import java.util.List;
import java.util.Map;

/**
 * Schema definitions for keybindings.json configuration.
 * Translated from src/keybindings/schema.ts
 */
public class KeybindingSchema {

    /**
     * Valid context names where keybindings can be applied.
     * Translated from KEYBINDING_CONTEXTS in schema.ts
     */
    public enum KeybindingContext {
        GLOBAL("Global"),
        CHAT("Chat"),
        AUTOCOMPLETE("Autocomplete"),
        CONFIRMATION("Confirmation"),
        HELP("Help"),
        TRANSCRIPT("Transcript"),
        HISTORY_SEARCH("HistorySearch"),
        TASK("Task"),
        THEME_PICKER("ThemePicker"),
        SETTINGS("Settings"),
        TABS("Tabs"),
        ATTACHMENTS("Attachments"),
        FOOTER("Footer"),
        MESSAGE_SELECTOR("MessageSelector"),
        MESSAGE_ACTIONS("MessageActions"),
        DIFF_DIALOG("DiffDialog"),
        MODEL_PICKER("ModelPicker"),
        SELECT("Select"),
        PLUGIN("Plugin");

        private final String value;

        KeybindingContext(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static KeybindingContext fromValue(String value) {
            for (KeybindingContext ctx : values()) {
                if (ctx.value.equals(value)) return ctx;
            }
            return null;
        }
    }

    /**
     * Human-readable descriptions for each keybinding context.
     * Translated from KEYBINDING_CONTEXT_DESCRIPTIONS in schema.ts
     */
    public static final Map<KeybindingContext, String> KEYBINDING_CONTEXT_DESCRIPTIONS = Map.ofEntries(
            Map.entry(KeybindingContext.GLOBAL, "Active everywhere, regardless of focus"),
            Map.entry(KeybindingContext.CHAT, "When the chat input is focused"),
            Map.entry(KeybindingContext.AUTOCOMPLETE, "When autocomplete menu is visible"),
            Map.entry(KeybindingContext.CONFIRMATION, "When a confirmation/permission dialog is shown"),
            Map.entry(KeybindingContext.HELP, "When the help overlay is open"),
            Map.entry(KeybindingContext.TRANSCRIPT, "When viewing the transcript"),
            Map.entry(KeybindingContext.HISTORY_SEARCH, "When searching command history (ctrl+r)"),
            Map.entry(KeybindingContext.TASK, "When a task/agent is running in the foreground"),
            Map.entry(KeybindingContext.THEME_PICKER, "When the theme picker is open"),
            Map.entry(KeybindingContext.SETTINGS, "When the settings menu is open"),
            Map.entry(KeybindingContext.TABS, "When tab navigation is active"),
            Map.entry(KeybindingContext.ATTACHMENTS, "When navigating image attachments in a select dialog"),
            Map.entry(KeybindingContext.FOOTER, "When footer indicators are focused"),
            Map.entry(KeybindingContext.MESSAGE_SELECTOR, "When the message selector (rewind) is open"),
            Map.entry(KeybindingContext.MESSAGE_ACTIONS, "When message actions overlay is active"),
            Map.entry(KeybindingContext.DIFF_DIALOG, "When the diff dialog is open"),
            Map.entry(KeybindingContext.MODEL_PICKER, "When the model picker is open"),
            Map.entry(KeybindingContext.SELECT, "When a select/list component is focused"),
            Map.entry(KeybindingContext.PLUGIN, "When the plugin dialog is open")
    );

    /**
     * All valid keybinding action identifiers.
     * Translated from KEYBINDING_ACTIONS in schema.ts
     */
    public enum KeybindingAction {
        // App-level actions (Global context)
        APP_INTERRUPT("app:interrupt"),
        APP_EXIT("app:exit"),
        APP_TOGGLE_TODOS("app:toggleTodos"),
        APP_TOGGLE_TRANSCRIPT("app:toggleTranscript"),
        APP_TOGGLE_BRIEF("app:toggleBrief"),
        APP_TOGGLE_TEAMMATE_PREVIEW("app:toggleTeammatePreview"),
        APP_TOGGLE_TERMINAL("app:toggleTerminal"),
        APP_REDRAW("app:redraw"),
        APP_GLOBAL_SEARCH("app:globalSearch"),
        APP_QUICK_OPEN("app:quickOpen"),
        // History navigation
        HISTORY_SEARCH("history:search"),
        HISTORY_PREVIOUS("history:previous"),
        HISTORY_NEXT("history:next"),
        // Chat input actions
        CHAT_CANCEL("chat:cancel"),
        CHAT_KILL_AGENTS("chat:killAgents"),
        CHAT_CYCLE_MODE("chat:cycleMode"),
        CHAT_MODEL_PICKER("chat:modelPicker"),
        CHAT_FAST_MODE("chat:fastMode"),
        CHAT_THINKING_TOGGLE("chat:thinkingToggle"),
        CHAT_SUBMIT("chat:submit"),
        CHAT_NEWLINE("chat:newline"),
        CHAT_UNDO("chat:undo"),
        CHAT_EXTERNAL_EDITOR("chat:externalEditor"),
        CHAT_STASH("chat:stash"),
        CHAT_IMAGE_PASTE("chat:imagePaste"),
        CHAT_MESSAGE_ACTIONS("chat:messageActions"),
        // Autocomplete menu actions
        AUTOCOMPLETE_ACCEPT("autocomplete:accept"),
        AUTOCOMPLETE_DISMISS("autocomplete:dismiss"),
        AUTOCOMPLETE_PREVIOUS("autocomplete:previous"),
        AUTOCOMPLETE_NEXT("autocomplete:next"),
        // Confirmation dialog actions
        CONFIRM_YES("confirm:yes"),
        CONFIRM_NO("confirm:no"),
        CONFIRM_PREVIOUS("confirm:previous"),
        CONFIRM_NEXT("confirm:next"),
        CONFIRM_NEXT_FIELD("confirm:nextField"),
        CONFIRM_PREVIOUS_FIELD("confirm:previousField"),
        CONFIRM_CYCLE_MODE("confirm:cycleMode"),
        CONFIRM_TOGGLE("confirm:toggle"),
        CONFIRM_TOGGLE_EXPLANATION("confirm:toggleExplanation"),
        // Tabs navigation actions
        TABS_NEXT("tabs:next"),
        TABS_PREVIOUS("tabs:previous"),
        // Transcript viewer actions
        TRANSCRIPT_TOGGLE_SHOW_ALL("transcript:toggleShowAll"),
        TRANSCRIPT_EXIT("transcript:exit"),
        // History search actions
        HISTORY_SEARCH_NEXT("historySearch:next"),
        HISTORY_SEARCH_ACCEPT("historySearch:accept"),
        HISTORY_SEARCH_CANCEL("historySearch:cancel"),
        HISTORY_SEARCH_EXECUTE("historySearch:execute"),
        // Task/agent actions
        TASK_BACKGROUND("task:background"),
        // Theme picker actions
        THEME_TOGGLE_SYNTAX_HIGHLIGHTING("theme:toggleSyntaxHighlighting"),
        // Help menu actions
        HELP_DISMISS("help:dismiss"),
        // Attachment navigation
        ATTACHMENTS_NEXT("attachments:next"),
        ATTACHMENTS_PREVIOUS("attachments:previous"),
        ATTACHMENTS_REMOVE("attachments:remove"),
        ATTACHMENTS_EXIT("attachments:exit"),
        // Footer indicator actions
        FOOTER_UP("footer:up"),
        FOOTER_DOWN("footer:down"),
        FOOTER_NEXT("footer:next"),
        FOOTER_PREVIOUS("footer:previous"),
        FOOTER_OPEN_SELECTED("footer:openSelected"),
        FOOTER_CLEAR_SELECTION("footer:clearSelection"),
        FOOTER_CLOSE("footer:close"),
        // Message selector (rewind) actions
        MESSAGE_SELECTOR_UP("messageSelector:up"),
        MESSAGE_SELECTOR_DOWN("messageSelector:down"),
        MESSAGE_SELECTOR_TOP("messageSelector:top"),
        MESSAGE_SELECTOR_BOTTOM("messageSelector:bottom"),
        MESSAGE_SELECTOR_SELECT("messageSelector:select"),
        // Message actions
        MESSAGE_ACTIONS_PREV("messageActions:prev"),
        MESSAGE_ACTIONS_NEXT("messageActions:next"),
        MESSAGE_ACTIONS_TOP("messageActions:top"),
        MESSAGE_ACTIONS_BOTTOM("messageActions:bottom"),
        MESSAGE_ACTIONS_PREV_USER("messageActions:prevUser"),
        MESSAGE_ACTIONS_NEXT_USER("messageActions:nextUser"),
        MESSAGE_ACTIONS_ESCAPE("messageActions:escape"),
        MESSAGE_ACTIONS_CTRLC("messageActions:ctrlc"),
        MESSAGE_ACTIONS_ENTER("messageActions:enter"),
        MESSAGE_ACTIONS_C("messageActions:c"),
        MESSAGE_ACTIONS_P("messageActions:p"),
        // Diff dialog actions
        DIFF_DISMISS("diff:dismiss"),
        DIFF_PREVIOUS_SOURCE("diff:previousSource"),
        DIFF_NEXT_SOURCE("diff:nextSource"),
        DIFF_BACK("diff:back"),
        DIFF_VIEW_DETAILS("diff:viewDetails"),
        DIFF_PREVIOUS_FILE("diff:previousFile"),
        DIFF_NEXT_FILE("diff:nextFile"),
        // Model picker actions
        MODEL_PICKER_DECREASE_EFFORT("modelPicker:decreaseEffort"),
        MODEL_PICKER_INCREASE_EFFORT("modelPicker:increaseEffort"),
        // Select component actions
        SELECT_NEXT("select:next"),
        SELECT_PREVIOUS("select:previous"),
        SELECT_ACCEPT("select:accept"),
        SELECT_CANCEL("select:cancel"),
        // Plugin dialog actions
        PLUGIN_TOGGLE("plugin:toggle"),
        PLUGIN_INSTALL("plugin:install"),
        // Permission dialog actions
        PERMISSION_TOGGLE_DEBUG("permission:toggleDebug"),
        // Settings config panel actions
        SETTINGS_SEARCH("settings:search"),
        SETTINGS_RETRY("settings:retry"),
        SETTINGS_CLOSE("settings:close"),
        // Voice actions
        VOICE_PUSH_TO_TALK("voice:pushToTalk"),
        // Selection copy
        SELECTION_COPY("selection:copy"),
        // Scroll actions
        SCROLL_PAGE_UP("scroll:pageUp"),
        SCROLL_PAGE_DOWN("scroll:pageDown"),
        SCROLL_LINE_UP("scroll:lineUp"),
        SCROLL_LINE_DOWN("scroll:lineDown"),
        SCROLL_TOP("scroll:top"),
        SCROLL_BOTTOM("scroll:bottom");

        private final String value;

        KeybindingAction(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static KeybindingAction fromValue(String value) {
            for (KeybindingAction action : values()) {
                if (action.value.equals(value)) return action;
            }
            return null;
        }
    }

    /**
     * A block of keybindings for a specific context.
     * Translated from KeybindingBlockSchema in schema.ts
     *
     * @param context  UI context where these bindings apply
     * @param bindings Map of keystroke patterns to actions (null value = unbind)
     */
    public record KeybindingBlock(
            String context,
            Map<String, String> bindings
    ) {}

    /**
     * The entire keybindings.json file structure.
     * Translated from KeybindingsSchema in schema.ts
     *
     * @param schema   Optional JSON Schema URL for editor validation ($schema)
     * @param docs     Optional documentation URL ($docs)
     * @param bindings Array of keybinding blocks by context
     */
    public record KeybindingsFile(
            String schema,
            String docs,
            List<KeybindingBlock> bindings
    ) {}

    private KeybindingSchema() {}
}
