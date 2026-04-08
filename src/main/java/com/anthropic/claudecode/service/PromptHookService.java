package com.anthropic.claudecode.service;

import com.anthropic.claudecode.client.AnthropicClient;
import com.anthropic.claudecode.util.ModelUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;

/**
 * Prompt hook execution service.
 * Translated from src/utils/hooks/execPromptHook.ts
 *
 * Executes prompt-based hooks using an LLM (Haiku).
 */
@Slf4j
@Service
public class PromptHookService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PromptHookService.class);


    private static final long DEFAULT_HOOK_TIMEOUT_MS = 30_000L;

    private final AnthropicClient anthropicClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public PromptHookService(AnthropicClient anthropicClient, ObjectMapper objectMapper) {
        this.anthropicClient = anthropicClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Execute a prompt-based hook.
     * Translated from execPromptHook() in execPromptHook.ts
     */
    public CompletableFuture<PromptHookResult> executePromptHook(
            PromptHookConfig hookConfig,
            String jsonInput) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Replace $ARGUMENTS with the JSON input
                String processedPrompt = hookConfig.getPrompt()
                    .replace("$ARGUMENTS", jsonInput);

                long timeoutMs = hookConfig.getTimeoutSeconds() != null
                    ? hookConfig.getTimeoutSeconds() * 1000L
                    : DEFAULT_HOOK_TIMEOUT_MS;

                List<Map<String, Object>> messages = List.of(
                    Map.of("role", "user", "content", processedPrompt)
                );

                AnthropicClient.MessageRequest request = AnthropicClient.MessageRequest.builder()
                    .model(ModelUtils.getSmallFastModel())
                    .maxTokens(1024)
                    .messages(messages)
                    .build();

                AnthropicClient.MessageResponse response = anthropicClient
                    .createMessage(request)
                    .get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);

                // Extract text from response
                String responseText = null;
                if (response.getContent() != null) {
                    for (Map<String, Object> block : response.getContent()) {
                        if ("text".equals(block.get("type"))) {
                            responseText = (String) block.get("text");
                            break;
                        }
                    }
                }

                // Parse response for hook directives
                boolean continueExecution = true;
                String output = responseText;

                if (responseText != null) {
                    try {
                        Map<String, Object> parsed = objectMapper.readValue(responseText, Map.class);
                        if (parsed.containsKey("continue")) {
                            continueExecution = Boolean.TRUE.equals(parsed.get("continue"));
                        }
                    } catch (Exception e) {
                        // Not JSON, treat as plain output
                    }
                }

                return new PromptHookResult(continueExecution, output, null);

            } catch (TimeoutException e) {
                log.debug("[prompt-hook] Hook timed out");
                return new PromptHookResult(true, null, "Hook timed out");
            } catch (Exception e) {
                log.debug("[prompt-hook] Error executing hook: {}", e.getMessage());
                return new PromptHookResult(true, null, e.getMessage());
            }
        });
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PromptHookConfig {
        private String prompt;
        private Integer timeoutSeconds;

        public String getPrompt() { return prompt; }
        public void setPrompt(String v) { prompt = v; }
        public Integer getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(Integer v) { timeoutSeconds = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PromptHookResult {
        private boolean continueExecution;
        private String output;
        private String error;

        public boolean isContinueExecution() { return continueExecution; }
        public void setContinueExecution(boolean v) { continueExecution = v; }
        public String getOutput() { return output; }
        public void setOutput(String v) { output = v; }
        public String getError() { return error; }
        public void setError(String v) { error = v; }
    }
}
