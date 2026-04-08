package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import com.anthropic.claudecode.service.QueryEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import lombok.Data;

/**
 * Agent tool for spawning sub-agents.
 * Translated from src/tools/AgentTool/AgentTool.tsx
 *
 * Launches specialized agents (subprocesses) that autonomously handle complex tasks.
 * Each agent type has specific capabilities and tools available to it.
 */
@Slf4j
@Component
public class AgentTool extends AbstractTool<AgentTool.Input, AgentTool.Output> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentTool.class);


    public static final String TOOL_NAME = "Agent";
    public static final String LEGACY_TOOL_NAME = "dispatch_agent";

    private final QueryEngine queryEngine;
    private final List<Tool<?, ?>> tools;

    @Autowired
    public AgentTool(QueryEngine queryEngine, List<Tool<?, ?>> tools) {
        this.queryEngine = queryEngine;
        this.tools = tools;
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public List<String> getAliases() {
        return List.of(LEGACY_TOOL_NAME);
    }

    @Override
    public String getSearchHint() {
        return "launch specialized sub-agents for complex tasks";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "description", Map.of(
                    "type", "string",
                    "description", "A short (3-5 word) description of the task"
                ),
                "prompt", Map.of(
                    "type", "string",
                    "description", "The task for the agent to perform"
                ),
                "subagent_type", Map.of(
                    "type", "string",
                    "description", "The type of specialized agent to use"
                ),
                "run_in_background", Map.of(
                    "type", "boolean",
                    "description", "Set to true to run this agent in the background"
                )
            ),
            "required", List.of("description", "prompt")
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
                log.info("Launching agent: {} (type={})", args.getDescription(), args.getSubagentType());

                // Create a sub-context for the agent
                ToolUseContext agentContext = createAgentContext(context, args);

                // Build initial messages
                List<Message> agentMessages = new ArrayList<>();
                Message.UserMessage userMsg = Message.UserMessage.builder()
                    .type("user")
                    .uuid(UUID.randomUUID().toString())
                    .content(List.of(new ContentBlock.TextBlock(args.getPrompt())))
                    .build();
                agentMessages.add(userMsg);

                // Run the agent query
                List<Message> result = queryEngine.query(
                    agentMessages,
                    getAgentSystemPrompt(args.getSubagentType()),
                    agentContext,
                    event -> {
                        // Forward progress to parent
                        if (onProgress != null) {
                            onProgress.accept(new Tool.ToolProgress(
                                args.getDescription(),
                                event
                            ));
                        }
                    }
                ).get();

                // Extract the final assistant message
                String agentResult = extractFinalResult(result);

                return this.result(Output.builder()
                    .result(agentResult)
                    .agentType(args.getSubagentType())
                    .description(args.getDescription())
                    .build());

            } catch (Exception e) {
                log.error("Agent execution failed: {}", e.getMessage(), e);
                throw new RuntimeException("Agent failed: " + e.getMessage(), e);
            }
        });
    }

    private ToolUseContext createAgentContext(ToolUseContext parentContext, Input args) {
        // Create a sub-context based on parent context
        ToolUseContext.Options options = ToolUseContext.Options.builder()
            .mainLoopModel(parentContext.getOptions() != null
                ? parentContext.getOptions().getMainLoopModel()
                : "claude-opus-4-6")
            .verbose(parentContext.getOptions() != null
                && parentContext.getOptions().isVerbose())
            .tools(tools)
            .isNonInteractiveSession(true) // Sub-agents are non-interactive
            .commands(List.of())
            .mcpClients(parentContext.getOptions() != null
                ? parentContext.getOptions().getMcpClients()
                : List.of())
            .mcpResources(parentContext.getOptions() != null
                ? parentContext.getOptions().getMcpResources()
                : Map.of())
            .agentDefinitions(new ToolUseContext.AgentDefinitionsResult(List.of(), null))
            .build();

        return ToolUseContext.builder()
            .options(options)
            .messages(new ArrayList<>())
            .readFileState(new HashMap<>())
            .inProgressToolUseIds(new HashSet<>())
            .agentId(UUID.randomUUID().toString())
            .agentType(args.getSubagentType())
            .build();
    }

    private String getAgentSystemPrompt(String agentType) {
        if (agentType == null) {
            return "You are a general-purpose agent. Complete the task given to you.";
        }
        return switch (agentType) {
            case "general-purpose" -> "You are a general-purpose agent for researching complex questions, searching for code, and executing multi-step tasks.";
            case "Explore" -> "You are an exploration agent specialized for exploring codebases. Find files by patterns, search code for keywords, or answer questions about the codebase.";
            case "Plan" -> "You are a software architect agent for designing implementation plans. Return step-by-step plans, identify critical files, and consider architectural trade-offs.";
            default -> "You are a specialized agent. Complete the task given to you.";
        };
    }

    private String extractFinalResult(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            if (msg instanceof Message.AssistantMessage assistantMsg) {
                if (assistantMsg.getContent() != null) {
                    for (ContentBlock block : assistantMsg.getContent()) {
                        if (block instanceof ContentBlock.TextBlock text) {
                            sb.append(text.getText());
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture(
            "Launching agent: " + (input != null ? input.getDescription() : "agent")
        );
    }

    @Override
    public boolean isReadOnly(Input input) { return false; }

    @Override
    public String userFacingName(Input input) {
        return input != null && input.getDescription() != null
            ? input.getDescription()
            : TOOL_NAME;
    }

    @Override
    public String getActivityDescription(Input input) {
        return "Running " + (input != null ? input.getDescription() : "agent");
    }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        return Map.of(
            "type", "tool_result",
            "tool_use_id", toolUseId,
            "content", content.getResult() != null ? content.getResult() : ""
        );
    }

    // =========================================================================
    // Input/Output types
    // =========================================================================

    public static class Input {
        private String description;
        private String prompt;
        private String subagentType;
        private Boolean runInBackground;
        private String isolation;
        private String model;
        public Input() {}
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
        public String getPrompt() { return prompt; }
        public void setPrompt(String v) { prompt = v; }
        public String getSubagentType() { return subagentType; }
        public void setSubagentType(String v) { subagentType = v; }
        public Boolean getRunInBackground() { return runInBackground; }
        public void setRunInBackground(Boolean v) { runInBackground = v; }
        public String getIsolation() { return isolation; }
        public void setIsolation(String v) { isolation = v; }
        public String getModel() { return model; }
        public void setModel(String v) { model = v; }
        public static InputBuilder builder() { return new InputBuilder(); }
        public static class InputBuilder {
            private final Input i = new Input();
            public InputBuilder description(String v) { i.description = v; return this; }
            public InputBuilder prompt(String v) { i.prompt = v; return this; }
            public InputBuilder subagentType(String v) { i.subagentType = v; return this; }
            public InputBuilder runInBackground(Boolean v) { i.runInBackground = v; return this; }
            public InputBuilder isolation(String v) { i.isolation = v; return this; }
            public InputBuilder model(String v) { i.model = v; return this; }
            public Input build() { return i; }
        }
    }

    public static class Output {
        private String result;
        private String agentType;
        private String description;
        public Output() {}
        public String getResult() { return result; }
        public void setResult(String v) { result = v; }
        public String getAgentType() { return agentType; }
        public void setAgentType(String v) { agentType = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
        public static OutputBuilder builder() { return new OutputBuilder(); }
        public static class OutputBuilder {
            private String result; private String agentType; private String description;
            public OutputBuilder result(String v) { this.result = v; return this; }
            public OutputBuilder agentType(String v) { this.agentType = v; return this; }
            public OutputBuilder description(String v) { this.description = v; return this; }
            public Output build() { Output o = new Output(); o.result = result; o.agentType = agentType; o.description = description; return o; }
        }
    }
}
