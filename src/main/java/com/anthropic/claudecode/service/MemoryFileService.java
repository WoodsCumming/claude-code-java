package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Memory file management service.
 * Translated from src/commands/memory/index.ts and src/utils/memory.ts
 *
 * Discovers CLAUDE.md memory files in the current project directory,
 * parent directories, and the user home directory.
 */
@Slf4j
@Service
public class MemoryFileService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MemoryFileService.class);


    private static final String MEMORY_FILENAME = "CLAUDE.md";

    private volatile List<Path> cachedMemoryFiles = null;

    /**
     * Discover all CLAUDE.md memory files visible from the current working directory.
     * Translated from getMemoryFiles() in memory.ts
     *
     * @return ordered list of memory file paths (project local → parent → user home)
     */
    public List<Path> getMemoryFiles() {
        if (cachedMemoryFiles != null) return cachedMemoryFiles;

        List<Path> files = new ArrayList<>();
        String cwd = System.getProperty("user.dir");
        String home = System.getProperty("user.home");

        // Walk upward from cwd collecting any CLAUDE.md files found
        Path current = Path.of(cwd);
        while (current != null) {
            Path candidate = current.resolve(MEMORY_FILENAME);
            if (Files.exists(candidate)) {
                files.add(candidate);
            }
            // Stop at home directory boundary or filesystem root
            if (current.toString().equals(home) || current.getParent() == null) break;
            current = current.getParent();
        }

        // Also include the user-level file if not already found
        Path userMemory = getUserMemoryFilePath();
        if (Files.exists(userMemory) && !files.contains(userMemory)) {
            files.add(userMemory);
        }

        cachedMemoryFiles = List.copyOf(files);
        return cachedMemoryFiles;
    }

    /**
     * Return the canonical path for the user-level memory file.
     * Translated from getUserMemoryFilePath() in memory.ts
     */
    public Path getUserMemoryFilePath() {
        return Path.of(System.getProperty("user.home"), ".claude", MEMORY_FILENAME);
    }

    /**
     * Return a human-readable relative path for display.
     */
    public String getRelativePath(Path path) {
        String cwd = System.getProperty("user.dir");
        String home = System.getProperty("user.home");
        String absPath = path.toAbsolutePath().toString();
        if (absPath.startsWith(cwd)) {
            String rel = absPath.substring(cwd.length());
            return rel.startsWith(File.separator) ? "." + rel : rel;
        }
        if (absPath.startsWith(home)) {
            return "~" + absPath.substring(home.length());
        }
        return absPath;
    }

    /**
     * Clear cached memory file list (called after editing).
     */
    public void clearCaches() {
        cachedMemoryFiles = null;
    }

    /**
     * Read the contents of a memory file.
     *
     * @return file contents, or empty string if the file does not exist
     */
    public String readMemoryFile(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path) : "";
        } catch (IOException e) {
            log.debug("Failed to read memory file {}: {}", path, e.getMessage());
            return "";
        }
    }
}
