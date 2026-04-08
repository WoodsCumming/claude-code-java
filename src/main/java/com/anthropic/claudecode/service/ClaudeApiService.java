package com.anthropic.claudecode.service;

import com.anthropic.claudecode.client.AnthropicClient;
import com.anthropic.claudecode.model.ContentBlock;
import com.anthropic.claudecode.model.Message;
import com.anthropic.claudecode.model.SystemPrompt;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Claude API service — high-level API call coordinator.
 * Translated from src/services/api/client.ts and src/services/api/claude.ts
 *
 * Responsibilities:
 * - Build default request headers (x-app, User-Agent, session ID, container/remote IDs)
 * - Parse ANTHROPIC_CUSTOM_HEADERS environment variable (curl-style Name: Value per line)
 * - Toggle additional-protection header (CLAUDE_CODE_ADDITIONAL_PROTECTION)
 * - Determine API provider (Bedrock / Vertex / Foundry / direct) from env flags
 * - Provide higher-level query helpers (queryModelWithoutStreaming, queryHaiku)
 *
 * All async operations return CompletableFuture (replacing TypeScript async/await).
 */
@Slf4j
@Service
public class ClaudeApiService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ClaudeApiService.class);


    // -----------------------------------------------------------------------
    // Constants  (from client.ts)
    // -----------------------------------------------------------------------

    /** Header name for client-generated request IDs. Translated from CLIENT_REQUEST_ID_HEADER. */
    public static final String CLIENT_REQUEST_ID_HEADER = AnthropicClient.CLIENT_REQUEST_ID_HEADER;

    // -----------------------------------------------------------------------
    // API provider enum
    // -----------------------------------------------------------------------

    public enum ApiProvider {
        DIRECT,    // Default: ANTHROPIC_API_KEY
        BEDROCK,   // CLAUDE_CODE_USE_BEDROCK=1
        VERTEX,    // CLAUDE_CODE_USE_VERTEX=1
        FOUNDRY    // CLAUDE_CODE_USE_FOUNDRY=1
    }

    // -----------------------------------------------------------------------
    // Dependencies
    // -----------------------------------------------------------------------

    private final AnthropicClient anthropicClient;
    private final ModelService modelService;
    private final AuthService authService;

    @Autowired
    public ClaudeApiService(AnthropicClient anthropicClient,
                            ModelService modelService,
                            AuthService authService) {
        this.anthropicClient = anthropicClient;
        this.modelService    = modelService;
        this.authService     = authService;
    }

    // -----------------------------------------------------------------------
    // getApiProvider
    // -----------------------------------------------------------------------

    /**
     * Determine which API provider is active based on environment variables.
     * Translated from getAPIProvider() / isFirstPartyAnthropicBaseUrl() in client.ts
     */
    public static ApiProvider getApiProvider() {
        if (isEnvTruthy("CLAUDE_CODE_USE_BEDROCK"))  return ApiProvider.BEDROCK;
        if (isEnvTruthy("CLAUDE_CODE_USE_VERTEX"))   return ApiProvider.VERTEX;
        if (isEnvTruthy("CLAUDE_CODE_USE_FOUNDRY"))  return ApiProvider.FOUNDRY;
        return ApiProvider.DIRECT;
    }

    // -----------------------------------------------------------------------
    // buildDefaultHeaders
    // -----------------------------------------------------------------------

    /**
     * Build the default HTTP headers that accompany every API request.
     * Translated from the defaultHeaders block inside getAnthropicClient() in client.ts
     *
     * Includes:
     * - x-app: cli
     * - User-Agent (with version)
     * - X-Claude-Code-Session-Id
     * - Custom headers from ANTHROPIC_CUSTOM_HEADERS
     * - Optional container/remote-session/client-app headers
     * - x-anthropic-additional-protection when CLAUDE_CODE_ADDITIONAL_PROTECTION=1
     */
    public Map<String, String> buildDefaultHeaders(String sessionId) {
        Map<String, String> headers = new LinkedHashMap<>();

        // Standard headers
        headers.put("x-app", "cli");
        headers.put("User-Agent", getUserAgent());
        if (sessionId != null) {
            headers.put("X-Claude-Code-Session-Id", sessionId);
        }

        // Merge custom headers from env (parsed curl-style)
        headers.putAll(parseCustomHeaders());

        // Optional remote / container / SDK headers
        String containerId = System.getenv("CLAUDE_CODE_CONTAINER_ID");
        if (containerId != null) headers.put("x-claude-remote-container-id", containerId);

        String remoteSessionId = System.getenv("CLAUDE_CODE_REMOTE_SESSION_ID");
        if (remoteSessionId != null) headers.put("x-claude-remote-session-id", remoteSessionId);

        String clientApp = System.getenv("CLAUDE_AGENT_SDK_CLIENT_APP");
        if (clientApp != null) headers.put("x-client-app", clientApp);

        // Additional protection header
        if (isEnvTruthy("CLAUDE_CODE_ADDITIONAL_PROTECTION")) {
            headers.put("x-anthropic-additional-protection", "true");
        }

        log.debug("[API:request] Creating client, ANTHROPIC_CUSTOM_HEADERS present: {}, has Authorization: {}",
            System.getenv("ANTHROPIC_CUSTOM_HEADERS") != null,
            headers.containsKey("Authorization"));

        return headers;
    }

    // -----------------------------------------------------------------------
    // parseCustomHeaders
    // -----------------------------------------------------------------------

    /**
     * Parse ANTHROPIC_CUSTOM_HEADERS environment variable.
     * Format: one "Name: Value" (curl-style) header per line.
     * Translated from getCustomHeaders() in client.ts
     */
    public static Map<String, String> parseCustomHeaders() {
        Map<String, String> result = new LinkedHashMap<>();
        String env = System.getenv("ANTHROPIC_CUSTOM_HEADERS");
        if (env == null || env.isBlank()) return result;

        for (String line : env.split("\n|\r\n")) {
            if (line.isBlank()) continue;
            int colonIdx = line.indexOf(':');
            if (colonIdx < 0) continue;
            String name  = line.substring(0, colonIdx).trim();
            String value = line.substring(colonIdx + 1).trim();
            if (!name.isEmpty()) result.put(name, value);
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // configureApiKeyHeaders
    // -----------------------------------------------------------------------

    /**
     * Add an Authorization: Bearer header when an API-key helper or explicit
     * ANTHROPIC_AUTH_TOKEN is available.
     * Translated from configureApiKeyHeaders() in client.ts
     */
    public CompletableFuture<Void> configureApiKeyHeaders(
            Map<String, String> headers, boolean isNonInteractive) {

        return CompletableFuture.runAsync(() -> {
            // Explicit token takes priority
            String token = System.getenv("ANTHROPIC_AUTH_TOKEN");
            if (token == null || token.isBlank()) {
                // Delegate to auth service for helper-provided token
                token = authService.getApiKeyFromHelper(isNonInteractive);
            }
            if (token != null && !token.isBlank()) {
                headers.put("Authorization", "Bearer " + token);
            }
        });
    }

    // -----------------------------------------------------------------------
    // streamMessages — used by QueryEngine for the main agentic loop
    // -----------------------------------------------------------------------

    /**
     * Stream messages from the Claude API, returning an iterable of StreamEvents.
     * Translated from the streaming model call in query.ts
     *
     * Since this Java port does not implement true SSE streaming, this method
     * calls the non-streaming API and wraps the result as a single AssistantMessageEvent.
     */
    public Iterable<QueryEngine.StreamEvent> streamMessages(
            List<Message> messages,
            SystemPrompt systemPrompt,
            Map<String, String> userContext,
            Map<String, String> systemContext,
            QueryEngine.QueryEngineConfig engineConfig) {

        List<Map<String, Object>> apiMessages = convertMessages(messages);
        String model = engineConfig.getMainLoopModel() != null
                ? engineConfig.getMainLoopModel()
                : modelService.getMainLoopModel();

        List<Map<String, Object>> systemBlocks = null;
        if (systemPrompt != null && systemPrompt.getParts() != null) {
            systemBlocks = systemPrompt.getParts().stream()
                    .filter(p -> p != null && !p.isBlank())
                    .map(p -> {
                        Map<String, Object> block = new LinkedHashMap<>();
                        block.put("type", "text");
                        block.put("text", p);
                        return block;
                    })
                    .collect(java.util.stream.Collectors.toList());
        }

        AnthropicClient.MessageRequest request = AnthropicClient.MessageRequest.builder()
            .model(model)
            .maxTokens(8096)
            .system(systemBlocks)
            .messages(apiMessages)
            .build();

        try {
            AnthropicClient.MessageResponse response = anthropicClient.createMessage(request).get();
            Message.AssistantMessage assistantMessage = convertResponseToAssistantMessage(response);
            return List.of(new QueryEngine.StreamEvent.AssistantMessageEvent(assistantMessage));
        } catch (Exception e) {
            log.error("Error calling Claude API", e);
            throw new RuntimeException("Failed to call Claude API: " + e.getMessage(), e);
        }
    }

    private Message.AssistantMessage convertResponseToAssistantMessage(AnthropicClient.MessageResponse response) {
        List<ContentBlock> contentBlocks = new ArrayList<>();
        if (response.getContent() != null) {
            for (Map<String, Object> block : response.getContent()) {
                String type = (String) block.get("type");
                if ("text".equals(type)) {
                    ContentBlock.TextBlock textBlock = new ContentBlock.TextBlock();
                    textBlock.setType("text");
                    textBlock.setText((String) block.get("text"));
                    contentBlocks.add(textBlock);
                } else if ("tool_use".equals(type)) {
                    ContentBlock.ToolUseBlock toolBlock = new ContentBlock.ToolUseBlock();
                    toolBlock.setType("tool_use");
                    toolBlock.setId((String) block.get("id"));
                    toolBlock.setName((String) block.get("name"));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> input = (Map<String, Object>) block.get("input");
                    toolBlock.setInput(input != null ? input : new LinkedHashMap<>());
                    contentBlocks.add(toolBlock);
                }
            }
        }
        Message.AssistantMessage msg = new Message.AssistantMessage();
        msg.setUuid(response.getId() != null ? response.getId() : java.util.UUID.randomUUID().toString());
        msg.setContent(contentBlocks);
        msg.setModel(response.getModel());
        msg.setStopReason(response.getStopReason());
        return msg;
    }

    // -----------------------------------------------------------------------
    // queryModelWithoutStreaming
    // -----------------------------------------------------------------------

    /**
     * Query the model without streaming.
     * Translated from queryModelWithoutStreaming() in claude.ts
     */
    public CompletableFuture<AnthropicClient.MessageResponse> queryModelWithoutStreaming(
            List<Message> messages,
            String model,
            int maxTokens,
            List<Map<String, Object>> systemPrompt) {

        List<Map<String, Object>> apiMessages = convertMessages(messages);

        AnthropicClient.MessageRequest request = AnthropicClient.MessageRequest.builder()
            .model(model != null ? model : modelService.getMainLoopModel())
            .maxTokens(maxTokens > 0 ? maxTokens : 4096)
            .system(systemPrompt)
            .messages(apiMessages)
            .build();

        return anthropicClient.createMessage(request);
    }

    // -----------------------------------------------------------------------
    // queryHaiku  (small fast model)
    // -----------------------------------------------------------------------

    /**
     * Query the small-fast model (Haiku) for quick classification tasks.
     * Translated from queryHaiku() in claude.ts
     */
    public CompletableFuture<String> queryHaiku(String prompt, String systemPrompt) {

        List<Map<String, Object>> messages = List.of(
            Map.of("role", "user", "content", prompt)
        );
        List<Map<String, Object>> system = systemPrompt != null
            ? List.of(Map.of("type", "text", "text", systemPrompt))
            : null;

        AnthropicClient.MessageRequest request = AnthropicClient.MessageRequest.builder()
            .model(modelService.getSmallFastModel())
            .maxTokens(1024)
            .system(system)
            .messages(messages)
            .build();

        return anthropicClient.createMessage(request).thenApply(response -> {
            if (response.getContent() == null) return "";
            for (Map<String, Object> block : response.getContent()) {
                if ("text".equals(block.get("type"))) {
                    Object text = block.get("text");
                    return text != null ? text.toString() : "";
                }
            }
            return "";
        });
    }

    // -----------------------------------------------------------------------
    // verifyApiKey
    // -----------------------------------------------------------------------

    /**
     * Verify that the API key is valid by making a minimal request.
     * Translated from verifyApiKey() in claude.ts
     */
    public CompletableFuture<Boolean> verifyApiKey(String apiKey) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                AnthropicClient.MessageRequest request = AnthropicClient.MessageRequest.builder()
                    .model(modelService.getSmallFastModel())
                    .maxTokens(1)
                    .messages(List.of(Map.of("role", "user", "content", "Hi")))
                    .build();
                anthropicClient.createMessage(request).get();
                return true;
            } catch (Exception e) {
                return false;
            }
        });
    }

    // -----------------------------------------------------------------------
    // getUserAgent
    // -----------------------------------------------------------------------

    /**
     * Build the User-Agent string for API requests.
     * Translated from getUserAgent() in http.ts (used in client.ts)
     */
    public String getUserAgent() {
        String version = System.getenv("CLAUDE_CODE_VERSION");
        if (version == null || version.isBlank()) version = "2.1.88";
        return "claude-code/" + version + " Java/21";
    }

    // -----------------------------------------------------------------------
    // getApiTimeoutMs
    // -----------------------------------------------------------------------

    /**
     * Get the API timeout in milliseconds.
     * Translated from the timeout field in ARGS inside getAnthropicClient() in client.ts
     */
    public static long getApiTimeoutMs() {
        String envVal = System.getenv("API_TIMEOUT_MS");
        if (envVal != null && !envVal.isBlank()) {
            try { return Long.parseLong(envVal.trim()); }
            catch (NumberFormatException ignored) { /* fall through */ }
        }
        return 600_000L; // 10 minutes default
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private List<Map<String, Object>> convertMessages(List<Message> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Message msg : messages) {
            if (msg instanceof Message.UserMessage userMsg) {
                Map<String, Object> apiMsg = new LinkedHashMap<>();
                apiMsg.put("role", "user");
                apiMsg.put("content", extractContent(userMsg.getContent()));
                result.add(apiMsg);
            } else if (msg instanceof Message.AssistantMessage assistantMsg) {
                Map<String, Object> apiMsg = new LinkedHashMap<>();
                apiMsg.put("role", "assistant");
                apiMsg.put("content", extractContent(assistantMsg.getContent()));
                result.add(apiMsg);
            }
        }
        return result;
    }

    private Object extractContent(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return "";
        if (blocks.size() == 1 && blocks.get(0) instanceof ContentBlock.TextBlock text) {
            return text.getText() != null ? text.getText() : "";
        }
        List<Map<String, Object>> content = new ArrayList<>();
        for (ContentBlock block : blocks) {
            if (block instanceof ContentBlock.TextBlock text) {
                content.add(Map.of("type", "text", "text",
                    text.getText() != null ? text.getText() : ""));
            }
        }
        return content;
    }

    /** Check whether an environment variable is set to a truthy value (1/true/yes). */
    private static boolean isEnvTruthy(String name) {
        String val = System.getenv(name);
        return "1".equals(val) || "true".equalsIgnoreCase(val) || "yes".equalsIgnoreCase(val);
    }
}
