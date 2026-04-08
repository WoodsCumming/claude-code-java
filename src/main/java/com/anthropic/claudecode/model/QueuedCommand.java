package com.anthropic.claudecode.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Builder;
import java.util.Map;

/**
 * Queued command for the REPL input queue.
 * Translated from QueuedCommand type in src/types/textInputTypes.ts
 */
@Data
@lombok.Builder

public class QueuedCommand {

    public enum Priority {
        NOW,
        NEXT,
        LATER
    }

    private String id;
    private String content;
    private Priority priority;
    private Map<Integer, Object> pastedContents;
    private long timestamp;
    private boolean isSlashCommand;

    public static QueuedCommand userInput(String content, Priority priority) {
        return QueuedCommand.builder()
            .id(java.util.UUID.randomUUID().toString())
            .content(content)
            .priority(priority)
            .timestamp(System.currentTimeMillis())
            .isSlashCommand(content != null && content.startsWith("/"))
            .build();
    }
}
