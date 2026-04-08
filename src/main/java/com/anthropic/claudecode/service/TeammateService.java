package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import com.anthropic.claudecode.util.TeammateContext;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Teammate coordination service for agent swarm identity and lifecycle.
 * Translated from src/utils/teammate.ts
 *
 * These helpers identify whether this Claude Code instance is running as a
 * spawned teammate in a swarm. Teammates receive their identity via CLI
 * arguments (--agent-id, --team-name, etc.) which are stored in dynamicTeamContext.
 *
 * For in-process teammates (running in the same JVM), ThreadLocal-based
 * TeammateContext provides isolated context per thread.
 *
 * Priority order for identity resolution:
 * 1. TeammateContext (ThreadLocal, in-process teammates)
 * 2. dynamicTeamContext (process teammates joined at runtime via CLI args)
 * 3. Environment variables
 */
@Slf4j
@Service
public class TeammateService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TeammateService.class);


    /**
     * Dynamic team context set at runtime when joining a team.
     * Equivalent to dynamicTeamContext in teammate.ts.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DynamicTeamContext {
        private String agentId;
        private String agentName;
        private String teamName;
        private String color;
        private boolean planModeRequired;
        private String parentSessionId;

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
    }

    private final AtomicReference<DynamicTeamContext> dynamicTeamContext = new AtomicReference<>();

    // -------------------------------------------------------------------------
    // Dynamic context lifecycle
    // -------------------------------------------------------------------------

    /**
     * Set the dynamic team context (called when joining a team at runtime).
     * Translated from setDynamicTeamContext() in teammate.ts
     */
    public void setDynamicTeamContext(DynamicTeamContext context) {
        dynamicTeamContext.set(context);
    }

    /**
     * Clear the dynamic team context (called when leaving a team).
     * Translated from clearDynamicTeamContext() in teammate.ts
     */
    public void clearDynamicTeamContext() {
        dynamicTeamContext.set(null);
    }

    /**
     * Get the current dynamic team context.
     * Translated from getDynamicTeamContext() in teammate.ts
     */
    public DynamicTeamContext getDynamicTeamContext() {
        return dynamicTeamContext.get();
    }

    // -------------------------------------------------------------------------
    // Identity resolution helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the parent session ID for this teammate.
     * Priority: ThreadLocal (in-process) > dynamicTeamContext > env var.
     * Translated from getParentSessionId() in teammate.ts
     */
    public String getParentSessionId() {
        Optional<TeammateContext.TeammateContextData> inProcess = TeammateContext.getTeammateContext();
        if (inProcess.isPresent()) return inProcess.get().getParentSessionId();

        DynamicTeamContext ctx = dynamicTeamContext.get();
        if (ctx != null) return ctx.getParentSessionId();

        return System.getenv("CLAUDE_CODE_PARENT_SESSION_ID");
    }

    /**
     * Returns the agent ID if this session is running as a teammate in a swarm.
     * Priority: ThreadLocal (in-process) > dynamicTeamContext > env var.
     * Translated from getAgentId() in teammate.ts
     */
    public String getAgentId() {
        Optional<TeammateContext.TeammateContextData> inProcess = TeammateContext.getTeammateContext();
        if (inProcess.isPresent()) return inProcess.get().getAgentId();

        DynamicTeamContext ctx = dynamicTeamContext.get();
        if (ctx != null) return ctx.getAgentId();

        return System.getenv("CLAUDE_CODE_AGENT_ID");
    }

    /**
     * Returns the agent name if this session is running as a teammate in a swarm.
     * Priority: ThreadLocal (in-process) > dynamicTeamContext > env var.
     * Translated from getAgentName() in teammate.ts
     */
    public String getAgentName() {
        Optional<TeammateContext.TeammateContextData> inProcess = TeammateContext.getTeammateContext();
        if (inProcess.isPresent()) return inProcess.get().getAgentName();

        DynamicTeamContext ctx = dynamicTeamContext.get();
        if (ctx != null) return ctx.getAgentName();

        return System.getenv("CLAUDE_CODE_AGENT_NAME");
    }

    /**
     * Returns the team name if this session is part of a team.
     * Priority: ThreadLocal (in-process) > dynamicTeamContext > env var > passed teamContext.
     * Translated from getTeamName() in teammate.ts
     *
     * @param teamContext optional team context from AppState (for leaders)
     */
    public String getTeamName(String teamContextTeamName) {
        Optional<TeammateContext.TeammateContextData> inProcess = TeammateContext.getTeammateContext();
        if (inProcess.isPresent()) return inProcess.get().getTeamName();

        DynamicTeamContext ctx = dynamicTeamContext.get();
        if (ctx != null && ctx.getTeamName() != null) return ctx.getTeamName();

        String envTeamName = System.getenv("CLAUDE_CODE_TEAM_NAME");
        if (envTeamName != null && !envTeamName.isBlank()) return envTeamName;

        return teamContextTeamName;
    }

    /** Convenience overload with no passed team context. */
    public String getTeamName() {
        return getTeamName(null);
    }

    /**
     * Returns true if this session is running as a teammate in a swarm.
     * For process teammates, requires BOTH agent ID AND team name.
     * Translated from isTeammate() in teammate.ts
     */
    public boolean isTeammate() {
        // In-process teammates run within the same JVM
        if (TeammateContext.isInProcessTeammate()) return true;

        // Process teammates require both agent ID and team name
        DynamicTeamContext ctx = dynamicTeamContext.get();
        return ctx != null
            && ctx.getAgentId() != null && !ctx.getAgentId().isBlank()
            && ctx.getTeamName() != null && !ctx.getTeamName().isBlank();
    }

    /**
     * Returns the teammate's assigned color, or null if none.
     * Priority: ThreadLocal (in-process) > dynamicTeamContext.
     * Translated from getTeammateColor() in teammate.ts
     */
    public String getTeammateColor() {
        Optional<TeammateContext.TeammateContextData> inProcess = TeammateContext.getTeammateContext();
        if (inProcess.isPresent()) return inProcess.get().getColor();

        DynamicTeamContext ctx = dynamicTeamContext.get();
        return ctx != null ? ctx.getColor() : null;
    }

    /**
     * Returns true if this teammate session requires plan mode before implementing.
     * Priority: ThreadLocal > dynamicTeamContext > env var.
     * Translated from isPlanModeRequired() in teammate.ts
     */
    public boolean isPlanModeRequired() {
        Optional<TeammateContext.TeammateContextData> inProcess = TeammateContext.getTeammateContext();
        if (inProcess.isPresent()) return inProcess.get().isPlanModeRequired();

        DynamicTeamContext ctx = dynamicTeamContext.get();
        if (ctx != null) return ctx.isPlanModeRequired();

        return EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_PLAN_MODE_REQUIRED"));
    }

    /**
     * Check if this session is a team lead.
     *
     * A session is considered a team lead if:
     * 1. A team context exists with a leadAgentId, AND
     * 2. Either our agent ID matches the leadAgentId, OR we have no agent ID set
     *    (backwards compat: original session that created the team before agent IDs).
     *
     * Translated from isTeamLead() in teammate.ts
     */
    public boolean isTeamLead(String leadAgentId) {
        if (leadAgentId == null || leadAgentId.isBlank()) return false;

        String myAgentId = getAgentId();

        if (myAgentId != null && myAgentId.equals(leadAgentId)) return true;

        // Backwards compat: no agent ID set → this is the original leading session
        return myAgentId == null;
    }
}
