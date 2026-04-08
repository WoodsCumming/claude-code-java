package com.anthropic.claudecode.util;

import java.util.Optional;

/**
 * Agent context for analytics attribution.
 * Translated from src/utils/agentContext.ts
 *
 * Uses ThreadLocal for per-thread agent context isolation.
 */
public class AgentContext {

    private static final ThreadLocal<AgentContextData> contextStorage = new ThreadLocal<>();

    /**
     * Run a function with a subagent context.
     * Translated from runWithAgentContext() in agentContext.ts
     */
    public static <T> T runWithAgentContext(AgentContextData context, java.util.concurrent.Callable<T> fn) throws Exception {
        AgentContextData previous = contextStorage.get();
        contextStorage.set(context);
        try {
            return fn.call();
        } finally {
            if (previous != null) {
                contextStorage.set(previous);
            } else {
                contextStorage.remove();
            }
        }
    }

    /**
     * Get the current agent context.
     * Translated from getAgentContext() in agentContext.ts
     */
    public static Optional<AgentContextData> getAgentContext() {
        return Optional.ofNullable(contextStorage.get());
    }

    /**
     * Clear the agent context.
     */
    public static void clearContext() {
        contextStorage.remove();
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    public sealed interface AgentContextData permits SubagentContext, TeammateContext {
        String getAgentId();
        String getAgentType();
    }

    public record SubagentContext(
        String agentId,
        String parentSessionId,
        String agentType,
        String subagentName
    ) implements AgentContextData {
        public SubagentContext(String agentId) {
            this(agentId, null, "subagent", null);
        }

        @Override public String getAgentId() { return agentId; }
        @Override public String getAgentType() { return agentType; }
    }

    public record TeammateContext(
        String agentId,
        String teamName,
        String agentType
    ) implements AgentContextData {
        public TeammateContext(String agentId, String teamName) {
            this(agentId, teamName, "teammate");
        }

        @Override public String getAgentId() { return agentId; }
        @Override public String getAgentType() { return agentType; }
    }
}
