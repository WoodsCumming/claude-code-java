package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.AgentDefinition;
import com.anthropic.claudecode.model.PluginTypes;
import com.anthropic.claudecode.util.FrontmatterParser;
import com.anthropic.claudecode.util.MarkdownConfigLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Plugin agents loader service.
 * Translated from src/utils/plugins/loadPluginAgents.ts
 *
 * Loads agent definitions from installed plugins.
 */
@Slf4j
@Service
public class PluginAgentsLoaderService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PluginAgentsLoaderService.class);


    private final PluginLoaderService pluginLoaderService;
    private volatile List<AgentDefinition> cachedAgents;

    @Autowired
    public PluginAgentsLoaderService(PluginLoaderService pluginLoaderService) {
        this.pluginLoaderService = pluginLoaderService;
    }

    /**
     * Load agents from all plugins asynchronously.
     * Translated from loadPluginAgents() in loadPluginAgents.ts
     */
    public CompletableFuture<List<AgentDefinition>> loadPluginAgents() {
        if (cachedAgents != null) return CompletableFuture.completedFuture(cachedAgents);

        return CompletableFuture.supplyAsync(() -> {
            List<AgentDefinition> agents = new ArrayList<>();
            List<PluginTypes.LoadedPlugin> plugins = pluginLoaderService.loadAllPluginsCacheOnly();

            for (PluginTypes.LoadedPlugin plugin : plugins) {
                if (!Boolean.TRUE.equals(plugin.getEnabled())) continue;
                if (plugin.getAgentsPath() == null) continue;

                try {
                    agents.addAll(loadAgentsFromDirectory(plugin.getAgentsPath(), plugin.getName()));
                } catch (Exception e) {
                    log.debug("Could not load agents from plugin {}: {}", plugin.getName(), e.getMessage());
                }
            }

            cachedAgents = agents;
            return agents;
        });
    }

    private List<AgentDefinition> loadAgentsFromDirectory(String dir, String pluginName) {
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
                final String finalBody = body;

                agents.add(AgentDefinition.builder()
                    .agentType(name)
                    .whenToUse(description)
                    .source("plugin:" + pluginName)
                    .model(frontmatter.getModel())
                    .tools(frontmatter.getAllowedTools())
                    .systemPromptSupplier(() -> finalBody)
                    .build());

            } catch (Exception e) {
                log.debug("Could not load agent {}: {}", file.getName(), e.getMessage());
            }
        }

        return agents;
    }

    /**
     * Clear the agent cache.
     */
    public void clearPluginAgentCache() {
        cachedAgents = null;
    }
}
