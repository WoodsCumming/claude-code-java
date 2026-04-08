package com.anthropic.claudecode.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Local shell task state types.
 * Translated from src/tasks/LocalShellTask/guards.ts
 */
public class LocalShellTaskState {

    public enum BashTaskKind {
        BASH("bash"),
        MONITOR("monitor");

        private final String value;
        BashTaskKind(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TaskResult {
        private int code;
        private boolean interrupted;

        public int getCode() { return code; }
        public void setCode(int v) { code = v; }
        public boolean isInterrupted() { return interrupted; }
        public void setInterrupted(boolean v) { interrupted = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TaskState {
        private String id;
        private String type = "local_bash";
        private String status = "running";
        private String description;
        private String toolUseId;
        private long startTime;
        private String command;
        private TaskResult result;
        private boolean completionStatusSentInAttachment = false;
        private int lastReportedTotalLines = 0;
        private boolean backgrounded = false;
        private String agentId;
        private BashTaskKind kind;
        private String outputFile;
        private int outputOffset = 0;
        private boolean notified = false;

        public String getId() { return id; }
        public void setId(String v) { id = v; }
        public String getType() { return type; }
        public void setType(String v) { type = v; }
        public String getStatus() { return status; }
        public void setStatus(String v) { status = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
        public String getToolUseId() { return toolUseId; }
        public void setToolUseId(String v) { toolUseId = v; }
        public long getStartTime() { return startTime; }
        public void setStartTime(long v) { startTime = v; }
        public String getCommand() { return command; }
        public void setCommand(String v) { command = v; }
        public TaskResult getResult() { return result; }
        public void setResult(TaskResult v) { result = v; }
        public boolean isCompletionStatusSentInAttachment() { return completionStatusSentInAttachment; }
        public void setCompletionStatusSentInAttachment(boolean v) { completionStatusSentInAttachment = v; }
        public int getLastReportedTotalLines() { return lastReportedTotalLines; }
        public void setLastReportedTotalLines(int v) { lastReportedTotalLines = v; }
        public boolean isBackgrounded() { return backgrounded; }
        public void setBackgrounded(boolean v) { backgrounded = v; }
        public String getAgentId() { return agentId; }
        public void setAgentId(String v) { agentId = v; }
        public BashTaskKind getKind() { return kind; }
        public void setKind(BashTaskKind v) { kind = v; }
        public String getOutputFile() { return outputFile; }
        public void setOutputFile(String v) { outputFile = v; }
        public int getOutputOffset() { return outputOffset; }
        public void setOutputOffset(int v) { outputOffset = v; }
        public boolean isNotified() { return notified; }
        public void setNotified(boolean v) { notified = v; }
    }

    /**
     * Check if a task is a local shell task.
     * Translated from isLocalShellTask() in guards.ts
     */
    public static boolean isLocalShellTask(Object task) {
        if (!(task instanceof TaskState ts)) return false;
        return "local_bash".equals(ts.getType());
    }
}
