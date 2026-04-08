package com.anthropic.claudecode.util;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Agent context utilities for analytics attribution.
 * Translated from src/utils/agentContext.ts
 *
 * Provides a way to track agent identity across async operations without
 * parameter drilling, using ThreadLocal (analogous to AsyncLocalStorage).
 *
 * Supports two agent types:
 *   1. Subagents (Agent tool): run in-process for quick, delegated tasks.
 *   2. In-process teammates: part of a swarm with team coordination.
 *
 * WHY ThreadLocal (not a shared singleton):
 * When agents are running concurrently in the same process, a shared mutable
 * field would be overwritten, causing Agent A's events to use Agent B's context.
 * ThreadLocal isolates each execution chain so concurrent agents don't interfere.
 */
public final class AgentContextUtils {

    private static final ThreadLocal<AgentContextData> CONTEXT_STORAGE = new ThreadLocal<>();

    // =========================================================================
    // Sealed interface + records for the discriminated union
    // =========================================================================

    /**
     * Discriminated union for agent context.
     * Use {@link #agentType()} to distinguish between subagent and teammate.
     * Translated from AgentContext union type in agentContext.ts
     */
    public sealed interface AgentContextData permits SubagentContext, TeammateAgentContext {
        String agentId();
        String agentType();
    }

    /**
     * Context for subagents (Agent tool agents).
     * Translated from SubagentContext in agentContext.ts
     */
    public record SubagentContext(
            /** The subagent's UUID */
            String agentId,
            /** The team lead's session ID, null for main REPL subagents */
            String parentSessionId,
            /** The subagent's type name, e.g. "Explore", "Bash", "code-reviewer" */
            String subagentName,
            /** Whether this is a built-in agent (vs user-defined custom agent) */
            Boolean isBuiltIn,
            /** The request_id in the invoking agent that spawned or resumed this agent */
            String invokingRequestId,
            /** Whether this invocation is the initial spawn or a subsequent resume */
            InvocationKind invocationKind,
            /** Whether this invocation's edge has been emitted to telemetry yet */
            boolean invocationEmitted
    ) implements AgentContextData {

        public SubagentContext(String agentId) {
            this(agentId, null, null, null, null, null, false);
        }

        @Override
        public String agentType() {
            return "subagent";
        }
    }

    /**
     * Context for in-process teammates.
     * Translated from TeammateAgentContext in agentContext.ts
     */
    public record TeammateAgentContext(
            /** Full agent ID, e.g. "researcher@my-team" */
            String agentId,
            /** Display name, e.g. "researcher" */
            String agentName,
            /** Team name this teammate belongs to */
            String teamName,
            /** UI color assigned to this teammate */
            String agentColor,
            /** Whether teammate must enter plan mode before implementing */
            boolean planModeRequired,
            /** The team lead's session ID for transcript correlation */
            String parentSessionId,
            /** Whether this agent is the team lead */
            boolean teamLead,
            /** The request_id in the invoking agent that spawned or resumed this teammate */
            String invokingRequestId,
            /** Whether this invocation is the initial spawn or a subsequent resume */
            InvocationKind invocationKind,
            /** Whether this invocation's edge has been emitted to telemetry yet */
            boolean invocationEmitted
    ) implements AgentContextData {

        public TeammateAgentContext(String agentId, String agentName, String teamName, String parentSessionId) {
            this(agentId, agentName, teamName, null, false, parentSessionId, false, null, null, false);
        }

        @Override
        public String agentType() {
            return "teammate";
        }
    }

    /**
     * Invocation kind — initial spawn or subsequent resume via SendMessage.
     * Translated from the 'spawn' | 'resume' union in agentContext.ts
     */
    public enum InvocationKind {
        SPAWN,
        RESUME
    }

    // =========================================================================
    // Context management
    // =========================================================================

    /**
     * Get the current agent context, if any.
     * Returns empty if not running within an agent context (subagent or teammate).
     * Translated from getAgentContext() in agentContext.ts
     */
    public static Optional<AgentContextData> getAgentContext() {
        return Optional.ofNullable(CONTEXT_STORAGE.get());
    }

    /**
     * Run a callable with the given agent context.
     * All synchronous operations within the callable will have access to this context.
     * Translated from runWithAgentContext() in agentContext.ts
     *
     * @param <T>     return type of the callable
     * @param context agent context to set
     * @param fn      callable to execute
     * @return result of the callable
     * @throws Exception if the callable throws
     */
    public static <T> T runWithAgentContext(AgentContextData context, Callable<T> fn) throws Exception {
        AgentContextData previous = CONTEXT_STORAGE.get();
        CONTEXT_STORAGE.set(context);
        try {
            return fn.call();
        } finally {
            if (previous != null) {
                CONTEXT_STORAGE.set(previous);
            } else {
                CONTEXT_STORAGE.remove();
            }
        }
    }

