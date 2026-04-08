package com.anthropic.claudecode.util;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Shell provider types.
 * Translated from src/utils/shell/shellProvider.ts
 */
public interface ShellProvider {

    enum ShellType {
        BASH("bash"),
        POWERSHELL("powershell");

        private final String value;
        ShellType(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    ShellType DEFAULT_HOOK_SHELL = ShellType.BASH;

    /**
     * Get the shell type.
     */
    ShellType getType();

    /**
     * Get the shell path.
     */
    String getShellPath();

    /**
     * Get spawn arguments.
     * Translated from ShellProvider.getSpawnArgs() in shellProvider.ts
     */
    List<String> getSpawnArgs(String commandString);

    /**
     * Get environment overrides.
     * Translated from ShellProvider.getEnvironmentOverrides() in shellProvider.ts
     */
    default CompletableFuture<Map<String, String>> getEnvironmentOverrides(String command) {
        return CompletableFuture.completedFuture(Map.of());
    }

    /**
     * Create a bash shell provider.
     */
    static ShellProvider bash() {
        return new ShellProvider() {
            @Override
            public ShellType getType() { return ShellType.BASH; }

            @Override
            public String getShellPath() {
                String shell = System.getenv("SHELL");
                return shell != null ? shell : "/bin/bash";
            }

            @Override
            public List<String> getSpawnArgs(String commandString) {
                return List.of("-c", commandString);
            }
        };
    }
}
