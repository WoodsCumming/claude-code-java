package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import lombok.Data;

/**
 * Todo/task list management tool.
 * Translated from src/tools/TodoWriteTool/TodoWriteTool.ts
 *
 * Creates and updates a structured task list for the current coding session.
 */
@Slf4j
@Component
public class TodoWriteTool extends AbstractTool<TodoWriteTool.Input, TodoWriteTool.Output> {



    public static final String TOOL_NAME = "TodoWrite";

    // In-memory todo list (in production, would be persisted per session)
    private List<TodoItem> currentTodos = new ArrayList<>();

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getSearchHint() {
        return "manage the session task checklist";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "todos", Map.of(
                    "type", "array",
                    "description", "The updated todo list",
                    "items", Map.of(
                        "type", "object",
                        "properties", Map.of(
                            "id", Map.of("type", "string"),
                            "content", Map.of("type", "string"),
                            "status", Map.of(
                                "type", "string",
                                "enum", List.of("pending", "in_progress", "completed")
                            ),
                            "priority", Map.of(
                                "type", "string",
                                "enum", List.of("high", "medium", "low")
                            )
                        ),
                        "required", List.of("id", "content", "status", "priority")
                    )
                )
            ),
            "required", List.of("todos")
        );
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
            Input args,
            ToolUseContext context,
            Tool.CanUseToolFn canUseTool,
            Message.AssistantMessage parentMessage,
            Consumer<Tool.ToolProgress> onProgress) {

        List<TodoItem> oldTodos = new ArrayList<>(currentTodos);
        currentTodos = args.getTodos() != null ? new ArrayList<>(args.getTodos()) : new ArrayList<>();

        Output output = Output.builder()
            .oldTodos(oldTodos)
            .newTodos(currentTodos)
            .build();

        return futureResult(output);
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        int count = input != null && input.getTodos() != null ? input.getTodos().size() : 0;
        return CompletableFuture.completedFuture("Updating todo list (" + count + " items)");
    }

    @Override
    public boolean isReadOnly(Input input) { return false; }

    @Override
    public boolean isConcurrencySafe(Input input) { return false; }

    @Override
    public String userFacingName(Input input) { return ""; }

    @Override
    public Object toAutoClassifierInput(Input input) {
        if (input == null || input.getTodos() == null) return "0 items";
        return input.getTodos().size() + " items";
    }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        // TodoWrite doesn't render in the transcript - it updates the todo panel
        return Map.of(
            "type", "tool_result",
            "tool_use_id", toolUseId,
            "content", "Todo list updated."
        );
    }

    // =========================================================================
    // Input/Output types
    // =========================================================================

    @Data
    @lombok.Builder
    
    public static class Input {
        private List<TodoItem> todos;
    }

    @Data
    @lombok.Builder
    
    public static class Output {
        private List<TodoItem> oldTodos;
        private List<TodoItem> newTodos;
        private Boolean verificationNudgeNeeded;
    }

    @Data
    @lombok.Builder
    
    public static class TodoItem {
        private String id;
        private String content;
        private String status; // "pending" | "in_progress" | "completed"
        private String priority; // "high" | "medium" | "low"
    }
}
