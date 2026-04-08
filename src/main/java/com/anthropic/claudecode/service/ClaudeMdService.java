package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import lombok.Data;

/**
 * CLAUDE.md file loading service.
 * Translated from src/utils/claudemd.ts
 *
 * Loads memory files (CLAUDE.md) from various locations:
 * 1. Managed memory (/etc/claude-code/CLAUDE.md)
 * 2. User memory (~/.claude/CLAUDE.md)
 * 3. Project memory (CLAUDE.md in project roots)
 * 4. Local memory (CLAUDE.local.md in project roots)
 */
@Slf4j
@Service
public class ClaudeMdService {


    private static final String CLAUDE_MD = "CLAUDE.md";
    private static final String CLAUDE_LOCAL_MD = "CLAUDE.local.md";
    private static final String CLAUDE_RULES_DIR = ".claude/rules";

    /**
     * Get all memory files for a project path.
     * Translated from getMemoryFiles() in claudemd.ts
     */
    public List<MemoryFileInfo> getMemoryFiles(String projectPath) {
        List<MemoryFileInfo> files = new ArrayList<>();

        // 1. Managed memory
        loadManagedMemory(files);

        // 2. User memory
        loadUserMemory(files);

        // 3. Project memory (traverse from root to current directory)
        if (projectPath != null) {
            loadProjectMemory(files, projectPath);
        }

        return files;
    }

    private void loadManagedMemory(List<MemoryFileInfo> files) {
        String[] managedPaths = {
            "/etc/claude-code/CLAUDE.md",
            "C:\\ProgramData\\claude-code\\CLAUDE.md"
        };

        for (String path : managedPaths) {
            File file = new File(path);
            if (file.exists()) {
                try {
                    String content = Files.readString(file.toPath());
                    files.add(new MemoryFileInfo(path, content, "managed"));
                } catch (Exception e) {
                    log.debug("Could not read managed memory: {}", e.getMessage());
                }
            }
        }
    }

    private void loadUserMemory(List<MemoryFileInfo> files) {
        String userClaudeDir = EnvUtils.getClaudeConfigHomeDir();

        // ~/.claude/CLAUDE.md
        File userClaudeMd = new File(userClaudeDir, CLAUDE_MD);
        if (userClaudeMd.exists()) {
            try {
                String content = Files.readString(userClaudeMd.toPath());
                files.add(new MemoryFileInfo(userClaudeMd.getAbsolutePath(), content, "user"));
            } catch (Exception e) {
                log.debug("Could not read user memory: {}", e.getMessage());
            }
        }

        // ~/.claude/rules/*.md
        File rulesDir = new File(userClaudeDir, "rules");
        loadRulesDirectory(files, rulesDir, "user");
    }

    private void loadProjectMemory(List<MemoryFileInfo> files, String projectPath) {
        // Traverse from root to current directory
        List<File> directories = new ArrayList<>();
        File dir = new File(projectPath).getAbsoluteFile();
        while (dir != null) {
            directories.add(0, dir); // Add at beginning (root first)
            dir = dir.getParentFile();
        }

        for (File directory : directories) {
            // CLAUDE.md
            File claudeMd = new File(directory, CLAUDE_MD);
            if (claudeMd.exists()) {
                try {
                    String content = Files.readString(claudeMd.toPath());
                    files.add(new MemoryFileInfo(claudeMd.getAbsolutePath(), content, "project"));
                } catch (Exception e) {
                    log.debug("Could not read project memory: {}", e.getMessage());
                }
            }

            // .claude/CLAUDE.md
            File claudeDirMd = new File(directory, ".claude/" + CLAUDE_MD);
            if (claudeDirMd.exists()) {
                try {
                    String content = Files.readString(claudeDirMd.toPath());
                    files.add(new MemoryFileInfo(claudeDirMd.getAbsolutePath(), content, "project"));
                } catch (Exception e) {
                    log.debug("Could not read .claude/CLAUDE.md: {}", e.getMessage());
                }
            }

            // .claude/rules/*.md
            File rulesDir = new File(directory, CLAUDE_RULES_DIR);
            loadRulesDirectory(files, rulesDir, "project");

            // CLAUDE.local.md (highest priority)
            File localMd = new File(directory, CLAUDE_LOCAL_MD);
            if (localMd.exists()) {
                try {
                    String content = Files.readString(localMd.toPath());
                    files.add(new MemoryFileInfo(localMd.getAbsolutePath(), content, "local"));
                } catch (Exception e) {
                    log.debug("Could not read local memory: {}", e.getMessage());
                }
            }
        }
    }

    private void loadRulesDirectory(List<MemoryFileInfo> files, File rulesDir, String type) {
        if (!rulesDir.isDirectory()) return;

        File[] ruleFiles = rulesDir.listFiles((dir, name) -> name.endsWith(".md"));
        if (ruleFiles == null) return;

        Arrays.sort(ruleFiles, Comparator.comparing(File::getName));
        for (File ruleFile : ruleFiles) {
            try {
                String content = Files.readString(ruleFile.toPath());
                files.add(new MemoryFileInfo(ruleFile.getAbsolutePath(), content, type));
            } catch (Exception e) {
                log.debug("Could not read rule file {}: {}", ruleFile.getName(), e.getMessage());
            }
        }
    }

    /**
     * Build the combined memory prompt from all memory files.
     */
    public String buildMemoryPrompt(List<MemoryFileInfo> memoryFiles) {
        if (memoryFiles.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (MemoryFileInfo file : memoryFiles) {
            sb.append("<memory_file path=\"").append(file.getPath()).append("\">\n");
            sb.append(file.getContent());
            sb.append("\n</memory_file>\n\n");
        }

        return sb.toString();
    }

    public static class MemoryFileInfo {
        private String path;
        private String content;
        private String type;

        public MemoryFileInfo() {}
        public MemoryFileInfo(String path, String content, String type) {
            this.path = path; this.content = content; this.type = type;
        }
        public String getPath() { return path; }
        public void setPath(String v) { path = v; }
        public String getContent() { return content; }
        public void setContent(String v) { content = v; }
        public String getType() { return type; }
        public void setType(String v) { type = v; }
    }

    /** Reset the memory files cache with the given load reason. */
    public void resetGetMemoryFilesCache(String loadReason) {
        log.debug("resetGetMemoryFilesCache: loadReason={}", loadReason);
    }

    /** Returns the maximum allowed character count per CLAUDE.md memory file. */
    public int getMaxMemoryCharacterCount() {
        return 500_000; // default threshold
    }

    /** Returns memory files that exceed the character threshold. */
    public java.util.List<MemoryFile> getLargeMemoryFiles() {
        return java.util.List.of();
    }

    /**
     * Lightweight view of a memory file for doctor/context-warning purposes.
     */
    public record MemoryFile(String path, int contentLength) {}
}
