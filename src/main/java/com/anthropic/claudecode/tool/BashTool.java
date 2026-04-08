package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import com.anthropic.claudecode.service.ShellService;
import com.anthropic.claudecode.util.PathUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import lombok.Data;

/**
 * Bash shell execution tool.
 * Translated from src/tools/BashTool/BashTool.tsx
 *
 * Executes bash commands in the user's shell environment.
 */
@Slf4j
@Component
public class BashTool extends AbstractTool<BashTool.Input, BashTool.Output> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BashTool.class);

    public static final String TOOL_NAME = "Bash";

    // Commands that are search operations
    private static final Set<String> BASH_SEARCH_COMMANDS = Set.of(
        "find", "grep", "rg", "ag", "ack", "locate", "which", "whereis"
    );

    // Commands that are read operations
    private static final Set<String> BASH_READ_COMMANDS = Set.of(
        "cat", "head", "tail", "less", "more",
        "wc", "stat", "file", "strings",
        "jq", "awk", "cut", "sort", "uniq", "tr"
    );

    // Commands that are directory listing operations
    private static final Set<String> BASH_LIST_COMMANDS = Set.of(
        "ls", "tree", "du"
    );

    // Semantic-neutral commands (pure output/status)
    private static final Set<String> BASH_SEMANTIC_NEUTRAL_COMMANDS = Set.of(
        "echo", "printf", "true", "false", ":"
    );

    // Default timeout in milliseconds (2 minutes)
    private static final long DEFAULT_TIMEOUT_MS = 120_000;

    // Maximum timeout in milliseconds (10 minutes)
    private static final long MAX_TIMEOUT_MS = 600_000;

    private final ShellService shellService;

    @Autowired
    public BashTool(ShellService shellService) {
        this.shellService = shellService;
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getSearchHint() {
        return "execute shell commands in terminal";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "command", Map.of(
                    "type", "string",
                    "description", "The bash command to execute"
                ),
                "timeout", Map.of(
                    "type", "integer",
                    "description", "Optional timeout in milliseconds (max 600000)",
                    "minimum", 0,
                    "maximum", 600000
                ),
                "description", Map.of(
                    "type", "string",
                    "description", "Clear, concise description of what this command does"
                )
            ),
            "required", List.of("command")
        );
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
            Input args,
            ToolUseContext context,
            Tool.CanUseToolFn canUseTool,
            Message.AssistantMessage parentMessage,
            Consumer<Tool.ToolProgress> onProgress) {

        if (context.isAborted()) {
            return failedFuture(new InterruptedException("Operation aborted"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                long timeoutMs = args.getTimeout() != null
                    ? Math.min(args.getTimeout(), MAX_TIMEOUT_MS)
                    : DEFAULT_TIMEOUT_MS;

                ShellService.ExecResult execResult = shellService.execute(
                    args.getCommand(),
                    timeoutMs,
                    context.isAborted()
                );

                Output output = Output.builder()
                    .stdout(execResult.getStdout())
                    .stderr(execResult.getStderr())
                    .exitCode(execResult.getExitCode())
                    .interrupted(execResult.isInterrupted())
                    .isImage(false)
                    .build();

                return result(output);
            } catch (Exception e) {
                log.error("Bash execution failed: {}", e.getMessage(), e);
                throw new RuntimeException("Bash execution failed: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture(
            "Running bash command: " + truncate(input.getCommand(), 100)
        );
    }

    @Override
    public boolean isReadOnly(Input input) {
        return false; // Bash can do anything
    }

    @Override
    public SearchOrReadInfo isSearchOrReadCommand(Input input) {
        return classifyBashCommand(input.getCommand());
    }

    @Override
    public Object toAutoClassifierInput(Input input) {
        return input.getCommand();
    }

    @Override
    public String userFacingName(Input input) {
        return input.getCommand() != null ? truncate(input.getCommand(), 60) : TOOL_NAME;
    }

    @Override
    public String getActivityDescription(Input input) {
        return "Running " + truncate(input.getCommand(), 50);
    }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        String text;
        if (content.isInterrupted()) {
            text = "<interrupted>\n" + buildOutputText(content);
        } else {
            text = buildOutputText(content);
        }

        return Map.of(
            "type", "tool_result",
            "tool_use_id", toolUseId,
            "content", text
        );
    }

    private String buildOutputText(Output content) {
        StringBuilder sb = new StringBuilder();
        if (content.getStdout() != null && !content.getStdout().isEmpty()) {
            sb.append(content.getStdout());
        }
        if (content.getStderr() != null && !content.getStderr().isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(content.getStderr());
        }
        if (content.getExitCode() != 0) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("Exit code: ").append(content.getExitCode());
        }
        return sb.toString();
    }

    /**
     * Classifies a bash command as search, read, or list operation.
     * Translated from isSearchOrReadBashCommand() in BashTool.tsx
     */
    public static SearchOrReadInfo classifyBashCommand(String command) {
        if (command == null || command.isBlank()) {
            return SearchOrReadInfo.NONE;
        }

        // Split by pipe, &&, ||, ; to get individual commands
        String[] parts = command.split("[|&;]+");
        if (parts.length == 0) {
            return SearchOrReadInfo.NONE;
        }

        boolean hasSearch = false;
        boolean hasRead = false;
        boolean hasList = false;
        boolean hasNonSearchRead = false;

        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;

            // Get the base command name (first word)
            String[] words = trimmed.split("\\s+");
            if (words.length == 0) continue;

            String cmd = words[0];
            // Handle paths like /usr/bin/grep
            if (cmd.contains("/")) {
                cmd = cmd.substring(cmd.lastIndexOf('/') + 1);
            }

            if (BASH_SEMANTIC_NEUTRAL_COMMANDS.contains(cmd)) {
                continue; // Skip neutral commands
            }

            if (BASH_SEARCH_COMMANDS.contains(cmd)) {
                hasSearch = true;
            } else if (BASH_READ_COMMANDS.contains(cmd)) {
                hasRead = true;
            } else if (BASH_LIST_COMMANDS.contains(cmd)) {
                hasList = true;
            } else {
                hasNonSearchRead = true;
            }
        }

        // If any non-search/read command, not collapsible
        if (hasNonSearchRead) {
            return SearchOrReadInfo.NONE;
        }

        return new SearchOrReadInfo(hasSearch, hasRead, hasList);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }

    // =========================================================================
    // Input/Output types
    // =========================================================================

    public static class Input {
        private String command;
        private Long timeout;
        private String description;
        public Input() {}
        public Input(String command, Long timeout, String description) { this.command = command; this.timeout = timeout; this.description = description; }
        public String getCommand() { return command; }
        public void setCommand(String v) { command = v; }
        public Long getTimeout() { return timeout; }
        public void setTimeout(Long v) { timeout = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
        public static InputBuilder builder() { return new InputBuilder(); }
        public static class InputBuilder {
            private String command; private Long timeout; private String description;
            public InputBuilder command(String v) { this.command = v; return this; }
            public InputBuilder timeout(Long v) { this.timeout = v; return this; }
            public InputBuilder description(String v) { this.description = v; return this; }
            public Input build() { return new Input(command, timeout, description); }
        }
    }

    public static class Output {
        private String stdout;
        private String stderr;
        private int exitCode;
        private boolean interrupted;
        private boolean isImage;
        private String imagePath;
        public Output() {}
        public String getStdout() { return stdout; }
        public void setStdout(String v) { stdout = v; }
        public String getStderr() { return stderr; }
        public void setStderr(String v) { stderr = v; }
        public int getExitCode() { return exitCode; }
        public void setExitCode(int v) { exitCode = v; }
        public boolean isInterrupted() { return interrupted; }
        public void setInterrupted(boolean v) { interrupted = v; }
        public boolean isIsImage() { return isImage; }
        public void setIsImage(boolean v) { isImage = v; }
        public String getImagePath() { return imagePath; }
        public void setImagePath(String v) { imagePath = v; }
        public static OutputBuilder builder() { return new OutputBuilder(); }
        public static class OutputBuilder {
            private String stdout; private String stderr; private int exitCode;
            private boolean interrupted; private boolean isImage; private String imagePath;
            public OutputBuilder stdout(String v) { this.stdout = v; return this; }
            public OutputBuilder stderr(String v) { this.stderr = v; return this; }
            public OutputBuilder exitCode(int v) { this.exitCode = v; return this; }
            public OutputBuilder interrupted(boolean v) { this.interrupted = v; return this; }
            public OutputBuilder isImage(boolean v) { this.isImage = v; return this; }
            public OutputBuilder imagePath(String v) { this.imagePath = v; return this; }
            public Output build() { Output o = new Output(); o.stdout = stdout; o.stderr = stderr; o.exitCode = exitCode; o.interrupted = interrupted; o.isImage = isImage; o.imagePath = imagePath; return o; }
        }
    }
}
