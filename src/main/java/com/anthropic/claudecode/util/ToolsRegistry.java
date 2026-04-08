package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Tools registry — assembles, filters, and deduplicates the tool pool.
 *
 * Translated from:
 *   - src/tools.ts                 — core registry, assembleToolPool, getTools
 *   - src/hooks/useMergedTools.ts  — REPL-level hook that wires assembleToolPool
 *                                    and mergeAndFilterTools together
 *   - src/utils/toolPool.ts        — mergeAndFilterTools()
 *
 * <h3>TypeScript → Java mapping for useMergedTools</h3>
 * <pre>
 * useMergedTools(initialTools, mcpTools, permissionContext)
 *     → mergeAndFilterTools(initialTools, mcpTools, permissionContext, mode)
 *
 * assembleToolPool(permissionContext, mcpTools) → assembleToolPool(ctx, mcpTools)
 * mergeAndFilterTools(initial, assembled, mode) → mergeAndFilterTools(...)
 * replBridgeEnabled / replBridgeOutboundOnly    → not yet used (false defaults retained)
 * </pre>
 *
 * In TypeScript, {@code useMergedTools} is a {@code useMemo} hook. The Java
 * equivalent is the stateless {@link #mergeAndFilterTools} method: callers
 * re-invoke it whenever the inputs change (no memoization is required in a
 * request-scoped or singleton service model).
 *
 * Key responsibilities (matching TypeScript):
 *  - TOOL_PRESETS / parseToolPreset()   — named tool presets
 *  - getAllBaseTools()                   — exhaustive list of all possible tools
 *  - getToolsForDefaultPreset()         — enabled tools for the "default" preset
 *  - filterToolsByDenyRules()           — blanket-deny filtering
 *  - getTools(permissionContext)        — runtime-filtered tool list
 *  - assembleToolPool()                 — built-ins + MCP, deduplicated
 *  - mergeAndFilterTools()              — useMergedTools hook logic
 *  - getMergedTools()                   — flat union (no dedup ordering guarantees)
 *
 * Re-exported constants (from constants/tools.ts equivalents):
 *  - ALL_AGENT_DISALLOWED_TOOLS
 *  - CUSTOM_AGENT_DISALLOWED_TOOLS
 *  - ASYNC_AGENT_ALLOWED_TOOLS
 *  - COORDINATOR_MODE_ALLOWED_TOOLS
 *  - REPL_ONLY_TOOLS
 */
@Slf4j
@Component
public class ToolsRegistry {



    // -------------------------------------------------------------------------
    // Tool preset constants
    // -------------------------------------------------------------------------

    /** All supported tool presets. Currently only "default". */
    public static final List<String> TOOL_PRESETS = Collections.unmodifiableList(
            Collections.singletonList("default")
    );

    // -------------------------------------------------------------------------
    // Tool name string constants (mirrors constants/tools.ts)
    // -------------------------------------------------------------------------

    /** Tools that are never available to sub-agents. */
    public static final Set<String> ALL_AGENT_DISALLOWED_TOOLS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "AgentTool"
            ))
    );

    /** Tools that are disallowed for custom (user-spawned) agents. */
    public static final Set<String> CUSTOM_AGENT_DISALLOWED_TOOLS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "AgentTool", "TaskStopTool"
            ))
    );

    /** Tools permitted in async agent mode. */
    public static final Set<String> ASYNC_AGENT_ALLOWED_TOOLS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "BashTool", "FileReadTool", "FileEditTool", "FileWriteTool",
                    "GlobTool", "GrepTool", "TaskOutputTool", "TaskStopTool"
            ))
    );

    /** Tools available in coordinator mode. */
    public static final Set<String> COORDINATOR_MODE_ALLOWED_TOOLS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "AgentTool", "TaskStopTool", "SendMessageTool",
                    "TodoWriteTool", "WebSearchTool"
            ))
    );

    /**
     * Tools hidden from direct use when REPL mode is active.
     * They are still accessible inside the REPL VM context.
     * Corresponds to TypeScript: REPL_ONLY_TOOLS
     */
    public static final Set<String> REPL_ONLY_TOOLS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "BashTool", "FileReadTool", "FileEditTool", "FileWriteTool",
                    "GlobTool", "GrepTool", "NotebookEditTool"
            ))
    );

    // -------------------------------------------------------------------------
    // ToolDefinition — minimal interface mirroring the TypeScript Tool type
    // -------------------------------------------------------------------------

    /**
     * Minimal representation of a tool, analogous to the TypeScript Tool interface.
     * Real tool objects implement this interface.
     */
    public interface ToolDefinition {
        /** Unique tool name used for matching, deduplication and permission rules. */
        String getName();

        /**
         * Optional MCP server info — present only for MCP-sourced tools.
         * Corresponds to TypeScript: tool.mcpInfo?: { serverName, toolName }
         */
        default Optional<McpInfo> getMcpInfo() {
            return Optional.empty();
        }

        /**
         * Whether this tool is currently enabled (respects feature flags, env vars, etc.).
         * Corresponds to TypeScript: tool.isEnabled()
         */
        default boolean isEnabled() {
            return true;
        }
    }

    /** MCP server metadata attached to MCP-sourced tool definitions. */
    public record McpInfo(String serverName, String toolName) {}

    // -------------------------------------------------------------------------
    // ToolPermissionContext — minimal representation
    // -------------------------------------------------------------------------

    /**
     * Minimal permission context used for deny-rule filtering.
     * The full context is defined in PermissionsTypes.ToolPermissionContext.
     * This interface exists here to avoid a hard dependency on the model package.
     */
    public interface PermissionContext {
        /**
         * Return the deny rules keyed by source.
         * Keys are source names; values are rule strings (tool names / prefixes).
         */
        Map<String, List<String>> getAlwaysDenyRules();
    }

    // -------------------------------------------------------------------------
    // Preset resolution
    // -------------------------------------------------------------------------

    /**
     * Parse and validate a preset string.
     * Returns null when the preset is unknown.
     * Corresponds to TypeScript: function parseToolPreset(preset): ToolPreset | null
     */
    public static String parseToolPreset(String preset) {
        if (preset == null) return null;
        String lower = preset.toLowerCase(Locale.ROOT);
        return TOOL_PRESETS.contains(lower) ? lower : null;
    }

    // -------------------------------------------------------------------------
    // Registry state
    // -------------------------------------------------------------------------

    /**
     * All registered base tool definitions. Populated at startup via
     * registerBaseTool() by each tool's Spring @Component initialiser.
     * Corresponds to TypeScript: getAllBaseTools()
     */
    private final List<ToolDefinition> baseTools = new ArrayList<>();

    /**
     * Register a tool as a base (built-in) tool.
     * Call this from each tool's @PostConstruct or via Spring injection.
     */
    public synchronized void registerBaseTool(ToolDefinition tool) {
        Objects.requireNonNull(tool, "tool must not be null");
        baseTools.add(tool);
        log.debug("Registered base tool: {}", tool.getName());
    }

    /**
     * Return the exhaustive, unfiltered list of all registered base tools.
     * Corresponds to TypeScript: function getAllBaseTools(): Tools
     */
    public synchronized List<ToolDefinition> getAllBaseTools() {
        return Collections.unmodifiableList(new ArrayList<>(baseTools));
    }

    /**
     * Return the names of all enabled tools in the "default" preset.
     * Corresponds to TypeScript: function getToolsForDefaultPreset(): string[]
     */
    public List<String> getToolsForDefaultPreset() {
        return getAllBaseTools().stream()
                .filter(ToolDefinition::isEnabled)
                .map(ToolDefinition::getName)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Deny-rule filtering
    // -------------------------------------------------------------------------

    /**
     * Filter out tools that are blanket-denied by the permission context.
     * A tool is removed when there is a deny rule matching its name with no
     * ruleContent (i.e. a blanket deny).
     *
     * Corresponds to TypeScript:
     *   function filterToolsByDenyRules<T>(tools, permissionContext): T[]
     */
    public <T extends ToolDefinition> List<T> filterToolsByDenyRules(
            List<T> tools, PermissionContext permissionContext) {

        if (tools == null || tools.isEmpty()) return Collections.emptyList();
        if (permissionContext == null) return tools;

        Map<String, List<String>> denyRules = permissionContext.getAlwaysDenyRules();
        if (denyRules == null || denyRules.isEmpty()) return tools;

        // Flatten all deny-rule values into a single set for O(1) lookup
        Set<String> blanketDenied = new HashSet<>();
        for (List<String> rules : denyRules.values()) {
            if (rules != null) blanketDenied.addAll(rules);
        }

        return tools.stream()
                .filter(tool -> !isBlanketDenied(tool, blanketDenied))
                .toList();
    }

    private static boolean isBlanketDenied(ToolDefinition tool, Set<String> blanketDenied) {
        if (blanketDenied.contains(tool.getName())) return true;
        // Also check MCP server prefix rules (e.g. "mcp__server" denies all tools
        // from that server — matching TypeScript getDenyRuleForTool semantics).
        return tool.getMcpInfo()
                .map(mcp -> blanketDenied.contains("mcp__" + mcp.serverName()))
                .orElse(false);
    }

    // -------------------------------------------------------------------------
    // getTools — runtime-filtered list
    // -------------------------------------------------------------------------

    /**
     * Get the filtered list of tools for a given permission context.
     * Applies blanket deny-rules and the isEnabled() check.
     *
     * In the TypeScript source this also handles SIMPLE mode and REPL mode
     * filtering; those branches are expressed here as guard conditions.
     *
     * Corresponds to TypeScript: const getTools = (permissionContext): Tools
     *
     * @param permissionContext  permission context for deny-rule filtering
     * @param simpleMode         true when CLAUDE_CODE_SIMPLE is set
     * @param replModeEnabled    true when REPL mode is active
     * @param specialToolNames   tool names that should be stripped from the list
     *                           (e.g. ListMcpResourcesTool, ReadMcpResourceTool,
     *                           SYNTHETIC_OUTPUT_TOOL_NAME)
     */
    public List<ToolDefinition> getTools(
            PermissionContext permissionContext,
            boolean simpleMode,
            boolean replModeEnabled,
            Set<String> specialToolNames) {

        List<ToolDefinition> tools;

        if (simpleMode) {
            // Simple mode: only the bare minimum tools
            tools = getAllBaseTools().stream()
                    .filter(t -> Set.of("BashTool", "FileReadTool", "FileEditTool")
                            .contains(t.getName()))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        } else {
            Set<String> special = specialToolNames != null ? specialToolNames : Collections.emptySet();
            tools = getAllBaseTools().stream()
                    .filter(t -> !special.contains(t.getName()))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }

        List<ToolDefinition> allowed = filterToolsByDenyRules(tools, permissionContext);

        if (replModeEnabled) {
            boolean replPresent = allowed.stream()
                    .anyMatch(t -> "REPLTool".equals(t.getName()));
            if (replPresent) {
                allowed = allowed.stream()
                        .filter(t -> !REPL_ONLY_TOOLS.contains(t.getName()))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            }
        }

        return allowed.stream()
                .filter(ToolDefinition::isEnabled)
                .toList();
    }

    // -------------------------------------------------------------------------
    // assembleToolPool — built-ins + MCP, deduplicated
    // -------------------------------------------------------------------------

    /**
     * Assemble the full tool pool: built-in tools + MCP tools, deduplicated by
     * name with built-ins taking precedence. Both partitions are sorted by name
     * for prompt-cache stability before deduplication.
     *
     * Corresponds to TypeScript:
     *   function assembleToolPool(permissionContext, mcpTools): Tools
     */
    public List<ToolDefinition> assembleToolPool(
            PermissionContext permissionContext,
            List<ToolDefinition> mcpTools) {

        List<ToolDefinition> builtIns = getTools(
                permissionContext, false, false, null);

        List<ToolDefinition> allowedMcp = filterToolsByDenyRules(
                mcpTools != null ? mcpTools : Collections.emptyList(),
                permissionContext);

        Comparator<ToolDefinition> byName =
                Comparator.comparing(ToolDefinition::getName);

        List<ToolDefinition> sorted = new ArrayList<>(builtIns);
        sorted.sort(byName);

        List<ToolDefinition> sortedMcp = new ArrayList<>(allowedMcp);
        sortedMcp.sort(byName);

        sorted.addAll(sortedMcp);

        // Deduplicate by name (first occurrence — built-in — wins)
        Map<String, ToolDefinition> seen = new LinkedHashMap<>();
        for (ToolDefinition t : sorted) {
            seen.putIfAbsent(t.getName(), t);
        }
        return Collections.unmodifiableList(new ArrayList<>(seen.values()));
    }

    // -------------------------------------------------------------------------
    // mergeAndFilterTools — useMergedTools hook equivalent
    // -------------------------------------------------------------------------

    /**
     * Merge initial tools with an assembled pool and apply mode-based filtering.
     *
     * Mirrors {@code mergeAndFilterTools(initialTools, assembled, mode)} from
     * {@code src/utils/toolPool.ts}, as called by {@code useMergedTools}.
     *
     * Logic:
     * <ol>
     *   <li>Start with {@code initialTools} (built-in + startup MCP from props).
     *       These take precedence in deduplication.</li>
     *   <li>Append tools from {@code assembledPool} that are not already present
     *       (deduplicated by name).</li>
     *   <li>Filter the merged list according to the permission mode:
     *       <ul>
     *         <li>{@code "agent"} — remove ALL_AGENT_DISALLOWED_TOOLS</li>
     *         <li>{@code "custom-agent"} — remove CUSTOM_AGENT_DISALLOWED_TOOLS</li>
     *         <li>any other mode — no additional filtering</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * Corresponds to TypeScript:
     * <pre>
     * useMergedTools(initialTools, mcpTools, toolPermissionContext)
     *   → assembleToolPool(ctx, mcpTools)
     *   → mergeAndFilterTools(initialTools, assembled, ctx.mode)
     * </pre>
     *
     * @param initialTools   extra tools that take precedence (built-in + startup MCP)
     * @param assembledPool  result of {@link #assembleToolPool(PermissionContext, List)}
     * @param mode           permission mode string (e.g. "default", "agent",
     *                       "custom-agent", "coordinator")
     * @return merged, deduplicated, mode-filtered tool list
     */
    public List<ToolDefinition> mergeAndFilterTools(
            List<ToolDefinition> initialTools,
            List<ToolDefinition> assembledPool,
            String mode) {

        // 1. Seed with initialTools (highest precedence)
        Map<String, ToolDefinition> byName = new LinkedHashMap<>();
        if (initialTools != null) {
            for (ToolDefinition t : initialTools) {
                byName.put(t.getName(), t);
            }
        }

        // 2. Append assembled pool entries not already present
        if (assembledPool != null) {
            for (ToolDefinition t : assembledPool) {
                byName.putIfAbsent(t.getName(), t);
            }
        }

        // 3. Apply mode-based filtering
        Set<String> denySet = resolveModeDisallowedTools(mode);
        List<ToolDefinition> merged = byName.values().stream()
                .filter(t -> !denySet.contains(t.getName()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        return Collections.unmodifiableList(merged);
    }

    /**
     * Convenience overload that handles the full {@code useMergedTools} pipeline:
     * assemble the pool then merge and filter in one call.
     *
     * @param initialTools          extra tools (built-in + startup MCP from props)
     * @param mcpTools              dynamically-discovered MCP tools
     * @param permissionContext     context for deny-rule filtering and mode resolution
     * @return merged, deduplicated, mode-filtered tool list
     */
    public List<ToolDefinition> useMergedTools(
            List<ToolDefinition> initialTools,
            List<ToolDefinition> mcpTools,
            PermissionContext permissionContext) {

        List<ToolDefinition> assembled = assembleToolPool(permissionContext, mcpTools);
        String mode = permissionContext instanceof ModeAwarePermissionContext
                ? ((ModeAwarePermissionContext) permissionContext).getMode()
                : "default";
        return mergeAndFilterTools(initialTools, assembled, mode);
    }

    /**
     * Resolve the set of tool names that are disallowed for the given permission mode.
     * Mirrors the mode-based filtering in {@code mergeAndFilterTools} in toolPool.ts.
     */
    private static Set<String> resolveModeDisallowedTools(String mode) {
        if ("agent".equals(mode))        return ALL_AGENT_DISALLOWED_TOOLS;
        if ("custom-agent".equals(mode)) return CUSTOM_AGENT_DISALLOWED_TOOLS;
        return Collections.emptySet();
    }

    /**
     * Optional extension of {@link PermissionContext} for contexts that also
     * carry a mode string (e.g. "default", "agent", "coordinator").
     * Mirrors the {@code mode} field on TypeScript {@code ToolPermissionContext}.
     */
    public interface ModeAwarePermissionContext extends PermissionContext {
        /** The current permission mode string. */
        String getMode();
    }

    // -------------------------------------------------------------------------
    // getMergedTools — flat union
    // -------------------------------------------------------------------------

    /**
     * Get the combined tool list without deduplication ordering guarantees.
     * Use when you need the full count (e.g. token counting, tool search
     * threshold) rather than the stable ordered pool.
     *
     * Corresponds to TypeScript:
     *   function getMergedTools(permissionContext, mcpTools): Tools
     */
    public List<ToolDefinition> getMergedTools(
            PermissionContext permissionContext,
            List<ToolDefinition> mcpTools) {

        List<ToolDefinition> builtIns = getTools(
                permissionContext, false, false, null);

        List<ToolDefinition> result = new ArrayList<>(builtIns);
        if (mcpTools != null) result.addAll(mcpTools);
        return Collections.unmodifiableList(result);
    }
}
