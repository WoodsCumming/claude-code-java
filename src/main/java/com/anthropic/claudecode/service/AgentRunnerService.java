package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.*;
import com.anthropic.claudecode.tool.Tool;
import com.anthropic.claudecode.util.MessageUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Agent runner service.
 * Translated from src/tools/AgentTool/runAgent.ts
 *
 * Runs agent queries with proper context and tool access.
 */
@Slf4j
@Service
public class AgentRunnerService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentRunnerService.class);


    private final QueryEngine queryEngine;
    private final AgentsLoaderService agentsLoaderService;
    private final AgentMemoryService agentMemoryService;

    @Autowired
    public AgentRunnerService(
            QueryEngine queryEngine,
            AgentsLoaderService agentsLoaderService,
            AgentMemoryService agentMemoryService) {
        this.queryEngine = queryEngine;
        this.agentsLoaderService = agentsLoaderService;
        this.agentMemoryService = agentMemoryService;
    }

    /**
     * Run an agent.
     * Translated from runAgent() in runAgent.ts
     */
    public CompletableFuture<AgentRunResult> runAgent(
            String agentType,
            String prompt,
            ToolUseContext parentContext,
            List<Tool<?, ?>> tools,
            Consumer<QueryEngine.QueryEvent> onEvent) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Find agent definition
                AgentsLoaderService.AgentDefinition agentDef = findAgentDefinition(agentType);
                String systemPrompt = buildAgentSystemPrompt(agentDef);

                // Create agent context
                String agentId = "a" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                ToolUseContext agentContext = createAgentContext(parentContext, agentId, agentType, tools);

                // Build initial messages
                List<Message> messages = new ArrayList<>();
                messages.add(MessageUtils.createUserMessage(prompt));

                // Run query
                List<Message> result = queryEngine.query(
                    messages,
                    systemPrompt,
                    agentContext,
                    onEvent != null ? (java.util.function.Consumer<QueryEngine.QueryEvent>) onEvent::accept : (java.util.function.Consumer<QueryEngine.QueryEvent>) event -> {}
                ).get();

                // Extract final result
                String output = extractFinalOutput(result);

                return new AgentRunResult(agentId, output, result, null);

            } catch (Exception e) {
                log.error("Agent run failed: {}", e.getMessage(), e);
                return new AgentRunResult(null, null, List.of(), e.getMessage());
            }
        });
    }

    private AgentsLoaderService.AgentDefinition findAgentDefinition(String agentType) {
        String cwd = System.getProperty("user.dir");
        AgentsLoaderService.AgentDefinitionsResult result = agentsLoaderService.loadAgentDefinitions(cwd);

        return result.getAgents().stream()
            .filter(a -> a.getAgentType() != null && a.getAgentType().equals(agentType)
                || a.getName().equals(agentType))
            .findFirst()
            .orElse(AgentsLoaderService.AgentDefinition.builder()
                .name(agentType)
                .agentType(agentType)
                .build());
    }

    private String buildAgentSystemPrompt(AgentsLoaderService.AgentDefinition agentDef) {
        if (agentDef.getSystemPrompt() != null) return agentDef.getSystemPrompt();

        String agentType = agentDef.getAgentType();
        if (agentType == null) agentType = agentDef.getName();

        return switch (agentType) {
            case "Explore" -> BuiltInAgents.EXPLORE_AGENT_SYSTEM_PROMPT;
            case "Plan" -> BuiltInAgents.PLAN_AGENT_SYSTEM_PROMPT;
            default -> BuiltInAgents.GENERAL_PURPOSE_AGENT_SYSTEM_PROMPT;
        };
    }

    private ToolUseContext createAgentContext(
            ToolUseContext parent,
            String agentId,
            String agentType,
            List<Tool<?, ?>> tools) {

        ToolUseContext.Options options = ToolUseContext.Options.builder()
            .mainLoopModel(parent.getOptions() != null
                ? parent.getOptions().getMainLoopModel()
                : "claude-opus-4-6")
            .verbose(parent.getOptions() != null && parent.getOptions().isVerbose())
            .tools(tools != null ? tools : (parent.getOptions() != null ? parent.getOptions().getTools() : List.of()))
            .isNonInteractiveSession(true)
            .commands(List.of())
            .mcpClients(parent.getOptions() != null ? parent.getOptions().getMcpClients() : List.of())
            .mcpResources(parent.getOptions() != null ? parent.getOptions().getMcpResources() : Map.of())
            .agentDefinitions(new ToolUseContext.AgentDefinitionsResult(List.of(), null))
            .build();

        return ToolUseContext.builder()
            .options(options)
            .messages(new ArrayList<>())
            .readFileState(new HashMap<>())
            .inProgressToolUseIds(new HashSet<>())
            .agentId(agentId)
            .agentType(agentType)
            .build();
    }

    private String extractFinalOutput(List<Message> messages) {
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

    @FunctionalInterface
    private interface Consumer<T> {
        void accept(T t);
    }

    public record AgentRunResult(
        String agentId,
        String output,
        List<Message> messages,
        String error
    ) {}
}
