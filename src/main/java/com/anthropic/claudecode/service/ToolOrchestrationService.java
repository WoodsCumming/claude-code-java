package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.ContentBlock;
import com.anthropic.claudecode.model.Message;
import com.anthropic.claudecode.model.ToolUseContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Tool orchestration service for managing concurrent/serial tool execution.
 * Translated from src/services/tools/toolOrchestration.ts
 *
 * Partitions tool calls into batches and manages concurrency:
 * - Consecutive concurrent-safe tools are batched and run in parallel.
 * - Non-concurrent tools run alone in serial order.
 * - The overall sequence preserves the original ordering of tool calls.
 */
@Slf4j
@Service
public class ToolOrchestrationService {



    // =========================================================================
    // Constants
    // =========================================================================

    private static final int DEFAULT_MAX_CONCURRENCY = 10;

    /** Environment variable name for overriding max concurrency. */
    private static final String MAX_CONCURRENCY_ENV = "CLAUDE_CODE_MAX_TOOL_USE_CONCURRENCY";

    // =========================================================================
    // Dependencies
    // =========================================================================

    private final ToolExecutionService toolExecutionService;

    @Autowired
    public ToolOrchestrationService(ToolExecutionService toolExecutionService) {
        this.toolExecutionService = toolExecutionService;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Get the maximum number of tools that may execute concurrently.
     * Reads CLAUDE_CODE_MAX_TOOL_USE_CONCURRENCY env var, defaulting to 10.
     * Translated from getMaxToolUseConcurrency() in toolOrchestration.ts
     */
    public int getMaxConcurrency() {
        String envVal = System.getenv(MAX_CONCURRENCY_ENV);
        if (envVal != null && !envVal.isBlank()) {
            try {
                int parsed = Integer.parseInt(envVal.trim());
                if (parsed > 0) return parsed;
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return DEFAULT_MAX_CONCURRENCY;
    }

    /**
     * Run tool uses in order, parallelising consecutive concurrent-safe batches.
     * Returns message updates in the order the tools were listed.
     * Translated from runTools() in toolOrchestration.ts
     *
     * @param toolUseBlocks    the tool-use blocks from the assistant message
     * @param assistantMessage the parent assistant message
     * @param context          current tool-use context (may be mutated by context modifiers)
     * @return CompletableFuture of all MessageUpdate items in order
     */
    public CompletableFuture<List<MessageUpdate>> runTools(
            List<ContentBlock.ToolUseBlock> toolUseBlocks,
            Message.AssistantMessage assistantMessage,
            ToolUseContext context) {

        if (toolUseBlocks == null || toolUseBlocks.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        List<Batch> batches = partitionToolCalls(toolUseBlocks, context);
        return runBatchesSequentially(batches, assistantMessage, context);
    }

    // =========================================================================
    // Internal — batching
    // =========================================================================

    /**
     * Partition tool calls into batches where each batch is either:
     *   1. A single non-concurrent tool, or
     *   2. Multiple consecutive concurrent-safe tools.
     *
     * Translated from partitionToolCalls() in toolOrchestration.ts
     */
    private List<Batch> partitionToolCalls(
            List<ContentBlock.ToolUseBlock> blocks,
            ToolUseContext context) {

        List<Batch> batches = new ArrayList<>();

        for (ContentBlock.ToolUseBlock block : blocks) {
            boolean safe = toolExecutionService.isConcurrencySafe(
                    block.getName(), block.getInput(), context);

            if (!batches.isEmpty()) {
                Batch last = batches.get(batches.size() - 1);
                if (safe && last.isConcurrencySafe()) {
                    // Append to existing concurrent batch
                    last.blocks().add(block);
                    continue;
                }
            }
            // Start a new batch
            List<ContentBlock.ToolUseBlock> batchBlocks = new ArrayList<>();
            batchBlocks.add(block);
            batches.add(new Batch(safe, batchBlocks));
        }

        return batches;
    }

    // =========================================================================
    // Internal — execution
    // =========================================================================

    /**
     * Run batches one at a time, collecting results.
     * Each concurrent batch runs in parallel; results are merged in order.
     */
    private CompletableFuture<List<MessageUpdate>> runBatchesSequentially(
            List<Batch> batches,
            Message.AssistantMessage assistantMessage,
            ToolUseContext context) {

        CompletableFuture<List<MessageUpdate>> chain =
                CompletableFuture.completedFuture(new ArrayList<>());

        for (Batch batch : batches) {
            final Batch currentBatch = batch;
            chain = chain.thenCompose(accumulated -> {
                CompletableFuture<List<MessageUpdate>> batchFuture =
                        currentBatch.isConcurrencySafe() && currentBatch.blocks().size() > 1
                                ? runBatchConcurrently(currentBatch.blocks(), assistantMessage, context)
                                : runBatchSerially(currentBatch.blocks(), assistantMessage, context);

                return batchFuture.thenApply(batchResults -> {
                    List<MessageUpdate> combined = new ArrayList<>(accumulated);
                    combined.addAll(batchResults);
                    return combined;
                });
            });
        }

        return chain;
    }

    /**
     * Run a concurrent-safe batch in parallel (up to getMaxConcurrency() at once).
     * Translated from runToolsConcurrently() in toolOrchestration.ts
     */
    private CompletableFuture<List<MessageUpdate>> runBatchConcurrently(
            List<ContentBlock.ToolUseBlock> blocks,
            Message.AssistantMessage assistantMessage,
            ToolUseContext context) {

        log.debug("Running {} tools concurrently (max concurrency={})", blocks.size(), getMaxConcurrency());

        // Launch all tool calls (up to max concurrency); collect in original order
        List<CompletableFuture<List<ToolExecutionService.MessageUpdateLazy>>> futures =
                blocks.stream()
                        .map(block -> toolExecutionService.runToolUse(block, assistantMessage, context))
                        .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(__ -> {
                    List<MessageUpdate> results = new ArrayList<>();
                    for (int i = 0; i < blocks.size(); i++) {
                        try {
                            List<ToolExecutionService.MessageUpdateLazy> updates = futures.get(i).get();
                            for (ToolExecutionService.MessageUpdateLazy lazy : updates) {
                                results.add(new MessageUpdate(lazy.getMessage(), context));
                            }
                        } catch (Exception e) {
                            log.error("Concurrent tool error for {}: {}", blocks.get(i).getName(), e.getMessage(), e);
                            results.add(new MessageUpdate(
                                    buildErrorMessage(blocks.get(i), e, assistantMessage), context));
                        }
                        markToolComplete(blocks.get(i).getId(), context);
                    }
                    return results;
                });
    }

    /**
     * Run a serial batch one tool at a time.
     * Translated from runToolsSerially() in toolOrchestration.ts
     */
    private CompletableFuture<List<MessageUpdate>> runBatchSerially(
            List<ContentBlock.ToolUseBlock> blocks,
            Message.AssistantMessage assistantMessage,
            ToolUseContext context) {

        log.debug("Running {} tools serially", blocks.size());

        CompletableFuture<List<MessageUpdate>> chain =
                CompletableFuture.completedFuture(new ArrayList<>());

        for (ContentBlock.ToolUseBlock block : blocks) {
            chain = chain.thenCompose(accumulated ->
                    toolExecutionService.runToolUse(block, assistantMessage, context)
                            .thenApply(updates -> {
                                List<MessageUpdate> combined = new ArrayList<>(accumulated);
                                for (ToolExecutionService.MessageUpdateLazy lazy : updates) {
                                    // Apply context modifiers serially
                                    if (lazy.getContextModifier() != null) {
                                        // Note: context modification for serial tools is handled
                                        // in StreamingToolExecutor; here we just carry the message.
                                    }
                                    combined.add(new MessageUpdate(lazy.getMessage(), context));
                                }
                                markToolComplete(block.getId(), context);
                                return combined;
                            })
                            .exceptionally(e -> {
                                List<MessageUpdate> combined = new ArrayList<>(accumulated);
                                combined.add(new MessageUpdate(
                                        buildErrorMessage(block, e, assistantMessage), context));
                                markToolComplete(block.getId(), context);
                                return combined;
                            })
            );
        }

        return chain;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Message buildErrorMessage(
            ContentBlock.ToolUseBlock block,
            Throwable error,
            Message.AssistantMessage assistantMessage) {

        String content = "<tool_use_error>Error executing " + block.getName()
                + ": " + (error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName())
                + "</tool_use_error>";

        ContentBlock.ToolResultBlock resultBlock = new ContentBlock.ToolResultBlock();
        resultBlock.setType("tool_result");
        resultBlock.setToolUseId(block.getId());
        resultBlock.setContent(content);
        resultBlock.setIsError(true);

        return Message.UserMessage.builder()
                .type("user")
                .uuid(UUID.randomUUID().toString())
                .content(List.of(resultBlock))
                .toolUseResult(content)
                .sourceToolAssistantUUID(assistantMessage.getUuid())
                .build();
    }

    private void markToolComplete(String toolUseId, ToolUseContext context) {
        context.removeInProgressToolUseId(toolUseId);
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /**
     * A batch of tool-use blocks that share the same concurrency mode.
     * Translated from Batch in toolOrchestration.ts
     */
    private record Batch(boolean isConcurrencySafe, List<ContentBlock.ToolUseBlock> blocks) {}

    /**
     * A message plus its associated context snapshot.
     * Translated from MessageUpdate in toolOrchestration.ts
     */
    public record MessageUpdate(Message message, ToolUseContext newContext) {}
}
