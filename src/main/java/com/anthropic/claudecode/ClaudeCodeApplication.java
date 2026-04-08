package com.anthropic.claudecode;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import com.anthropic.claudecode.command.MainCommand;
import picocli.CommandLine;

/**
 * Main entry point for Claude Code Java CLI.
 * Translated from src/main.tsx
 */
@SpringBootApplication
public class ClaudeCodeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClaudeCodeApplication.class, args);
    }

    @Bean
    public CommandLineRunner commandLineRunner(MainCommand mainCommand) {
        return args -> {
            // Filter out Spring Boot's own arguments (--spring.*, --server.*)
            String[] filteredArgs = java.util.Arrays.stream(args)
                .filter(a -> !a.startsWith("--spring.") && !a.startsWith("--server."))
                .toArray(String[]::new);
            int exitCode = new CommandLine(mainCommand).execute(filteredArgs);
            System.exit(exitCode);
        };
    }
}
