package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.AgentDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Shared utilities for displaying agent information.
 * Translated from src/tools/AgentTool/agentDisplay.ts
 *
 * Used by both the CLI `claude agents` handler and the interactive `/agents` command.
 */
@Slf4j
@Service
public class AgentDisplayService {



    /**
     * Agent source type — mirrors the TypeScript union:
     * SettingSource | 'built-in' | 'plugin'
     */
    public enum AgentSource {
        USER_SETTINGS("userSettings"),
        PROJECT_SETTINGS("projectSettings"),
        LOCAL_SETTINGS("localSettings"),
        POLICY_SETTINGS("policySettings"),
        FLAG_SETTINGS("flagSettings"),
        BUILT_IN("built-in"),
        PLUGIN("plugin");

        private final String value;

        AgentSource(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static AgentSource fromValue(String value) {
            for (AgentSource source : values()) {
                if (source.value.equals(value)) return source;
            }
            return null;
        }
    }

    /**
     * Ordered list of agent source groups for display.
     * Both the CLI and interactive UI should use this to ensure consistent ordering.
     * Translated from AGENT_SOURCE_GROUPS constant.
     */
    public record AgentSourceGroup(String label, AgentSource source) {}

    public static final List<AgentSourceGroup> AGENT_SOURCE_GROUPS = List.of(
        new AgentSourceGroup("User agents",    AgentSource.USER_SETTINGS),
        new AgentSourceGroup("Project agents", AgentSource.PROJECT_SETTINGS),
        new AgentSourceGroup("Local agents",   AgentSource.LOCAL_SETTINGS),
        new AgentSourceGroup("Managed agents", AgentSource.POLICY_SETTINGS),
        new AgentSourceGroup("Plugin agents",  AgentSource.PLUGIN),
        new AgentSourceGroup("CLI arg agents", AgentSource.FLAG_SETTINGS),
        new AgentSourceGroup("Built-in agents",AgentSource.BUILT_IN)
    );

    /**
     * An agent annotated with override information.
     * Translated from ResolvedAgent type.
     */
    public record ResolvedAgent(AgentDefinition definition, AgentSource overriddenBy) {
        /** Convenience: no override. */
        public ResolvedAgent(AgentDefinition definition) {
            this(definition, null);
        }

        public boolean isOverridden() {
            return overriddenBy != null;
        }
    }

    /**
     * Annotate agents with override information by comparing against the active
     * (winning) agent list. An agent is "overridden" when another agent with the
     * same type from a higher-priority source takes precedence.
     *
     * Also deduplicates by (agentType, source) to handle git worktree duplicates
     * where the same agent file is loaded from both the worktree and main repo.
     *
     * Translated from resolveAgentOverrides().
     */
    public List<ResolvedAgent> resolveAgentOverrides(
            List<AgentDefinition> allAgents,
            List<AgentDefinition> activeAgents) {

        // Build a map from agentType -> active agent for O(1) lookup
        Map<String, AgentDefinition> activeMap = new LinkedHashMap<>();
        for (AgentDefinition agent : activeAgents) {
            activeMap.put(agent.getAgentType(), agent);
        }

        Set<String> seen = new LinkedHashSet<>();
        List<ResolvedAgent> resolved = new ArrayList<>();

        for (AgentDefinition agent : allAgents) {
            // Deduplicate by (agentType, source)
            String key = agent.getAgentType() + ":" + agent.getSource();
            if (seen.contains(key)) continue;
            seen.add(key);

            AgentDefinition active = activeMap.get(agent.getAgentType());
            AgentSource overriddenBy = null;
            if (active != null && !active.getSource().equals(agent.getSource())) {
                overriddenBy = AgentSource.fromValue(active.getSource());
            }
            resolved.add(new ResolvedAgent(agent, overriddenBy));
        }

        return resolved;
    }

    /**
     * Resolve the display model string for an agent.
     * Returns the model alias or null (when no model is configured).
     * Translated from resolveAgentModelDisplay().
     */
    public String resolveAgentModelDisplay(AgentDefinition agent) {
        String model = agent.getModel();
        if (model == null || model.isBlank()) {
            // equivalent to getDefaultSubagentModel() returning null
            return null;
        }
        return "inherit".equals(model) ? "inherit" : model;
    }

    /**
     * Get a human-readable label for the source that overrides an agent.
     * Returns lowercase, e.g. "user", "project", "managed".
     * Translated from getOverrideSourceLabel().
     */
    public String getOverrideSourceLabel(AgentSource source) {
        return getSourceDisplayName(source).toLowerCase(Locale.ROOT);
    }

    /**
     * Human-readable display name for each agent source.
     * Mirrors getSourceDisplayName() from utils/settings/constants.ts.
     */
    private String getSourceDisplayName(AgentSource source) {
        return switch (source) {
            case USER_SETTINGS    -> "User";
            case PROJECT_SETTINGS -> "Project";
            case LOCAL_SETTINGS   -> "Local";
            case POLICY_SETTINGS  -> "Managed";
            case FLAG_SETTINGS    -> "CLI Arg";
            case BUILT_IN         -> "Built-in";
            case PLUGIN           -> "Plugin";
        };
    }

    /**
     * Compare agents alphabetically by name (case-insensitive).
     * Translated from compareAgentsByName().
     */
    public int compareAgentsByName(AgentDefinition a, AgentDefinition b) {
        return a.getAgentType().compareToIgnoreCase(b.getAgentType());
    }
}
