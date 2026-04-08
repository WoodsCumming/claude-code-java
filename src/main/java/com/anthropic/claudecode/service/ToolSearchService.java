package com.anthropic.claudecode.service;

import com.anthropic.claudecode.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Tool search service for dynamically discovering deferred tools.
 * Translated from src/utils/toolSearch.ts
 *
 * When enabled, deferred tools (MCP and shouldDefer tools) are sent with
 * defer_loading: true and discovered via ToolSearchTool rather than being
 * loaded upfront. This removes limits on total MCP tool quantity.
 */
@Slf4j
@Service
public class ToolSearchService {



    // =========================================================================
    // Tool search mode
    // =========================================================================

    /**
     * Tool search mode — determines how deferrable tools (MCP + shouldDefer) are surfaced.
     *
     * <ul>
     *   <li>TST — Tool Search Tool always enabled; all deferrable tools use defer_loading.</li>
     *   <li>TST_AUTO — tools deferred only when they exceed the auto threshold.</li>
     *   <li>STANDARD — tool search disabled; all tools exposed inline.</li>
     * </ul>
     *
     * Translated from ToolSearchMode in toolSearch.ts
     */
    public enum ToolSearchMode {
        TST, TST_AUTO, STANDARD
    }

    /**
     * Determines the tool search mode from the ENABLE_TOOL_SEARCH env var.
     *
     * <pre>
     * ENABLE_TOOL_SEARCH    Mode
     * auto / auto:1-99      TST_AUTO
     * true / auto:0         TST
     * false / auto:100      STANDARD
     * (unset)               TST  (default: always defer MCP / shouldDefer tools)
     * </pre>
     *
     * Translated from getToolSearchMode() in toolSearch.ts
     */
    public ToolSearchMode getToolSearchMode() {
        String disableBetas = System.getenv("CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS");
        if (isEnvTruthy(disableBetas)) {
            return ToolSearchMode.STANDARD;
        }

        String value = System.getenv("ENABLE_TOOL_SEARCH");

        // Handle auto:N edge cases first.
        Integer autoPercent = parseAutoPercentage(value);
        if (autoPercent != null) {
            if (autoPercent == 0) return ToolSearchMode.TST;
            if (autoPercent == 100) return ToolSearchMode.STANDARD;
            return ToolSearchMode.TST_AUTO; // 1–99
        }

        if (isAutoToolSearchMode(value)) return ToolSearchMode.TST_AUTO; // plain "auto"
        if (isEnvTruthy(value)) return ToolSearchMode.TST;
        if (isEnvDefinedFalsy(value)) return ToolSearchMode.STANDARD;

        return ToolSearchMode.TST; // default
    }

    // =========================================================================
    // Model support
    // =========================================================================

    /**
     * Default model name substrings for models that do NOT support tool_reference.
     * New models are assumed to support tool_reference unless explicitly listed here.
     */
    private static final List<String> DEFAULT_UNSUPPORTED_MODEL_PATTERNS = List.of("haiku");

    /**
     * Check if a model supports tool_reference blocks (required for tool search).
     *
     * Uses a negative test: models are assumed to support tool_reference UNLESS they
     * match a pattern in the unsupported list. This ensures new models work by default.
     *
     * Translated from modelSupportsToolReference() in toolSearch.ts
     */
    public boolean modelSupportsToolReference(String model) {
        String normalised = model.toLowerCase(Locale.ROOT);
        return DEFAULT_UNSUPPORTED_MODEL_PATTERNS.stream()
            .noneMatch(pattern -> normalised.contains(pattern.toLowerCase(Locale.ROOT)));
    }

    // =========================================================================
    // Optimistic check
    // =========================================================================

    private boolean loggedOptimistic = false;

    /**
     * Check if tool search <em>might</em> be enabled (optimistic check).
     *
     * Returns {@code true} if tool search could potentially be enabled, without
     * checking dynamic factors like model support or threshold. Returns {@code false}
     * only when tool search is definitively disabled (STANDARD mode).
     *
     * Translated from isToolSearchEnabledOptimistic() in toolSearch.ts
     */
    public boolean isToolSearchEnabledOptimistic() {
        ToolSearchMode mode = getToolSearchMode();
        if (mode == ToolSearchMode.STANDARD) {
            if (!loggedOptimistic) {
                loggedOptimistic = true;
                log.debug("[ToolSearch:optimistic] mode={}, ENABLE_TOOL_SEARCH={}, result=false",
                    mode, System.getenv("ENABLE_TOOL_SEARCH"));
            }
            return false;
        }

        if (!loggedOptimistic) {
            loggedOptimistic = true;
            log.debug("[ToolSearch:optimistic] mode={}, ENABLE_TOOL_SEARCH={}, result=true",
                mode, System.getenv("ENABLE_TOOL_SEARCH"));
        }
        return true;
    }

    // =========================================================================
    // Definitive check
    // =========================================================================

