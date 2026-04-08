package com.anthropic.claudecode.service;

import com.anthropic.claudecode.client.AnthropicClient;
import com.anthropic.claudecode.model.Message;
import com.anthropic.claudecode.util.MessageUtils;
import com.anthropic.claudecode.util.ModelUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;

/**
 * Away summary service.
 * Translated from src/services/awaySummary.ts
 *
 * Generates a short session recap for the "while you were away" card.
 * Returns empty on abort, empty transcript, or error.
 */
@Slf4j
@Service
public class AwaySummaryService {



    /**
     * Recap only needs recent context — truncate to avoid "prompt too long"
     * on large sessions. 30 messages ≈ ~15 exchanges, plenty for "where we left off."
     * Translated from RECENT_MESSAGE_WINDOW constant in awaySummary.ts
     */
    private static final int RECENT_MESSAGE_WINDOW = 30;

    private final AnthropicClient anthropicClient;
    private final SessionMemoryService sessionMemoryService;

    @Autowired
    public AwaySummaryService(AnthropicClient anthropicClient,
                               SessionMemoryService sessionMemoryService) {
        this.anthropicClient = anthropicClient;
        this.sessionMemoryService = sessionMemoryService;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Generates a short session recap for the "while you were away" card.
     * Returns {@link Optional#empty()} on abort, empty transcript, or error.
     *
     * Translated from generateAwaySummary() in awaySummary.ts
     *
     * @param messages all messages in the current session
     * @param signal   cancellation signal; the future will complete empty when cancelled
     */
    public CompletableFuture<Optional<String>> generateAwaySummary(
            List<Message> messages,
            java.util.concurrent.atomic.AtomicBoolean signal) {

        if (messages == null || messages.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Get broader session memory if available
                String memory = sessionMemoryService.getSessionMemoryContent();

                // Slice to the most recent RECENT_MESSAGE_WINDOW messages
                List<Message> recent = messages.subList(
                        Math.max(0, messages.size() - RECENT_MESSAGE_WINDOW),
                        messages.size());

                // Build the conversation for the API call
                List<Map<String, Object>> apiMessages = buildApiMessages(recent);

                // Append the summary prompt as the final user turn
                String prompt = buildAwaySummaryPrompt(memory);
                apiMessages.add(Map.of("role", "user", "content", prompt));

                // Check for cancellation before the network call
                if (signal != null && signal.get()) {
                    return Optional.<String>empty();
                }

                AnthropicClient.MessageRequest request = AnthropicClient.MessageRequest.builder()
                        .model(ModelUtils.getSmallFastModel())
                        .maxTokens(200)
                        .messages(apiMessages)
                        .build();

                AnthropicClient.MessageResponse response =
                        anthropicClient.createMessage(request).get();

                // Check for cancellation after the network call
                if (signal != null && signal.get()) {
                    return Optional.<String>empty();
                }

                if (response == null || response.getContent() == null) {
                    return Optional.empty();
                }

                // Extract the first text block
                for (Map<String, Object> block : response.getContent()) {
                    if ("text".equals(block.get("type"))) {
                        String text = (String) block.get("text");
                        if (text != null && !text.isBlank()) {
                            return Optional.of(text.trim());
                        }
                    }
                }

                return Optional.empty();

            } catch (CancellationException e) {
                return Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (Exception e) {
                log.debug("[AwaySummary] generation failed: {}", e.getMessage());
                return Optional.empty();
            }
        });
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Build the away summary prompt, optionally prefixed with session memory.
     * Translated from buildAwaySummaryPrompt() in awaySummary.ts
     */
    private String buildAwaySummaryPrompt(String memory) {
        String memoryBlock = (memory != null && !memory.isBlank())
                ? "Session memory (broader context):\n" + memory + "\n\n"
                : "";

        return memoryBlock
                + "The user stepped away and is coming back. Write exactly 1-3 short sentences. "
                + "Start by stating the high-level task \u2014 what they are building or debugging, "
                + "not implementation details. Next: the concrete next step. "
                + "Skip status reports and commit recaps.";
    }

    /**
     * Convert internal Message objects to Anthropic API message maps.
     */
    private List<Map<String, Object>> buildApiMessages(List<Message> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Message msg : messages) {
            if (msg instanceof Message.UserMessage userMsg) {
                String text = MessageUtils.getUserMessageText(userMsg);
                if (!text.isBlank()) {
                    result.add(Map.of("role", "user", "content", text));
                }
            } else if (msg instanceof Message.AssistantMessage assistantMsg) {
                String text = MessageUtils.getAssistantMessageText(assistantMsg);
                if (!text.isBlank()) {
                    result.add(Map.of("role", "assistant", "content", text));
                }
            }
        }
        return result;
    }
}
