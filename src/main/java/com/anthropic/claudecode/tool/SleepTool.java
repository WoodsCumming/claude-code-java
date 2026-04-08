package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Sleep tool for waiting.
 * Translated from src/tools/SleepTool/
 *
 * Waits for a specified duration. The user can interrupt at any time.
 */
@Slf4j
@Component
public class SleepTool extends AbstractTool<SleepTool.Input, SleepTool.Output> {



    public static final String TOOL_NAME = "Sleep";

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "duration", Map.of(
                    "type", "number",
                    "description", "Duration to sleep in milliseconds"
                )
            ),
            "required", List.of("duration")
        );
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
            Input args,
            ToolUseContext context,
            Tool.CanUseToolFn canUseTool,
            Message.AssistantMessage parentMessage,
            Consumer<Tool.ToolProgress> onProgress) {

        long durationMs = (long) args.getDuration();
        log.debug("Sleeping for {}ms", durationMs);

        return CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(durationMs);
                return result(Output.builder()
                    .durationMs(durationMs)
                    .interrupted(false)
                    .build());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result(Output.builder()
                    .durationMs(durationMs)
                    .interrupted(true)
                    .build());
            }
        });
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Sleeping for " + (long)input.getDuration() + "ms");
    }

    @Override
    public boolean isReadOnly(Input input) { return true; }

    @Override
    public boolean isConcurrencySafe(Input input) { return true; }

    @Override
    public InterruptBehavior getInterruptBehavior() { return InterruptBehavior.CANCEL; }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        String text = content.isInterrupted()
            ? "Sleep interrupted after " + content.getDurationMs() + "ms"
            : "Slept for " + content.getDurationMs() + "ms";
        return Map.of("type", "tool_result", "tool_use_id", toolUseId, "content", text);
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Input {
        private double duration;
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Output {
        private long durationMs;
        private boolean interrupted;
    }
}
