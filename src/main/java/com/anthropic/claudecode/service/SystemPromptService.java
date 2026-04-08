package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * System prompt construction service.
 * Translated from src/utils/systemPrompt.ts
 *
 * Builds the effective system prompt array from the various sources:
 *   0. Override system prompt  — replaces all others when set
 *   1. Coordinator prompt      — when coordinator mode is active
 *   2. Agent system prompt     — when a mainThreadAgentDefinition is set
 *   3. Custom system prompt    — when specified via --system-prompt
 *   4. Default system prompt   — standard Claude Code prompt
 *   + appendSystemPrompt appended at the end (except when override is set)
 */
@Slf4j
@Service
public class SystemPromptService {



    private final BootstrapStateService bootstrapStateService;

    @Autowired
    public SystemPromptService(BootstrapStateService bootstrapStateService) {
        this.bootstrapStateService = bootstrapStateService;
    }

    // =========================================================================
    // System prompt construction
    // =========================================================================

    /**
     * Build the effective system prompt array from all configured sources.
     * Translated from buildEffectiveSystemPrompt() in utils/systemPrompt.ts
     *
     * Priority order:
     *   0. overrideSystemPrompt → replaces everything (used by loop mode)
     *   1. Coordinator prompt   → when CLAUDE_CODE_COORDINATOR_MODE env is set
     *   2. Agent prompt         → when mainThreadAgentDefinition is set
     *      - In proactive mode: appended to defaultSystemPrompt
     *      - Otherwise: replaces defaultSystemPrompt
     *   3. customSystemPrompt   → when specified via --system-prompt flag
     *   4. defaultSystemPrompt  → standard Claude Code base prompt
     *   Plus appendSystemPrompt at the end (unless override is set)
     */
    public SystemPrompt buildEffectiveSystemPrompt(SystemPromptInput input) {
        // 0. Override: replaces everything
        if (input.overrideSystemPrompt() != null && !input.overrideSystemPrompt().isBlank()) {
            return asSystemPrompt(List.of(input.overrideSystemPrompt()));
        }

        // 1. Coordinator mode
        boolean coordinatorModeActive = isEnvTruthy("CLAUDE_CODE_COORDINATOR_MODE")
            && input.mainThreadAgentDefinition() == null;
        if (coordinatorModeActive) {
            List<String> parts = new ArrayList<>();
            parts.add(getCoordinatorSystemPrompt());
            if (input.appendSystemPrompt() != null) parts.add(input.appendSystemPrompt());
            return asSystemPrompt(parts);
        }

        // 2. Resolve agent system prompt (if a main-thread agent is configured)
        String agentSystemPrompt = null;
        if (input.mainThreadAgentDefinition() != null) {
            agentSystemPrompt = input.mainThreadAgentDefinition().getSystemPrompt(input);
        }

        // In proactive mode, agent instructions are APPENDED to the default prompt
        boolean isProactiveActive = isProactiveActive();
        if (agentSystemPrompt != null && isProactiveActive) {
            List<String> parts = new ArrayList<>(input.defaultSystemPrompt());
            parts.add("\n# Custom Agent Instructions\n" + agentSystemPrompt);
            if (input.appendSystemPrompt() != null) parts.add(input.appendSystemPrompt());
            return asSystemPrompt(parts);
        }

        // 3. Build base: agent > custom > default
        List<String> base = new ArrayList<>();
        if (agentSystemPrompt != null) {
            base.add(agentSystemPrompt);
        } else if (input.customSystemPrompt() != null && !input.customSystemPrompt().isBlank()) {
            base.add(input.customSystemPrompt());
        } else {
            base.addAll(input.defaultSystemPrompt());
        }

        if (input.appendSystemPrompt() != null) {
            base.add(input.appendSystemPrompt());
        }

        return asSystemPrompt(base);
    }

    // =========================================================================
    // SystemPrompt type constructors
    // =========================================================================

    /**
     * Wrap a list of prompt parts into a SystemPrompt.
     * Translated from asSystemPrompt() in utils/systemPromptType.ts
     */
    public static SystemPrompt asSystemPrompt(List<String> parts) {
        return new SystemPrompt(parts);
    }

    /**
     * Async system prompt builder. Returns a list of system prompt parts.
     * Used by QueryContextService.
     */
    public java.util.concurrent.CompletableFuture<List<String>> getSystemPromptAsync(
            List<Object> tools,
            String mainLoopModel,
            List<String> additionalWorkingDirectories,
            List<Object> mcpClients) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            SystemPromptInput input = new SystemPromptInput(null, null, List.of(), null, null);
            SystemPrompt prompt = buildEffectiveSystemPrompt(input);
            return prompt != null ? prompt.parts() : List.of();
        });
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private boolean isEnvTruthy(String envVar) {
        String value = System.getenv(envVar);
        return "1".equals(value) || "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value);
    }

    private boolean isProactiveActive() {
        // In Java we check the feature flag env; the full proactive module is not translated here.
        return isEnvTruthy("CLAUDE_CODE_PROACTIVE") || isEnvTruthy("CLAUDE_CODE_KAIROS");
    }

    private String getCoordinatorSystemPrompt() {
        // Placeholder — the full coordinator prompt is in coordinator/coordinatorMode.ts
        return "You are a coordinator agent. Delegate tasks to worker agents as appropriate.";
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /**
     * The effective system prompt passed to the API.
     * Translated from SystemPrompt type in utils/systemPromptType.ts
     *
     * A list of string parts; the API receives them as separate system blocks
     * (or joined, depending on the caller).
     */
    public record SystemPrompt(List<String> parts) {
        /** Join all parts into a single string. */
        public String join() {
            return String.join("\n\n", parts);
        }
    }

    /**
     * Input to buildEffectiveSystemPrompt().
     * Translated from the parameter object of buildEffectiveSystemPrompt() in systemPrompt.ts
     */
    public record SystemPromptInput(
        AgentDefinition mainThreadAgentDefinition,
        String customSystemPrompt,
        List<String> defaultSystemPrompt,
        String appendSystemPrompt,
        String overrideSystemPrompt
    ) {}

    /**
     * Minimal agent definition interface.
     * Translated from AgentDefinition in tools/AgentTool/loadAgentsDir.ts
     */
    public interface AgentDefinition {
        /**
         * Return the system prompt for this agent.
         * Built-in agents receive the full toolUseContext; external agents do not.
         */
        String getSystemPrompt(SystemPromptInput context);

        /** Optional agent type identifier. */
        default String getAgentType() { return null; }

        /** Optional memory scope (for logging). */
        default String getMemory() { return null; }
    }
}
