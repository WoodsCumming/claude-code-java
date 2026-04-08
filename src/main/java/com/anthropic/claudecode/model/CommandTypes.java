package com.anthropic.claudecode.model;

import lombok.Builder;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.function.Supplier;

/**
 * Command type definitions for slash commands.
 * Translated from src/types/command.ts
 *
 * Note: The existing {@link Command} class in this package already covers the
 * primary {@code Command} and {@code CommandBase} shapes. This class provides
 * the remaining supporting types and the sealed hierarchy for the three
 * command implementation variants.
 */
public class CommandTypes {

    // -------------------------------------------------------------------------
    // LocalCommandResult — sealed union
    // -------------------------------------------------------------------------

    /**
     * Result returned by a local command implementation.
     * Models the TypeScript union:
     *   | { type: 'text'; value: string }
     *   | { type: 'compact'; displayText?: string }
     *   | { type: 'skip' }
     */
    public sealed interface LocalCommandResult
        permits LocalCommandResult.Text,
                LocalCommandResult.Compact,
                LocalCommandResult.Skip {

        record Text(String value) implements LocalCommandResult {}

        record Compact(String displayText) implements LocalCommandResult {}

        record Skip() implements LocalCommandResult {}
    }

    // -------------------------------------------------------------------------
    // ResumeEntrypoint — enum
    // -------------------------------------------------------------------------

    /**
     * Describes the mechanism through which a session resume was triggered.
     */
    public enum ResumeEntrypoint {
        CLI_FLAG("cli_flag"),
        SLASH_COMMAND_PICKER("slash_command_picker"),
        SLASH_COMMAND_SESSION_ID("slash_command_session_id"),
        SLASH_COMMAND_TITLE("slash_command_title"),
        FORK("fork");

        private final String value;

        ResumeEntrypoint(String value) { this.value = value; }

        public String getValue() { return value; }
    }

    // -------------------------------------------------------------------------
    // CommandResultDisplay — enum
    // -------------------------------------------------------------------------

    /**
     * Controls how a command's result message is displayed in the conversation.
     */
    public enum CommandResultDisplay {
        SKIP("skip"),
        SYSTEM("system"),
        USER("user");

        private final String value;

        CommandResultDisplay(String value) { this.value = value; }

    }

    // -------------------------------------------------------------------------
    // CommandAvailability — enum
    // -------------------------------------------------------------------------

    /**
     * Declares which auth/provider environments a command is available in.
     *
     * Commands without an availability restriction are available everywhere.
     * Commands with availability are only shown if the user matches at least
     * one of the listed auth types.
     */
    public enum CommandAvailability {
        /** claude.ai OAuth subscriber (Pro/Max/Team/Enterprise via claude.ai) */
        CLAUDE_AI("claude-ai"),
        /** Console API key user (direct api.anthropic.com, not via claude.ai OAuth) */
        CONSOLE("console");

        private final String value;

        CommandAvailability(String value) { this.value = value; }

    }

    // -------------------------------------------------------------------------
    // CommandBase — base fields shared by all command types
    // -------------------------------------------------------------------------

    /**
     * Base fields shared by all command types.
     */
    @Data
    @lombok.Builder
    
    public static class CommandBase {
        private List<CommandAvailability> availability;
        private String description;
        private Boolean hasUserSpecifiedDescription;
        /** Defaults to true. Only set when the command has conditional enablement. */
        private Supplier<Boolean> isEnabled;
        /** Defaults to false. Only set when the command should be hidden from typeahead/help. */
        private Boolean isHidden;
        private String name;
        private List<String> aliases;
        private Boolean isMcp;
        private String argumentHint;
        private String whenToUse;
        private String version;
        private Boolean disableModelInvocation;
        private Boolean userInvocable;
        /**
         * Where the command was loaded from.
         * Values: "commands_DEPRECATED" | "skills" | "plugin" | "managed" | "bundled" | "mcp"
         */
        private String loadedFrom;
        /** "workflow" distinguishes workflow-backed commands (badged in autocomplete). */
        private String kind;
        /** If true, command executes immediately without waiting for a stop point. */
        private Boolean immediate;
        /** If true, args are redacted from the conversation history. */
        private Boolean isSensitive;
        /** Defaults to {@code name}. Override when the displayed name differs. */
        private Supplier<String> userFacingName;

        /**
         * Get the user-visible name, falling back to {@code name} when not overridden.
         * Translated from getCommandName() in command.ts
         */
        public String getUserFacingName() {
            if (userFacingName != null) return userFacingName.get();
            return name;
        }

        /**
         * Check whether the command is enabled, defaulting to true.
         * Translated from isCommandEnabled() in command.ts
         */
        public boolean isCommandEnabled() {
            if (isEnabled != null) return isEnabled.get();
            return true;
        }
    }

    // -------------------------------------------------------------------------
    // OnDoneOptions — options passed to LocalJSXCommandOnDone
    // -------------------------------------------------------------------------

    /**
     * Options passed to a local JSX command's {@code onDone} callback.
     */
    @Data
    @lombok.Builder
    
    public static class OnDoneOptions {
        private CommandResultDisplay display;
        private Boolean shouldQuery;
        private List<String> metaMessages;
        private String nextInput;
        private Boolean submitNextInput;
    }

    private CommandTypes() {}
}
