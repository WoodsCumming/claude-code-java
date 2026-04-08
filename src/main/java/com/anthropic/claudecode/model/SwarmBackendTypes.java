package com.anthropic.claudecode.model;

/**
 * Swarm backend types.
 * Translated from src/utils/swarm/backends/types.ts
 */
public class SwarmBackendTypes {

    /**
     * Types of backends available for teammate execution.
     */
    public enum BackendType {
        TMUX("tmux"),
        ITERM2("iterm2"),
        IN_PROCESS("in-process");

        private final String value;
        BackendType(String value) { this.value = value; }
        public String getValue() { return value; }

        public static BackendType fromValue(String value) {
            for (BackendType type : values()) {
                if (type.value.equals(value)) return type;
            }
            return IN_PROCESS;
        }
    }

    /**
     * Pane-based backend types.
     */
    public enum PaneBackendType {
        TMUX("tmux"),
        ITERM2("iterm2");

        private final String value;
        PaneBackendType(String value) { this.value = value; }
    }

    /**
     * Check if a backend type is a pane backend.
     * Translated from isPaneBackend() in types.ts
     */
    public static boolean isPaneBackend(BackendType type) {
        return type == BackendType.TMUX || type == BackendType.ITERM2;
    }

    private SwarmBackendTypes() {}
}
