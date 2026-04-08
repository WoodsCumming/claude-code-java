package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import com.anthropic.claudecode.util.FrontmatterParser;
import com.anthropic.claudecode.util.MarkdownConfigLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import lombok.Data;

/**
 * Agent definitions loader and display service.
 *
 * Merges two TypeScript sources:
 * <ul>
 *   <li>{@code src/tools/AgentTool/loadAgentsDir.ts} — loading agent definitions</li>
 *   <li>{@code src/cli/handlers/agents.ts} — {@code agentsHandler()} CLI output</li>
 * </ul>
 *
 * Translated from src/cli/handlers/agents.ts and src/tools/AgentTool/loadAgentsDir.ts
 */
@Slf4j
@Service
public class AgentsLoaderService {



    private static final String AGENTS_SUBDIR = "agents";

    // One-shot built-in agent types
    public static final Set<String> ONE_SHOT_BUILTIN_AGENT_TYPES = Set.of("Explore", "Plan");

    // -------------------------------------------------------------------------
    // Domain model
    // -------------------------------------------------------------------------

    @Data
    @lombok.Builder
    
    public static class AgentDefinition {
        private String name;
        private String description;
        private String systemPrompt;
        private List<String> allowedTools;
        private String model;
        private String source;   // "user" | "project" | "builtin"
        private boolean isBuiltin;
        private String agentType;
        private String memory;   // optional memory config
    }

    /** A resolved agent with override information. */
    @Data
    @lombok.Builder
    
    public static class ResolvedAgent {
        private String agentType;
        private String source;
        private String model;     // may be null
        private String memory;    // may be null
        /** Source label of the agent that overrides this one, or null if active. */
        private String overriddenBy;
    }

    /** Source group descriptor — mirrors AGENT_SOURCE_GROUPS in agentDisplay.ts. */
    public record AgentSourceGroup(String label, String source) {}

    /** Result of loading agent definitions. */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AgentDefinitionsResult {
        private List<AgentDefinition> agents;
        private String error;

        public List<AgentDefinition> getAgents() { return agents; }
        public void setAgents(List<AgentDefinition> v) { agents = v; }
        public String getError() { return error; }
        public void setError(String v) { error = v; }
    }

    // -------------------------------------------------------------------------
    // Source groups (mirrors AGENT_SOURCE_GROUPS in agentDisplay.ts)
    // -------------------------------------------------------------------------

    public static final List<AgentSourceGroup> AGENT_SOURCE_GROUPS = List.of(
        new AgentSourceGroup("Project agents", "project"),
        new AgentSourceGroup("User agents", "user"),
        new AgentSourceGroup("Built-in agents", "builtin")
    );

    // -------------------------------------------------------------------------
    // CLI handler  (src/cli/handlers/agents.ts → agentsHandler)
    // -------------------------------------------------------------------------

    /**
     * Print the list of configured agents to stdout.
     * Translated from {@code agentsHandler()} in agents.ts.
     */
    public CompletableFuture<Void> agentsHandler(String cwd) {
        return CompletableFuture.runAsync(() -> {
            AgentDefinitionsResult result = loadAgentDefinitions(cwd);
            List<AgentDefinition> allAgents = result.getAgents();
            List<AgentDefinition> activeAgents = getActiveAgentsFromList(allAgents);
            List<ResolvedAgent> resolvedAgents = resolveAgentOverrides(allAgents, activeAgents);

            List<String> lines = new ArrayList<>();
            int totalActive = 0;

            for (AgentSourceGroup group : AGENT_SOURCE_GROUPS) {
                List<ResolvedAgent> groupAgents = resolvedAgents.stream()
                    .filter(a -> group.source().equals(a.getSource()))
                    .sorted(Comparator.comparing(a -> a.getAgentType().toLowerCase()))
                    .collect(Collectors.toList());

                if (groupAgents.isEmpty()) continue;

                lines.add(group.label() + ":");
                for (ResolvedAgent agent : groupAgents) {
                    String formatted = formatAgent(agent);
                    if (agent.getOverriddenBy() != null) {
                        String winnerSource = getOverrideSourceLabel(agent.getOverriddenBy());
                        lines.add("  (shadowed by " + winnerSource + ") " + formatted);
                    } else {
                        lines.add("  " + formatted);
                        totalActive++;
                    }
                }
                lines.add("");
            }

            if (lines.isEmpty()) {
                System.out.println("No agents found.");
            } else {
                System.out.println(totalActive + " active agents\n");
                String output = String.join("\n", lines);
                // trimEnd equivalent
                System.out.println(output.stripTrailing());
            }
        });
    }

