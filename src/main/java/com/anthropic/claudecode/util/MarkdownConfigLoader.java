package com.anthropic.claudecode.util;

import com.anthropic.claudecode.model.SettingSource;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Markdown configuration loader.
 * Translated from src/utils/markdownConfigLoader.ts
 *
 * Loads markdown files from Claude configuration directories.
 */
@Slf4j
@Component
public class MarkdownConfigLoader {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MarkdownConfigLoader.class);


    public static final List<String> CLAUDE_CONFIG_DIRECTORIES = List.of(
        "commands", "agents", "output-styles", "skills", "workflows"
    );

    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class MarkdownFile {
        private String filePath;
        private String baseDir;
        private FrontmatterParser.FrontmatterData frontmatter;
        private String content;
        private String source;

        public String getFilePath() { return filePath; }
        public void setFilePath(String v) { filePath = v; }
        public String getBaseDir() { return baseDir; }
        public void setBaseDir(String v) { baseDir = v; }
        public String getContent() { return content; }
        public void setContent(String v) { content = v; }
        public String getSource() { return source; }
        public void setSource(String v) { source = v; }
        public FrontmatterParser.FrontmatterData getFrontmatter() { return frontmatter; }
        public void setFrontmatter(FrontmatterParser.FrontmatterData v) { frontmatter = v; }
    }

    /**
     * Extract a description from markdown content.
     * Translated from extractDescriptionFromMarkdown() in markdownConfigLoader.ts
     */
    public static String extractDescriptionFromMarkdown(String content, String defaultDescription) {
        if (content == null) return defaultDescription;

        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                // Strip markdown header prefix
                if (trimmed.startsWith("#")) {
                    trimmed = trimmed.replaceFirst("^#+\\s*", "");
                }
                if (!trimmed.isEmpty()) return trimmed;
            }
        }

        return defaultDescription != null ? defaultDescription : "Custom item";
    }

    /**
     * Load markdown files from a subdirectory.
     * Translated from loadMarkdownFilesForSubdir() in markdownConfigLoader.ts
     */
    public static List<MarkdownFile> loadMarkdownFilesForSubdir(String subdir, String cwd) {
        List<MarkdownFile> files = new ArrayList<>();

        // Load from user directory
        String userDir = EnvUtils.getClaudeConfigHomeDir() + "/" + subdir;
        loadFromDir(files, userDir, SettingSource.USER_SETTINGS.getValue());

        // Load from project directory
        if (cwd != null) {
            String projectDir = cwd + "/.claude/" + subdir;
            loadFromDir(files, projectDir, SettingSource.PROJECT_SETTINGS.getValue());
        }

        return files;
    }

    private static void loadFromDir(List<MarkdownFile> files, String dir, String source) {
        File directory = new File(dir);
        if (!directory.isDirectory()) return;

        File[] mdFiles = directory.listFiles((d, name) -> name.endsWith(".md"));
        if (mdFiles == null) return;

        Arrays.sort(mdFiles, Comparator.comparing(File::getName));

        for (File file : mdFiles) {
            try {
                String content = Files.readString(file.toPath());
                FrontmatterParser.FrontmatterData frontmatter = FrontmatterParser.parseFrontmatter(content);
                String bodyContent = FrontmatterParser.removeFrontmatter(content);

                files.add(new MarkdownFile(
                    file.getAbsolutePath(),
                    directory.getAbsolutePath(),
                    frontmatter,
                    bodyContent,
                    source
                ));
            } catch (Exception e) {
                log.debug("Could not load markdown file {}: {}", file.getName(), e.getMessage());
            }
        }
    }

    private MarkdownConfigLoader() {}
}