    /**
     * Check whether ToolSearchTool is available in the provided tools list.
     * If ToolSearchTool is not available (e.g. disallowed via disallowedTools),
     * tool search cannot function and should be disabled.
     * Translated from isToolSearchToolAvailable() in toolSearch.ts
     */
    public boolean isToolSearchToolAvailable(List<? extends Tool<?, ?>> tools) {
        return tools.stream().anyMatch(t -> "ToolSearch".equals(t.getName()));
    }

    /**
     * Check if tool search is enabled for a specific request.
     *
     * This is the definitive check that includes model compatibility and
     * ToolSearchTool availability. In TST_AUTO mode it also checks the
     * character-based threshold for deferred tool descriptions.
     *
     * Translated from isToolSearchEnabled() in toolSearch.ts
     *
     * @param model The model being used for this request.
     * @param tools All available tools (including MCP tools).
     * @return true if tool search should be enabled for this request.
     */
    public boolean isToolSearchEnabled(String model, List<? extends Tool<?, ?>> tools) {
        if (!modelSupportsToolReference(model)) {
            log.debug("Tool search disabled for model '{}': model does not support tool_reference blocks.", model);
            return false;
        }

        if (!isToolSearchToolAvailable(tools)) {
            log.debug("Tool search disabled: ToolSearchTool is not available.");
            return false;
        }

        ToolSearchMode mode = getToolSearchMode();
        return switch (mode) {
            case TST -> true;
            case TST_AUTO -> checkAutoThreshold(tools);
            case STANDARD -> false;
        };
    }

    // =========================================================================
    // Deferred tool helpers
    // =========================================================================

    /**
     * Check if a tool is a deferred tool (MCP or explicitly marked shouldDefer).
     * Translated from isDeferredTool() in ToolSearchTool/prompt.ts
     */
    public boolean isDeferredTool(Tool<?, ?> tool) {
        return tool.isMcp() || tool.isShouldDefer();
    }

    /**
     * Get tools that should be included in the initial API request.
     * Non-deferred tools always load; deferred tools only load when tool search is disabled.
     */
    public List<Tool<?, ?>> getInitialTools(List<Tool<?, ?>> allTools, boolean toolSearchEnabled) {
        if (!toolSearchEnabled) {
            return allTools;
        }
        return allTools.stream()
            .filter(t -> !isDeferredTool(t))
            .collect(Collectors.toList());
    }

    /**
     * Get all deferred tools from a tool list.
     */
    public List<Tool<?, ?>> getDeferredTools(List<Tool<?, ?>> allTools) {
        return allTools.stream()
            .filter(this::isDeferredTool)
            .collect(Collectors.toList());
    }

    /**
     * Format a deferred tool line for the system prompt.
     * Translated from formatDeferredToolLine() in ToolSearchTool/prompt.ts
     */
    public String formatDeferredToolLine(Tool<?, ?> tool) {
        String hint = tool.getSearchHint();
        return "- " + tool.getName() + (hint != null && !hint.isEmpty() ? ": " + hint : "");
    }

    /**
     * Extract tool names from tool_reference blocks in message history.
     *
     * When dynamic tool loading is enabled, MCP tools are not pre-declared in the
     * tools array. Instead they are discovered via ToolSearchTool which returns
     * tool_reference blocks. This function scans the message history to find all
     * tool names that have been referenced.
     *
     * Translated from extractDiscoveredToolNames() in toolSearch.ts
     *
     * @param messages List of raw message maps from the conversation history.
     * @return Set of tool names that have been discovered via tool_reference blocks.
     */
    @SuppressWarnings("unchecked")
    public Set<String> extractDiscoveredToolNames(List<Map<String, Object>> messages) {
        Set<String> discoveredTools = new LinkedHashSet<>();

        for (Map<String, Object> msg : messages) {
            String type = (String) msg.get("type");
            if (!"user".equals(type)) continue;

            Object messageObj = msg.get("message");
            if (!(messageObj instanceof Map)) continue;

            Object contentObj = ((Map<String, Object>) messageObj).get("content");
            if (!(contentObj instanceof List)) continue;

            for (Object block : (List<?>) contentObj) {
                if (!(block instanceof Map)) continue;
                Map<String, Object> blockMap = (Map<String, Object>) block;
                if (!"tool_result".equals(blockMap.get("type"))) continue;

                Object innerContent = blockMap.get("content");
                if (!(innerContent instanceof List)) continue;

                for (Object item : (List<?>) innerContent) {
                    if (!(item instanceof Map)) continue;
                    Map<String, Object> itemMap = (Map<String, Object>) item;
                    if ("tool_reference".equals(itemMap.get("type"))) {
                        Object toolName = itemMap.get("tool_name");
                        if (toolName instanceof String name) {
                            discoveredTools.add(name);
                        }
                    }
                }
            }
        }

        if (!discoveredTools.isEmpty()) {
            log.debug("Dynamic tool loading: found {} discovered tools in message history",
                discoveredTools.size());
        }

        return discoveredTools;
    }

    /**
     * Delta of deferred tools vs what was previously announced in this conversation.
     * Translated from DeferredToolsDelta in toolSearch.ts
     */
    public record DeferredToolsDelta(
        List<String> addedNames,
        List<String> addedLines,
        List<String> removedNames
    ) {}

