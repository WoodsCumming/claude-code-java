package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * API microcompact service.
 * Translated from src/services/compact/apiMicrocompact.ts
 *
 * Manages API-level context management strategies using the native
 * context-management API (clear_tool_uses_20250919, clear_thinking_20251015).
 *
 * docs: https://docs.google.com/document/d/1oCT4evvWTh3P6z-kcfNQwWTCxAhkoFndSaNS9Gm40uw/edit?tab=t.0
 */
@Slf4j
@Service
public class ApiMicrocompactService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApiMicrocompactService.class);


    // Default values for context management strategies.
    // Match client-side microcompact token values (from apiMicrocompact.ts).
    private static final int DEFAULT_MAX_INPUT_TOKENS = 180_000;  // Typical warning threshold
    private static final int DEFAULT_TARGET_INPUT_TOKENS = 40_000; // Keep last 40k tokens like client-side

    // Tools whose RESULTS can be cleared by the API (content cleared, use retained).
    private static final List<String> TOOLS_CLEARABLE_RESULTS = List.of(
        // Shell tool names
        "Bash",
        // File/search tools
        "Glob",
        "Grep",
        "Read",
        "WebFetch",
        "WebSearch"
    );

    // Tools whose USES can be cleared entirely by the API.
    private static final List<String> TOOLS_CLEARABLE_USES = List.of(
        "Edit",
        "Write",
        "NotebookEdit"
    );

    // =========================================================================
    // Sealed-interface equivalents for ContextEditStrategy union type
    // =========================================================================

    /**
     * Sealed hierarchy for context edit strategies.
     * Translated from the {@code ContextEditStrategy} union type in apiMicrocompact.ts.
     */
    public sealed interface ContextEditStrategy
            permits ContextEditStrategy.ClearToolUses, ContextEditStrategy.ClearThinking {

        /**
         * Strategy that clears selected tool uses / results from the context.
         * Corresponds to {@code clear_tool_uses_20250919} in the API.
         */
        record ClearToolUses(
            String type,
            Map<String, Object> trigger,
            Map<String, Object> keep,
            List<String> clearToolInputs,
            List<String> excludeTools,
            Map<String, Object> clearAtLeast
        ) implements ContextEditStrategy {}

        /**
         * Strategy that clears thinking blocks from prior assistant turns.
         * Corresponds to {@code clear_thinking_20251015} in the API.
         */
        record ClearThinking(
            String type,
            Object keep  // Either "all" or Map { type: "thinking_turns", value: N }
        ) implements ContextEditStrategy {}
    }

    /**
     * Context management configuration wrapper.
     * Translated from {@code ContextManagementConfig} in apiMicrocompact.ts.
     *
     * @param edits List of edit strategies to apply in order.
     */
    public record ContextManagementConfig(List<ContextEditStrategy> edits) {}

    // =========================================================================
    // Options record for getAPIContextManagement
    // =========================================================================

    /**
     * Options for {@link #getAPIContextManagement(Options)}.
     */
    public record Options(
        boolean hasThinking,
        boolean isRedactThinkingActive,
        boolean clearAllThinking
    ) {
        public static Options defaults() {
            return new Options(false, false, false);
        }
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * API-based microcompact implementation that uses native context management.
     * Translated from {@code getAPIContextManagement()} in apiMicrocompact.ts
     *
     * <p>Behaviour mirrors the TypeScript exactly:
     * <ol>
     *   <li>Adds a {@code clear_thinking_20251015} strategy when the model is
     *       using extended thinking and redact-thinking is NOT active.</li>
     *   <li>Adds tool-clearing strategies only when the {@code USER_TYPE=ant}
     *       environment variable is set AND the corresponding env flags are
     *       truthy ({@code USE_API_CLEAR_TOOL_RESULTS},
     *       {@code USE_API_CLEAR_TOOL_USES}).</li>
     * </ol>
     *
     * @param options Thinking/redaction flags for the current request.
     * @return Optional containing the config, or empty when no strategies apply.
     */
    public Optional<ContextManagementConfig> getAPIContextManagement(Options options) {
        if (options == null) {
            options = Options.defaults();
        }

        List<ContextEditStrategy> strategies = new ArrayList<>();

        // Preserve thinking blocks in previous assistant turns. Skip when
        // redact-thinking is active — redacted blocks have no model-visible content.
        // When clearAllThinking is set (>1h idle = cache miss), keep only the last
        // thinking turn — the API schema requires value >= 1.
        if (options.hasThinking() && !options.isRedactThinkingActive()) {
            Object keep = options.clearAllThinking()
                    ? Map.of("type", "thinking_turns", "value", 1)
                    : "all";
            strategies.add(new ContextEditStrategy.ClearThinking("clear_thinking_20251015", keep));
        }

        // Tool clearing strategies are ant-only (USER_TYPE=ant).
        String userType = System.getenv("USER_TYPE");
        if (!"ant".equals(userType)) {
            return strategies.isEmpty() ? Optional.empty()
                                        : Optional.of(new ContextManagementConfig(strategies));
        }

        boolean useClearToolResults = isEnvTruthy("USE_API_CLEAR_TOOL_RESULTS");
        boolean useClearToolUses   = isEnvTruthy("USE_API_CLEAR_TOOL_USES");

        if (!useClearToolResults && !useClearToolUses) {
            return strategies.isEmpty() ? Optional.empty()
                                        : Optional.of(new ContextManagementConfig(strategies));
        }

        int triggerThreshold = parseEnvInt("API_MAX_INPUT_TOKENS", DEFAULT_MAX_INPUT_TOKENS);
        int keepTarget        = parseEnvInt("API_TARGET_INPUT_TOKENS", DEFAULT_TARGET_INPUT_TOKENS);

        if (useClearToolResults) {
            Map<String, Object> trigger   = Map.of("type", "input_tokens", "value", triggerThreshold);
            Map<String, Object> clearAtLeast = Map.of("type", "input_tokens", "value", triggerThreshold - keepTarget);
            strategies.add(new ContextEditStrategy.ClearToolUses(
                "clear_tool_uses_20250919",
                trigger,
                null,
                TOOLS_CLEARABLE_RESULTS,
                null,
                clearAtLeast
            ));
        }

        if (useClearToolUses) {
            Map<String, Object> trigger   = Map.of("type", "input_tokens", "value", triggerThreshold);
            Map<String, Object> clearAtLeast = Map.of("type", "input_tokens", "value", triggerThreshold - keepTarget);
            strategies.add(new ContextEditStrategy.ClearToolUses(
                "clear_tool_uses_20250919",
                trigger,
                null,
                null,
                TOOLS_CLEARABLE_USES,
                clearAtLeast
            ));
        }

        return strategies.isEmpty() ? Optional.empty()
                                    : Optional.of(new ContextManagementConfig(strategies));
    }

    /**
     * Convenience overload using default options.
     */
    public Optional<ContextManagementConfig> getAPIContextManagement() {
        return getAPIContextManagement(Options.defaults());
    }

    /**
     * Serialise a {@link ContextManagementConfig} to the map structure expected
     * by the Anthropic API request body.
     *
     * @param config Config to serialise.
     * @return Map suitable for embedding in the API request.
     */
    public Map<String, Object> toApiMap(ContextManagementConfig config) {
        List<Map<String, Object>> edits = new ArrayList<>();
        for (ContextEditStrategy strategy : config.edits()) {
            edits.add(strategyToMap(strategy));
        }
        return Map.of("edits", edits);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private Map<String, Object> strategyToMap(ContextEditStrategy strategy) {
        Map<String, Object> map = new LinkedHashMap<>();
        switch (strategy) {
            case ContextEditStrategy.ClearThinking ct -> {
                map.put("type", ct.type());
                map.put("keep", ct.keep());
            }
            case ContextEditStrategy.ClearToolUses ctu -> {
                map.put("type", ctu.type());
                if (ctu.trigger() != null)        map.put("trigger",             ctu.trigger());
                if (ctu.keep() != null)           map.put("keep",                ctu.keep());
                if (ctu.clearToolInputs() != null) map.put("clear_tool_inputs", ctu.clearToolInputs());
                if (ctu.excludeTools() != null)   map.put("exclude_tools",      ctu.excludeTools());
                if (ctu.clearAtLeast() != null)   map.put("clear_at_least",     ctu.clearAtLeast());
            }
        }
        return map;
    }

    private boolean isEnvTruthy(String name) {
        String val = System.getenv(name);
        return "1".equals(val) || "true".equalsIgnoreCase(val) || "yes".equalsIgnoreCase(val);
    }

    private int parseEnvInt(String name, int defaultValue) {
        String val = System.getenv(name);
        if (val != null) {
            try {
                return Integer.parseInt(val);
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return defaultValue;
    }
}
