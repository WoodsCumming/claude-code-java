package com.anthropic.claudecode.command;

import com.anthropic.claudecode.config.ClaudeCodeConfig;
import com.anthropic.claudecode.service.PromptSubmitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;

/**
 * Commit command for creating git commits.
 * Translated from src/commands/commit.ts
 */
@Slf4j
@Component
@Command(
    name = "commit",
    description = "Create a git commit with AI-generated message"
)
public class CommitCommand implements Callable<Integer> {



    @Option(names = {"--message", "-m"}, description = "Custom commit message")
    private String message;

    @Option(names = {"--no-attribution"}, description = "Skip adding co-authored-by attribution")
    private boolean noAttribution;

    private final ClaudeCodeConfig config;

    @Autowired
    public CommitCommand(ClaudeCodeConfig config) {
        this.config = config;
    }

    @Override
    public Integer call() {
        System.out.println("Creating git commit...");
        // In full implementation, would use Claude to generate commit message
        return 0;
    }
}
