package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.Message;
import com.anthropic.claudecode.model.SystemPrompt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import lombok.Data;

/**
 * Post-sampling hook service.
 * Translated from src/utils/hooks/postSamplingHooks.ts
 *
 * Manages hooks that run after model sampling completes.
 */
@Slf4j
@Service
public class PostSamplingHookService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PostSamplingHookService.class);


    private final List<PostSamplingHook> hooks = new CopyOnWriteArrayList<>();

    /**
     * Register a post-sampling hook.
     * Translated from registerPostSamplingHook() in postSamplingHooks.ts
     */
    public void registerHook(PostSamplingHook hook) {
        hooks.add(hook);
    }

    /**
     * Clear all hooks (for testing).
     * Translated from clearPostSamplingHooks() in postSamplingHooks.ts
     */
    public void clearHooks() {
        hooks.clear();
    }

    /**
     * Execute all registered hooks.
     * Translated from executePostSamplingHooks() in postSamplingHooks.ts
     */
    public CompletableFuture<Void> executeHooks(REPLHookContext context) {
        return CompletableFuture.runAsync(() -> {
            for (PostSamplingHook hook : hooks) {
                try {
                    hook.execute(context);
                } catch (Exception e) {
                    log.error("Post-sampling hook failed: {}", e.getMessage(), e);
                }
            }
        });
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    @FunctionalInterface
    public interface PostSamplingHook {
        void execute(REPLHookContext context) throws Exception;
    }

    @Data
    @lombok.Builder
    
    public static class REPLHookContext {
        private List<Message> messages;
        private SystemPrompt systemPrompt;
        private Map<String, String> userContext;
        private Map<String, String> systemContext;
        private String querySource;
    }
}
