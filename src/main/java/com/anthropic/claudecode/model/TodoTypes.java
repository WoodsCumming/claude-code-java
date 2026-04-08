package com.anthropic.claudecode.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Builder;

import java.util.List;

/**
 * Todo type definitions.
 * Translated from src/utils/todo/types.ts
 *
 * Defines the TodoItem and TodoList types used by the todo management tool.
 */
public final class TodoTypes {

    private TodoTypes() {}

    // =========================================================================
    // TodoStatus — mirrors TodoStatusSchema in types.ts
    // =========================================================================

    /**
     * Status values for a TodoItem.
     * Translated from TodoStatusSchema (z.enum) in types.ts
     */
    public enum TodoStatus {
        pending,
        in_progress,
        completed;

        /** Get the snake_case string value used in the API / JSON. */
        public String getValue() {
            return name(); // name() already returns snake_case for these values
        }
    }

    // =========================================================================
    // TodoItem — mirrors TodoItemSchema in types.ts
    // =========================================================================

    /**
     * A single todo item.
     * Translated from TodoItem / TodoItemSchema in types.ts
     *
     * Validation rules (mirroring Zod):
     *   - content must be non-empty
     *   - activeForm must be non-empty
     *   - status must be one of the TodoStatus enum values
     */
    @Data
    @lombok.Builder
    
    public static class TodoItem {

        /** The text content of the todo. Must not be empty. */
        private String content;

        /** Current status of the todo item. */
        private TodoStatus status;

        /**
         * Present-continuous form of the action (e.g., "Fixing authentication bug"),
         * shown in the spinner when the item is in_progress. Must not be empty.
         */
        private String activeForm;

        // -------------------------------------------------------------------------
        // Validation helpers
        // -------------------------------------------------------------------------

        /**
         * Validate this item against the Zod schema rules.
         * Throws IllegalArgumentException if validation fails.
         */
        public void validate() {
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("Content cannot be empty");
            }
            if (status == null) {
                throw new IllegalArgumentException("Status must not be null");
            }
            if (activeForm == null || activeForm.isBlank()) {
                throw new IllegalArgumentException("Active form cannot be empty");
            }
        }

        /**
         * Return true when this item passes all Zod validation rules.
         */
        public boolean isValid() {
            try {
                validate();
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
    }

    // =========================================================================
    // TodoList — mirrors TodoListSchema (z.array(TodoItemSchema())) in types.ts
    // =========================================================================

    /**
     * Type alias for a list of TodoItems.
     * Translated from TodoList / TodoListSchema in types.ts
     */
    public static List<TodoItem> emptyTodoList() {
        return List.of();
    }
}
