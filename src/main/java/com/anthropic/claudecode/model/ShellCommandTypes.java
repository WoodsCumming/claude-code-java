package com.anthropic.claudecode.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Shell command types.
 * Translated from src/utils/ShellCommand.ts
 */
public class ShellCommandTypes {

    /**
     * Result of executing a shell command.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ExecResult {
        private String stdout;
        private String stderr;
        private int code;
        private boolean interrupted;
        private String backgroundTaskId;
        private boolean backgroundedByUser;
        private boolean assistantAutoBackgrounded;
        private String outputFilePath;
        private Long outputFileSize;
        private String outputTaskId;
        private String preSpawnError;

        public String getStdout() { return stdout; }
        public void setStdout(String v) { stdout = v; }
        public String getStderr() { return stderr; }
        public void setStderr(String v) { stderr = v; }
        public int getCode() { return code; }
        public void setCode(int v) { code = v; }
        public boolean isInterrupted() { return interrupted; }
        public void setInterrupted(boolean v) { interrupted = v; }
        public String getBackgroundTaskId() { return backgroundTaskId; }
        public void setBackgroundTaskId(String v) { backgroundTaskId = v; }
        public boolean isBackgroundedByUser() { return backgroundedByUser; }
        public void setBackgroundedByUser(boolean v) { backgroundedByUser = v; }
        public boolean isAssistantAutoBackgrounded() { return assistantAutoBackgrounded; }
        public void setAssistantAutoBackgrounded(boolean v) { assistantAutoBackgrounded = v; }
        public String getOutputFilePath() { return outputFilePath; }
        public void setOutputFilePath(String v) { outputFilePath = v; }
        public Long getOutputFileSize() { return outputFileSize; }
        public void setOutputFileSize(Long v) { outputFileSize = v; }
        public String getOutputTaskId() { return outputTaskId; }
        public void setOutputTaskId(String v) { outputTaskId = v; }
        public String getPreSpawnError() { return preSpawnError; }
        public void setPreSpawnError(String v) { preSpawnError = v; }
    }

    public enum ShellCommandStatus {
        RUNNING("running"),
        BACKGROUNDED("backgrounded"),
        COMPLETED("completed"),
        KILLED("killed");

        private final String value;
        ShellCommandStatus(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    private ShellCommandTypes() {}
}
