package com.anthropic.claudecode.util;

import com.anthropic.claudecode.model.AgentComponentTypes.AgentDefinition;
import com.anthropic.claudecode.model.AgentComponentTypes.AgentPaths;
import com.anthropic.claudecode.model.AgentComponentTypes.AgentSource;
import com.anthropic.claudecode.model.AgentComponentTypes.SettingSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * File-system utilities for persisting agent definitions.
 *
 * <p>Translated from TypeScript {@code src/components/agents/agentFileUtils.ts}.
 * All async TS operations become {@link CompletableFuture}-returning methods executed
 * on the common fork-join pool.
 */
@Slf4j
public final class AgentFileUtils {



    private AgentFileUtils() {}

    // ---------------------------------------------------------------------------
    // formatAgentAsMarkdown
    // ---------------------------------------------------------------------------

    /**
     * Formats agent data as markdown front-matter file content.
     *
     * @param agentType    the agent identifier
     * @param whenToUse    human-readable description (may contain special characters)
     * @param tools        list of allowed tool names; {@code null} means all tools
     * @param systemPrompt the system prompt body
     * @param color        optional colour hint
     * @param model        optional model override
     * @param memory       optional memory scope identifier
     * @param effort       optional effort level
     * @return formatted markdown string ready to be written to a {@code .md} file
     */
    public static String formatAgentAsMarkdown(
            String agentType,
            String whenToUse,
            @Nullable List<String> tools,
            String systemPrompt,
            @Nullable String color,
            @Nullable String model,
            @Nullable String memory,
            @Nullable String effort) {
        // YAML double-quoted string escaping (mirrors the TS implementation):
        //   \ → \\  (escape backslashes first)
        //   " → \"  (escape double quotes)
        //   \n → \\n (newlines become literal \n in YAML)
        String escapedWhenToUse = whenToUse
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\\\n");

        boolean isAllTools = tools == null || (tools.size() == 1 && "*".equals(tools.get(0)));
        String toolsLine  = isAllTools ? "" : "\ntools: " + String.join(", ", tools);
        String modelLine  = model  != null ? "\nmodel: "  + model  : "";
        String effortLine = effort != null ? "\neffort: " + effort : "";
        String colorLine  = color  != null ? "\ncolor: "  + color  : "";
        String memoryLine = memory != null ? "\nmemory: " + memory : "";

        return "---\n"
                + "name: " + agentType + "\n"
                + "description: \"" + escapedWhenToUse + "\""
                + toolsLine + modelLine + effortLine + colorLine + memoryLine + "\n"
                + "---\n\n"
                + systemPrompt + "\n";
    }

    // ---------------------------------------------------------------------------
    // Path helpers
    // ---------------------------------------------------------------------------

    /**
     * Returns the absolute directory path for agents of a given {@link SettingSource}.
     *
     * @throws IllegalArgumentException for {@code FLAG_SETTINGS} which has no directory
     */
    public static Path getAgentDirectoryPath(SettingSource location) {
        return switch (location) {
            case FLAG_SETTINGS   -> throw new IllegalArgumentException(
                    "Cannot get directory path for " + location + " agents");
            case USER_SETTINGS   -> getClaudeConfigHomeDir().resolve(AgentPaths.AGENTS_DIR);
            case PROJECT_SETTINGS -> getCwd()
                    .resolve(AgentPaths.FOLDER_NAME)
                    .resolve(AgentPaths.AGENTS_DIR);
            case POLICY_SETTINGS  -> getManagedFilePath()
                    .resolve(AgentPaths.FOLDER_NAME)
                    .resolve(AgentPaths.AGENTS_DIR);
            case LOCAL_SETTINGS   -> getCwd()
                    .resolve(AgentPaths.FOLDER_NAME)
                    .resolve(AgentPaths.AGENTS_DIR);
        };
    }

    private static Path getRelativeAgentDirectoryPath(SettingSource location) {
        if (location == SettingSource.PROJECT_SETTINGS) {
            return Paths.get(".").resolve(AgentPaths.FOLDER_NAME).resolve(AgentPaths.AGENTS_DIR);
        }
        return getAgentDirectoryPath(location);
    }

    /**
     * Returns the absolute path for a new agent file (does not verify existence).
     */
    public static Path getNewAgentFilePath(SettingSource source, String agentType) {
        return getAgentDirectoryPath(source).resolve(agentType + ".md");
    }

    /**
     * Returns the actual file path for an existing agent (handles filename vs agentType mismatch).
     *
     * @throws IllegalArgumentException for plugin agents
     */
    public static Path getActualAgentFilePath(AgentDefinition agent) {
        if (agent.source() == AgentSource.BUILT_IN) {
            return Paths.get("Built-in");
        }
        if (agent.source() == AgentSource.PLUGIN) {
            throw new IllegalArgumentException("Cannot get file path for plugin agents");
        }
        SettingSource source = toSettingSource(agent.source());
        Path dir = getAgentDirectoryPath(source);
        String filename = agent.filename() != null ? agent.filename() : agent.agentType();
        return dir.resolve(filename + ".md");
    }

    /**
     * Returns the relative display path for a new agent (for UI messaging).
     */
    public static String getNewRelativeAgentFilePath(AgentSource source, String agentType) {
        if (source == AgentSource.BUILT_IN) {
            return "Built-in";
        }
        Path dir = getRelativeAgentDirectoryPath(toSettingSource(source));
        return dir.resolve(agentType + ".md").toString();
    }

