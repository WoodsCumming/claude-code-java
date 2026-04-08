package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.ToolConstants;
import com.anthropic.claudecode.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Agent tool utilities service.
 * Translated from src/tools/AgentTool/agentToolUtils.ts
 *
 * Provides utilities for filtering and managing tools in agent contexts.
 */
@Slf4j
@Service
public class AgentToolUtilsService {



    // Tools that are never allowed in agents
    public static final Set<String> ALL_AGENT_DISALLOWED_TOOLS = Set.of(
        "AskUserQuestion",
        "EnterPlanMode",
        "ExitPlanMode",
        "EnterWorktree",
        "ExitWorktree"
    );

    // Tools allowed for async agents
    public static final Set<String> ASYNC_AGENT_ALLOWED_TOOLS = Set.of(
        "Read", "Write", "Edit", "Bash", "Glob", "Grep",
        "WebSearch", "WebFetch", "Agent", "NotebookEdit",
        "TodoWrite", "TaskCreate", "TaskUpdate", "TaskList",
        "TaskGet", "TaskOutput", "TaskStop", "SendMessage",
        "SleepTool", "SkillTool", "ToolSearch"
    );

    // Tools not allowed for custom agents
    public static final Set<String> CUSTOM_AGENT_DISALLOWED_TOOLS = Set.of(
        "TeamCreate", "TeamDelete"
    );

    // Tools allowed for in-process teammates
    public static final Set<String> IN_PROCESS_TEAMMATE_ALLOWED_TOOLS = new HashSet<>(ASYNC_AGENT_ALLOWED_TOOLS);

    static {
        IN_PROCESS_TEAMMATE_ALLOWED_TOOLS.add("TeamCreate");
        IN_PROCESS_TEAMMATE_ALLOWED_TOOLS.add("TeamDelete");
    }

    private final AgentSummaryService agentSummaryService;

    @Autowired
    public AgentToolUtilsService(AgentSummaryService agentSummaryService) {
        this.agentSummaryService = agentSummaryService;
    }

    /**
     * Filter tools for an agent context.
     * Translated from filterToolsForAgent() in agentToolUtils.ts
     */
    public <I, O> List<Tool<I, O>> filterToolsForAgent(
            List<Tool<I, O>> tools,
            boolean isCustomAgent,
            boolean isAsyncAgent) {

        Set<String> allowedSet = isAsyncAgent ? ASYNC_AGENT_ALLOWED_TOOLS : null;
        Set<String> disallowedSet = new HashSet<>(ALL_AGENT_DISALLOWED_TOOLS);
        if (isCustomAgent) disallowedSet.addAll(CUSTOM_AGENT_DISALLOWED_TOOLS);

        return tools.stream()
            .filter(t -> !disallowedSet.contains(t.getName()))
            .filter(t -> allowedSet == null || allowedSet.contains(t.getName()))
            .collect(Collectors.toList());
    }

    /**
     * Resolve agent tools from an available pool.
     */
    @SuppressWarnings("unchecked")
    public List<com.anthropic.claudecode.tool.Tool<?, ?>> resolveAgentTools(
            com.anthropic.claudecode.model.AgentDefinition agentDefinition,
            List<com.anthropic.claudecode.tool.Tool<?, ?>> availableTools,
            boolean isAsync) {
        return availableTools;
    }

    /**
     * Assemble the worker tool pool for an agent.
     */
    public List<com.anthropic.claudecode.tool.Tool<?, ?>> assembleWorkerToolPool(
            com.anthropic.claudecode.model.AgentDefinition agentDefinition,
            com.anthropic.claudecode.model.ToolUseContext toolUseContext) {
        return java.util.Collections.emptyList();
    }

    /**
     * Get the query source string for an agent.
     */
    public String getQuerySourceForAgent(com.anthropic.claudecode.model.AgentDefinition agentDefinition) {
        return agentDefinition != null ? "agent:" + agentDefinition.getAgentType() : "agent";
    }
}
