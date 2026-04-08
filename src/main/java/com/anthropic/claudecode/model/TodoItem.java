package com.anthropic.claudecode.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Builder;

/**
 * Todo item type.
 * Translated from src/utils/todo/types.ts
 */
@Data
@lombok.Builder

public class TodoItem {

    private String content;
    private String status; // "pending" | "in_progress" | "completed"
    private String activeForm;

    public enum Status {
        PENDING("pending"),
        IN_PROGRESS("in_progress"),
        COMPLETED("completed");

        private final String value;
        Status(String value) { this.value = value; }
        public String getValue() { return value; }
    }
}