    /**
     * Returns the actual relative path for an existing agent (for UI messaging).
     */
    public static String getActualRelativeAgentFilePath(AgentDefinition agent) {
        return switch (agent.source()) {
            case BUILT_IN -> "Built-in";
            case PLUGIN   -> "Plugin: " + (agent.plugin() != null ? agent.plugin() : "Unknown");
            case FLAG_SETTINGS -> "CLI argument";
            default -> {
                Path dir = getRelativeAgentDirectoryPath(toSettingSource(agent.source()));
                String filename = agent.filename() != null ? agent.filename() : agent.agentType();
                yield dir.resolve(filename + ".md").toString();
            }
        };
    }

    // ---------------------------------------------------------------------------
    // Async persistence operations (CompletableFuture mirrors TS async/await)
    // ---------------------------------------------------------------------------

    /**
     * Saves an agent to the file-system.
     *
     * @param checkExists when {@code true} the write will fail if the file already exists
     *                    (equivalent to TS flag {@code 'wx'})
     */
    public static CompletableFuture<Void> saveAgentToFile(
            AgentSource source,
            String agentType,
            String whenToUse,
            @Nullable List<String> tools,
            String systemPrompt,
            boolean checkExists,
            @Nullable String color,
            @Nullable String model,
            @Nullable String memory,
            @Nullable String effort) {
        return CompletableFuture.runAsync(() -> {
            if (source == AgentSource.BUILT_IN) {
                throw new RuntimeException("Cannot save built-in agents");
            }
            SettingSource settingSource = toSettingSource(source);
            try {
                ensureAgentDirectoryExists(settingSource);
                Path filePath = getNewAgentFilePath(settingSource, agentType);
                String content = formatAgentAsMarkdown(
                        agentType, whenToUse, tools, systemPrompt,
                        color, model, memory, effort);
                writeFileAndFlush(filePath, content, checkExists);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Updates an existing agent file.
     */
    public static CompletableFuture<Void> updateAgentFile(
            AgentDefinition agent,
            String newWhenToUse,
            @Nullable List<String> newTools,
            String newSystemPrompt,
            @Nullable String newColor,
            @Nullable String newModel,
            @Nullable String newMemory,
            @Nullable String newEffort) {
        return CompletableFuture.runAsync(() -> {
            if (agent.source() == AgentSource.BUILT_IN) {
                throw new RuntimeException("Cannot update built-in agents");
            }
            Path filePath = getActualAgentFilePath(agent);
            String content = formatAgentAsMarkdown(
                    agent.agentType(), newWhenToUse, newTools, newSystemPrompt,
                    newColor, newModel, newMemory, newEffort);
            try {
                writeFileAndFlush(filePath, content, false);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Deletes an agent file. Silently ignores ENOENT (file not found).
     */
    public static CompletableFuture<Void> deleteAgentFromFile(AgentDefinition agent) {
        return CompletableFuture.runAsync(() -> {
            if (agent.source() == AgentSource.BUILT_IN) {
                throw new RuntimeException("Cannot delete built-in agents");
            }
            Path filePath = getActualAgentFilePath(agent);
            try {
                Files.deleteIfExists(filePath);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    // ---------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------

    private static void ensureAgentDirectoryExists(SettingSource source) throws IOException {
        Path dir = getAgentDirectoryPath(source);
        Files.createDirectories(dir);
    }

    /**
     * Writes content to a file and flushes to storage (mirrors TS {@code writeFileAndFlush}).
     *
     * @param checkExists when {@code true} the operation uses {@link StandardOpenOption#CREATE_NEW}
     *                    which throws if the file already exists (equivalent to flag {@code 'wx'})
     */
    private static void writeFileAndFlush(
            Path filePath,
            String content,
            boolean checkExists
    ) throws IOException {
        OpenOption createOption = checkExists
                ? StandardOpenOption.CREATE_NEW
                : StandardOpenOption.CREATE;

        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(
                filePath,
                createOption,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(java.nio.ByteBuffer.wrap(bytes));
            channel.force(true); // datasync equivalent
        } catch (java.nio.file.FileAlreadyExistsException e) {
            throw new RuntimeException("Agent file already exists: " + filePath, e);
        }
    }

    /** Converts an {@link AgentSource} to a {@link SettingSource} (non-special variants only). */
    static SettingSource toSettingSource(AgentSource source) {
        return switch (source) {
            case FLAG_SETTINGS    -> SettingSource.FLAG_SETTINGS;
            case USER_SETTINGS    -> SettingSource.USER_SETTINGS;
            case PROJECT_SETTINGS -> SettingSource.PROJECT_SETTINGS;
            case POLICY_SETTINGS  -> SettingSource.POLICY_SETTINGS;
            case LOCAL_SETTINGS   -> SettingSource.LOCAL_SETTINGS;
            case BUILT_IN, PLUGIN -> throw new IllegalArgumentException(
                    "AgentSource " + source + " has no SettingSource equivalent");
        };
    }

    // ---------------------------------------------------------------------------
    // Environment stubs – replace with real implementations / Spring @Value injection
    // ---------------------------------------------------------------------------

    /** Returns the Claude config home directory (e.g. {@code ~/.config/claude}). */
    private static Path getClaudeConfigHomeDir() {
        String env = System.getenv("CLAUDE_CONFIG_HOME");
        if (env != null) {
            return Paths.get(env);
        }
        return Paths.get(System.getProperty("user.home"), ".config", "claude");
    }

    /** Returns the current working directory. */
    private static Path getCwd() {
        return Paths.get(System.getProperty("user.dir"));
    }

    /** Returns the managed-file base path (for policy settings). */
    private static Path getManagedFilePath() {
        String env = System.getenv("CLAUDE_MANAGED_FILE_PATH");
        if (env != null) {
            return Paths.get(env);
        }
        return Paths.get("/etc/claude");
    }
}
