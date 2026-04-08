package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Prompt submit service for handling user input.
 * Translated from src/utils/handlePromptSubmit.ts
 *
 * Processes user prompts and executes them through the query engine.
 */
@Slf4j
@Service
public class PromptSubmitService {



    private final QueryEngine queryEngine;
    private final HookService hookService;

    @Autowired
    public PromptSubmitService(QueryEngine queryEngine, HookService hookService) {
        this.queryEngine = queryEngine;
        this.hookService = hookService;
    }

    /**
     * Execute a user prompt.
     * Translated from executeInput() in handlePromptSubmit.ts
     */
    public CompletableFuture<List<Message>> executePrompt(
            String prompt,
            List<Message> messages,
            String model,
            ToolUseContext context) {

        // Execute pre-prompt hooks
        Map<String, Object> hookInput = Map.of(
            "prompt", prompt,
            "model", model
        );

        return hookService.executeHooks(
            HookService.HookEvent.USER_PROMPT_SUBMIT,
            hookInput,
            null
        ).thenCompose(hookResult -> {
            if (!hookResult.isContinue()) {
                return CompletableFuture.completedFuture(messages);
            }

            // Add user message
            List<Message> updatedMessages = new ArrayList<>(messages);
            updatedMessages.add(Message.UserMessage.builder()
                .type("user")
                .uuid(java.util.UUID.randomUUID().toString())
                .content(List.of(new ContentBlock.TextBlock(prompt)))
                .build());

            // Execute query
            return queryEngine.query(
                updatedMessages,
                null, // system prompt from context
                context,
                event -> {} // progress handler
            );
        });
    }
}
