package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.Command;
import com.anthropic.claudecode.model.PluginTypes;
import com.anthropic.claudecode.util.FrontmatterParser;
import com.anthropic.claudecode.util.MarkdownConfigLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plugin commands loader service.
 * Translated from src/utils/plugins/loadPluginCommands.ts
 *
 * Loads slash commands from installed plugins.
 */
@Slf4j
@Service
public class PluginCommandsLoaderService {



    private final MarkdownConfigLoader markdownConfigLoader;
    private final Map<String, List<Command>> commandCache = new ConcurrentHashMap<>();

    @Autowired
    public PluginCommandsLoaderService(MarkdownConfigLoader markdownConfigLoader) {
        this.markdownConfigLoader = markdownConfigLoader;
    }

    /**
     * Load commands from plugins.
     * Translated from loadPluginCommands() in loadPluginCommands.ts
     */
    public List<Command> loadPluginCommands(List<PluginTypes.LoadedPlugin> plugins) {
        List<Command> commands = new ArrayList<>();

        for (PluginTypes.LoadedPlugin plugin : plugins) {
            if (!Boolean.TRUE.equals(plugin.getEnabled())) continue;
            if (plugin.getCommandsPath() == null) continue;

            String cacheKey = plugin.getName() + ":" + plugin.getCommandsPath();
            List<Command> cached = commandCache.get(cacheKey);
            if (cached != null) {
                commands.addAll(cached);
                continue;
            }

            List<Command> pluginCommands = loadCommandsFromDir(
                plugin.getCommandsPath(),
                plugin.getName()
            );
            commandCache.put(cacheKey, pluginCommands);
            commands.addAll(pluginCommands);
        }

        return commands;
    }

    private List<Command> loadCommandsFromDir(String commandsDir, String pluginName) {
        List<Command> commands = new ArrayList<>();
        File dir = new File(commandsDir);
        if (!dir.isDirectory()) return commands;

        File[] mdFiles = dir.listFiles(f -> f.isFile() && f.getName().endsWith(".md"));
        if (mdFiles == null) return commands;

        for (File mdFile : mdFiles) {
            try {
                String content = new String(java.nio.file.Files.readAllBytes(mdFile.toPath()));
                FrontmatterParser.FrontmatterData frontmatter = FrontmatterParser.parseFrontmatter(content);
                if (frontmatter == null) continue;

                String commandName = mdFile.getName().replace(".md", "");
                Command command = Command.builder()
                    .name(commandName)
                    .description(frontmatter.getDescription())
                    .type(Command.CommandType.PROMPT)
                    .source("plugin:" + pluginName)
                    .userInvocable(true)
                    .build();

                commands.add(command);
            } catch (Exception e) {
                log.debug("Could not load command from {}: {}", mdFile.getName(), e.getMessage());
            }
        }

        return commands;
    }

    /**
     * Clear the command cache.
     */
    public void clearPluginCommandCache() {
        commandCache.clear();
    }
}
