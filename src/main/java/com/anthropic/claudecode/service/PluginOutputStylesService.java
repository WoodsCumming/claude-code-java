package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.OutputStylesConstants.OutputStyleConfig;
import com.anthropic.claudecode.model.PluginTypes.LoadedPlugin;
import com.anthropic.claudecode.util.FrontmatterParser;
import com.anthropic.claudecode.util.MarkdownConfigLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loads markdown output-style files from a .claude/output-styles directory
 * tree and caches the result.
 * Translated from src/outputStyles/loadOutputStylesDir.ts
 *
 * Structure expected on disk:
 *   - Project  .claude/output-styles/*.md  → project styles
 *   - User    ~/.claude/output-styles/*.md  → user styles
 *
 * Each filename becomes the style name; frontmatter provides name and
 * description overrides; the file body becomes the style prompt.
 *
 * Also used by src/utils/plugins/loadPluginOutputStyles.ts logic to load
 * output styles from enabled plugin directories (hence also covers the
 * plugin-output-styles concern).
 */
@Slf4j
@Service
public class PluginOutputStylesService {



    private final PluginLoaderService pluginLoaderService;

    /** Cache for getOutputStyleDirStyles(), keyed by cwd. */
    private volatile List<OutputStyleConfig> cachedDirStyles;
    private volatile String cachedDirStylesCwd;

    /** Cache for plugin output styles (source == "plugin"). */
    private volatile List<OutputStyleConfig> cachedPluginStyles;

    @Autowired
    public PluginOutputStylesService(PluginLoaderService pluginLoaderService) {
        this.pluginLoaderService = pluginLoaderService;
    }

    // -------------------------------------------------------------------------
    // getOutputStyleDirStyles (memoized by cwd)
    // -------------------------------------------------------------------------

