package com.anthropic.claudecode.model;

/**
 * Agent component types translated from TypeScript types.ts.
 *
 * <p>The TypeScript {@code ModeState} discriminated union is represented as a Java sealed interface
 * hierarchy so callers can use Java 21 pattern-matching switch expressions.
 *
 * <p>Constant paths from {@code AGENT_PATHS} are kept as a nested utility class.
 *
 * <p>{@code AgentValidationResult} becomes a Java record.
 */
public final class AgentComponentTypes {

    private AgentComponentTypes() {}

    // ---------------------------------------------------------------------------
    // AGENT_PATHS constants
    // ---------------------------------------------------------------------------

    public static final class AgentPaths {
        private AgentPaths() {}

        public static final String FOLDER_NAME = ".claude";
        public static final String AGENTS_DIR  = "agents";
    }

    // ---------------------------------------------------------------------------
    // SettingSource enum (maps the TypeScript union used throughout the codebase)
    // ---------------------------------------------------------------------------

    public enum SettingSource {
        FLAG_SETTINGS,
        USER_SETTINGS,
        PROJECT_SETTINGS,
        POLICY_SETTINGS,
        LOCAL_SETTINGS
    }

    // ---------------------------------------------------------------------------
    // AgentSource covers the extended "source" type used in AgentDefinition
    // ---------------------------------------------------------------------------

    public enum AgentSource {
        FLAG_SETTINGS,
        USER_SETTINGS,
        PROJECT_SETTINGS,
        POLICY_SETTINGS,
        LOCAL_SETTINGS,
        BUILT_IN,
        PLUGIN
    }

    // ---------------------------------------------------------------------------
    // AgentDefinition – lightweight representation of a loaded agent
    // ---------------------------------------------------------------------------

    public record AgentDefinition(
            String agentType,
            AgentSource source,
            String filename,   // nullable – may differ from agentType
            String plugin      // nullable – only set for plugin agents
    ) {}

    // ---------------------------------------------------------------------------
    // ModeState sealed interface hierarchy
    //
    // TypeScript:
    //   type ModeState =
    //     | { mode: 'main-menu' }
    //     | { mode: 'list-agents'; source: SettingSource | 'all' | 'built-in' }
    //     | ({ mode: 'agent-menu' }  & WithAgent & WithPreviousMode)
    //     | ({ mode: 'view-agent' }  & WithAgent & WithPreviousMode)
    //     | { mode: 'create-agent' }
    //     | ({ mode: 'edit-agent' }   & WithAgent & WithPreviousMode)
    //     | ({ mode: 'delete-confirm' } & WithAgent & WithPreviousMode)
    // ---------------------------------------------------------------------------

    public sealed interface ModeState
            permits ModeState.MainMenu,
                    ModeState.ListAgents,
                    ModeState.AgentMenu,
                    ModeState.ViewAgent,
                    ModeState.CreateAgent,
                    ModeState.EditAgent,
                    ModeState.DeleteConfirm {

        /** { mode: 'main-menu' } */
        record MainMenu() implements ModeState {}

        /**
         * { mode: 'list-agents'; source: SettingSource | 'all' | 'built-in' }
         *
         * <p>The TypeScript type allows three literal string values beyond SettingSource.
         * We encode them as the sentinel values ALL, BUILT_IN (already in {@link AgentSource})
         * and keep the source as a plain {@link String} to stay flexible.
         */
        record ListAgents(String source) implements ModeState {}

        /** { mode: 'agent-menu' } & WithAgent & WithPreviousMode */
        record AgentMenu(AgentDefinition agent, ModeState previousMode) implements ModeState {}

        /** { mode: 'view-agent' } & WithAgent & WithPreviousMode */
        record ViewAgent(AgentDefinition agent, ModeState previousMode) implements ModeState {}

        /** { mode: 'create-agent' } */
        record CreateAgent() implements ModeState {}

        /** { mode: 'edit-agent' } & WithAgent & WithPreviousMode */
        record EditAgent(AgentDefinition agent, ModeState previousMode) implements ModeState {}

        /** { mode: 'delete-confirm' } & WithAgent & WithPreviousMode */
        record DeleteConfirm(AgentDefinition agent, ModeState previousMode) implements ModeState {}
    }

    // ---------------------------------------------------------------------------
    // AgentValidationResult
    // ---------------------------------------------------------------------------

    public record AgentValidationResult(
            boolean isValid,
            java.util.List<String> errors,
            java.util.List<String> warnings
    ) {}
}
