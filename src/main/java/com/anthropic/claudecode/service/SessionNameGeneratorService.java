package com.anthropic.claudecode.service;

import com.anthropic.claudecode.client.AnthropicClient;
import com.anthropic.claudecode.model.Message;
import com.anthropic.claudecode.util.ModelUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Session name generator service.
 * Translated from src/commands/rename/generateSessionName.ts
 *
 * Generates short kebab-case names for sessions.
 */
@Slf4j
@Service
public class SessionNameGeneratorService {



    private final AnthropicClient anthropicClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public SessionNameGeneratorService(AnthropicClient anthropicClient, ObjectMapper objectMapper) {
        this.anthropicClient = anthropicClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Generate a session name from messages.
     * Translated from generateSessionName() in generateSessionName.ts
     */
    public CompletableFuture<Optional<String>> generateSessionName(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        // Extract conversation text
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            if (msg instanceof Message.UserMessage userMsg) {
                if (userMsg.getContent() != null) {
                    for (var block : userMsg.getContent()) {
                        if (block instanceof com.anthropic.claudecode.model.ContentBlock.TextBlock text) {
                            sb.append("User: ").append(text.getText()).append("\n");
                        }
                    }
                }
            }
        }

        if (sb.isEmpty()) return CompletableFuture.completedFuture(Optional.empty());

        String conversationText = sb.toString().substring(0, Math.min(500, sb.length()));

        return CompletableFuture.supplyAsync(() -> {
            try {
                String systemPrompt = "Generate a short kebab-case name (2-4 words) that captures the main topic. " +
                    "Use lowercase words separated by hyphens. " +
                    "Examples: fix-login-bug, add-auth-feature, refactor-api-client. " +
                    "Return JSON with a 'name' field.";

                List<Map<String, Object>> messages2 = List.of(
                    Map.of("role", "user", "content", conversationText)
                );

                AnthropicClient.MessageRequest request = AnthropicClient.MessageRequest.builder()
                    .model(ModelUtils.getSmallFastModel())
                    .maxTokens(100)
                    .system(List.of(Map.of("type", "text", "text", systemPrompt)))
                    .messages(messages2)
                    .build();

                AnthropicClient.MessageResponse response = anthropicClient
                    .createMessage(request)
                    .get();

                if (response.getContent() != null) {
                    for (Map<String, Object> block : response.getContent()) {
                        if ("text".equals(block.get("type"))) {
                            String text = (String) block.get("text");
                            if (text != null) {
                                Map<String, Object> parsed = objectMapper.readValue(text, Map.class);
                                String name = (String) parsed.get("name");
                                if (name != null && !name.isBlank()) {
                                    return Optional.of(name);
                                }
                            }
                        }
                    }
                }

                return Optional.empty();

            } catch (Exception e) {
                log.debug("Could not generate session name: {}", e.getMessage());
                return Optional.empty();
            }
        });
    }
}
