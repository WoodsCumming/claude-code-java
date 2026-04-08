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
 * Team deletion tool.
 * Translated from src/tools/TeamDeleteTool/
 */
@Slf4j
@Component
public class TeamDeleteTool extends AbstractTool<TeamDeleteTool.Input, TeamDeleteTool.Output> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TeamDeleteTool.class);


    public static final String TOOL_NAME = "TeamDelete";

    private final TeamService teamService;

    @Autowired
    public TeamDeleteTool(TeamService teamService) {
        this.teamService = teamService;
    }

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "team_name", Map.of("type", "string", "description", "Name of the team to delete")
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

        boolean success = teamService.cleanupTeam(args.getTeamName());
        return futureResult(Output.builder()
            .success(success)
            .message(success ? "Team deleted: " + args.getTeamName() : "Team not found: " + args.getTeamName())
            .build());
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Deleting team: " + input.getTeamName());
    }

    @Override
    public boolean isReadOnly(Input input) { return false; }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        return Map.of("type", "tool_result", "tool_use_id", toolUseId, "content", content.getMessage());
    }

    public static class Input {
        private String teamName;
        public Input() {}
        public Input(String teamName) { this.teamName = teamName; }
        public String getTeamName() { return teamName; }
        public void setTeamName(String v) { teamName = v; }
        public static InputBuilder builder() { return new InputBuilder(); }
        public static class InputBuilder {
            private String teamName;
            public InputBuilder teamName(String v) { this.teamName = v; return this; }
            public Input build() { return new Input(teamName); }
        }
    }

    public static class Output {
        private boolean success;
        private String message;
        public Output() {}
        public Output(boolean success, String message) { this.success = success; this.message = message; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean v) { success = v; }
        public String getMessage() { return message; }
        public void setMessage(String v) { message = v; }
        public static OutputBuilder builder() { return new OutputBuilder(); }
        public static class OutputBuilder {
            private boolean success;
            private String message;
            public OutputBuilder success(boolean v) { this.success = v; return this; }
            public OutputBuilder message(String v) { this.message = v; return this; }
            public Output build() { return new Output(success, message); }
        }
    }
}
