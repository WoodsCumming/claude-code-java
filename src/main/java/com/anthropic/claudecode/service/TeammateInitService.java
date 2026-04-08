package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.TeammateContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Teammate initialization service.
 * Translated from src/utils/swarm/teammateInit.ts
 *
 * Handles initialization for Claude Code instances running as teammates.
 */
@Slf4j
@Service
public class TeammateInitService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TeammateInitService.class);


    private final HookService hookService;
    private final TeammateMailboxService teammateMailboxService;
    private final SwarmTeamHelpersService swarmTeamHelpersService;

    @Autowired
    public TeammateInitService(HookService hookService,
                                TeammateMailboxService teammateMailboxService,
                                SwarmTeamHelpersService swarmTeamHelpersService) {
        this.hookService = hookService;
        this.teammateMailboxService = teammateMailboxService;
        this.swarmTeamHelpersService = swarmTeamHelpersService;
    }

    /**
     * Initialize hooks for a teammate running in a swarm.
     * Translated from initializeTeammateHooks() in teammateInit.ts
     */
    public void initializeTeammateHooks(String sessionId) {
        if (!TeammateContext.isTeammate()) return;

        String teamName = TeammateContext.getTeamName();
        String agentName = System.getProperty("claude.agent-name", "worker");

        log.info("[teammate] Initializing hooks for {} in team {}", agentName, teamName);

        // Register Stop hook to notify leader when idle
        hookService.registerSessionHook(
            sessionId,
            "Stop",
            null,
            java.util.Map.of(
                "type", "function",
                "statusMessage", "Notifying team leader..."
            )
        );

        log.debug("[teammate] Hooks initialized for {}", agentName);
    }

    /**
     * Notify the team leader that this teammate is idle.
     * Translated from notifyLeaderIdle() in teammateInit.ts
     */
    public void notifyLeaderIdle() {
        String teamName = TeammateContext.getTeamName();
        if (teamName == null) return;

        String agentName = System.getProperty("claude.agent-name", "worker");
        String agentId = System.getProperty("claude.agent-id", "unknown");

        java.util.Map<String, Object> message = new java.util.LinkedHashMap<>();
        message.put("type", "teammate_idle");
        message.put("agent_name", agentName);
        message.put("agent_id", agentId);

        teammateMailboxService.sendToMailbox(
            teamName,
            "team-lead",
            new TeammateMailboxService.MailboxMessage(
                java.util.UUID.randomUUID().toString(),
                agentName,
                "Teammate idle",
                null,
                "teammate_idle",
                message
            )
        );

        log.debug("[teammate] Notified leader of idle state");
    }
}
