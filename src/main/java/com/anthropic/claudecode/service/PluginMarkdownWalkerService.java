package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.*;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * Plugin markdown walker service.
 * Translated from src/utils/plugins/walkPluginMarkdown.ts
 *
 * Recursively walks plugin directories for markdown files.
 */
@Slf4j
@Service
public class PluginMarkdownWalkerService {



    /**
     * Walk a plugin directory and invoke callback for each .md file.
     * Translated from walkPluginMarkdown() in walkPluginMarkdown.ts
     */
    public void walkPluginMarkdown(
            String pluginRoot,
            boolean stopAtSkillDir,
            BiConsumer<String, List<String>> onFile) {

        walkDir(new File(pluginRoot), pluginRoot, new ArrayList<>(), stopAtSkillDir, onFile);
    }

    private void walkDir(
            File dir,
            String root,
            List<String> namespace,
            boolean stopAtSkillDir,
            BiConsumer<String, List<String>> onFile) {

        if (!dir.isDirectory()) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        // Check if this directory contains skill.md
        boolean hasSkillMd = Arrays.stream(files)
            .anyMatch(f -> f.getName().equalsIgnoreCase("skill.md"));

        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".md")) {
                onFile.accept(file.getAbsolutePath(), new ArrayList<>(namespace));
            } else if (file.isDirectory() && !(stopAtSkillDir && hasSkillMd)) {
                List<String> childNamespace = new ArrayList<>(namespace);
                childNamespace.add(file.getName());
                walkDir(file, root, childNamespace, stopAtSkillDir, onFile);
            }
        }
    }
}
