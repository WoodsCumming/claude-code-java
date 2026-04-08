package com.anthropic.claudecode.service.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * DebugSkill — enable debug logging for the current session and help diagnose
 * issues by reading the session debug log.
 *
 * <p>Translated from: src/skills/bundled/debug.ts
 */
@Slf4j
@Service
public class DebugSkill {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DebugSkill.class);


    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final int DEFAULT_DEBUG_LINES_READ = 20;
    private static final long TAIL_READ_BYTES = 64L * 1024;

    private static final String CLAUDE_CODE_GUIDE_AGENT_TYPE = "claude-code-guide";

    // -------------------------------------------------------------------------
    // Skill metadata
    // -------------------------------------------------------------------------

    public static final String NAME = "debug";

    public static final String DESCRIPTION_ANT =
            "Debug your current Claude Code session by reading the session debug log. Includes all event logging";
    public static final String DESCRIPTION_DEFAULT =
            "Enable debug logging for this session and help diagnose issues";

    public static final String ARGUMENT_HINT = "[issue description]";

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private boolean debugLoggingEnabled = false;
    private Path debugLogPath;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Returns the skill description, which varies by user type. */
    public String getDescription() {
        return "ant".equals(System.getenv("USER_TYPE")) ? DESCRIPTION_ANT : DESCRIPTION_DEFAULT;
    }

    /**
     * Builds the prompt for the /debug command.
     *
     * @param args optional issue description
     * @return prompt parts to send to the model
     */
    public CompletableFuture<List<PromptPart>> getPromptForCommand(String args) {
        return CompletableFuture.supplyAsync(() -> {
            boolean wasAlreadyLogging = enableDebugLogging();
            Path logPath = getDebugLogPath();

            String logInfo = readLogTail(logPath);

            String justEnabledSection = wasAlreadyLogging ? "" :
                    "\n## Debug Logging Just Enabled\n\n"
                            + "Debug logging was OFF for this session until now. Nothing prior to this /debug invocation was captured.\n\n"
                            + "Tell the user that debug logging is now active at `" + logPath + "`, ask them to reproduce the issue, "
                            + "then re-read the log. If they can't reproduce, they can also restart with `claude --debug` to capture logs from startup.\n";

            String issueDescription = (args == null || args.isBlank())
                    ? "The user did not describe a specific issue. Read the debug log and summarize any errors, warnings, or notable issues."
                    : args;

            String prompt = "# Debug Skill\n\n"
                    + "Help the user debug an issue they're encountering in this current Claude Code session.\n"
                    + justEnabledSection
                    + "\n## Session Debug Log\n\n"
                    + "The debug log for the current session is at: `" + logPath + "`\n\n"
                    + logInfo + "\n\n"
                    + "For additional context, grep for [ERROR] and [WARN] lines across the full file.\n\n"
                    + "## Issue Description\n\n"
                    + issueDescription + "\n\n"
                    + "## Settings\n\n"
                    + "Remember that settings are in:\n"
                    + "* user - " + getSettingsFilePath("userSettings") + "\n"
                    + "* project - " + getSettingsFilePath("projectSettings") + "\n"
                    + "* local - " + getSettingsFilePath("localSettings") + "\n\n"
                    + "## Instructions\n\n"
                    + "1. Review the user's issue description\n"
                    + "2. The last " + DEFAULT_DEBUG_LINES_READ + " lines show the debug file format. Look for [ERROR] and [WARN] entries, stack traces, and failure patterns across the file\n"
                    + "3. Consider launching the " + CLAUDE_CODE_GUIDE_AGENT_TYPE + " subagent to understand the relevant Claude Code features\n"
                    + "4. Explain what you found in plain language\n"
                    + "5. Suggest concrete fixes or next steps\n";

            return List.of(new PromptPart("text", prompt));
        });
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Enables debug logging for this session.
     *
     * @return {@code true} if logging was already enabled before this call
     */
    private synchronized boolean enableDebugLogging() {
        boolean wasEnabled = debugLoggingEnabled;
        debugLoggingEnabled = true;
        return wasEnabled;
    }

    /**
     * Returns the path to the session debug log, creating the parent directory
     * if necessary.
     */
    private synchronized Path getDebugLogPath() {
        if (debugLogPath == null) {
            String home = System.getProperty("user.home");
            Path dir = Paths.get(home, ".claude", "debug");
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                log.warn("Could not create debug log directory {}", dir, e);
            }
            // Use a session-specific file; here we use PID as a simple identifier.
            String sessionId = ProcessHandle.current().pid() + "";
            debugLogPath = dir.resolve(sessionId + ".txt");
        }
        return debugLogPath;
    }

    /**
     * Reads the last {@value #DEFAULT_DEBUG_LINES_READ} lines from the debug log,
     * tailing at most {@value #TAIL_READ_BYTES} bytes so we avoid loading the
     * entire file into memory.
     */
    private String readLogTail(Path logPath) {
        File file = logPath.toFile();
        if (!file.exists()) {
            return "No debug log exists yet — logging was just enabled.";
        }
        try {
            long fileSize = file.length();
            long readSize = Math.min(fileSize, TAIL_READ_BYTES);
            long startOffset = fileSize - readSize;

            byte[] buffer = new byte[(int) readSize];
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.seek(startOffset);
                int bytesRead = raf.read(buffer);
                String content = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                String[] allLines = content.split("\n");
                String[] lastLines = Arrays.copyOfRange(
                        allLines,
                        Math.max(0, allLines.length - DEFAULT_DEBUG_LINES_READ),
                        allLines.length);
                String tail = String.join("\n", lastLines);
                return "Log size: " + formatFileSize(fileSize) + "\n\n"
                        + "### Last " + DEFAULT_DEBUG_LINES_READ + " lines\n\n"
                        + "```\n" + tail + "\n```";
            }
        } catch (IOException e) {
            return "Failed to read last " + DEFAULT_DEBUG_LINES_READ
                    + " lines of debug log: " + e.getMessage();
        }
    }

    /** Formats a byte count as a human-readable string (KB / MB). */
    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    /** Returns the settings file path for a given source identifier. */
    private static String getSettingsFilePath(String source) {
        String home = System.getProperty("user.home");
        return switch (source) {
            case "userSettings" -> home + "/.claude/settings.json";
            case "projectSettings" -> ".claude/settings.json";
            case "localSettings" -> ".claude/settings.local.json";
            default -> home + "/.claude/" + source + ".json";
        };
    }

    /** Simple record representing a single prompt part. */
    public record PromptPart(String type, String text) {}
}
