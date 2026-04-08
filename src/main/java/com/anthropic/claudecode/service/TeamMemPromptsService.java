package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Team memory prompts service.
 * Translated from src/memdir/teamMemPrompts.ts
 *
 * Builds prompts for team memory operations.
 */
@Slf4j
@Service
public class TeamMemPromptsService {



    private final MemdirContentService memdirContentService;
    private final TeamMemPathsService teamMemPathsService;
    private final MemdirService memdirService;

    @Autowired
    public TeamMemPromptsService(MemdirContentService memdirContentService,
                                  TeamMemPathsService teamMemPathsService,
                                  MemdirService memdirService) {
        this.memdirContentService = memdirContentService;
        this.teamMemPathsService = teamMemPathsService;
        this.memdirService = memdirService;
    }

    /**
     * Build the combined memory prompt for auto + team memory.
     * Translated from buildCombinedMemoryPrompt() in teamMemPrompts.ts
     */
    public String buildCombinedMemoryPrompt(boolean skipIndex) {
        String autoDir = memdirService.getAutoMemPath();
        String teamDir = teamMemPathsService.getTeamsMemDir();

        StringBuilder sb = new StringBuilder();
        sb.append("# Memory System\n\n");
        sb.append("You have access to two memory directories:\n");
        sb.append("- Auto memory: ").append(autoDir).append("\n");
        sb.append("- Team memory: ").append(teamDir).append("\n\n");
        sb.append("Use these to store and retrieve information across sessions.\n");

        return sb.toString();
    }

    /**
     * Build the team-only memory prompt.
     * Translated from buildTeamOnlyMemoryPrompt() in teamMemPrompts.ts
     */
    public String buildTeamOnlyMemoryPrompt() {
        String teamDir = teamMemPathsService.getTeamsMemDir();

        StringBuilder sb = new StringBuilder();
        sb.append("# Team Memory\n\n");
        sb.append("Team memory is located at: ").append(teamDir).append("\n\n");
        sb.append("Use this directory to store shared team knowledge.\n");

        return sb.toString();
    }
}
