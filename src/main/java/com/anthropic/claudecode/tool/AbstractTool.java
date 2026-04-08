package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Abstract base class for tools providing default implementations.
 * Translated from buildTool() factory function in src/Tool.ts
 *
 * The TypeScript buildTool() fills in default implementations for:
 * - isEnabled → true
 * - isConcurrencySafe → false
 * - isReadOnly → false
 * - isDestructive → false
 * - checkPermissions → allow
 * - toAutoClassifierInput → ''
 * - userFacingName → name
 */
public abstract class AbstractTool<Input, Output> implements Tool<Input, Output> {

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public java.util.concurrent.CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return java.util.concurrent.CompletableFuture.completedFuture(getDescription());
    }

    /**
     * Returns a static description of this tool.
     * Subclasses should override this to provide a meaningful description.
     */
    public String getDescription() {
        return getName();
    }

    @Override
    public boolean isConcurrencySafe(Input input) {
        return false;
    }

    @Override
    public boolean isReadOnly(Input input) {
        return false;
    }

    @Override
    public boolean isDestructive(Input input) {
        return false;
    }

    @Override
    public CompletableFuture<PermissionResult> checkPermissions(
            Input input,
            ToolUseContext context) {
        return CompletableFuture.completedFuture(
            PermissionResult.AllowDecision.builder().build()
        );
    }

    @Override
    public Object toAutoClassifierInput(Input input) {
        return "";
    }

    @Override
    public String userFacingName(Input input) {
        return getName();
    }

    @Override
    public CompletableFuture<ValidationResult> validateInput(
            Input input,
            ToolUseContext context) {
        return CompletableFuture.completedFuture(ValidationResult.ok());
    }

    @Override
    public SearchOrReadInfo isSearchOrReadCommand(Input input) {
        return SearchOrReadInfo.NONE;
    }

    @Override
    public InterruptBehavior getInterruptBehavior() {
        return InterruptBehavior.BLOCK;
    }

    @Override
    public String getToolUseSummary(Input input) {
        return null;
    }

    @Override
    public String getActivityDescription(Input input) {
        return null;
    }

    @Override
    public int getMaxResultSizeChars() {
        return 200_000;
    }

    @Override
    public List<String> getAliases() {
        return List.of();
    }

    @Override
    public String getSearchHint() {
        return null;
    }

    /**
     * Helper to create a completed ToolResult.
     */
    protected ToolResult<Output> result(Output data) {
        return ToolResult.of(data);
    }

    /**
     * Helper to create a completed ToolResult with new messages.
     */
    protected ToolResult<Output> result(Output data, List<Message> newMessages) {
        return ToolResult.of(data, newMessages);
    }

    /**
     * Helper to create a completed future with ToolResult.
     */
    protected CompletableFuture<ToolResult<Output>> futureResult(Output data) {
        return CompletableFuture.completedFuture(result(data));
    }

    /**
     * Helper to create a failed future.
     */
    protected CompletableFuture<ToolResult<Output>> failedFuture(Throwable cause) {
        CompletableFuture<ToolResult<Output>> future = new CompletableFuture<>();
        future.completeExceptionally(cause);
        return future;
    }
}
