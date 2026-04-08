package com.anthropic.claudecode.model;

import lombok.Builder;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * Shell command execution result.
 * Translated from ExecResult type in src/utils/ShellCommand.ts
 */
@Data
@lombok.Builder

public class ExecResult {

    private String stdout;
    private String stderr;
    private int code;
    private boolean interrupted;
    private String backgroundTaskId;
    private Boolean backgroundedByUser;
    private Boolean assistantAutoBackgrounded;
    private String outputFilePath;
    private Long outputFileSize;
    private String outputTaskId;
    private String preSpawnError;

    public boolean isSuccess() {
        return code == 0 && !interrupted && preSpawnError == null;
    }
}
