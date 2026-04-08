package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.AgentComponentTypes.AgentDefinition;
import com.anthropic.claudecode.model.AgentComponentTypes.AgentValidationResult;
import com.anthropic.claudecode.util.AgentComponentUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Agent validation logic.
 *
 * <p>Translated from TypeScript {@code src/components/agents/validateAgent.ts}.
 *
 * <p>The TypeScript module exported two plain functions; here they are service methods
 * so Spring can inject this bean wherever validation is needed.
 */
@Slf4j
@Service
public class ValidateAgentService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ValidateAgentService.class);


    /** Minimum / maximum length constraints (mirrors the TS constants). */
    private static final int AGENT_TYPE_MIN_LEN  = 3;
    private static final int AGENT_TYPE_MAX_LEN  = 50;
    private static final int WHEN_TO_USE_MIN_LEN = 10;
    private static final int WHEN_TO_USE_WARN_LEN = 5000;
    private static final int SYSTEM_PROMPT_MIN_LEN = 20;
    private static final int SYSTEM_PROMPT_WARN_LEN = 10_000;

    /**
     * Valid agent-type pattern: starts and ends with alphanumeric, interior may include hyphens.
     */
    private static final Pattern AGENT_TYPE_PATTERN =
            Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9-]*[a-zA-Z0-9]$");

    // ---------------------------------------------------------------------------
    // validateAgentType
    // ---------------------------------------------------------------------------

    /**
     * Validates an agent-type identifier string.
     *
     * @param agentType the identifier to validate
     * @return an error message, or {@code null} if valid
     */
    public String validateAgentType(String agentType) {
        if (agentType == null || agentType.isBlank()) {
            return "Agent type is required";
        }
        if (!AGENT_TYPE_PATTERN.matcher(agentType).matches()) {
            return "Agent type must start and end with alphanumeric characters "
                    + "and contain only letters, numbers, and hyphens";
        }
        if (agentType.length() < AGENT_TYPE_MIN_LEN) {
            return "Agent type must be at least " + AGENT_TYPE_MIN_LEN + " characters long";
        }
        if (agentType.length() > AGENT_TYPE_MAX_LEN) {
            return "Agent type must be less than " + AGENT_TYPE_MAX_LEN + " characters";
        }
        return null;
    }

    // ---------------------------------------------------------------------------
    // validateAgent
    // ---------------------------------------------------------------------------

    /**
     * Performs full validation of an agent definition.
     *
     * <p>TypeScript signature:
     * <pre>
     * export function validateAgent(
     *   agent: Omit&lt;CustomAgentDefinition, 'location'&gt;,
     *   availableTools: Tools,
     *   existingAgents: AgentDefinition[],
     * ): AgentValidationResult
     * </pre>
     *
     * <p>The {@code availableTools} set and the system-prompt supplier are modelled as plain Java
     * parameters. The tool-resolution logic (checking for invalid tool names) is delegated to the
     * {@code resolveTools} callback so this service stays decoupled from tool infrastructure.
     *
     * @param agentType          the agent identifier being validated
     * @param whenToUse          description / trigger text
     * @param tools              requested tool names ({@code null} = all tools; empty = no tools)
     * @param systemPromptSupplier supplies the resolved system-prompt text
     * @param availableToolNames set of tool names that actually exist in the current session
     * @param existingAgents     agents already registered (used for duplicate detection)
     * @param agentSource        the source of the agent being validated (for duplicate exclusion)
     * @return validation result with collected errors and warnings
     */
    public AgentValidationResult validateAgent(
            String agentType,
            String whenToUse,
            List<String> tools,
            Supplier<String> systemPromptSupplier,
            List<String> availableToolNames,
            List<AgentDefinition> existingAgents,
            String agentSource
    ) {
        List<String> errors   = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // --- Validate agent type ---
        if (agentType == null || agentType.isBlank()) {
            errors.add("Agent type is required");
        } else {
            String typeError = validateAgentType(agentType);
            if (typeError != null) {
                errors.add(typeError);
            }

            // Duplicate check (exclude self when editing)
            boolean hasDuplicate = existingAgents.stream()
                    .anyMatch(a -> agentType.equals(a.agentType())
                            && !String.valueOf(a.source()).equalsIgnoreCase(agentSource));
            if (hasDuplicate) {
                AgentDefinition duplicate = existingAgents.stream()
                        .filter(a -> agentType.equals(a.agentType()))
                        .findFirst()
                        .orElseThrow();
                String sourceName = AgentComponentUtils.getAgentSourceDisplayName(
                        String.valueOf(duplicate.source()));
                errors.add("Agent type \"" + agentType + "\" already exists in " + sourceName);
            }
        }

        // --- Validate description (whenToUse) ---
        if (whenToUse == null || whenToUse.isBlank()) {
            errors.add("Description (description) is required");
        } else if (whenToUse.length() < WHEN_TO_USE_MIN_LEN) {
            warnings.add("Description should be more descriptive (at least "
                    + WHEN_TO_USE_MIN_LEN + " characters)");
        } else if (whenToUse.length() > WHEN_TO_USE_WARN_LEN) {
            warnings.add("Description is very long (over " + WHEN_TO_USE_WARN_LEN + " characters)");
        }

        // --- Validate tools ---
        if (tools != null && !isValidToolsList(tools)) {
            errors.add("Tools must be an array");
        } else {
            if (tools == null) {
                warnings.add("Agent has access to all tools");
            } else if (tools.isEmpty()) {
                warnings.add("No tools selected - agent will have very limited capabilities");
            }

            // Check for invalid tool names
            if (tools != null && availableToolNames != null) {
                List<String> invalidTools = tools.stream()
                        .filter(t -> !"*".equals(t) && !availableToolNames.contains(t))
                        .toList();
                if (!invalidTools.isEmpty()) {
                    errors.add("Invalid tools: " + String.join(", ", invalidTools));
                }
            }
        }

        // --- Validate system prompt ---
        String systemPrompt = systemPromptSupplier != null ? systemPromptSupplier.get() : null;
        if (systemPrompt == null || systemPrompt.isBlank()) {
            errors.add("System prompt is required");
        } else if (systemPrompt.length() < SYSTEM_PROMPT_MIN_LEN) {
            errors.add("System prompt is too short (minimum " + SYSTEM_PROMPT_MIN_LEN + " characters)");
        } else if (systemPrompt.length() > SYSTEM_PROMPT_WARN_LEN) {
            warnings.add("System prompt is very long (over " + SYSTEM_PROMPT_WARN_LEN + " characters)");
        }

        return new AgentValidationResult(errors.isEmpty(), errors, warnings);
    }

    // ---------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------

    /** Guards against a non-list value being passed where a list is required. */
    private boolean isValidToolsList(List<String> tools) {
        return tools != null; // Java type system guarantees it's a List if non-null
    }
}
