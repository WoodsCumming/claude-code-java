package com.anthropic.claudecode.command;

import com.anthropic.claudecode.config.ClaudeCodeConfig;
import com.anthropic.claudecode.service.QueryEngine;
import com.anthropic.claudecode.service.OAuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;

/**
 * Main CLI command.
 * Translated from src/main.tsx - the Commander.js CLI setup.
 *
 * Usage:
 *   claude [options] [prompt]
 *   claude [command]
 */
@Slf4j
@Component
@Command(
    name = "claude",
    mixinStandardHelpOptions = true,
    version = "2.1.88",
    description = "Claude Code - AI coding assistant",
    subcommands = {
        LoginCommand.class,
        LogoutCommand.class,
        ConfigCommand.class,
        SessionCommand.class,
        McpCommand.class,
    }
)
public class MainCommand implements Callable<Integer> {



    @Parameters(
        index = "0",
        description = "Initial prompt or message",
        arity = "0..1"
    )
    private String prompt;

    @Option(
        names = {"-p", "--print"},
        description = "Print mode: output response and exit"
    )
    private boolean printMode;

    @Option(
        names = {"--model"},
        description = "Model to use (default: claude-opus-4-6)"
    )
    private String model;

    @Option(
        names = {"--permission-mode"},
        description = "Permission mode: default, acceptEdits, bypassPermissions, dontAsk, plan"
    )
    private String permissionMode;

    @Option(
        names = {"--no-interactive"},
        description = "Non-interactive mode"
    )
    private boolean nonInteractive;

    @Option(
        names = {"--verbose", "-v"},
        description = "Enable verbose output"
    )
    private boolean verbose;

    @Option(
        names = {"--output-format"},
        description = "Output format: text, json, stream-json"
    )
    private String outputFormat;

    @Option(
        names = {"--max-turns"},
        description = "Maximum number of turns"
    )
    private Integer maxTurns;

    @Option(
        names = {"--system-prompt"},
        description = "Custom system prompt"
    )
    private String systemPrompt;

    @Option(
        names = {"--append-system-prompt"},
        description = "Append to system prompt"
    )
    private String appendSystemPrompt;

    @Option(
        names = {"--resume"},
        description = "Resume a previous session"
    )
    private String resumeSessionId;

    @Option(
        names = {"--continue", "-c"},
        description = "Continue the most recent session"
    )
    private boolean continueSession;

    @Option(
        names = {"--debug"},
        description = "Enable debug output"
    )
    private boolean debug;

    @Option(
        names = {"--dangerously-skip-permissions"},
        description = "Skip all permission checks (dangerous)"
    )
    private boolean dangerouslySkipPermissions;

    @Option(
        names = {"--add-dir"},
        description = "Add additional working directories",
        arity = "0..*"
    )
    private String[] additionalDirs;

    private final QueryEngine queryEngine;
    private final ClaudeCodeConfig config;
    private final ReplLauncher replLauncher;

    @Autowired
    public MainCommand(
            QueryEngine queryEngine,
            ClaudeCodeConfig config,
            ReplLauncher replLauncher) {
        this.queryEngine = queryEngine;
        this.config = config;
        this.replLauncher = replLauncher;
    }

    @Override
    public Integer call() throws Exception {
        // Apply CLI options to config
        if (model != null) config.setModel(model);
        if (permissionMode != null) config.setPermissionMode(permissionMode);
        if (verbose) config.setVerbose(true);
        if (debug) config.setVerbose(true);
        if (systemPrompt != null) config.setCustomSystemPrompt(systemPrompt);
        if (appendSystemPrompt != null) config.setAppendSystemPrompt(appendSystemPrompt);
        if (nonInteractive) config.setNonInteractiveSession(true);
        if (dangerouslySkipPermissions) config.setPermissionMode("bypassPermissions");

        // Determine mode
        if (printMode || nonInteractive) {
            // Non-interactive / print mode
            return runPrintMode();
        }

        // Interactive REPL mode
        return replLauncher.launch(prompt, continueSession, resumeSessionId);
    }

    private Integer runPrintMode() {
        if (prompt == null || prompt.isBlank()) {
            System.err.println("Error: prompt is required in print mode");
            return 1;
        }

        try {
            log.info("Running in print mode with prompt: {}", prompt);
            replLauncher.runSingleQuery(prompt, outputFormat);
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            if (verbose) {
                e.printStackTrace(System.err);
            }
            log.error("Print mode failed", e);
            return 1;
        }
    }
}
