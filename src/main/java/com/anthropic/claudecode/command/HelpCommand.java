package com.anthropic.claudecode.command;

import com.anthropic.claudecode.config.ClaudeCodeConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;

/**
 * Help command for showing available commands.
 * Translated from src/commands/help/
 */
@Slf4j
@Component
@Command(
    name = "help",
    description = "Show help and available commands"
)
public class HelpCommand implements Callable<Integer> {



    private final ClaudeCodeConfig config;

    @Autowired
    public HelpCommand(ClaudeCodeConfig config) {
        this.config = config;
    }

    @Override
    public Integer call() {
        System.out.println("Claude Code v" + config.getVersion());
        System.out.println();
        System.out.println("Available slash commands:");
        System.out.println("  /help          Show this help");
        System.out.println("  /clear         Clear conversation history");
        System.out.println("  /compact       Compact conversation with summary");
        System.out.println("  /commit        Create a git commit");
        System.out.println("  /review        Review a pull request");
        System.out.println("  /init          Initialize CLAUDE.md");
        System.out.println("  /stats         Show usage statistics");
        System.out.println("  /doctor        Diagnose installation");
        System.out.println("  /exit          Exit Claude Code");
        System.out.println();
        System.out.println("For more information, visit: https://claude.com/claude-code");
        return 0;
    }
}
