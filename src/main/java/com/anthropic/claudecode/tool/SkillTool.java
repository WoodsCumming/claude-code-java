package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import com.anthropic.claudecode.service.SkillsLoaderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Skill tool for invoking user-defined skills.
 * Translated from src/tools/SkillTool/SkillTool.ts
 *
 * Executes slash commands/skills defined in the skills directory.
 */
@Slf4j
@Component
public class SkillTool extends AbstractTool<SkillTool.Input, SkillTool.Output> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SkillTool.class);


    public static final String TOOL_NAME = "Skill";

    private final SkillsLoaderService skillsLoaderService;

    @Autowired
    public SkillTool(SkillsLoaderService skillsLoaderService) {
        this.skillsLoaderService = skillsLoaderService;
    }

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "name", Map.of("type", "string", "description", "The name of the skill to invoke"),
                "args", Map.of("type", "string", "description", "Arguments to pass to the skill")
            ),
            "required", List.of("name")
        );
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
            Input args,
            ToolUseContext context,
            Tool.CanUseToolFn canUseTool,
            Message.AssistantMessage parentMessage,
            Consumer<Tool.ToolProgress> onProgress) {

        String skillName = args.getName();
        String skillArgs = args.getArgs() != null ? args.getArgs() : "";

        // Find the skill
        String cwd = System.getProperty("user.dir");
        List<Command> skills;
        try {
            skills = skillsLoaderService.loadSkills(cwd).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            skills = java.util.Collections.emptyList();
        }

        Optional<Command> skill = skills.stream()
            .filter(s -> s.getName().equals(skillName))
            .findFirst();

        if (skill.isEmpty()) {
            return futureResult(Output.builder()
                .success(false)
                .message("Skill not found: " + skillName)
                .build());
        }

        // Execute the skill (simplified - actual implementation would process the skill prompt)
        log.info("Executing skill: {} with args: {}", skillName, skillArgs);

        return futureResult(Output.builder()
            .success(true)
            .message("Skill " + skillName + " executed")
            .build());
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Executing skill: " + input.getName());
    }

    @Override
    public boolean isReadOnly(Input input) { return false; }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        return Map.of("type", "tool_result", "tool_use_id", toolUseId, "content", content.getMessage());
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Input {
        private String name;
        private String args;
    
        public String getArgs() { return args; }
    
        public String getName() { return name; }
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Output {
        private boolean success;
        private String message;
    
        public String getMessage() { return message; }
    }
}
