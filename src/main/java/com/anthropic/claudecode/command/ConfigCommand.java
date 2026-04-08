package com.anthropic.claudecode.command;

import com.anthropic.claudecode.config.ClaudeCodeConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;

/**
 * Config management command.
 * Translated from src/commands/config/
 */
@Slf4j
@Component
@Command(
    name = "config",
    description = "Manage Claude Code configuration",
    subcommands = {
        ConfigCommand.GetCommand.class,
        ConfigCommand.SetCommand.class,
        ConfigCommand.ListCommand.class,
    }
)
public class ConfigCommand implements Runnable {



    @Override
    public void run() {
        System.out.println("Use 'claude config --help' for available subcommands.");
    }

    @Component
    @Command(name = "get", description = "Get a configuration value")
    public static class GetCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "Configuration key")
        private String key;

        private final ClaudeCodeConfig config;

        @Autowired
        public GetCommand(ClaudeCodeConfig config) {
            this.config = config;
        }

        @Override
        public Integer call() {
            String value = getConfigValue(key);
            if (value != null) {
                System.out.println(key + "=" + value);
            } else {
                System.err.println("Unknown configuration key: " + key);
                return 1;
            }
            return 0;
        }

        private String getConfigValue(String key) {
            return switch (key) {
                case "model" -> config.getModel();
                case "verbose" -> String.valueOf(config.isVerbose());
                case "permission-mode" -> config.getPermissionMode();
                case "api-key" -> config.getApiKey() != null ? "***" : null;
                default -> null;
            };
        }
    }

    @Component
    @Command(name = "set", description = "Set a configuration value")
    public static class SetCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "Configuration key")
        private String key;

        @Parameters(index = "1", description = "Configuration value")
        private String value;

        private final ClaudeCodeConfig config;

        @Autowired
        public SetCommand(ClaudeCodeConfig config) {
            this.config = config;
        }

        @Override
        public Integer call() {
            boolean success = setConfigValue(key, value);
            if (success) {
                System.out.println("Set " + key + "=" + value);
            } else {
                System.err.println("Unknown configuration key: " + key);
                return 1;
            }
            return 0;
        }

        private boolean setConfigValue(String key, String value) {
            switch (key) {
                case "model" -> { config.setModel(value); return true; }
                case "verbose" -> { config.setVerbose(Boolean.parseBoolean(value)); return true; }
                case "permission-mode" -> { config.setPermissionMode(value); return true; }
                default -> { return false; }
            }
        }
    }

    @Component
    @Command(name = "list", description = "List all configuration values")
    public static class ListCommand implements Callable<Integer> {
        private final ClaudeCodeConfig config;

        @Autowired
        public ListCommand(ClaudeCodeConfig config) {
            this.config = config;
        }

        @Override
        public Integer call() {
            System.out.println("model=" + config.getModel());
            System.out.println("verbose=" + config.isVerbose());
            System.out.println("permission-mode=" + config.getPermissionMode());
            System.out.println("auto-compact-enabled=" + config.isAutoCompactEnabled());
            return 0;
        }
    }
}
