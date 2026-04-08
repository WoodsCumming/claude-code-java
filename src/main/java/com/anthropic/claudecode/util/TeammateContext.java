package com.anthropic.claudecode.util;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Runtime context for in-process teammates.
 * Translated from src/utils/teammateContext.ts
 *
 * This module provides ThreadLocal-based context for in-process teammates,
 * enabling concurrent teammate execution without global state conflicts.
 *
 * Relationship with other teammate identity mechanisms:
 * - Env vars (CLAUDE_CODE_AGENT_ID): Process-based teammates spawned via external process
 * - dynamicTeamContext (TeammateService): Process-based teammates joining at runtime
 * - TeammateContext (this class): In-process teammates via ThreadLocal
 *
 * The helper functions in TeammateService check ThreadLocal first, then
 * dynamicTeamContext, then env vars.
 */
public final class TeammateContext {

    /**
     * ThreadLocal storage for in-process teammate context.
     * Corresponds to AsyncLocalStorage<TeammateContext> in the TypeScript source.
     * Note: For virtual-thread environments (Java 21+), InheritableThreadLocal
     * would propagate context to child threads; ThreadLocal keeps it isolated
     * within the same thread, which matches the AsyncLocalStorage semantics.
     */
    private static final ThreadLocal<TeammateContextData> contextStorage = new ThreadLocal<>();

    // -------------------------------------------------------------------------
    // Context access
    // -------------------------------------------------------------------------

    /**
     * Get the current in-process teammate context, if running as one.
     * Returns empty Optional if not running within an in-process teammate context.
     * Translated from getTeammateContext() in teammateContext.ts
     */
    public static Optional<TeammateContextData> getTeammateContext() {
        return Optional.ofNullable(contextStorage.get());
    }

    /**
     * Check if current execution is within an in-process teammate.
     * Translated from isInProcessTeammate() in teammateContext.ts
     */
    public static boolean isInProcessTeammate() {
        return contextStorage.get() != null;
    }

    /** Check if running in teammate context. */
    public static boolean isTeammate() {
        return isInProcessTeammate();
    }

    /** Get the team name from the current teammate context, or null if not in teammate mode. */
    public static String getTeamName() {
        TeammateContextData ctx = contextStorage.get();
        return ctx != null ? ctx.getTeamName() : null;
    }

    // -------------------------------------------------------------------------
    // Context lifecycle
    // -------------------------------------------------------------------------

    /**
     * Run a callable with teammate context set.
     * Used when spawning an in-process teammate to establish its execution context.
     * Restores any previous context (or clears it) when the callable completes.
     * Translated from runWithTeammateContext() in teammateContext.ts
     *
     * @param context the teammate context to activate
     * @param fn      the callable to run with that context
     * @return the return value of fn
     */
    public static <T> T runWithTeammateContext(TeammateContextData context,
                                                Callable<T> fn) throws Exception {
        TeammateContextData previous = contextStorage.get();
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
     * Convenience variant that wraps checked exceptions in RuntimeException.
     */
    public static <T> T runWithTeammateContextUnchecked(TeammateContextData context,
                                                          Callable<T> fn) {
        try {
            return runWithTeammateContext(context, fn);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Create a TeammateContextData from spawn configuration.
     * The abortFlag is an optional mechanism for lifecycle management;
     * pass null if not used.
     * Translated from createTeammateContext() in teammateContext.ts
     */
    public static TeammateContextData create(String agentId,
                                              String agentName,
                                              String teamName,
                                              String color,
                                              boolean planModeRequired,
                                              String parentSessionId) {
        return new TeammateContextData(
            agentId, agentName, teamName, color, planModeRequired, parentSessionId, true);
    }

    // -------------------------------------------------------------------------
    // Data record
    // -------------------------------------------------------------------------

    /**
     * Immutable runtime context for an in-process teammate.
     * Corresponds to the TeammateContext type in teammateContext.ts.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TeammateContextData {
        /** Full agent ID, e.g., "researcher@my-team" */
        private String agentId;
        /** Display name, e.g., "researcher" */
        private String agentName;
        /** Team name this teammate belongs to */
        private String teamName;
        /** UI color assigned to this teammate */
        private String color;
        /** Whether teammate must enter plan mode before implementing */
        private boolean planModeRequired;
        /** Leader's session ID (for transcript correlation) */
        private String parentSessionId;
        /** Discriminator – always true for in-process teammates */
        private boolean isInProcess;

        public String getAgentId() { return agentId; }
        public void setAgentId(String v) { agentId = v; }
        public String getAgentName() { return agentName; }
        public void setAgentName(String v) { agentName = v; }
        public String getTeamName() { return teamName; }
        public void setTeamName(String v) { teamName = v; }
        public String getColor() { return color; }
        public void setColor(String v) { color = v; }
        public boolean isPlanModeRequired() { return planModeRequired; }
        public void setPlanModeRequired(boolean v) { planModeRequired = v; }
        public String getParentSessionId() { return parentSessionId; }
        public void setParentSessionId(String v) { parentSessionId = v; }
        public boolean isIsInProcess() { return isInProcess; }
        public void setIsInProcess(boolean v) { isInProcess = v; }
    
    }

    private TeammateContext() {}
}
