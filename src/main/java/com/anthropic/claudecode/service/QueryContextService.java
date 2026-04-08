package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.SystemPrompt;
import com.anthropic.claudecode.util.FileStateCache;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Query context service for building the API cache-key prefix.
 * Translated from src/utils/queryContext.ts
 *
 * Fetches the three context pieces that form the API cache-key prefix:
 *   systemPrompt parts, userContext, systemContext.
 *
 * Lives in its own class because it imports from ContextService and
 * SystemPromptService which are high in the dependency graph; separating
 * avoids cycles analogous to the TS module separation.
 *
 * Only entry-point-layer classes should import from here
 * (QueryEngine, SideQuestionService).
 */
@Slf4j
@Service
public class QueryContextService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(QueryContextService.class);


    private final ContextService contextService;
    private final SystemPromptService systemPromptService;
    private final ModelService modelService;

    @Autowired
    public QueryContextService(ContextService contextService,
                                SystemPromptService systemPromptService,
                                ModelService modelService) {
        this.contextService = contextService;
        this.systemPromptService = systemPromptService;
        this.modelService = modelService;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Fetch the three context pieces that form the API cache-key prefix:
     * systemPrompt parts, userContext, systemContext.
     *
     * When customSystemPrompt is set the default getSystemPrompt build and
     * getSystemContext are skipped — the custom prompt replaces the default
     * entirely, and systemContext would be appended to a default that isn't
     * being used.
     *
     * Translated from fetchSystemPromptParts() in queryContext.ts
     *
     * @param tools                         tool definitions for system-prompt construction
     * @param mainLoopModel                 main loop model identifier
     * @param additionalWorkingDirectories  extra working directories to include
     * @param mcpClients                    MCP server connections
     * @param customSystemPrompt            optional caller-supplied system prompt override
     * @return SystemPromptParts containing defaultSystemPrompt, userContext, systemContext
     */
    public CompletableFuture<SystemPromptParts> fetchSystemPromptParts(
            List<Object> tools,
            String mainLoopModel,
            List<String> additionalWorkingDirectories,
            List<Object> mcpClients,
            String customSystemPrompt) {

        CompletableFuture<List<String>> defaultPromptFuture = customSystemPrompt != null
            // Custom prompt supplied — skip the default build entirely.
            ? CompletableFuture.completedFuture(Collections.emptyList())
            : systemPromptService.getSystemPromptAsync(tools, mainLoopModel, additionalWorkingDirectories, mcpClients);

        CompletableFuture<Map<String, String>> userContextFuture =
            contextService.getUserContextAsync();

        CompletableFuture<Map<String, String>> systemContextFuture = customSystemPrompt != null
            // Skip system context when using a custom prompt.
            ? CompletableFuture.completedFuture(Collections.emptyMap())
            : contextService.getSystemContextAsync();

        return CompletableFuture.allOf(defaultPromptFuture, userContextFuture, systemContextFuture)
            .thenApply(ignored -> new SystemPromptParts(
                defaultPromptFuture.join(),
                userContextFuture.join(),
                systemContextFuture.join()
            ));
    }

    /**
     * Build CacheSafeParams from raw inputs when getLastCacheSafeParams() is null.
     *
     * Used by the SDK side_question handler on resume before a turn completes
     * — there is no stopHooks snapshot yet. Mirrors the system-prompt assembly
     * in QueryEngine.ask() so the rebuilt prefix matches what the main loop
     * will send, preserving the cache hit in the common case.
     *
     * Translated from buildSideQuestionFallbackParams() in queryContext.ts
     */
    public CompletableFuture<CacheSafeParams> buildSideQuestionFallbackParams(
            List<Object> tools,
            List<Object> commands,
            List<Object> mcpClients,
            List<Object> messages,
            FileStateCache readFileState,
            String customSystemPrompt,
            String appendSystemPrompt) {

        String mainLoopModel = modelService.getMainLoopModel();

        return fetchSystemPromptParts(
            tools,
            mainLoopModel,
            Collections.emptyList(),
            mcpClients,
            customSystemPrompt
        ).thenApply(parts -> {
            // Assemble the system prompt from default (or custom) + optional append.
            List<String> promptParts = new ArrayList<>();
            if (customSystemPrompt != null) {
                promptParts.add(customSystemPrompt);
            } else {
                promptParts.addAll(parts.getDefaultSystemPrompt());
            }
            if (appendSystemPrompt != null && !appendSystemPrompt.isBlank()) {
                promptParts.add(appendSystemPrompt);
            }

            SystemPrompt systemPrompt = SystemPrompt.of(promptParts);

            return new CacheSafeParams(
                systemPrompt,
                parts.getUserContext(),
                parts.getSystemContext(),
                messages
            );
        });
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /**
     * The three cache-key-prefix pieces returned by fetchSystemPromptParts.
     * Mirrors the return type of fetchSystemPromptParts() in queryContext.ts
     */
    public static class SystemPromptParts {
        private List<String> defaultSystemPrompt;
        private Map<String, String> userContext;
        private Map<String, String> systemContext;

        public SystemPromptParts() {}
        public SystemPromptParts(List<String> defaultSystemPrompt, Map<String, String> userContext, Map<String, String> systemContext) {
            this.defaultSystemPrompt = defaultSystemPrompt;
            this.userContext = userContext;
            this.systemContext = systemContext;
        }
        public List<String> getDefaultSystemPrompt() { return defaultSystemPrompt; }
        public void setDefaultSystemPrompt(List<String> v) { defaultSystemPrompt = v; }
        public Map<String, String> getUserContext() { return userContext; }
        public void setUserContext(Map<String, String> v) { userContext = v; }
        public Map<String, String> getSystemContext() { return systemContext; }
        public void setSystemContext(Map<String, String> v) { systemContext = v; }
    }

    public static class CacheSafeParams {
        private SystemPrompt systemPrompt;
        private Map<String, String> userContext;
        private Map<String, String> systemContext;
        private List<Object> forkContextMessages;

        public CacheSafeParams() {}
        public CacheSafeParams(SystemPrompt systemPrompt, Map<String, String> userContext, Map<String, String> systemContext, List<Object> forkContextMessages) {
            this.systemPrompt = systemPrompt;
            this.userContext = userContext;
            this.systemContext = systemContext;
            this.forkContextMessages = forkContextMessages;
        }
        public SystemPrompt getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(SystemPrompt v) { systemPrompt = v; }
        public Map<String, String> getUserContext() { return userContext; }
        public void setUserContext(Map<String, String> v) { userContext = v; }
        public Map<String, String> getSystemContext() { return systemContext; }
        public void setSystemContext(Map<String, String> v) { systemContext = v; }
        public List<Object> getForkContextMessages() { return forkContextMessages; }
        public void setForkContextMessages(List<Object> v) { forkContextMessages = v; }
    }
}
