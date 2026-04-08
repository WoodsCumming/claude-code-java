package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Send message tool for multi-agent communication.
 * Translated from src/tools/SendMessageTool/SendMessageTool.ts
 *
 * Sends messages between agents in a multi-agent swarm.
 */
@Slf4j
@Component
public class SendMessageTool extends AbstractTool<SendMessageTool.Input, SendMessageTool.Output> {



    public static final String TOOL_NAME = "SendMessage";

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "to", Map.of("type", "string", "description", "The recipient agent ID or name"),
                "message", Map.of("type", "string", "description", "The message to send")
            ),
            "required", List.of("to", "message")
        );
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
            Input args,
            ToolUseContext context,
            Tool.CanUseToolFn canUseTool,
            Message.AssistantMessage parentMessage,
            Consumer<Tool.ToolProgress> onProgress) {

        log.info("Sending message to {}: {}", args.getTo(), args.getMessage());

        // In a real implementation, this would route the message to the target agent
        return futureResult(Output.builder()
            .delivered(true)
            .to(args.getTo())
            .build());
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Sending message to " + input.getTo());
    }

    @Override
    public boolean isReadOnly(Input input) { return false; }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        String text = content.isDelivered()
            ? "Message delivered to " + content.getTo()
            : "Message delivery failed";
        return Map.of("type", "tool_result", "tool_use_id", toolUseId, "content", text);
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Input {
        private String to;
        private String message;
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Output {
        private boolean delivered;
        private String to;
    }
}
