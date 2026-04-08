package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Lightweight git config parser.
 * Translated from src/utils/git/gitConfigParser.ts
 */
@Slf4j
public class GitConfigParser {



    /**
     * Parse a value from a .git/config file.
     * Translated from parseGitConfigValue() in gitConfigParser.ts
     */
    public static Optional<String> parseGitConfigValue(
            String gitDir,
            String section,
            String subsection,
            String key) {

        try {
            String config = Files.readString(Paths.get(gitDir, "config"));
            return parseConfigString(config, section, subsection, key);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Parse a config value from a config string.
     * Translated from parseConfigString() in gitConfigParser.ts
     */
    public static Optional<String> parseConfigString(
            String config,
            String section,
            String subsection,
            String key) {

        if (config == null) return Optional.empty();

        String[] lines = config.split("\n");
        boolean inSection = false;

        // Build section header pattern
        String sectionHeader;
        if (subsection != null) {
            sectionHeader = "[" + section.toLowerCase() + " \"" + subsection + "\"]";
        } else {
            sectionHeader = "[" + section.toLowerCase() + "]";
        }

        for (String line : lines) {
            String trimmed = line.trim();

            // Check for section header
            if (trimmed.startsWith("[")) {
                inSection = trimmed.toLowerCase().startsWith(sectionHeader.toLowerCase());
                continue;
            }

            // Parse key-value in current section
            if (inSection && trimmed.contains("=")) {
                int eqIdx = trimmed.indexOf('=');
                String lineKey = trimmed.substring(0, eqIdx).trim().toLowerCase();
                if (lineKey.equals(key.toLowerCase())) {
                    String value = trimmed.substring(eqIdx + 1).trim();
                    // Remove inline comments
                    int commentIdx = value.indexOf('#');
                    if (commentIdx >= 0) value = value.substring(0, commentIdx).trim();
                    commentIdx = value.indexOf(';');
                    if (commentIdx >= 0) value = value.substring(0, commentIdx).trim();
                    return Optional.of(value);
                }
            }
        }

        return Optional.empty();
    }

    private GitConfigParser() {}
}
