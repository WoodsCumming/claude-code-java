package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Command registry, routing, and merge service.
 *
 * Translated from:
 *   - src/commands.ts               — registry, routing, filtering, lookup
 *   - src/hooks/useMergedCommands.ts — REPL-level hook that merges initial and
 *                                      MCP commands with deduplication
 *
 * <h3>TypeScript → Java mapping for useMergedCommands</h3>
 * <pre>
 * useMergedCommands(initialCommands, mcpCommands)
 *     → mergeCommands(initialCommands, mcpCommands)
 *
 * uniqBy([...initial, ...mcp], 'name')
 *     → LinkedHashMap dedup keyed by command name (first-seen wins for initial,
 *       MCP entries fill the remaining slots)
 * </pre>
 *
 * In TypeScript, {@code useMergedCommands} is a {@code useMemo} hook — it only
 * re-computes when either input list changes. The Java equivalent
 * {@link #mergeCommands} is a pure stateless method; callers invoke it when
 * their command lists change.
 *
 * Manages the full set of available commands (built-in, skills, plugins, workflows),
 * with availability and isEnabled filtering, caching, and command lookup utilities.
 *
 * Note: The heavy dynamic loading (skills dirs, plugins, workflows) is modelled as
 * extension points via CommandLoader SPI; the core registry logic is translated directly.
 */
@Slf4j
@Service
public class CommandQueueService {



    // =========================================================================
    // Command availability / type sealed hierarchy
    // =========================================================================

    /** Command availability requirements (who can use the command). */
    public enum CommandAvailability {
        CLAUDE_AI,
        CONSOLE
    }

    /** Command source for display annotation. */
    public enum CommandSource {
        BUILTIN,
        USER,
        PROJECT,
        LOCAL,
        PLUGIN,
        BUNDLED,
        MCP
    }

    /** Where the command was loaded from. */
    public enum CommandLoadedFrom {
        BUNDLED,
        SKILLS,
        COMMANDS_DEPRECATED,
        PLUGIN,
        MCP
    }

    /** Whether a local command is safe to execute over the Remote Control bridge. */
    public static final Set<String> BRIDGE_SAFE_COMMAND_NAMES = Set.of(
        "compact", "clear", "cost", "summary", "release-notes", "files"
    );

    /** Commands safe for remote mode (--remote). */
    public static final Set<String> REMOTE_SAFE_COMMAND_NAMES = Set.of(
        "session", "exit", "clear", "help", "theme", "color", "vim",
        "cost", "usage", "copy", "btw", "feedback", "plan", "keybindings",
        "statusline", "stickers", "mobile"
    );

    // =========================================================================
    // Command registry
    // =========================================================================

    private final List<Command> builtinCommands = new CopyOnWriteArrayList<>();
    private final List<CommandLoader> commandLoaders = new CopyOnWriteArrayList<>();

    // Memoized per-cwd all-commands cache
    private final ConcurrentHashMap<String, CompletableFuture<List<Command>>> commandCache
        = new ConcurrentHashMap<>();

    @Autowired
    public CommandQueueService(List<CommandLoader> commandLoaders) {
        this.commandLoaders.addAll(commandLoaders);
    }

    public CommandQueueService() {
        // no-arg for tests
    }

    /**
     * Register a built-in command.
     */
    public void registerCommand(Command command) {
        builtinCommands.add(command);
    }

    /**
     * Get all built-in command names (including aliases).
     * Translated from builtInCommandNames() in commands.ts
     */
    public Set<String> getBuiltInCommandNames() {
        Set<String> names = new HashSet<>();
        for (Command cmd : builtinCommands) {
            names.add(cmd.getName());
            if (cmd.getAliases() != null) names.addAll(cmd.getAliases());
        }
        return names;
    }

    /**
     * Get all available commands for the given working directory.
     * Includes built-ins, skills, plugins, and workflows.
     * Filters by availability and isEnabled checks (not memoized so auth changes apply).
     * Translated from getCommands() in commands.ts
     */
    public CompletableFuture<List<Command>> getCommands(String cwd) {
        return loadAllCommands(cwd).thenApply(allCommands -> {
            List<Command> base = allCommands.stream()
                .filter(cmd -> meetsAvailabilityRequirement(cmd) && isCommandEnabled(cmd))
                .collect(Collectors.toList());
            return base;
        });
    }

    /**
     * Load and cache all commands for a cwd (expensive disk I/O, memoized).
     * Translated from loadAllCommands() memoized function in commands.ts
     */
    public CompletableFuture<List<Command>> loadAllCommands(String cwd) {
        return commandCache.computeIfAbsent(cwd, key -> CompletableFuture.supplyAsync(() -> {
            List<Command> all = new ArrayList<>(builtinCommands);

            // Load from registered loaders (skills, plugins, workflows)
            List<CompletableFuture<List<Command>>> futures = commandLoaders.stream()
                .map(loader -> loader.loadCommands(cwd)
                    .exceptionally(err -> {
                        log.error("Command loader failed: {}", err.getMessage());
                        return List.of();
                    }))
                .collect(Collectors.toList());

            for (CompletableFuture<List<Command>> future : futures) {
                try {
                    all.addAll(future.get());
                } catch (Exception e) {
                    log.error("Failed to load commands from loader: {}", e.getMessage());
                }
            }
            return all;
        }));
    }

    /**
     * Clear memoized command caches (after dynamic skills are added, etc.).
     * Translated from clearCommandMemoizationCaches() in commands.ts
     */
    public void clearCommandMemoizationCaches() {
        commandCache.clear();
    }

    /**
     * Full cache clear including plugin and skill caches.
     * Translated from clearCommandsCache() in commands.ts
     */
    public void clearCommandsCache() {
        clearCommandMemoizationCaches();
        commandLoaders.forEach(CommandLoader::clearCache);
    }

    // =========================================================================
    // Availability and enabled filtering
    // =========================================================================

    /**
     * Whether a command meets its declared availability requirement.
     * Commands without availability are treated as universal.
     * Translated from meetsAvailabilityRequirement() in commands.ts
     */
    public boolean meetsAvailabilityRequirement(Command cmd) {
        if (cmd.getAvailability() == null || cmd.getAvailability().isEmpty()) return true;
        for (CommandAvailability a : cmd.getAvailability()) {
            switch (a) {
                case CLAUDE_AI -> {
                    if (isClaudeAISubscriber()) return true;
                }
                case CONSOLE -> {
                    if (!isClaudeAISubscriber() && !isUsing3PServices() && isFirstPartyAnthropicBaseUrl())
                        return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether a command is currently enabled (feature flags, settings).
     * Translated from isCommandEnabled() in commands.ts
     */
    public boolean isCommandEnabled(Command cmd) {
        if (cmd.getEnabledCheck() != null) {
            try {
                return cmd.getEnabledCheck().test(cmd);
            } catch (Exception e) {
                log.debug("isCommandEnabled check failed for {}: {}", cmd.getName(), e.getMessage());
                return false;
            }
        }
        return true;
    }

    // =========================================================================
    // Command lookup
    // =========================================================================

    /**
     * Find a command by name or alias.
     * Translated from findCommand() in commands.ts
     */
    public Optional<Command> findCommand(String name, List<Command> commands) {
        return commands.stream()
            .filter(cmd -> cmd.getName().equals(name)
                || getCommandName(cmd).equals(name)
                || (cmd.getAliases() != null && cmd.getAliases().contains(name)))
            .findFirst();
    }

    /**
     * Whether a command with the given name exists.
     * Translated from hasCommand() in commands.ts
     */
    public boolean hasCommand(String name, List<Command> commands) {
        return findCommand(name, commands).isPresent();
    }

    /**
     * Get a command by name, throwing if not found.
     * Translated from getCommand() in commands.ts
     */
    public Command getCommand(String name, List<Command> commands) {
        return findCommand(name, commands)
            .orElseThrow(() -> {
                String available = commands.stream()
                    .map(c -> {
                        String cName = getCommandName(c);
                        return c.getAliases() != null && !c.getAliases().isEmpty()
                            ? cName + " (aliases: " + String.join(", ", c.getAliases()) + ")"
                            : cName;
                    })
                    .sorted()
                    .collect(Collectors.joining(", "));
                return new NoSuchElementException(
                    "Command " + name + " not found. Available commands: " + available);
            });
    }

    /**
     * Compute the effective display name for a command.
     * Translated from getCommandName() in commands.ts
     */
    public String getCommandName(Command cmd) {
        if (cmd.getName() != null) return cmd.getName();
        return "";
    }

    /**
     * Format a command's description with its source annotation for user-facing UI.
     * For model-facing prompts, use cmd.getDescription() directly.
     * Translated from formatDescriptionWithSource() in commands.ts
     */
    public String formatDescriptionWithSource(Command cmd) {
        if (!"prompt".equals(cmd.getType())) {
            return cmd.getDescription();
        }
        if ("workflow".equals(cmd.getKind())) {
            return cmd.getDescription() + " (workflow)";
        }
        if (CommandSource.PLUGIN == cmd.getSource()) {
            String pluginName = cmd.getPluginName();
            if (pluginName != null && !pluginName.isBlank()) {
                return "(" + pluginName + ") " + cmd.getDescription();
            }
            return cmd.getDescription() + " (plugin)";
        }
        if (CommandSource.BUILTIN == cmd.getSource() || CommandSource.MCP == cmd.getSource()) {
            return cmd.getDescription();
        }
        if (CommandSource.BUNDLED == cmd.getSource()) {
            return cmd.getDescription() + " (bundled)";
        }
        return cmd.getDescription() + " (" + getSettingSourceName(cmd.getSource()) + ")";
    }

    /**
     * Whether a command is safe to execute over the Remote Control bridge.
     * Translated from isBridgeSafeCommand() in commands.ts
     */
    public boolean isBridgeSafeCommand(Command cmd) {
        if ("local-jsx".equals(cmd.getType())) return false;
        if ("prompt".equals(cmd.getType())) return true;
        return BRIDGE_SAFE_COMMAND_NAMES.contains(cmd.getName());
    }

    /**
     * Filter commands to only those safe for remote mode.
     * Translated from filterCommandsForRemoteMode() in commands.ts
     */
    public List<Command> filterCommandsForRemoteMode(List<Command> commands) {
        return commands.stream()
            .filter(cmd -> REMOTE_SAFE_COMMAND_NAMES.contains(cmd.getName()))
            .collect(Collectors.toList());
    }

    // =========================================================================
    // useMergedCommands() equivalent
    // =========================================================================

    /**
     * Merge initial commands with MCP commands, deduplicating by name.
     *
     * Mirrors {@code useMergedCommands(initialCommands, mcpCommands)} in
     * src/hooks/useMergedCommands.ts.
     *
     * Rules (matching TypeScript exactly):
     * <ul>
     *   <li>If {@code mcpCommands} is empty, return {@code initialCommands} unchanged.</li>
     *   <li>Otherwise concatenate {@code [...initialCommands, ...mcpCommands]} and
     *       deduplicate by {@code name} — first-seen wins (initialCommands entries
     *       take precedence over MCP entries with the same name).</li>
     * </ul>
     *
     * In TypeScript this is implemented with {@code lodash.uniqBy}:
     * <pre>
     *   uniqBy([...initialCommands, ...mcpCommands], 'name')
     * </pre>
     *
     * @param initialCommands built-in + skill commands (higher precedence)
     * @param mcpCommands     commands discovered from MCP servers
     * @return merged, deduplicated command list
     */
    public List<Command> mergeCommands(
            List<Command> initialCommands,
            List<Command> mcpCommands) {

        if (mcpCommands == null || mcpCommands.isEmpty()) {
            return initialCommands != null ? initialCommands : List.of();
        }

        // uniqBy([...initial, ...mcp], 'name') — first-seen wins
        Map<String, Command> seen = new LinkedHashMap<>();
        List<Command> combined = new ArrayList<>();
        if (initialCommands != null) combined.addAll(initialCommands);
        combined.addAll(mcpCommands);

        for (Command cmd : combined) {
            seen.putIfAbsent(cmd.getName(), cmd);
        }

        return Collections.unmodifiableList(new ArrayList<>(seen.values()));
    }

    /**
     * Get MCP skill commands (prompt-type, model-invocable, loaded from MCP).
     * Translated from getMcpSkillCommands() in commands.ts
     */
    public List<Command> getMcpSkillCommands(List<Command> mcpCommands) {
        return mcpCommands.stream()
            .filter(cmd -> "prompt".equals(cmd.getType())
                && CommandLoadedFrom.MCP == cmd.getLoadedFrom()
                && !cmd.isDisableModelInvocation())
            .collect(Collectors.toList());
    }

    /**
     * Get skill tool commands: all prompt-based commands the model can invoke.
     * Translated from getSkillToolCommands() in commands.ts
     */
    public CompletableFuture<List<Command>> getSkillToolCommands(String cwd) {
        return getCommands(cwd).thenApply(all -> all.stream()
            .filter(cmd -> "prompt".equals(cmd.getType())
                && !cmd.isDisableModelInvocation()
                && CommandSource.BUILTIN != cmd.getSource()
                && (CommandLoadedFrom.BUNDLED == cmd.getLoadedFrom()
                    || CommandLoadedFrom.SKILLS == cmd.getLoadedFrom()
                    || CommandLoadedFrom.COMMANDS_DEPRECATED == cmd.getLoadedFrom()
                    || cmd.isHasUserSpecifiedDescription()
                    || cmd.getWhenToUse() != null))
            .collect(Collectors.toList()));
    }

    /**
     * Get slash-command tool skills (skills for the slash-command tool).
     * Translated from getSlashCommandToolSkills() in commands.ts
     */
    public CompletableFuture<List<Command>> getSlashCommandToolSkills(String cwd) {
        return getCommands(cwd)
            .exceptionally(err -> {
                log.error("Returning empty skills array due to load failure: {}", err.getMessage());
                return List.of();
            })
            .thenApply(all -> all.stream()
                .filter(cmd -> "prompt".equals(cmd.getType())
                    && CommandSource.BUILTIN != cmd.getSource()
                    && (cmd.isHasUserSpecifiedDescription() || cmd.getWhenToUse() != null)
                    && (CommandLoadedFrom.SKILLS == cmd.getLoadedFrom()
                        || CommandLoadedFrom.PLUGIN == cmd.getLoadedFrom()
                        || CommandLoadedFrom.BUNDLED == cmd.getLoadedFrom()
                        || cmd.isDisableModelInvocation()))
                .collect(Collectors.toList()));
    }

    // =========================================================================
    // Private helpers (auth/provider checks — delegate to auth services)
    // =========================================================================

    protected boolean isClaudeAISubscriber() {
        // Delegate to AuthService in real implementation
        return false;
    }

    protected boolean isUsing3PServices() {
        return false;
    }

    protected boolean isFirstPartyAnthropicBaseUrl() {
        return true;
    }

    private String getSettingSourceName(CommandSource source) {
        if (source == null) return "unknown";
        return source.name().toLowerCase();
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /**
     * Represents a Claude Code command (built-in, skill, plugin, or workflow).
     * Translated from Command type in types/command.ts
     */
    @Data
    @lombok.Builder
    
    public static class Command {
        /** Unique command name (used for slash command invocation). */
        private String name;
        /** Optional alternate names. */
        private List<String> aliases;
        /** Short description shown in help/typeahead. */
        private String description;
        /** Command type: "local", "local-jsx", or "prompt". */
        private String type;
        /** For prompt commands: how the skill was loaded. */
        private CommandLoadedFrom loadedFrom;
        /** Where this command's configuration comes from. */
        private CommandSource source;
        /** Availability requirements (null = universal). */
        private List<CommandAvailability> availability;
        /** Optional sub-kind (e.g. "workflow"). */
        private String kind;
        /** Plugin display name (for plugin-sourced commands). */
        private String pluginName;
        /** Whether model invocation is disabled. */
        private boolean disableModelInvocation;
        /** Whether a user-specified description is present. */
        private boolean hasUserSpecifiedDescription;
        /** Condition text describing when to use this skill. */
        private String whenToUse;
        /** Custom isEnabled predicate (checked on each getCommands() call). */
        private transient Predicate<Command> enabledCheck;

        public String getName() { return name; }
        public void setName(String v) { name = v; }
        public List<String> getAliases() { return aliases; }
        public void setAliases(List<String> v) { aliases = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
        public String getType() { return type; }
        public void setType(String v) { type = v; }
        public CommandLoadedFrom getLoadedFrom() { return loadedFrom; }
        public void setLoadedFrom(CommandLoadedFrom v) { loadedFrom = v; }
        public CommandSource getSource() { return source; }
        public void setSource(CommandSource v) { source = v; }
        public List<CommandAvailability> getAvailability() { return availability; }
        public void setAvailability(List<CommandAvailability> v) { availability = v; }
        public String getKind() { return kind; }
        public void setKind(String v) { kind = v; }
        public String getPluginName() { return pluginName; }
        public void setPluginName(String v) { pluginName = v; }
        public boolean isDisableModelInvocation() { return disableModelInvocation; }
        public void setDisableModelInvocation(boolean v) { disableModelInvocation = v; }
        public boolean isHasUserSpecifiedDescription() { return hasUserSpecifiedDescription; }
        public void setHasUserSpecifiedDescription(boolean v) { hasUserSpecifiedDescription = v; }
        public String getWhenToUse() { return whenToUse; }
        public void setWhenToUse(String v) { whenToUse = v; }
        public Predicate<Command> getEnabledCheck() { return enabledCheck; }
        public void setEnabledCheck(Predicate<Command> v) { enabledCheck = v; }
    }

    /**
     * SPI for loading commands from external sources (skills, plugins, workflows).
     */
    public interface CommandLoader {
        CompletableFuture<List<Command>> loadCommands(String cwd);
        default void clearCache() {}
    }
}
