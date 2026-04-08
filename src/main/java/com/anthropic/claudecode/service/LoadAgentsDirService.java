package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.AgentDefinition;
import com.anthropic.claudecode.model.BuiltInAgents;
import com.anthropic.claudecode.util.EnvUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Load agents directory service.
 * Translated from src/tools/AgentTool/loadAgentsDir.ts
 *
 * Loads, merges, and exposes the full set of agent definitions from all sources:
 * built-in, plugin, user settings, project settings, policy (managed) settings,
 * and CLI flag settings. Provides type helpers and the active-agent resolution logic.
 */
@Slf4j
@Service
public class LoadAgentsDirService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LoadAgentsDirService.class);


    private final AgentMemorySnapshotService agentMemorySnapshotService;
    private final PluginAgentsLoaderService pluginAgentsLoaderService;
    private final MarkdownConfigLoaderService markdownConfigLoaderService;

    @Autowired
    public LoadAgentsDirService(AgentMemorySnapshotService agentMemorySnapshotService,
                                PluginAgentsLoaderService pluginAgentsLoaderService,
                                MarkdownConfigLoaderService markdownConfigLoaderService) {
        this.agentMemorySnapshotService = agentMemorySnapshotService;
        this.pluginAgentsLoaderService = pluginAgentsLoaderService;
        this.markdownConfigLoaderService = markdownConfigLoaderService;
    }

    // -------------------------------------------------------------------------
    // Result type
    // -------------------------------------------------------------------------

    /**
     * Mirrors the TypeScript AgentDefinitionsResult type.
     */
    public record AgentDefinitionsResult(
        List<AgentDefinition> activeAgents,
        List<AgentDefinition> allAgents,
        List<FailedFile> failedFiles,
        List<String> allowedAgentTypes
    ) {
        public record FailedFile(String path, String error) {}
    }

    // -------------------------------------------------------------------------
    // Type guards (mirrors isBuiltInAgent / isCustomAgent / isPluginAgent)
    // -------------------------------------------------------------------------

    public static boolean isBuiltInAgent(AgentDefinition agent) {
        return "built-in".equals(agent.getSource());
    }

    public static boolean isCustomAgent(AgentDefinition agent) {
        String source = agent.getSource();
        return !"built-in".equals(source) && !"plugin".equals(source);
    }

    public static boolean isPluginAgent(AgentDefinition agent) {
        return "plugin".equals(agent.getSource());
    }

    // -------------------------------------------------------------------------
    // Active-agent resolution
    // -------------------------------------------------------------------------

    /**
     * From a flat list of all agents (all sources), derive the deduplicated
     * list of active (winning) agents. Higher-priority sources override
     * lower-priority ones with the same agentType.
     *
     * Priority order (lowest to highest):
     *   built-in → plugin → user → project → flag → managed (policy)
     *
     * Translated from getActiveAgentsFromList().
     */
    public List<AgentDefinition> getActiveAgentsFromList(List<AgentDefinition> allAgents) {
        List<AgentDefinition> builtIns  = filterBySource(allAgents, "built-in");
        List<AgentDefinition> plugins   = filterBySource(allAgents, "plugin");
        List<AgentDefinition> user      = filterBySource(allAgents, "userSettings");
        List<AgentDefinition> project   = filterBySource(allAgents, "projectSettings");
        List<AgentDefinition> managed   = filterBySource(allAgents, "policySettings");
        List<AgentDefinition> flagAgents = filterBySource(allAgents, "flagSettings");

        // Insert order defines override priority: last write wins
        List<List<AgentDefinition>> groups = List.of(
            builtIns, plugins, user, project, flagAgents, managed
        );

        Map<String, AgentDefinition> agentMap = new LinkedHashMap<>();
        for (List<AgentDefinition> group : groups) {
            for (AgentDefinition agent : group) {
                agentMap.put(agent.getAgentType(), agent);
            }
        }
        return new ArrayList<>(agentMap.values());
    }

    // -------------------------------------------------------------------------
    // MCP server requirement helpers
    // -------------------------------------------------------------------------

    /**
     * Checks if an agent's required MCP servers are available.
     * Returns true if no requirements or all requirements are met.
     * Translated from hasRequiredMcpServers().
     */
    public boolean hasRequiredMcpServers(AgentDefinition agent, List<String> availableServers) {
        List<String> required = agent.getRequiredMcpServers();
        if (required == null || required.isEmpty()) return true;
        return required.stream().allMatch(pattern ->
            availableServers.stream().anyMatch(server ->
                server.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT))
            )
        );
    }

    /**
     * Filters agents based on MCP server requirements.
     * Translated from filterAgentsByMcpRequirements().
     */
    public List<AgentDefinition> filterAgentsByMcpRequirements(
            List<AgentDefinition> agents,
            List<String> availableServers) {
        return agents.stream()
            .filter(a -> hasRequiredMcpServers(a, availableServers))
            .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Main load method (memoized per cwd)
    // -------------------------------------------------------------------------

    /**
     * Load all agent definitions for the given working directory.
     * Translated from getAgentDefinitionsWithOverrides() (memoized).
     *
     * Returns a CompletableFuture that resolves to the full AgentDefinitionsResult.
     * Results are cached per cwd via Spring Cache (@Cacheable).
     */
    @Cacheable(value = "agentDefinitions", key = "#cwd")
    public CompletableFuture<AgentDefinitionsResult> getAgentDefinitionsWithOverrides(String cwd) {
        return CompletableFuture.supplyAsync(() -> {
            // Simple mode: skip custom agents, only return built-ins
            if (EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_SIMPLE"))) {
                List<AgentDefinition> builtIns = getBuiltInAgents();
                return new AgentDefinitionsResult(builtIns, builtIns, null, null);
            }

            try {
                // Load markdown agent files from all configured subdirectories
                List<MarkdownConfigLoaderService.MarkdownFileEntry> markdownFiles =
                    markdownConfigLoaderService.loadMarkdownFilesForSubdir("agents", cwd);

                List<AgentDefinitionsResult.FailedFile> failedFiles = new ArrayList<>();
                List<AgentDefinition> customAgents = new ArrayList<>();

                for (MarkdownConfigLoaderService.MarkdownFileEntry entry : markdownFiles) {
                    AgentDefinition agent = parseAgentFromMarkdown(entry);
                    if (agent == null) {
                        if (entry.frontmatter().get("name") != null) {
                            String errorMsg = getParseError(entry.frontmatter());
                            failedFiles.add(new AgentDefinitionsResult.FailedFile(entry.filePath(), errorMsg));
                            log.debug("Failed to parse agent from {}: {}", entry.filePath(), errorMsg);
                        }
                        // else: silently skip files without agent frontmatter
                    } else {
                        customAgents.add(agent);
                    }
                }

                // Initialize memory snapshots for custom agents with memory enabled
                initializeAgentMemorySnapshots(customAgents);

                // Load plugin agents concurrently
                List<AgentDefinition> pluginAgents = pluginAgentsLoaderService
                    .loadPluginAgents().get();

                List<AgentDefinition> builtInAgents = getBuiltInAgents();

                List<AgentDefinition> allAgentsList = new ArrayList<>();
                allAgentsList.addAll(builtInAgents);
                allAgentsList.addAll(pluginAgents);
                allAgentsList.addAll(customAgents);

                List<AgentDefinition> activeAgents = getActiveAgentsFromList(allAgentsList);

                return new AgentDefinitionsResult(
                    activeAgents,
                    allAgentsList,
                    failedFiles.isEmpty() ? null : failedFiles,
                    null
                );

            } catch (Exception error) {
                String errorMessage = error.getMessage() != null ? error.getMessage() : error.toString();
                log.error("Error loading agent definitions: {}", errorMessage, error);

                List<AgentDefinition> builtIns = getBuiltInAgents();
                return new AgentDefinitionsResult(
                    builtIns,
                    builtIns,
                    List.of(new AgentDefinitionsResult.FailedFile("unknown", errorMessage)),
                    null
                );
            }
        });
    }

    /**
     * Clear the memoized agent definitions cache (e.g. after a hot-reload).
     * Translated from clearAgentDefinitionsCache().
     */
    @CacheEvict(value = "agentDefinitions", allEntries = true)
    public void clearAgentDefinitionsCache() {
        pluginAgentsLoaderService.clearPluginAgentCache();
        log.debug("Agent definitions cache cleared");
    }

    // -------------------------------------------------------------------------
    // Parsing helpers
    // -------------------------------------------------------------------------

    /**
     * Parse an AgentDefinition from a markdown file entry.
     * Translated from parseAgentFromMarkdown().
     */
    public AgentDefinition parseAgentFromMarkdown(
            MarkdownConfigLoaderService.MarkdownFileEntry entry) {
        try {
            Map<String, Object> fm = entry.frontmatter();

            Object nameRaw = fm.get("name");
            if (!(nameRaw instanceof String agentType) || agentType.isBlank()) return null;

            Object descRaw = fm.get("description");
            if (!(descRaw instanceof String whenToUse) || whenToUse.isBlank()) {
                log.debug("Agent file {} is missing required 'description' in frontmatter", entry.filePath());
                return null;
            }

            // Unescape YAML-escaped newlines
            whenToUse = whenToUse.replace("\\n", "\n");

            // Model
            String model = null;
            Object modelRaw = fm.get("model");
            if (modelRaw instanceof String modelStr && !modelStr.isBlank()) {
                String trimmed = modelStr.trim();
                model = "inherit".equalsIgnoreCase(trimmed) ? "inherit" : trimmed;
            }

            // Background flag
            Object backgroundRaw = fm.get("background");
            boolean background = "true".equals(backgroundRaw) || Boolean.TRUE.equals(backgroundRaw);

            // Memory scope
            AgentMemoryService.AgentMemoryScope memory = parseMemoryScope(fm, entry.filePath());

            // Isolation mode
            String isolation = parseIsolation(fm, entry.filePath());

            // Tools
            List<String> tools = markdownConfigLoaderService.parseAgentToolsFromFrontmatter(fm.get("tools"));

            // DisallowedTools
            List<String> disallowedTools = fm.containsKey("disallowedTools")
                ? markdownConfigLoaderService.parseAgentToolsFromFrontmatter(fm.get("disallowedTools"))
                : null;

            // Skills
            List<String> skills = markdownConfigLoaderService
                .parseSlashCommandToolsFromFrontmatter(fm.get("skills"));

            // MaxTurns
            Integer maxTurns = parsePositiveInt(fm.get("maxTurns"), entry.filePath(), "maxTurns");

            // PermissionMode
            String permissionMode = parsePermissionMode(fm, entry.filePath());

            // InitialPrompt
            String initialPrompt = null;
            Object ipRaw = fm.get("initialPrompt");
            if (ipRaw instanceof String ipStr && !ipStr.isBlank()) initialPrompt = ipStr;

            // Filename without extension
            String filename = Path.of(entry.filePath()).getFileName().toString()
                .replaceAll("\\.md$", "");

            String systemPrompt = entry.content().trim();
            final String finalMemory = memory != null ? memory.name().toLowerCase(Locale.ROOT) : null;
            final String finalAgentType = agentType;

            return AgentDefinition.builder()
                .agentType(agentType)
                .whenToUse(whenToUse)
                .source(entry.source())
                .filename(filename)
                .baseDir(entry.baseDir())
                .model(model)
                .tools(tools)
                .disallowedTools(disallowedTools)
                .skills(skills)
                .maxTurns(maxTurns)
                .permissionMode(permissionMode)
                .initialPrompt(initialPrompt)
                .background(background ? true : null)
                .memory(finalMemory)
                .isolation(isolation)
                .systemPromptSupplier(() -> buildSystemPrompt(systemPrompt, finalAgentType, finalMemory))
                .build();

        } catch (Exception e) {
            log.error("Error parsing agent from {}: {}", entry.filePath(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * Parse an AgentDefinition from a JSON definition object.
     * Translated from parseAgentFromJson().
     */
    public AgentDefinition parseAgentFromJson(
            String name,
            Map<String, Object> definition,
            String source) {
        try {
            String description = (String) definition.get("description");
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("Missing required 'description' field");
            }
            String prompt = (String) definition.get("prompt");
            if (prompt == null || prompt.isBlank()) {
                throw new IllegalArgumentException("Missing required 'prompt' field");
            }

            String model = (String) definition.get("model");
            if (model != null) {
                model = "inherit".equalsIgnoreCase(model.trim()) ? "inherit" : model.trim();
            }

            List<String> tools = markdownConfigLoaderService
                .parseAgentToolsFromFrontmatter(definition.get("tools"));
            List<String> disallowedTools = definition.containsKey("disallowedTools")
                ? markdownConfigLoaderService
                    .parseAgentToolsFromFrontmatter(definition.get("disallowedTools"))
                : null;

            Object maxTurnsRaw = definition.get("maxTurns");
            Integer maxTurns = (maxTurnsRaw instanceof Number n && n.intValue() > 0)
                ? n.intValue() : null;

            String memory = (String) definition.get("memory");
            Boolean background = definition.get("background") instanceof Boolean b ? b : null;
            String isolation = (String) definition.get("isolation");
            String permissionMode = (String) definition.get("permissionMode");

            final String finalName = name;
            final String finalMemory = memory;
            final String finalPrompt = prompt;

            return AgentDefinition.builder()
                .agentType(name)
                .whenToUse(description)
                .source(source != null ? source : "flagSettings")
                .model(model)
                .tools(tools)
                .disallowedTools(disallowedTools)
                .maxTurns(maxTurns)
                .permissionMode(permissionMode)
                .background(background)
                .memory(finalMemory)
                .isolation(isolation)
                .systemPromptSupplier(() -> buildSystemPrompt(finalPrompt, finalName, finalMemory))
                .build();

        } catch (Exception e) {
            log.error("Error parsing agent '{}' from JSON: {}", name, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Parse multiple agents from a JSON map.
     * Translated from parseAgentsFromJson().
     */
    @SuppressWarnings("unchecked")
    public List<AgentDefinition> parseAgentsFromJson(Object agentsJson, String source) {
        if (!(agentsJson instanceof Map<?, ?> raw)) return List.of();
        List<AgentDefinition> results = new ArrayList<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String name = entry.getKey().toString();
            if (!(entry.getValue() instanceof Map<?, ?> def)) continue;
            AgentDefinition agent = parseAgentFromJson(name, (Map<String, Object>) def, source);
            if (agent != null) results.add(agent);
        }
        return results;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String buildSystemPrompt(String base, String agentType,
                                     String memory) {
        if (memory != null) {
            // Memory prompt appended at runtime — placeholder shown here
            return base + "\n\n[agent-memory:" + agentType + ":" + memory + "]";
        }
        return base;
    }

    private List<AgentDefinition> filterBySource(List<AgentDefinition> agents, String source) {
        return agents.stream()
            .filter(a -> source.equals(a.getSource()))
            .collect(Collectors.toList());
    }

    private void initializeAgentMemorySnapshots(List<AgentDefinition> agents) {
        for (AgentDefinition agent : agents) {
            if (!"user".equals(agent.getMemory())) continue;
            try {
                AgentMemorySnapshotService.SnapshotCheckResult result =
                    agentMemorySnapshotService.checkAgentMemorySnapshot(
                        agent.getAgentType(),
                        AgentMemoryService.AgentMemoryScope.USER
                    ).get();

                switch (result.action()) {
                    case INITIALIZE -> {
                        log.debug("Initializing {} memory from project snapshot", agent.getAgentType());
                        agentMemorySnapshotService.initializeFromSnapshot(
                            agent.getAgentType(),
                            AgentMemoryService.AgentMemoryScope.USER,
                            result.snapshotTimestamp()
                        ).get();
                    }
                    case PROMPT_UPDATE -> {
                        try {
                            agent.setPendingSnapshotUpdate(Long.parseLong(result.snapshotTimestamp()));
                        } catch (NumberFormatException e) {
                            log.debug("Could not parse snapshotTimestamp '{}' as Long", result.snapshotTimestamp());
                        }
                        log.debug("Newer snapshot available for {} memory (snapshot: {})",
                            agent.getAgentType(), result.snapshotTimestamp());
                    }
                    default -> { /* nothing */ }
                }
            } catch (Exception e) {
                log.debug("Error checking memory snapshot for agent {}: {}",
                    agent.getAgentType(), e.getMessage());
            }
        }
    }

    private List<AgentDefinition> getBuiltInAgents() {
        return BuiltInAgents.getBuiltInAgents();
    }

    private String getParseError(Map<String, Object> frontmatter) {
        Object agentType = frontmatter.get("name");
        Object description = frontmatter.get("description");
        if (agentType == null || !(agentType instanceof String)) {
            return "Missing required \"name\" field in frontmatter";
        }
        if (description == null || !(description instanceof String)) {
            return "Missing required \"description\" field in frontmatter";
        }
        return "Unknown parsing error";
    }

    private AgentMemoryService.AgentMemoryScope parseMemoryScope(
            Map<String, Object> fm, String filePath) {
        Object raw = fm.get("memory");
        if (!(raw instanceof String s)) return null;
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "user"    -> AgentMemoryService.AgentMemoryScope.USER;
            case "project" -> AgentMemoryService.AgentMemoryScope.PROJECT;
            case "local"   -> AgentMemoryService.AgentMemoryScope.LOCAL;
            default -> {
                log.debug("Agent file {} has invalid memory value '{}'. " +
                    "Valid options: user, project, local", filePath, s);
                yield null;
            }
        };
    }

    private String parseIsolation(Map<String, Object> fm, String filePath) {
        Object raw = fm.get("isolation");
        if (!(raw instanceof String s)) return null;
        boolean isAnt = "ant".equals(System.getenv("USER_TYPE"));
        Set<String> valid = isAnt ? Set.of("worktree", "remote") : Set.of("worktree");
        String trimmed = s.trim().toLowerCase(Locale.ROOT);
        if (valid.contains(trimmed)) return trimmed;
        log.debug("Agent file {} has invalid isolation value '{}'. Valid options: {}",
            filePath, s, valid);
        return null;
    }

    private String parsePermissionMode(Map<String, Object> fm, String filePath) {
        Object raw = fm.get("permissionMode");
        if (!(raw instanceof String s)) return null;
        Set<String> valid = Set.of("default", "acceptEdits", "bypassPermissions", "bubble", "auto");
        if (valid.contains(s)) return s;
        log.debug("Agent file {} has invalid permissionMode '{}'. Valid options: {}",
            filePath, s, valid);
        return null;
    }

    private Integer parsePositiveInt(Object raw, String filePath, String field) {
        if (raw == null) return null;
        try {
            int v = (raw instanceof Number n) ? n.intValue() : Integer.parseInt(raw.toString());
            if (v > 0) return v;
        } catch (NumberFormatException ignored) {}
        log.debug("Agent file {} has invalid {} '{}'. Must be a positive integer.", filePath, field, raw);
        return null;
    }

}