    /**
     * Load output styles from .claude/output-styles directories throughout the
     * project tree and from ~/.claude/output-styles.
     *
     * Memoized: repeated calls with the same cwd return the cached result.
     *
     * Corresponds to TypeScript: const getOutputStyleDirStyles = memoize(async (cwd) => ...)
     *
     * @param cwd current working directory for project directory traversal
     */
    public synchronized List<OutputStyleConfig> getOutputStyleDirStyles(String cwd) {
        if (cachedDirStyles != null && cwd != null && cwd.equals(cachedDirStylesCwd)) {
            return cachedDirStyles;
        }
        try {
            List<MarkdownConfigLoader.MarkdownFile> markdownFiles =
                    MarkdownConfigLoader.loadMarkdownFilesForSubdir("output-styles", cwd);

            List<OutputStyleConfig> styles = new ArrayList<>();
            for (MarkdownConfigLoader.MarkdownFile mf : markdownFiles) {
                try {
                    String fileName = new File(mf.getFilePath()).getName();
                    String styleName = fileName.replaceFirst("\\.md$", "");

                    String name = mf.getFrontmatter().getOrDefault("name", styleName).toString();

                    Object rawDesc = mf.getFrontmatter().get("description");
                    String description = FrontmatterParser.coerceDescriptionToString(rawDesc, styleName);
                    if (description == null) {
                        description = MarkdownConfigLoader.extractDescriptionFromMarkdown(
                                mf.getContent(), "Custom " + styleName + " output style");
                    }

                    // Parse keep-coding-instructions (boolean or "true"/"false" string)
                    Object kci = mf.getFrontmatter().get("keep-coding-instructions");
                    Boolean keepCodingInstructions = null;
                    if (Boolean.TRUE.equals(kci) || "true".equals(kci)) {
                        keepCodingInstructions = true;
                    } else if (Boolean.FALSE.equals(kci) || "false".equals(kci)) {
                        keepCodingInstructions = false;
                    }

                    // Warn if force-for-plugin is set on a non-plugin style
                    if (mf.getFrontmatter().containsKey("force-for-plugin")) {
                        log.warn("Output style \"{}\" has force-for-plugin set, but this option " +
                                 "only applies to plugin output styles. Ignoring.", name);
                    }

                    styles.add(new OutputStyleConfig(
                            name,
                            description,
                            mf.getContent().strip(),
                            mf.getSource(),
                            keepCodingInstructions
                    ));
                } catch (Exception e) {
                    log.error("Error loading output style from {}: {}", mf.getFilePath(), e.getMessage(), e);
                }
            }

            cachedDirStyles = Collections.unmodifiableList(styles);
            cachedDirStylesCwd = cwd;
            return cachedDirStyles;
        } catch (Exception e) {
            log.error("Error loading output-style directory styles: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // -------------------------------------------------------------------------
    // loadPluginOutputStyles (memoized globally)
    // -------------------------------------------------------------------------

    /**
     * Load output styles from all enabled plugins.
     * Memoized until clearPluginOutputStyleCache() is called.
     *
     * Corresponds to TypeScript: loadPluginOutputStyles() in
     * src/utils/plugins/loadPluginOutputStyles.ts
     */
    public synchronized List<OutputStyleConfig> loadPluginOutputStyles() {
        if (cachedPluginStyles != null) return cachedPluginStyles;

        List<OutputStyleConfig> styles = new ArrayList<>();
        List<LoadedPlugin> plugins = pluginLoaderService.loadAllPluginsCacheOnly();

        for (LoadedPlugin plugin : plugins) {
            if (!Boolean.TRUE.equals(plugin.getEnabled())) continue;

            // Collect all output-style paths from the plugin
            List<String> paths = new ArrayList<>();
            if (plugin.getOutputStylesPath() != null) {
                paths.add(plugin.getOutputStylesPath());
            }
            if (plugin.getOutputStylesPaths() != null) {
                paths.addAll(plugin.getOutputStylesPaths());
            }

            for (String path : paths) {
                try {
                    styles.addAll(loadStylesFromDirectory(path, plugin.getName()));
                } catch (Exception e) {
                    log.debug("Could not load output styles from plugin {}: {}",
                              plugin.getName(), e.getMessage());
                }
            }
        }

        cachedPluginStyles = Collections.unmodifiableList(styles);
        return cachedPluginStyles;
    }

    // -------------------------------------------------------------------------
    // clearOutputStyleCaches
    // -------------------------------------------------------------------------

    /**
     * Clear all memoized caches.
     * Corresponds to TypeScript: function clearOutputStyleCaches()
     */
    public synchronized void clearOutputStyleCaches() {
        cachedDirStyles = null;
        cachedDirStylesCwd = null;
        clearPluginOutputStyleCache();
    }

    /**
     * Clear only the plugin output style cache.
     * Corresponds to TypeScript: function clearPluginOutputStyleCache()
     */
    public synchronized void clearPluginOutputStyleCache() {
        cachedPluginStyles = null;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private List<OutputStyleConfig> loadStylesFromDirectory(String dir, String pluginName) {
        List<OutputStyleConfig> styles = new ArrayList<>();
        File directory = new File(dir);
        if (!directory.isDirectory()) return styles;

        File[] files = directory.listFiles((d, name) -> name.endsWith(".md"));
        if (files == null) return styles;

        for (File file : files) {
            try {
                String content = Files.readString(file.toPath());
                FrontmatterParser.ParsedFrontmatter parsed = FrontmatterParser.parseFrontmatterWithBody(content);
                String body = parsed.body();

                String name = file.getName().replace(".md", "");
                Object rawDesc = parsed.frontmatter().get("description");
                String description = FrontmatterParser.coerceDescriptionToString(rawDesc, name);
                if (description == null) {
                    description = MarkdownConfigLoader.extractDescriptionFromMarkdown(body, name);
                }

                // Detect forceForPlugin flag on plugin styles
                Object ffp = parsed.frontmatter().get("force-for-plugin");
                Boolean forceForPlugin = Boolean.TRUE.equals(ffp) || "true".equals(ffp) ? true : null;

                styles.add(new OutputStyleConfig(
                        name,
                        description,
                        body.strip(),
                        "plugin",
                        null,
                        forceForPlugin
                ));
            } catch (Exception e) {
                log.debug("Could not load output style {}: {}", file.getName(), e.getMessage());
            }
        }
        return styles;
    }
}
