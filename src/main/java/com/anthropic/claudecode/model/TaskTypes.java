package com.anthropic.claudecode.model;

import java.util.*;

/**
 * Task type definitions.
 * Translated from src/Task.ts
 */
public class TaskTypes {

    public enum TaskType {
        LOCAL_BASH("local_bash"),
        LOCAL_AGENT("local_agent"),
        REMOTE_AGENT("remote_agent"),
        IN_PROCESS_TEAMMATE("in_process_teammate"),
        LOCAL_WORKFLOW("local_workflow"),
        MONITOR_MCP("monitor_mcp"),
        DREAM("dream");

        private final String value;
        TaskType(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    public enum TaskStatus {
        PENDING("pending"),
        RUNNING("running"),
        COMPLETED("completed"),
        FAILED("failed"),
        KILLED("killed");

        private final String value;
        TaskStatus(String value) { this.value = value; }

        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED || this == KILLED;
        }
    }

    // Task ID prefixes for generating task IDs
    public static final Map<String, String> TASK_ID_PREFIXES = Map.of(
        "local_bash", "b",
        "local_agent", "a",
        "remote_agent", "r",
        "in_process_teammate", "t",
        "local_workflow", "w",
        "monitor_mcp", "m",
        "dream", "d"
    );

    /**
     * Generate a task ID with the appropriate prefix.
     * Translated from generateTaskId() in Task.ts
     */
    public static String generateTaskId(String taskType) {
        String prefix = TASK_ID_PREFIXES.getOrDefault(taskType, "x");
        byte[] bytes = new byte[8];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(prefix);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private TaskTypes() {}
}
