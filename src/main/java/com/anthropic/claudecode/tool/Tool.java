package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Core Tool interface.
 * Translated from src/Tool.ts Tool type.
 *
 * In TypeScript, Tool is a generic type with Input (Zod schema), Output, and Progress.
 * In Java, we use generics with Input as Map<String,Object> (JSON input),
 * Output as the result type, and Progress as the progress type.
 *
 * Static helpers toolMatchesName(), findToolByName(), and buildTool() are
 * translated from the same-named exports in Tool.ts.
 */
public interface Tool<Input, Output> {

    /**
     * The primary name of this tool.
     */
    String getName();

    /**
     * Optional aliases for backwards compatibility.
     */
    default List<String> getAliases() {
        return List.of();
    }

    /**
     * Optional search hint for keyword matching.
     * 3-10 words, helps the model find this tool via keyword search.
     */
    default String getSearchHint() {
        return null;
    }

    /**
     * Execute the tool with the given input.
     * Translated from Tool.call()
     *
     * @param args       Parsed tool input
     * @param context    Execution context
     * @param canUseTool Function to check if tool can be used
     * @param onProgress Optional progress callback
     * @return Future with the tool result
     */
    CompletableFuture<ToolResult<Output>> call(
        Input args,
        ToolUseContext context,
        CanUseToolFn canUseTool,
        Message.AssistantMessage parentMessage,
        Consumer<ToolProgress> onProgress
    );

    /**
     * Returns a human-readable description of this tool use.
     */
    CompletableFuture<String> description(
        Input input,
        DescriptionOptions options
    );

    /**
     * Returns the JSON schema for this tool's input.
     */
    Map<String, Object> getInputSchema();

    /**
     * Whether this tool can run concurrently with other tools.
     * Defaults to false (assume not safe).
     */
    default boolean isConcurrencySafe(Input input) {
        return false;
    }

    /**
     * Whether this tool is currently enabled.
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * Whether this tool only reads (doesn't write).
     */
    default boolean isReadOnly(Input input) {
        return false;
    }

    /**
     * Whether this tool performs irreversible operations.
     */
    default boolean isDestructive(Input input) {
        return false;
    }

    /**
     * Check tool-specific permissions.
     * General permission logic is in PermissionService.
     */
    default CompletableFuture<PermissionResult> checkPermissions(
        Input input,
        ToolUseContext context
    ) {
        return CompletableFuture.completedFuture(
            PermissionResult.AllowDecision.builder()
                .updatedInput(input instanceof Map ? (Map<String, Object>) input : null)
                .build()
        );
    }

    /**
     * Validate the input before execution.
     */
    default CompletableFuture<ValidationResult> validateInput(
        Input input,
        ToolUseContext context
    ) {
        return CompletableFuture.completedFuture(ValidationResult.ok());
    }

    /**
     * Maximum size in characters for tool result before it gets persisted to disk.
     */
    default int getMaxResultSizeChars() {
        return 200_000;
    }

    /**
     * Returns a user-facing name for this tool use.
     */
    default String userFacingName(Input input) {
        return getName();
    }

    /**
     * Returns a compact representation for the auto-mode security classifier.
     */
    default Object toAutoClassifierInput(Input input) {
        return "";
    }

    /**
     * Returns whether this tool is a search or read operation.
     */
    default SearchOrReadInfo isSearchOrReadCommand(Input input) {
        return SearchOrReadInfo.NONE;
    }

    /**
     * What should happen when user submits a new message while this tool is running.
     */
    default InterruptBehavior getInterruptBehavior() {
        return InterruptBehavior.BLOCK;
    }

    /**
     * Returns a short string summary of this tool use.
     */
    default String getToolUseSummary(Input input) {
        return null;
    }

    /**
     * Returns the description of this tool.
     */
    default String getDescription() {
        return null;
    }

    /**
     * Whether this tool is an MCP (Model Context Protocol) tool.
     */
    default boolean isMcp() {
        return false;
    }

    /**
     * Whether this tool should be deferred (loaded lazily via tool search).
     */
    default boolean isShouldDefer() {
        return false;
    }

    /**
     * Returns a human-readable present-tense activity description.
     * Example: "Reading src/foo.ts", "Running bun test"
     */
    default String getActivityDescription(Input input) {
        return null;
    }

    /**
     * Convert the tool result to a ToolResultBlockParam for the API.
     */
    default Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("type", "tool_result");
        result.put("tool_use_id", toolUseId);
        result.put("content", content != null ? content.toString() : "");
        return result;
    }

    // =========================================================================
    // Nested types
    // =========================================================================

    record ValidationResult(boolean valid, String message, int errorCode) {
        public static ValidationResult ok() {
            return new ValidationResult(true, null, 0);
        }

        public static ValidationResult invalid(String message, int errorCode) {
            return new ValidationResult(false, message, errorCode);
        }
    }

    record SearchOrReadInfo(boolean isSearch, boolean isRead, boolean isList) {
        public static final SearchOrReadInfo NONE = new SearchOrReadInfo(false, false, false);
        public static final SearchOrReadInfo SEARCH = new SearchOrReadInfo(true, false, false);
        public static final SearchOrReadInfo READ = new SearchOrReadInfo(false, true, false);
        public static final SearchOrReadInfo LIST = new SearchOrReadInfo(false, false, true);
    }

    enum InterruptBehavior {
        CANCEL,
        BLOCK
    }

    record DescriptionOptions(
        boolean isNonInteractiveSession,
        ToolPermissionContext toolPermissionContext,
        List<Tool<?, ?>> tools
    ) {}

    record ToolProgress(String toolUseId, Object data) {}

    @FunctionalInterface
    interface CanUseToolFn {
        CompletableFuture<PermissionResult> canUseTool(
            Tool<?, ?> tool,
            Map<String, Object> input,
            ToolUseContext context
        );
    }

    // =========================================================================
    // Static helpers
    // Translated from toolMatchesName(), findToolByName(), buildTool() in Tool.ts
    // =========================================================================

    /**
     * Checks if a tool matches the given name (primary name or any alias).
     * Translated from toolMatchesName() in Tool.ts
     */
    static boolean toolMatchesName(Tool<?, ?> tool, String name) {
        if (tool.getName().equals(name)) return true;
        List<String> aliases = tool.getAliases();
        return aliases != null && aliases.contains(name);
    }

    /**
     * Finds a tool by name or alias from a list of tools.
     * Translated from findToolByName() in Tool.ts
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Optional<Tool<?, ?>> findToolByName(List<? extends Tool<?, ?>> tools, String name) {
        for (Tool<?, ?> t : tools) {
            if (toolMatchesName(t, name)) {
                return Optional.of((Tool<?, ?>) (Tool) t);
            }
        }
        return Optional.empty();
    }
}