    /**
     * Format a single resolved agent for display.
     * Translated from {@code formatAgent()} in agents.ts.
     */
    private String formatAgent(ResolvedAgent agent) {
        List<String> parts = new ArrayList<>();
        parts.add(agent.getAgentType());
        if (agent.getModel() != null && !agent.getModel().isEmpty()) {
            parts.add(agent.getModel());
        }
        if (agent.getMemory() != null && !agent.getMemory().isEmpty()) {
            parts.add(agent.getMemory() + " memory");
        }
        return String.join(" · ", parts);
    }

    /**
     * Get a human-readable label for the source that wins an override.
     * Translated from {@code getOverrideSourceLabel()} in agentDisplay.ts.
     */
    public String getOverrideSourceLabel(String source) {
        return switch (source) {
            case "project" -> "project";
            case "user"    -> "user";
            case "builtin" -> "built-in";
            default        -> source;
        };
    }

    // -------------------------------------------------------------------------
    // Active / override resolution  (src/tools/AgentTool/loadAgentsDir.ts)
    // -------------------------------------------------------------------------

    /**
     * Return the subset of agents that are "active" (one per agentType, highest
     * priority source wins: project > user > builtin).
     * Translated from {@code getActiveAgentsFromList()} in loadAgentsDir.ts.
     */
    public List<AgentDefinition> getActiveAgentsFromList(List<AgentDefinition> agents) {
        // Priority order: project (highest) > user > builtin
        Map<String, AgentDefinition> byType = new LinkedHashMap<>();
        List<String> priorityOrder = List.of("project", "user", "builtin");

        for (String source : priorityOrder) {
            for (AgentDefinition agent : agents) {
                if (source.equals(agent.getSource()) && !byType.containsKey(agent.getAgentType())) {
                    byType.put(agent.getAgentType(), agent);
                }
            }
        }
        return new ArrayList<>(byType.values());
    }

