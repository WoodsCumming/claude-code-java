package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.Optional;

/**
 * Swarm reconnection service.
 * Translated from src/utils/swarm/reconnection.ts
 *
 * Handles initialization of swarm context for teammates.
 */
@Slf4j
@Service
public class SwarmReconnectionService {



    private final TeammateService teammateService;
    private final TeamService teamService;

    @Autowired
    public SwarmReconnectionService(TeammateService teammateService, TeamService teamService) {
        this.teammateService = teammateService;
        this.teamService = teamService;
    }

    /**
     * Compute the initial team context.
     * Translated from computeInitialTeamContext() in reconnection.ts
     */
    public Optional<TeamContext> computeInitialTeamContext() {
        if (!teammateService.isTeammate()) {
            return Optional.empty();
        }

        String teamName = teammateService.getTeamName();
        String agentName = teammateService.getAgentName();

        if (teamName == null || agentName == null) {
            return Optional.empty();
        }

        Optional<TeamService.TeamInfo> teamInfo = teamService.readTeamFile(teamName);

        return Optional.of(new TeamContext(
            teamName,
            agentName,
            teamInfo.map(TeamService.TeamInfo::getLeadAgentId).orElse(null)
        ));
    }

    public record TeamContext(String teamName, String agentName, String leadAgentId) {}
}
