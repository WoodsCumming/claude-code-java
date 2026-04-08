package com.anthropic.claudecode.service;

import com.anthropic.claudecode.client.AnthropicClient;
import com.anthropic.claudecode.model.Message;
import com.anthropic.claudecode.util.ModelUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Session title generation service using the small/fast (Haiku) model.
 * Translated from src/utils/sessionTitle.ts
 *
 * Generates concise sentence-case session titles so users can recognise a
 * session in a list. This is the single source of truth for AI-generated
 * session titles across all surfaces.
 */
@Slf4j
@Service
public class SessionTitleService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SessionTitleService.class);


    private static final int MAX_CONVERSATION_TEXT = 1000;

    /**
     * System prompt for title generation.
     * Translated verbatim from SESSION_TITLE_PROMPT in sessionTitle.ts
     */
    private static final String SESSION_TITLE_PROMPT =
            "Generate a concise, sentence-case title (3-7 words) that captures the main topic or goal "
            + "of this coding session. The title should be clear enough that the user recognizes the "
            + "session in a list. Use sentence case: capitalize only the first word and proper nouns.\n\n"
            + "Return JSON with a single \"title\" field.\n\n"
            + "Good examples:\n"
            + "{\"title\": \"Fix login button on mobile\"}\n"
            + "{\"title\": \"Add OAuth authentication\"}\n"
            + "{\"title\": \"Debug failing CI tests\"}\n"
            + "{\"title\": \"Refactor API client error handling\"}\n\n"
            + "Bad (too vague): {\"title\": \"Code changes\"}\n"
            + "Bad (too long): {\"title\": \"Investigate and fix the issue where the login button does not respond on mobile devices\"}\n"
            + "Bad (wrong case): {\"title\": \"Fix Login Button On Mobile\"}";

    private final AnthropicClient anthropicClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public SessionTitleService(AnthropicClient anthropicClient, ObjectMapper objectMapper) {
        this.anthropicClient = anthropicClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Flatten a message list into a single text string for Haiku title input.
     * Skips meta / non-human messages and tail-slices to the last 1000 chars.
     * Translated from extractConversationText() in sessionTitle.ts
     */
    public String extractConversationText(List<Message> messages) {
        List<String> parts = new ArrayList<>();
        for (Message msg : messages) {
            if (!(msg instanceof Message.UserMessage)
                    && !(msg instanceof Message.AssistantMessage)) {
                continue;
            }
            // Skip meta messages
            if (msg instanceof Message.UserMessage userMsg && userMsg.isMeta()) continue;

            String text = msg.extractPlainText();
            if (text != null && !text.isEmpty()) {
                parts.add(text);
            }
        }

        String text = String.join("\n", parts);
        if (text.length() > MAX_CONVERSATION_TEXT) {
            text = text.substring(text.length() - MAX_CONVERSATION_TEXT);
        }
        return text;
    }

    /**
     * Generate a sentence-case session title from the given description or first message.
     * Returns an empty Optional on error or when the model returns an unparseable response.
     * Translated from generateSessionTitle() in sessionTitle.ts
     *
     * @param description User's first message or a description of the session.
     * @return CompletableFuture containing the title, or empty if generation failed.
     */
    public CompletableFuture<Optional<String>> generateSessionTitle(String description) {
        if (description == null || description.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        String trimmed = description.strip();

        return CompletableFuture.supplyAsync(() -> {
            try {
                List<Map<String, Object>> messages = List.of(
                        Map.of("role", "user", "content", trimmed));

                AnthropicClient.MessageRequest request = AnthropicClient.MessageRequest.builder()
                        .model(ModelUtils.getSmallFastModel())
                        .maxTokens(100)
                        .system(List.of(Map.of("type", "text", "text", SESSION_TITLE_PROMPT)))
                        .messages(messages)
                        // Request JSON output matching the schema { title: string }
                        .outputFormat(Map.of(
                                "type", "json_schema",
                                "schema", Map.of(
                                        "type", "object",
                                        "properties", Map.of("title", Map.of("type", "string")),
                                        "required", List.of("title"),
                                        "additionalProperties", false)))
                        .build();

                AnthropicClient.MessageResponse response =
                        anthropicClient.createMessage(request).get();

                if (response.getContent() != null) {
                    for (Map<String, Object> block : response.getContent()) {
                        if ("text".equals(block.get("type"))) {
                            String text = (String) block.get("text");
                            if (text != null) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> parsed =
                                        objectMapper.readValue(text, Map.class);
                                String title = (String) parsed.get("title");
                                if (title != null && !title.isBlank()) {
                                    log.debug("Generated session title: {}", title.strip());
                                    return Optional.of(title.strip());
                                }
                            }
                        }
                    }
                }

                log.debug("generateSessionTitle: model returned no usable title");
                return Optional.<String>empty();

            } catch (Exception e) {
                log.debug("generateSessionTitle failed: {}", e.getMessage());
                return Optional.<String>empty();
            }
        });
    }

    /**
     * Convenience overload: generate a title from the first user message in a
     * conversation. Returns empty if no suitable text is found.
     */
    public CompletableFuture<Optional<String>> generateSessionTitle(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String conversationText = extractConversationText(messages);
        return generateSessionTitle(conversationText);
    }
}