    /**
     * Produce a flat list of {@link ResolvedAgent}s, marking shadowed ones with
     * the source that overrides them.
     * Translated from {@code resolveAgentOverrides()} in agentDisplay.ts.
     */
    public List<ResolvedAgent> resolveAgentOverrides(
            List<AgentDefinition> allAgents,
            List<AgentDefinition> activeAgents) {

        Set<String> activeIds = activeAgents.stream()
            .map(a -> a.getAgentType() + "@" + a.getSource())
            .collect(Collectors.toSet());

        // Build a map: agentType → winning source
        Map<String, String> winnerSource = new HashMap<>();
        for (AgentDefinition a : activeAgents) {
            winnerSource.put(a.getAgentType(), a.getSource());
        }

        return allAgents.stream().map(agent -> {
            String id = agent.getAgentType() + "@" + agent.getSource();
            boolean isActive = activeIds.contains(id);
            String overriddenBy = null;
            if (!isActive) {
                overriddenBy = winnerSource.get(agent.getAgentType());
            }
            return ResolvedAgent.builder()
                .agentType(agent.getAgentType() != null ? agent.getAgentType() : agent.getName())
                .source(agent.getSource())
                .model(agent.getModel())
                .memory(agent.getMemory())
                .overriddenBy(overriddenBy)
                .build();
        }).collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Loading  (src/tools/AgentTool/loadAgentsDir.ts)
    // -------------------------------------------------------------------------

    /**
     * Load all agent definitions (built-in + user + project).
     * Translated from {@code getAgentDefinitionsWithOverrides()} in loadAgentsDir.ts.
     */
    public AgentDefinitionsResult loadAgentDefinitions(String cwd) {
        List<AgentDefinition> agents = new ArrayList<>();

        agents.addAll(getBuiltInAgents());

        String userAgentsDir = EnvUtils.getClaudeConfigHomeDir() + "/" + AGENTS_SUBDIR;
        agents.addAll(loadAgentsFromDir(userAgentsDir, "user"));

        if (cwd != null) {
            String projectAgentsDir = cwd + "/.claude/" + AGENTS_SUBDIR;
            agents.addAll(loadAgentsFromDir(projectAgentsDir, "project"));
        }

        return new AgentDefinitionsResult(agents, null);
    }

    private List<AgentDefinition> getBuiltInAgents() {
        return List.of(
            AgentDefinition.builder()
                .name("general-purpose")
                .description("General-purpose agent for researching complex questions")
                .isBuiltin(true)
                .source("builtin")
                .agentType("general-purpose")
                .build(),
            AgentDefinition.builder()
                .name("Explore")
                .description("Fast agent specialized for exploring codebases")
                .isBuiltin(true)
                .source("builtin")
                .agentType("Explore")
                .build(),
            AgentDefinition.builder()
                .name("Plan")
                .description("Software architect agent for designing implementation plans")
                .isBuiltin(true)
                .source("builtin")
                .agentType("Plan")
                .build()
        );
    }

    private List<AgentDefinition> loadAgentsFromDir(String dir, String source) {
        List<AgentDefinition> agents = new ArrayList<>();
        File directory = new File(dir);

        if (!directory.isDirectory()) return agents;

        File[] files = directory.listFiles((d, name) -> name.endsWith(".md"));
        if (files == null) return agents;

        for (File file : files) {
            try {
                String content = Files.readString(file.toPath());
                FrontmatterParser.FrontmatterData frontmatter = FrontmatterParser.parseFrontmatter(content);
                String body = FrontmatterParser.removeFrontmatter(content);

                String name = file.getName().replace(".md", "");
                String description = frontmatter.getDescription() != null
                    ? frontmatter.getDescription()
                    : MarkdownConfigLoader.extractDescriptionFromMarkdown(body, name);

                agents.add(AgentDefinition.builder()
                    .name(name)
                    .description(description)
                    .systemPrompt(body)
                    .allowedTools(frontmatter.getAllowedTools())
                    .model(frontmatter.getModel())
                    .source(source)
                    .isBuiltin(false)
                    .agentType(name)
                    .build());

            } catch (Exception e) {
                log.debug("Could not load agent {}: {}", file.getName(), e.getMessage());
            }
        }

        return agents;
    }

    // -------------------------------------------------------------------------
    // Predicates
    // -------------------------------------------------------------------------

    /**
     * Check if an agent is a built-in agent.
     * Translated from isBuiltInAgent() in loadAgentsDir.ts.
     */
    public boolean isBuiltInAgent(String agentType) {
        return Set.of("general-purpose", "Explore", "Plan",
                      "claude-code-guide", "statusline-setup", "find-skills")
                  .contains(agentType);
    }

    /**
     * Check if an agent is a custom (user-defined) agent.
     * Translated from isCustomAgent() in loadAgentsDir.ts.
     */
    public boolean isCustomAgent(String agentType) {
        return !isBuiltInAgent(agentType);
    }

    /**
     * Load agents and run the agents UI.
     * Stub implementation.
     */
    public java.util.concurrent.CompletableFuture<Void> loadAndRunAgentsUI() {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
}
