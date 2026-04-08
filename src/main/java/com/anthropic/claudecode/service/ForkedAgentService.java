package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.*;
import com.anthropic.claudecode.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import lombok.Data;

/**
 * Forked agent service for running sub-agents with shared cache.
 * Translated from src/utils/forkedAgent.ts
 *
 * Runs forked agent query loops that share the parent's prompt cache
 * for efficiency.
 */
@Slf4j
@Service
public class ForkedAgentService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ForkedAgentService.class);


    private final QueryEngine queryEngine;

    @Autowired
    public ForkedAgentService(QueryEngine queryEngine) {
        this.queryEngine = queryEngine;
    }

    /**
     * Run a forked agent.
     * Translated from runForkedAgent() in forkedAgent.ts
     */
    public CompletableFuture<ForkedAgentResult> runForkedAgent(
            ForkedAgentParams params,
            ToolUseContext parentContext) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                String agentId = "a" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

                // Create forked context
                ToolUseContext forkedContext = createForkedContext(parentContext, agentId);

                // Build initial messages
                List<Message> messages = new ArrayList<>();
                if (params.getInitialMessage() != null) {
                    messages.add(Message.UserMessage.builder()
                        .type("user")
                        .uuid(UUID.randomUUID().toString())
                        .content(List.of(new ContentBlock.TextBlock(params.getInitialMessage())))
                        .build());
                }

                // Run the query
                List<Message> result = queryEngine.query(
                    messages,
                    params.getSystemPrompt(),
                    forkedContext,
                    event -> log.debug("Fork agent event: {}", event.getType())
                ).get();

                // Extract final result
                String output = extractFinalOutput(result);

                return new ForkedAgentResult(agentId, output, result);

            } catch (Exception e) {
                throw new RuntimeException("Forked agent failed: " + e.getMessage(), e);
            }
        });
    }

    private ToolUseContext createForkedContext(ToolUseContext parent, String agentId) {
        ToolUseContext.Options options = ToolUseContext.Options.builder()
            .mainLoopModel(parent.getOptions() != null
                ? parent.getOptions().getMainLoopModel()
                : "claude-opus-4-6")
            .verbose(parent.getOptions() != null && parent.getOptions().isVerbose())
            .tools(parent.getOptions() != null ? parent.getOptions().getTools() : List.of())
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

    // =========================================================================
    // Inner types
    // =========================================================================

    @Data
    @lombok.Builder
    
    public static class ForkedAgentParams {
        private String systemPrompt;
        private String initialMessage;
        private List<Tool<?, ?>> tools;
        private String querySource;
        private Integer maxOutputTokens;
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    
    public static class ForkedAgentResult {
        private String agentId;
        private String output;
        private List<Message> messages;
    }
}
