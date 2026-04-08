package com.anthropic.claudecode.service;

import com.anthropic.claudecode.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tool pool service for managing and filtering tool sets.
 * Translated from src/utils/toolPool.ts
 *
 * Manages the available tool pool and applies coordinator mode filtering.
 * Merges tool pools, deduplicates, and applies coordinator mode filtering.
 */
@Slf4j
@Service
public class ToolPoolService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ToolPoolService.class);


    /**
     * Tools allowed when coordinator mode is active.
     * Mirrors COORDINATOR_MODE_ALLOWED_TOOLS from constants/tools.ts.
     */
    private static final Set<String> COORDINATOR_MODE_ALLOWED_TOOLS = Set.of(
        "Agent", "Bash", "Read", "Write", "Edit", "Glob", "Grep",
        "TeamCreate", "TeamDelete", "SendMessage", "SyntheticOutput", "TaskStop"
    );

    /**
     * MCP tool name suffixes for PR activity subscription.
     * These are lightweight orchestration actions the coordinator calls directly.
     * Matched by suffix since the MCP server name prefix may vary.
     */
    private static final List<String> PR_ACTIVITY_TOOL_SUFFIXES = List.of(
        "subscribe_pr_activity",
        "unsubscribe_pr_activity"
    );

    private final CoordinatorModeService coordinatorModeService;

    @Autowired
    public ToolPoolService(CoordinatorModeService coordinatorModeService) {
        this.coordinatorModeService = coordinatorModeService;
    }

    /**
     * Check if a tool is a PR activity subscription tool.
     * Translated from isPrActivitySubscriptionTool() in toolPool.ts
     */
    public boolean isPrActivitySubscriptionTool(String name) {
        return PR_ACTIVITY_TOOL_SUFFIXES.stream().anyMatch(name::endsWith);
    }

    /**
     * Filters a tool array to the set allowed in coordinator mode.
     * PR activity subscription tools are always allowed since subscription
     * management is orchestration.
     * Translated from applyCoordinatorToolFilter() in toolPool.ts
     */
    public <I, O> List<Tool<I, O>> applyCoordinatorToolFilter(List<Tool<I, O>> tools) {
        return tools.stream()
            .filter(t -> COORDINATOR_MODE_ALLOWED_TOOLS.contains(t.getName())
                || isPrActivitySubscriptionTool(t.getName()))
            .collect(Collectors.toList());
    }

    /**
     * Pure function that merges tool pools and applies coordinator mode filtering.
     *
     * Merges initialTools on top (they take precedence in deduplication).
     * Partition-sorts for prompt-cache stability: built-ins must stay a
     * contiguous prefix for the server's cache policy (same as assembleToolPool).
     *
     * @param initialTools Extra tools to include (built-in + startup MCP from props).
     * @param assembled    Tools from assembleToolPool (built-in + MCP, deduped).
     * @param mode         The permission context mode (for coordinator check).
     * @return Merged, deduplicated, and coordinator-filtered tool array.
     *
     * Translated from mergeAndFilterTools() in toolPool.ts
     */
    public <I, O> List<Tool<I, O>> mergeAndFilterTools(
            List<Tool<I, O>> initialTools,
            List<Tool<I, O>> assembled,
            String mode) {

        // Merge initialTools on top — they take precedence in deduplication.
        // uniqBy([...initialTools, ...assembled], 'name'): initialTools first.
        Map<String, Tool<I, O>> byName = new LinkedHashMap<>();
        for (Tool<I, O> tool : initialTools) {
            byName.put(tool.getName(), tool);
        }
        for (Tool<I, O> tool : assembled) {
            byName.putIfAbsent(tool.getName(), tool);
        }

        List<Tool<I, O>> unique = new ArrayList<>(byName.values());

        // Partition-sort: built-ins prefix, then MCP — stable for prompt cache.
        List<Tool<I, O>> builtIn = unique.stream()
            .filter(t -> !t.isMcp())
            .sorted(Comparator.comparing(Tool::getName))
            .collect(Collectors.toList());

        List<Tool<I, O>> mcp = unique.stream()
            .filter(Tool::isMcp)
            .sorted(Comparator.comparing(Tool::getName))
            .collect(Collectors.toList());

        List<Tool<I, O>> tools = Stream.concat(builtIn.stream(), mcp.stream())
            .collect(Collectors.toList());

        // Apply coordinator mode filter if active.
        if (coordinatorModeService.isCoordinatorMode()) {
            return applyCoordinatorToolFilter(tools);
        }

        return tools;
    }
}