    /**
     * Run a runnable with the given agent context (no return value).
     */
    public static void runWithAgentContext(AgentContextData context, Runnable fn) {
        AgentContextData previous = CONTEXT_STORAGE.get();
        CONTEXT_STORAGE.set(context);
        try {
            fn.run();
        } finally {
            if (previous != null) {
                CONTEXT_STORAGE.set(previous);
            } else {
                CONTEXT_STORAGE.remove();
            }
        }
    }

    /**
     * Clear the agent context for the current thread.
     */
    public static void clearContext() {
        CONTEXT_STORAGE.remove();
    }

    // =========================================================================
    // Type guards
    // =========================================================================

    /**
     * Type guard to check if context is a SubagentContext.
     * Translated from isSubagentContext() in agentContext.ts
     */
    public static boolean isSubagentContext(AgentContextData context) {
        return context instanceof SubagentContext;
    }

    /**
     * Type guard to check if context is a TeammateAgentContext.
     * Also respects the agent swarms feature gate.
     * Translated from isTeammateAgentContext() in agentContext.ts
     */
    public static boolean isTeammateAgentContext(AgentContextData context) {
        if (!AgentSwarmsUtils.isAgentSwarmsEnabled()) {
            return false;
        }
        return context instanceof TeammateAgentContext;
    }

    // =========================================================================
    // Convenience accessors
    // =========================================================================

    /**
     * Get the subagent log name suitable for analytics logging.
     * Returns the agent type name for built-in agents, "user-defined" for custom agents,
     * or null if not running within a subagent context.
     * Translated from getSubagentLogName() in agentContext.ts
     */
    public static String getSubagentLogName() {
        AgentContextData ctx = CONTEXT_STORAGE.get();
        if (!(ctx instanceof SubagentContext sub) || sub.subagentName() == null) {
            return null;
        }
        return Boolean.TRUE.equals(sub.isBuiltIn()) ? sub.subagentName() : "user-defined";
    }

    /**
     * Get the invoking request_id for the current agent context — once per invocation.
     * Returns the id on the first call after a spawn/resume, then null until the next boundary.
     * Also null on the main thread or when the spawn path had no request_id.
     *
     * Sparse edge semantics: the value appears on exactly one telemetry event per invocation,
     * so a non-null value downstream marks a spawn/resume boundary.
     *
     * Translated from consumeInvokingRequestId() in agentContext.ts
     *
     * NOTE: Because Java records are immutable, the "consumed" flag cannot be set on
     * the record itself. Use {@link #markInvocationEmitted()} after consuming the id.
     */
    public static Optional<InvocationEdge> consumeInvokingRequestId() {
        AgentContextData ctx = CONTEXT_STORAGE.get();
        if (ctx == null) return Optional.empty();

        String requestId = switch (ctx) {
            case SubagentContext s -> s.invokingRequestId();
            case TeammateAgentContext t -> t.invokingRequestId();
        };
        boolean emitted = switch (ctx) {
            case SubagentContext s -> s.invocationEmitted();
            case TeammateAgentContext t -> t.invocationEmitted();
        };

        if (requestId == null || emitted) {
            return Optional.empty();
        }

        InvocationKind kind = switch (ctx) {
            case SubagentContext s -> s.invocationKind();
            case TeammateAgentContext t -> t.invocationKind();
        };

        markInvocationEmitted();
        return Optional.of(new InvocationEdge(requestId, kind));
    }

    /**
     * Mark the current invocation's edge as emitted (idempotent).
     * Should be called immediately after consuming the invokingRequestId.
     */
    public static void markInvocationEmitted() {
        AgentContextData ctx = CONTEXT_STORAGE.get();
        if (ctx == null) return;
        AgentContextData updated = switch (ctx) {
            case SubagentContext s -> new SubagentContext(
                    s.agentId(), s.parentSessionId(), s.subagentName(),
                    s.isBuiltIn(), s.invokingRequestId(), s.invocationKind(), true);
            case TeammateAgentContext t -> new TeammateAgentContext(
                    t.agentId(), t.agentName(), t.teamName(), t.agentColor(),
                    t.planModeRequired(), t.parentSessionId(), t.teamLead(),
                    t.invokingRequestId(), t.invocationKind(), true);
        };
        CONTEXT_STORAGE.set(updated);
    }

    /**
     * Carries the invocation edge information returned by consumeInvokingRequestId().
     */
    public record InvocationEdge(String invokingRequestId, InvocationKind invocationKind) {}

    private AgentContextUtils() {}
}