    /**
     * Diff the current deferred-tool pool against what has already been announced
     * in this conversation. Returns null if nothing changed.
     *
     * Translated from getDeferredToolsDelta() in toolSearch.ts
     */
    @SuppressWarnings("unchecked")
    public DeferredToolsDelta getDeferredToolsDelta(
            List<Tool<?, ?>> tools,
            List<Map<String, Object>> messages) {

        Set<String> announced = new LinkedHashSet<>();
        for (Map<String, Object> msg : messages) {
            if (!"attachment".equals(msg.get("type"))) continue;
            Object attachment = msg.get("attachment");
            if (!(attachment instanceof Map)) continue;
            Map<String, Object> att = (Map<String, Object>) attachment;
            if (!"deferred_tools_delta".equals(att.get("type"))) continue;

            Object addedNames = att.get("addedNames");
            if (addedNames instanceof List) {
                for (Object n : (List<?>) addedNames) {
                    if (n instanceof String s) announced.add(s);
                }
            }
            Object removedNames = att.get("removedNames");
            if (removedNames instanceof List) {
                for (Object n : (List<?>) removedNames) {
                    if (n instanceof String s) announced.remove(s);
                }
            }
        }

        List<Tool<?, ?>> deferred = tools.stream()
            .filter(this::isDeferredTool)
            .collect(Collectors.toList());
        Set<String> deferredNames = deferred.stream()
            .map(Tool::getName)
            .collect(Collectors.toSet());
        Set<String> poolNames = tools.stream()
            .map(Tool::getName)
            .collect(Collectors.toSet());

        List<Tool<?, ?>> added = deferred.stream()
            .filter(t -> !announced.contains(t.getName()))
            .collect(Collectors.toList());

        List<String> removed = new ArrayList<>();
        for (String n : announced) {
            if (deferredNames.contains(n)) continue;
            if (!poolNames.contains(n)) removed.add(n);
        }

        if (added.isEmpty() && removed.isEmpty()) return null;

        List<String> addedNames = added.stream().map(Tool::getName).sorted().collect(Collectors.toList());
        List<String> addedLines = added.stream().map(this::formatDeferredToolLine).sorted().collect(Collectors.toList());
        Collections.sort(removed);

        return new DeferredToolsDelta(addedNames, addedLines, removed);
    }

    // =========================================================================
    // Auto threshold
    // =========================================================================

    private static final int DEFAULT_AUTO_TOOL_SEARCH_PERCENTAGE = 10;
    private static final double CHARS_PER_TOKEN = 2.5;

    private int getAutoToolSearchPercentage() {
        String value = System.getenv("ENABLE_TOOL_SEARCH");
        if (value == null) return DEFAULT_AUTO_TOOL_SEARCH_PERCENTAGE;
        if ("auto".equals(value)) return DEFAULT_AUTO_TOOL_SEARCH_PERCENTAGE;
        Integer parsed = parseAutoPercentage(value);
        return parsed != null ? parsed : DEFAULT_AUTO_TOOL_SEARCH_PERCENTAGE;
    }

    /**
     * Check whether deferred tools exceed the auto-threshold for enabling TST.
     * Uses character-based heuristic.
     * Translated from checkAutoThreshold() in toolSearch.ts
     */
    private boolean checkAutoThreshold(List<? extends Tool<?, ?>> tools) {
        List<? extends Tool<?, ?>> deferred = tools.stream()
            .filter(t -> isDeferredTool((Tool<?, ?>) t))
            .collect(Collectors.toList());

        if (deferred.isEmpty()) return false;

        long totalChars = deferred.stream()
            .mapToLong(t -> t.getName().length()
                + (t.getDescription() != null ? t.getDescription().length() : 0))
            .sum();

        // Approximate threshold: 10% of 200k context * 2.5 chars/token = 50k chars
        long charThreshold = (long) (200_000 * (getAutoToolSearchPercentage() / 100.0) * CHARS_PER_TOKEN);

        return totalChars >= charThreshold;
    }

    // =========================================================================
    // Env var helpers
    // =========================================================================

    private static boolean isEnvTruthy(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    private static boolean isEnvDefinedFalsy(String value) {
        return "false".equalsIgnoreCase(value) || "0".equals(value) || "no".equalsIgnoreCase(value);
    }

    private static boolean isAutoToolSearchMode(String value) {
        if (value == null) return false;
        return "auto".equals(value) || value.startsWith("auto:");
    }

    /**
     * Parse auto:N syntax from ENABLE_TOOL_SEARCH env var.
     * Returns the percentage clamped to 0–100, or null if not auto:N format.
     * Translated from parseAutoPercentage() in toolSearch.ts
     */
    private static Integer parseAutoPercentage(String value) {
        if (value == null || !value.startsWith("auto:")) return null;
        try {
            int percent = Integer.parseInt(value.substring(5));
            return Math.max(0, Math.min(100, percent));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
