package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Session start / setup hooks processing service.
 * Translated from src/utils/sessionStart.ts
 *
 * Runs hooks at session start (and at setup) and surfaces hook-provided
 * result messages and initial user messages to the rest of the system.
 *
 * Note: do NOT add any warmup logic — startup performance is critical.
 */
@Slf4j
@Service
public class SessionStartService {



    /**
     * Source context for processSessionStartHooks().
     * Mirrors the TypeScript union 'startup' | 'resume' | 'clear' | 'compact'.
     */
    public enum SessionStartSource {
        STARTUP, RESUME, CLEAR, COMPACT
    }

    /**
     * Trigger context for processSetupHooks().
     * Mirrors the TypeScript union 'init' | 'maintenance'.
     */
    public enum SetupTrigger {
        INIT, MAINTENANCE
    }

    // Set by processSessionStartHooks when a hook emits initialUserMessage;
    // consumed once by takeInitialUserMessage().
    private final AtomicReference<String> pendingInitialUserMessage = new AtomicReference<>();

    private final HookService hookService;

    @Autowired
    public SessionStartService(HookService hookService) {
        this.hookService = hookService;
    }

    /**
     * Take (and clear) the pending initial user message, if any.
     * Returns null when none is pending.
     * Translated from takeInitialUserMessage() in sessionStart.ts
     */
    public String takeInitialUserMessage() {
        return pendingInitialUserMessage.getAndSet(null);
    }

    /**
     * Process SessionStart hooks and return any hook result messages.
     *
     * Loads plugin hooks (memoized), executes SessionStart hooks, collects
     * result messages, additional context strings, watch paths, and an
     * optional initial user message.
     *
     * Translated from processSessionStartHooks() in sessionStart.ts
     *
     * @param source          Why session start hooks are being run.
     * @param sessionId       Optional session ID to pass to hooks.
     * @param agentType       Optional agent type to pass to hooks.
     * @param model           Optional model name to pass to hooks.
     * @param forceSyncExec   When true, force synchronous hook execution.
     * @return CompletableFuture resolving to the list of hook result messages.
     */
    public CompletableFuture<List<Message>> processSessionStartHooks(
            SessionStartSource source,
            String sessionId,
            String agentType,
            String model,
            boolean forceSyncExec) {

        Map<String, Object> hookInput = new HashMap<>();
        hookInput.put("source", source.name().toLowerCase());
        if (sessionId  != null) hookInput.put("session_id",  sessionId);
        if (agentType  != null) hookInput.put("agent_type",  agentType);
        if (model      != null) hookInput.put("model",        model);
        if (forceSyncExec)      hookInput.put("force_sync",   true);

        return hookService.executeHooks(HookService.HookEvent.SESSION_START, hookInput, null)
                .thenApply(result -> {
                    List<Message> messages = new ArrayList<>();

                    if (result.getInitialUserMessageStr() != null) {
                        pendingInitialUserMessage.set(result.getInitialUserMessageStr());
                    }

                    if (result.getSystemMessage() != null) {
                        messages.add(Message.SystemMessage.builder()
                                .uuid(java.util.UUID.randomUUID().toString())
                                .timestamp(java.time.Instant.now().toString())
                                .content(result.getSystemMessage())
                                .level(Message.SystemMessageLevel.INFO)
                                .subtype(Message.SystemMessageSubtype.INFORMATIONAL)
                                .build());
                    }

                    if (result.getAdditionalContextsList() != null
                            && !result.getAdditionalContextsList().isEmpty()) {
                        // Additional contexts are injected as a single attachment message
                        for (String ctx : result.getAdditionalContextsList()) {
                            messages.add(Message.SystemMessage.builder()
                                    .uuid(java.util.UUID.randomUUID().toString())
                                    .timestamp(java.time.Instant.now().toString())
                                    .content(ctx)
                                    .level(Message.SystemMessageLevel.INFO)
                                    .subtype(Message.SystemMessageSubtype.INFORMATIONAL)
                                    .build());
                        }
                    }

                    return messages;
                });
    }

    /**
     * Synchronous convenience overload accepting a string source name.
     * Returns a List of string messages (text content from system messages).
     */
    public List<String> processSessionStartHooks(String sourceName) {
        SessionStartSource source;
        try {
            source = SessionStartSource.valueOf(sourceName.toUpperCase());
        } catch (IllegalArgumentException e) {
            source = SessionStartSource.STARTUP;
        }
        try {
            List<Message> msgs = processSessionStartHooks(source, null, null, null, false).get();
            List<String> result = new java.util.ArrayList<>();
            for (Message m : msgs) {
                if (m instanceof Message.SystemMessage sm && sm.getContent() != null) {
                    result.add(sm.getContent());
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("processSessionStartHooks failed for source {}: {}", sourceName, e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Convenience overload with default parameters.
     */
    public CompletableFuture<List<Message>> processSessionStartHooks(
            SessionStartSource source,
            String sessionId,
            String agentType,
            String model) {
        return processSessionStartHooks(source, sessionId, agentType, model, false);
    }

    /**
     * Process Setup hooks and return any hook result messages.
     * Translated from processSetupHooks() in sessionStart.ts
     *
     * @param trigger        Why setup hooks are being run.
     * @param forceSyncExec  When true, force synchronous hook execution.
     * @return CompletableFuture resolving to the list of hook result messages.
     */
    public CompletableFuture<List<Message>> processSetupHooks(
            SetupTrigger trigger,
            boolean forceSyncExec) {

        Map<String, Object> hookInput = new HashMap<>();
        hookInput.put("trigger", trigger.name().toLowerCase());
        if (forceSyncExec) hookInput.put("force_sync", true);

        return hookService.executeHooks(HookService.HookEvent.SETUP, hookInput, null)
                .thenApply(result -> {
                    List<Message> messages = new ArrayList<>();

                    if (result.getSystemMessage() != null) {
                        messages.add(Message.SystemMessage.builder()
                                .uuid(java.util.UUID.randomUUID().toString())
                                .timestamp(java.time.Instant.now().toString())
                                .content(result.getSystemMessage())
                                .level(Message.SystemMessageLevel.INFO)
                                .subtype(Message.SystemMessageSubtype.INFORMATIONAL)
                                .build());
                    }

                    if (result.getAdditionalContextsList() != null
                            && !result.getAdditionalContextsList().isEmpty()) {
                        for (String ctx : result.getAdditionalContextsList()) {
                            messages.add(Message.SystemMessage.builder()
                                    .uuid(java.util.UUID.randomUUID().toString())
                                    .timestamp(java.time.Instant.now().toString())
                                    .content(ctx)
                                    .level(Message.SystemMessageLevel.INFO)
                                    .subtype(Message.SystemMessageSubtype.INFORMATIONAL)
                                    .build());
                        }
                    }

                    return messages;
                });
    }

    /**
     * Convenience overload with default forceSyncExec = false.
     */
    public CompletableFuture<List<Message>> processSetupHooks(SetupTrigger trigger) {
        return processSetupHooks(trigger, false);
    }
}
