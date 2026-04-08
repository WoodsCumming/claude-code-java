package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.OutputStyleConfig;
import com.anthropic.claudecode.util.EnvUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Output style management service.
 * Translated from src/commands/output-style/index.ts and
 * src/outputStyles/loadOutputStylesDir.ts
 *
 * The /output-style command is deprecated (users should use /config to
 * change the output style instead). This service retains the underlying
 * logic for loading output style configurations from markdown files.
 */
@Slf4j
@Service
public class OutputStyleService {



    // -------------------------------------------------------------------------
    // Command metadata  (mirrors the Command object in src/commands/output-style/index.ts)
    // -------------------------------------------------------------------------

    /** Command name as registered in the command palette. */
    public static final String COMMAND_NAME = "output-style";
    public static final String COMMAND_TYPE = "local-jsx";
    /** Shown in the palette; marked deprecated in favour of /config. */
    public static final String COMMAND_DESCRIPTION = "Deprecated: use /config to change output style";
    /** Hidden from the command palette — users should use /config instead. */
    public static final boolean IS_HIDDEN = true;

    // -------------------------------------------------------------------------
    // Output-style directory loading  (src/outputStyles/loadOutputStylesDir.ts)
    // -------------------------------------------------------------------------

    private static final String OUTPUT_STYLES_DIR = "output-styles";

    /**
     * Get output styles from directories.
     * Translated from getOutputStyleDirStyles() in loadOutputStylesDir.ts
     */
    public List<OutputStyleConfig> getOutputStyleDirStyles(String cwd) {
        List<OutputStyleConfig> styles = new ArrayList<>();

        // Load from user directory
        String userStylesDir = EnvUtils.getClaudeConfigHomeDir() + "/" + OUTPUT_STYLES_DIR;
        styles.addAll(loadStylesFromDir(userStylesDir, "user"));

        // Load from project directory
        if (cwd != null) {
            String projectStylesDir = cwd + "/.claude/" + OUTPUT_STYLES_DIR;
            styles.addAll(loadStylesFromDir(projectStylesDir, "project"));
        }

        return styles;
    }

    private List<OutputStyleConfig> loadStylesFromDir(String dir, String source) {
        List<OutputStyleConfig> styles = new ArrayList<>();
        File directory = new File(dir);

        if (!directory.isDirectory()) return styles;

        File[] files = directory.listFiles((d, name) -> name.endsWith(".md"));
        if (files == null) return styles;

        for (File file : files) {
            try {
                String content = Files.readString(file.toPath());
                String name = file.getName().replace(".md", "");

                // Parse frontmatter (simplified)
                String description = extractDescription(content);
                String prompt = removeFrontmatter(content);

                styles.add(OutputStyleConfig.builder()
                    .name(name)
                    .description(description != null ? description : name)
                    .prompt(prompt)
                    .source(source)
                    .build());

            } catch (Exception e) {
                log.debug("Could not load output style {}: {}", file.getName(), e.getMessage());
            }
        }

        return styles;
    }

    private String extractDescription(String content) {
        if (content.startsWith("---")) {
            int end = content.indexOf("---", 3);
            if (end > 0) {
                String frontmatter = content.substring(3, end);
                for (String line : frontmatter.split("\n")) {
                    if (line.startsWith("description:")) {
                        return line.substring(12).trim();
                    }
                }
            }
        }
        return null;
    }

    private String removeFrontmatter(String content) {
        if (content.startsWith("---")) {
            int end = content.indexOf("---", 3);
            if (end > 0) {
                return content.substring(end + 3).trim();
            }
        }
        return content;
    }
}
