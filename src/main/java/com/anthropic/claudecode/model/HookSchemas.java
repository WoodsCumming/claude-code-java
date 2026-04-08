package com.anthropic.claudecode.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Hook schema types for Claude Code lifecycle hooks.
 * Translated from src/schemas/hooks.ts
 *
 * Contains discriminated hook command types (command, prompt, agent, http),
 * matcher configuration, and the overall hooks settings shape keyed by HookEvent.
 *
 * Hook types use a sealed interface hierarchy to model the TypeScript
 * discriminated union on the `type` field.
 */
public class HookSchemas {

    // ──────────────────────────────────────────────────────────────────────────
    // Shared: if-condition
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Optional condition field using permission rule syntax.
     * E.g. "Bash(git *)" or "Read(*.ts)".
     * Filters hooks before spawning — only runs if the tool call matches.
     * Translated from IfConditionSchema in hooks.ts
     */
    // (represented as a plain String field on each hook type)

    // ──────────────────────────────────────────────────────────────────────────
    // Shell type enum
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Shell interpreter for command hooks.
     * 'bash' uses $SHELL (bash/zsh/sh); 'powershell' uses pwsh.
     * Translated from SHELL_TYPES in shellProvider.ts
     */
    public enum ShellType {
        @JsonProperty("bash")       BASH,
        @JsonProperty("powershell") POWERSHELL
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Hook command sealed hierarchy
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Sealed base for all persisted hook command types.
     * Translated from the HookCommand discriminated union in hooks.ts
     * (type = "command" | "prompt" | "agent" | "http")
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public sealed interface HookCommand
        permits HookCommand.BashCommandHook,
                HookCommand.PromptHook,
                HookCommand.AgentHook,
                HookCommand.HttpHook {

        String getType();

        // ── BashCommandHook ─────────────────────────────────────────────────

        /**
         * Shell command hook (type = "command").
         * Translated from BashCommandHookSchema in hooks.ts
         */
        @Data
        @lombok.Builder
        
        @JsonInclude(JsonInclude.Include.NON_NULL)
        final class BashCommandHook implements HookCommand {
            /** Discriminator — always "command". */
            private final String type = "command";
            /** Shell command to execute. */
            private String command;
            /** Optional if-condition in permission rule syntax. */
            @JsonProperty("if")
            private String ifCondition;
            /** Shell interpreter (bash or powershell). Defaults to bash. */
            private ShellType shell;
            /** Timeout in seconds for this command. */
            private Double timeout;
            /** Custom status message shown in the spinner while the hook runs. */
            private String statusMessage;
            /** If true, hook runs once and is removed after execution. */
            private Boolean once;
            /** If true, hook runs in the background without blocking. */
            @JsonProperty("async")
            private Boolean async;
            /**
             * If true, hook runs in the background and wakes the model on exit
             * code 2 (blocking error). Implies async.
             */
            private Boolean asyncRewake;
        }

        // ── PromptHook ──────────────────────────────────────────────────────

        /**
         * LLM prompt hook (type = "prompt").
         * Translated from PromptHookSchema in hooks.ts
         */
        @Data
        @lombok.Builder
        
        @JsonInclude(JsonInclude.Include.NON_NULL)
        final class PromptHook implements HookCommand {
            private final String type = "prompt";
            /**
             * Prompt evaluated with an LLM.
             * Use $ARGUMENTS placeholder for hook input JSON.
             */
            private String prompt;
            @JsonProperty("if")
            private String ifCondition;
            /** Timeout in seconds for this prompt evaluation. */
            private Double timeout;
            /** Model to use (e.g. "claude-sonnet-4-6"). Defaults to small fast model. */
            private String model;
            /** Custom status message shown in the spinner. */
            private String statusMessage;
            /** If true, hook runs once and is removed after execution. */
            private Boolean once;
        }

        // ── AgentHook ───────────────────────────────────────────────────────

        /**
         * Agentic verifier hook (type = "agent").
         * Translated from AgentHookSchema in hooks.ts
         */
        @Data
        @lombok.Builder
        
        @JsonInclude(JsonInclude.Include.NON_NULL)
        final class AgentHook implements HookCommand {
            private final String type = "agent";
            /**
             * Prompt describing what to verify.
             * Use $ARGUMENTS placeholder for hook input JSON.
             */
            private String prompt;
            @JsonProperty("if")
            private String ifCondition;
            /** Timeout in seconds for agent execution. Default 60. */
            private Double timeout;
            /** Model to use (e.g. "claude-sonnet-4-6"). Defaults to Haiku. */
            private String model;
            /** Custom status message shown in the spinner. */
            private String statusMessage;
            /** If true, hook runs once and is removed after execution. */
            private Boolean once;
        }

        // ── HttpHook ────────────────────────────────────────────────────────

        /**
         * HTTP hook (type = "http").
         * Translated from HttpHookSchema in hooks.ts
         */
        @Data
        @lombok.Builder
        
        @JsonInclude(JsonInclude.Include.NON_NULL)
        final class HttpHook implements HookCommand {
            private final String type = "http";
            /** URL to POST the hook input JSON to. */
            private String url;
            @JsonProperty("if")
            private String ifCondition;
            /** Timeout in seconds for this request. */
            private Double timeout;
            /**
             * Additional headers for the request.
             * Values may reference env vars using $VAR_NAME or ${VAR_NAME} syntax.
             * Only vars listed in allowedEnvVars will be interpolated.
             */
            private Map<String, String> headers;
            /**
             * Explicit list of env var names that may be interpolated in header values.
             * Required for env var interpolation to work.
             */
            private List<String> allowedEnvVars;
            /** Custom status message shown in the spinner. */
            private String statusMessage;
            /** If true, hook runs once and is removed after execution. */
            private Boolean once;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // HookMatcher
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Matcher configuration with multiple hooks.
     * Translated from HookMatcherSchema in hooks.ts
     *
     * The `matcher` field is a string pattern (e.g. tool name "Write") to match
     * values related to the hook event. If absent, hooks run for every occurrence.
     */
    @Data
    @lombok.Builder
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HookMatcher {
        /** String pattern to match (e.g. tool names like "Write"). */
        private String matcher;
        /** List of hooks to execute when the matcher matches. */
        private List<HookCommand> hooks;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // HooksSettings
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Top-level hooks configuration.
     * Translated from HooksSchema in hooks.ts
     *
     * Key = hook event name (matches HookEvent enum values).
     * Value = list of matcher configurations for that event.
     * Uses Map<String, List<HookMatcher>> since not all hook events need to be defined.
     */
    public record HooksSettings(
        Map<String, List<HookMatcher>> hooks
    ) {}

    private HookSchemas() {}
}
