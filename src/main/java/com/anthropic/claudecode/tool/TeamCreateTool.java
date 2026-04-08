package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import com.anthropic.claudecode.service.TeamService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Team creation tool for multi-agent swarms.
 * Translated from src/tools/TeamCreateTool/TeamCreateTool.ts
 *
 * Creates a new agent team for coordinated multi-agent work.
 */
@Slf4j
@Component
public class TeamCreateTool extends AbstractTool<TeamCreateTool.Input, TeamCreateTool.Output> {



    public static final String TOOL_NAME = "TeamCreate";

    private final TeamService teamService;

    @Autowired
    public TeamCreateTool(TeamService teamService) {
        this.teamService = teamService;
    }

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "team_name", Map.of("type", "string", "description", "Name for the new team to create"),
                "description", Map.of("type", "string", "description", "Team description/purpose"),
                "agent_type", Map.of("type", "string", "description", "Type/role of the team lead")
            ),
            "required", List.of("team_name")
        );
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
            Input args,
            ToolUseContext context,
            Tool.CanUseToolFn canUseTool,
            Message.AssistantMessage parentMessage,
            Consumer<Tool.ToolProgress> onProgress) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                TeamService.TeamInfo team = teamService.createTeam(
                    args.getTeamName(),
                    args.getAgentType(),
                    args.getDescription()
                );

                return result(Output.builder()
                    .teamName(team.getTeamName())
                    .teamFilePath(team.getTeamFilePath())
                    .leadAgentId(team.getLeadAgentId())
                    .build());

            } catch (Exception e) {
                throw new RuntimeException("Failed to create team: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Creating team: " + input.getTeamName());
    }

    @Override
    public boolean isReadOnly(Input input) { return false; }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        return Map.of("type", "tool_result", "tool_use_id", toolUseId,
            "content", "Team created: " + content.getTeamName() + " (lead: " + content.getLeadAgentId() + ")");
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Input {
        private String teamName;
        private String description;
        private String agentType;
    
        public String getAgentType() { return agentType; }
    
        public String getDescription() { return description; }
    
        public String getTeamName() { return teamName; }
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Output {
        private String teamName;
        private String teamFilePath;
        private String leadAgentId;
    
        public String getLeadAgentId() { return leadAgentId; }
    
        public String getTeamName() { return teamName; }
    
        public static OutputBuilder builder() { return new OutputBuilder(); }
        public static class OutputBuilder {
            private String teamName;
            private String teamFilePath;
            private String leadAgentId;
            public OutputBuilder teamName(String v) { this.teamName = v; return this; }
            public OutputBuilder teamFilePath(String v) { this.teamFilePath = v; return this; }
            public OutputBuilder leadAgentId(String v) { this.leadAgentId = v; return this; }
            public Output build() {
                Output o = new Output();
                o.teamName = teamName;
                o.teamFilePath = teamFilePath;
                o.leadAgentId = leadAgentId;
                return o;
            }
        }
    }
}
