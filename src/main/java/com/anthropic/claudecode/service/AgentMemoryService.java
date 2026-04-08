package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Agent memory service.
 * Translated from src/tools/AgentTool/agentMemory.ts
 *
 * Manages persistent agent memory storage.
 */
@Slf4j
@Service
public class AgentMemoryService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentMemoryService.class);


    public enum AgentMemoryScope {
        USER, PROJECT, LOCAL
    }

    /**
     * Get the agent memory directory.
     * Translated from getAgentMemoryDir() in agentMemory.ts
     */
    public String getAgentMemoryDir(String agentType, AgentMemoryScope scope) {
        String dirName = sanitizeAgentTypeForPath(agentType);
        String cwd = System.getProperty("user.dir");

        return switch (scope) {
            case PROJECT -> cwd + "/.claude/agent-memory/" + dirName + "/";
            case LOCAL -> getLocalAgentMemoryDir(dirName);
            case USER -> EnvUtils.getClaudeConfigHomeDir() + "/agent-memory/" + dirName + "/";
        };
    }

    private String getLocalAgentMemoryDir(String dirName) {
        String remoteMemDir = System.getenv("CLAUDE_CODE_REMOTE_MEMORY_DIR");
        if (remoteMemDir != null) {
            return remoteMemDir + "/agent-memory-local/" + dirName + "/";
        }
        return System.getProperty("user.dir") + "/.claude/agent-memory-local/" + dirName + "/";
    }

    private String sanitizeAgentTypeForPath(String agentType) {
        return agentType.replace(":", "-");
    }

    /**
     * Check if a path is an agent memory path.
     * Translated from isAgentMemoryPath() in agentMemory.ts
     */
    public boolean isAgentMemoryPath(String path) {
        if (path == null) return false;
        return path.contains("/agent-memory/")
            || path.contains("/agent-memory-local/");
    }

    /**
     * Load agent memory prompt.
     * Translated from loadAgentMemoryPrompt() in agentMemory.ts
     */
    public Optional<String> loadAgentMemoryPrompt(String agentType, AgentMemoryScope scope) {
        String memDir = getAgentMemoryDir(agentType, scope);
        File dir = new File(memDir);

        if (!dir.exists()) return Optional.empty();

        File[] files = dir.listFiles((d, name) -> name.endsWith(".md"));
        if (files == null || files.length == 0) return Optional.empty();

        StringBuilder sb = new StringBuilder();
        Arrays.sort(files, Comparator.comparing(File::getName));

        for (File file : files) {
            try {
                sb.append(Files.readString(file.toPath())).append("\n\n");
            } catch (Exception e) {
                log.debug("Could not read memory file {}: {}", file.getName(), e.getMessage());
            }
        }

        return sb.length() > 0 ? Optional.of(sb.toString().trim()) : Optional.empty();
    }
}
