package com.anthropic.claudecode.client;

import com.anthropic.claudecode.config.ClaudeCodeConfig;
import com.anthropic.claudecode.model.ContentBlock;
import com.anthropic.claudecode.model.Message;
import com.anthropic.claudecode.service.AuthService;
import com.anthropic.claudecode.service.SecureStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.Locale;

/**
 * Anthropic API client.
 * Translated from src/services/api/client.ts and src/services/api/claude.ts
 *
 * Supports:
 * - Direct API (ANTHROPIC_API_KEY)
 * - AWS Bedrock (CLAUDE_CODE_USE_BEDROCK)
 * - Google Vertex AI (CLAUDE_CODE_USE_VERTEX)
 * - Azure Foundry (CLAUDE_CODE_USE_FOUNDRY)
 * - OAuth (claude.ai subscriber)
 */
@Slf4j
@Component
public class AnthropicClient {

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final String BETA_BASE_URL = "https://api.anthropic.com";
    private static final String MESSAGES_PATH = "/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final long DEFAULT_TIMEOUT_MS = 600_000; // 10 minutes

    /**
     * Client-generated request ID header.
     * Correlates timeouts (which return no server request ID) with server logs.
     * Translated from CLIENT_REQUEST_ID_HEADER in client.ts
     */
    public static final String CLIENT_REQUEST_ID_HEADER = "x-client-request-id";

    private final ClaudeCodeConfig config;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    @Lazy
    @Autowired(required = false)
    private AuthService authService;

    @Lazy
    @Autowired(required = false)
    private SecureStorageService secureStorageService;

    @Autowired
    public AnthropicClient(ClaudeCodeConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = buildHttpClient();
    }

    private OkHttpClient buildHttpClient() {
        long timeoutMs = config.getApiTimeoutMs() > 0
            ? config.getApiTimeoutMs()
            : DEFAULT_TIMEOUT_MS;

        return new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(30))
            .readTimeout(Duration.ofMillis(timeoutMs))
            .writeTimeout(Duration.ofSeconds(60))
            .addInterceptor(chain -> {
                Request original = chain.request();
                Request.Builder builder = original.newBuilder();

                // Add standard headers
                builder.header("x-app", "cli");
                builder.header("User-Agent", getUserAgent());
                builder.header("anthropic-version", ANTHROPIC_VERSION);

                // Add authentication
                String apiKey = getApiKey();
                if (apiKey != null) {
                    builder.header("x-api-key", apiKey);
                }

                String oauthToken = getOauthToken();
                if (oauthToken != null) {
                    builder.header("Authorization", "Bearer " + oauthToken);
                }

                // Add custom headers from environment
                Map<String, String> customHeaders = parseCustomHeaders();
                customHeaders.forEach(builder::header);

                return chain.proceed(builder.build());
            })
            .build();
    }

    /**
     * Stream messages from the Claude API.
     * Translated from the streaming API call in claude.ts
     *
     * @param request    The API request
     * @param onEvent    Callback for each stream event
     * @param onComplete Callback when streaming is complete
     * @param onError    Callback on error
     * @return A future that completes when streaming is done
     */
    public CompletableFuture<MessageResponse> streamMessages(
            MessageRequest request,
            Consumer<StreamEvent> onEvent,
            Consumer<MessageResponse> onComplete,
            Consumer<Throwable> onError) {

        CompletableFuture<MessageResponse> future = new CompletableFuture<>();

        try {
            String url = getBaseUrl() + MESSAGES_PATH;
            String body = objectMapper.writeValueAsString(buildRequestBody(request, true));

            Request httpRequest = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body, MediaType.parse("application/json")))
                .header("Accept", "text/event-stream")
                .build();

            EventSource.Factory factory = EventSources.createFactory(httpClient);
            factory.newEventSource(httpRequest, new EventSourceListener() {
                private final MessageAccumulator accumulator = new MessageAccumulator();

                @Override
                public void onEvent(EventSource eventSource, String id, String type, String data) {
                    try {
                        if ("message_start".equals(type)) {
                            Map<String, Object> parsed = objectMapper.readValue(data, Map.class);
                            accumulator.onMessageStart(parsed);
                        } else if ("content_block_start".equals(type)) {
                            Map<String, Object> parsed = objectMapper.readValue(data, Map.class);
                            accumulator.onContentBlockStart(parsed);
                        } else if ("content_block_delta".equals(type)) {
                            Map<String, Object> parsed = objectMapper.readValue(data, Map.class);
                            accumulator.onContentBlockDelta(parsed);
                            StreamEvent event = StreamEvent.delta(parsed);
                            onEvent.accept(event);
                        } else if ("content_block_stop".equals(type)) {
                            accumulator.onContentBlockStop();
                        } else if ("message_delta".equals(type)) {
                            Map<String, Object> parsed = objectMapper.readValue(data, Map.class);
                            accumulator.onMessageDelta(parsed);
                        } else if ("message_stop".equals(type)) {
                            MessageResponse response = accumulator.build();
                            onComplete.accept(response);
                            future.complete(response);
                        } else if ("error".equals(type)) {
                            Map<String, Object> parsed = objectMapper.readValue(data, Map.class);
                            RuntimeException error = new RuntimeException("API error: " + parsed);
                            onError.accept(error);
                            future.completeExceptionally(error);
                        }
                    } catch (Exception e) {
                        log.error("Error processing SSE event: {}", e.getMessage(), e);
                        onError.accept(e);
                        future.completeExceptionally(e);
                    }
                }

                @Override
                public void onFailure(EventSource eventSource, Throwable t, Response response) {
                    String msg = t != null ? t.getMessage() : "Unknown error";
                    if (response != null) {
                        msg += " (HTTP " + response.code() + ")";
                    }
                    RuntimeException error = new RuntimeException("Stream failed: " + msg, t);
                    onError.accept(error);
                    future.completeExceptionally(error);
                }
            });

        } catch (Exception e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Non-streaming message API call.
     * Translated from the non-streaming path in client.ts / claude.ts
     *
     * Injects x-client-request-id for first-party API calls so that
     * timeouts (which return no server request ID) can still be correlated
     * with server logs by the API team.
     */
    public CompletableFuture<MessageResponse> createMessage(MessageRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = getBaseUrl() + MESSAGES_PATH;
                String body = objectMapper.writeValueAsString(buildRequestBody(request, false));

                // Generate a client-side request ID for correlation
                String clientRequestId = UUID.randomUUID().toString();

                Request httpRequest = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(body, MediaType.parse("application/json")))
                    .header(CLIENT_REQUEST_ID_HEADER, clientRequestId)
                    .build();

                log.debug("[API REQUEST] {} {}={}", MESSAGES_PATH,
                    CLIENT_REQUEST_ID_HEADER, clientRequestId);

                try (Response response = httpClient.newCall(httpRequest).execute()) {
                    if (!response.isSuccessful()) {
                        String errorBody = response.body() != null ? response.body().string() : "";
                        // Capture all response headers for retry/error-handling logic
                        Map<String, String> headers = captureHeaders(response.headers());
                        throw new ApiException(response.code(), errorBody, headers);
                    }

                    String responseBody = response.body() != null ? response.body().string() : "{}";
                    MessageResponse result = objectMapper.readValue(responseBody, MessageResponse.class);
                    // Store request ID in response so callers can log it
                    result.setClientRequestId(clientRequestId);
                    result.setRequestId(response.header("request-id"));
                    return result;
                }
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("API request failed: " + e.getMessage(), e);
            }
        });
    }

    /** Extract all headers into a plain map for error reporting. */
    private static Map<String, String> captureHeaders(Headers headers) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            map.put(headers.name(i).toLowerCase(Locale.ROOT), headers.value(i));
        }
        return map;
    }

    private Map<String, Object> buildRequestBody(MessageRequest request, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModel());
        body.put("max_tokens", request.getMaxTokens());

        if (request.getSystem() != null && !request.getSystem().isEmpty()) {
            body.put("system", request.getSystem());
        }

        body.put("messages", request.getMessages());

        if (request.getTools() != null && !request.getTools().isEmpty()) {
            body.put("tools", request.getTools());
        }

        if (request.getToolChoice() != null) {
            body.put("tool_choice", request.getToolChoice());
        }

        if (request.getTemperature() != null) {
            body.put("temperature", request.getTemperature());
        }

        if (request.getThinking() != null) {
            body.put("thinking", request.getThinking());
        }

        if (stream) {
            body.put("stream", true);
        }

        // Add betas if any
        List<String> betas = getBetaHeaders(request);
        if (!betas.isEmpty()) {
            body.put("betas", betas);
        }

        return body;
    }

    private List<String> getBetaHeaders(MessageRequest request) {
        List<String> betas = new ArrayList<>();
        // Add relevant beta features
        if (request.isComputerUse()) betas.add("computer-use-2024-10-22");
        if (request.isPromptCaching()) betas.add("prompt-caching-2024-07-31");
        return betas;
    }

    private String getApiKey() {
        // Priority: explicit config > env var
        if (config.getApiKey() != null) return config.getApiKey();
        return System.getenv("ANTHROPIC_API_KEY");
    }

    private String getOauthToken() {
        // First try environment variable
        String envToken = System.getenv("ANTHROPIC_AUTH_TOKEN");
        if (envToken != null) return envToken;
        // Then try AuthService (OAuth token from keychain/storage)
        if (authService != null) {
            try {
                String token = authService.getClaudeAiOAuthAccessToken();
                if (token != null) return token;
            } catch (Exception e) {
                log.debug("Could not get OAuth token from AuthService: {}", e.getMessage());
            }
        }
        return null;
    }

    private String getBaseUrl() {
        String envUrl = System.getenv("ANTHROPIC_BASE_URL");
        if (envUrl != null) return envUrl;
        return DEFAULT_BASE_URL;
    }

    private String getUserAgent() {
        String version = config.getVersion() != null ? config.getVersion() : "2.1.88";
        return "claude-code/" + version + " Java/21";
    }

    private Map<String, String> parseCustomHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        String customHeadersEnv = System.getenv("ANTHROPIC_CUSTOM_HEADERS");
        if (customHeadersEnv == null) return headers;

        for (String line : customHeadersEnv.split("\n|\r\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int colonIdx = line.indexOf(':');
            if (colonIdx < 0) continue;
            String name = line.substring(0, colonIdx).trim();
            String value = line.substring(colonIdx + 1).trim();
            if (!name.isEmpty()) {
                headers.put(name, value);
            }
        }
        return headers;
    }

    /**
     * Count tokens for a given set of messages and tools.
     * Calls the /v1/messages/count_tokens API endpoint (beta feature).
     * Translated from anthropic.beta.messages.countTokens() in tokenEstimation.ts
     */
    public java.util.concurrent.CompletableFuture<CountTokensResponse> countTokens(CountTokensRequest request) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                String model = request.getModel() != null ? request.getModel()
                        : (config.getModel() != null ? config.getModel() : "claude-3-5-sonnet-20241022");

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", model);
                body.put("messages", request.getMessages() != null ? request.getMessages() : List.of());
                if (request.getTools() != null && !request.getTools().isEmpty()) {
                    body.put("tools", request.getTools());
                }

                String url = getBaseUrl() + "/v1/messages/count_tokens";
                String bodyJson = objectMapper.writeValueAsString(body);

                Request httpRequest = new Request.Builder()
                        .url(url)
                        .post(RequestBody.create(bodyJson, MediaType.parse("application/json")))
                        .header("anthropic-beta", "token-counting-2024-11-01")
                        .header(CLIENT_REQUEST_ID_HEADER, UUID.randomUUID().toString())
                        .build();

                try (Response response = httpClient.newCall(httpRequest).execute()) {
                    if (!response.isSuccessful()) {
                        log.warn("[countTokens] API error {}", response.code());
                        return null;
                    }
                    String responseBody = response.body() != null ? response.body().string() : "{}";
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = objectMapper.readValue(responseBody, Map.class);
                    Object inputTokens = parsed.get("input_tokens");
                    CountTokensResponse result = new CountTokensResponse();
                    if (inputTokens instanceof Number n) {
                        result.setInputTokens(n.intValue());
                    }
                    return result;
                }
            } catch (Exception e) {
                log.debug("[countTokens] Failed: {}", e.getMessage());
                return null;
            }
        });
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    public static class MessageRequest {
        private String model;
        private int maxTokens;
        private List<Map<String, Object>> system;
        private List<Map<String, Object>> messages;
        private List<Map<String, Object>> tools;
        private Map<String, Object> toolChoice;
        private Double temperature;
        private Map<String, Object> thinking;
        private boolean stream;
        private boolean computerUse;
        private boolean promptCaching;
        private Map<String, Object> outputFormat;
        public String getModel() { return model; }
        public void setModel(String v) { this.model = v; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int v) { this.maxTokens = v; }
        public List<Map<String, Object>> getSystem() { return system; }
        public void setSystem(List<Map<String, Object>> v) { this.system = v; }
        public List<Map<String, Object>> getMessages() { return messages; }
        public void setMessages(List<Map<String, Object>> v) { this.messages = v; }
        public List<Map<String, Object>> getTools() { return tools; }
        public void setTools(List<Map<String, Object>> v) { this.tools = v; }
        public Map<String, Object> getToolChoice() { return toolChoice; }
        public void setToolChoice(Map<String, Object> v) { this.toolChoice = v; }
        public Double getTemperature() { return temperature; }
        public void setTemperature(Double v) { this.temperature = v; }
        public Map<String, Object> getThinking() { return thinking; }
        public void setThinking(Map<String, Object> v) { this.thinking = v; }
        public boolean isStream() { return stream; }
        public void setStream(boolean v) { this.stream = v; }
        public boolean isComputerUse() { return computerUse; }
        public void setComputerUse(boolean v) { this.computerUse = v; }
        public boolean isPromptCaching() { return promptCaching; }
        public void setPromptCaching(boolean v) { this.promptCaching = v; }
        public Map<String, Object> getOutputFormat() { return outputFormat; }
        public void setOutputFormat(Map<String, Object> v) { this.outputFormat = v; }

        public static MessageRequestBuilder builder() { return new MessageRequestBuilder(); }
        public static class MessageRequestBuilder {
            private final MessageRequest req = new MessageRequest();
            public MessageRequestBuilder model(String v) { req.model = v; return this; }
            public MessageRequestBuilder maxTokens(int v) { req.maxTokens = v; return this; }
            public MessageRequestBuilder system(List<Map<String, Object>> v) { req.system = v; return this; }
            public MessageRequestBuilder messages(List<Map<String, Object>> v) { req.messages = v; return this; }
            public MessageRequestBuilder tools(List<Map<String, Object>> v) { req.tools = v; return this; }
            public MessageRequestBuilder toolChoice(Map<String, Object> v) { req.toolChoice = v; return this; }
            public MessageRequestBuilder temperature(Double v) { req.temperature = v; return this; }
            public MessageRequestBuilder thinking(Map<String, Object> v) { req.thinking = v; return this; }
            public MessageRequestBuilder stream(boolean v) { req.stream = v; return this; }
            public MessageRequestBuilder computerUse(boolean v) { req.computerUse = v; return this; }
            public MessageRequestBuilder promptCaching(boolean v) { req.promptCaching = v; return this; }
            public MessageRequestBuilder outputFormat(Map<String, Object> v) { req.outputFormat = v; return this; }
            public MessageRequest build() { return req; }
        }
    }

    /** Usage statistics returned in a message response. */
    public static class UsageStats {
        private int inputTokens;
        private int outputTokens;
        private Integer cacheCreationInputTokens;
        private Integer cacheReadInputTokens;
        public int getInputTokens() { return inputTokens; }
        public void setInputTokens(int v) { inputTokens = v; }
        public int getOutputTokens() { return outputTokens; }
        public void setOutputTokens(int v) { outputTokens = v; }
        public Integer getCacheCreationInputTokens() { return cacheCreationInputTokens; }
        public void setCacheCreationInputTokens(Integer v) { cacheCreationInputTokens = v; }
        public Integer getCacheReadInputTokens() { return cacheReadInputTokens; }
        public void setCacheReadInputTokens(Integer v) { cacheReadInputTokens = v; }
    }

    /** Count tokens request. */
    public static class CountTokensRequest {
        private List<Object> messages;
        private List<Object> tools;
        private String model;
        public static Builder builder() { return new Builder(); }
        public static class Builder {
            private final CountTokensRequest r = new CountTokensRequest();
            public Builder messages(List<Object> v) { r.messages = v; return this; }
            public Builder tools(List<Object> v) { r.tools = v; return this; }
            public Builder model(String v) { r.model = v; return this; }
            public CountTokensRequest build() { return r; }
        }
        public List<Object> getMessages() { return messages; }
        public List<Object> getTools() { return tools; }
        public String getModel() { return model; }
    }

    /** Count tokens response. */
    public static class CountTokensResponse {
        private int inputTokens;
        public int getInputTokens() { return inputTokens; }
        public void setInputTokens(int v) { inputTokens = v; }
    }

    public static class MessageResponse {
        private String id;
        private String type;
        private String role;
        private List<Map<String, Object>> content;
        private String model;
        private String stopReason;
        private String stopSequence;
        private Map<String, Object> usage;
        @com.fasterxml.jackson.annotation.JsonIgnore
        private String requestId;
        @com.fasterxml.jackson.annotation.JsonIgnore
        private String clientRequestId;
        public String getId() { return id; }
        public void setId(String v) { this.id = v; }
        public String getModel() { return model; }
        public void setModel(String v) { this.model = v; }
        public String getType() { return type; }
        public void setType(String v) { this.type = v; }
        public String getRole() { return role; }
        public void setRole(String v) { this.role = v; }
        public List<Map<String, Object>> getContent() { return content; }
        public void setContent(List<Map<String, Object>> v) { this.content = v; }
        public String getStopReason() { return stopReason; }
        public void setStopReason(String v) { this.stopReason = v; }
        public String getStopSequence() { return stopSequence; }
        public void setStopSequence(String v) { this.stopSequence = v; }
        public Map<String, Object> getUsage() { return usage; }
        public void setUsage(Map<String, Object> v) { this.usage = v; }
        /** Get usage as typed object. */
        public UsageStats getTypedUsage() {
            if (usage == null) return null;
            UsageStats s = new UsageStats();
            Object inp = usage.get("input_tokens");
            if (inp instanceof Number) s.setInputTokens(((Number)inp).intValue());
            Object out = usage.get("output_tokens");
            if (out instanceof Number) s.setOutputTokens(((Number)out).intValue());
            Object cc = usage.get("cache_creation_input_tokens");
            if (cc instanceof Number) s.setCacheCreationInputTokens(((Number)cc).intValue());
            Object cr = usage.get("cache_read_input_tokens");
            if (cr instanceof Number) s.setCacheReadInputTokens(((Number)cr).intValue());
            return s;
        }
        public String getRequestId() { return requestId; }
        public void setRequestId(String v) { this.requestId = v; }
        public String getClientRequestId() { return clientRequestId; }
        public void setClientRequestId(String v) { this.clientRequestId = v; }
    }

    public static class StreamEvent {
        private String type;
        private Map<String, Object> data;

        public StreamEvent() {}
        public StreamEvent(String type, Map<String, Object> data) {
            this.type = type;
            this.data = data;
        }

        public Map<String, Object> getData() { return data; }
        public void setData(Map<String, Object> v) { this.data = v; }

        public static StreamEvent delta(Map<String, Object> data) {
            return new StreamEvent("content_block_delta", data);
        }
    }

    /**
     * Accumulates streaming events into a complete message.
     */
    private static class MessageAccumulator {
        private String id;
        private String model;
        private String stopReason;
        private Map<String, Object> usage = new LinkedHashMap<>();
        private List<Map<String, Object>> contentBlocks = new ArrayList<>();
        private Map<String, Object> currentBlock;
        private StringBuilder currentText = new StringBuilder();

        void onMessageStart(Map<String, Object> data) {
            Map<String, Object> message = (Map<String, Object>) data.get("message");
            if (message != null) {
                id = (String) message.get("id");
                model = (String) message.get("model");
                usage = (Map<String, Object>) message.getOrDefault("usage", new LinkedHashMap<>());
            }
        }

        void onContentBlockStart(Map<String, Object> data) {
            currentBlock = (Map<String, Object>) data.get("content_block");
            currentText = new StringBuilder();
        }

        void onContentBlockDelta(Map<String, Object> data) {
            Map<String, Object> delta = (Map<String, Object>) data.get("delta");
            if (delta != null) {
                String type = (String) delta.get("type");
                if ("text_delta".equals(type)) {
                    currentText.append(delta.get("text"));
                } else if ("input_json_delta".equals(type)) {
                    currentText.append(delta.get("partial_json"));
                }
            }
        }

        void onContentBlockStop() {
            if (currentBlock != null) {
                Map<String, Object> block = new LinkedHashMap<>(currentBlock);
                String type = (String) block.get("type");
                if ("text".equals(type)) {
                    block.put("text", currentText.toString());
                } else if ("tool_use".equals(type)) {
                    try {
                        ObjectMapper mapper = new ObjectMapper();
                        block.put("input", mapper.readValue(currentText.toString(), Map.class));
                    } catch (Exception e) {
                        block.put("input", Map.of());
                    }
                }
                contentBlocks.add(block);
            }
            currentBlock = null;
            currentText = new StringBuilder();
        }

        void onMessageDelta(Map<String, Object> data) {
            Map<String, Object> delta = (Map<String, Object>) data.get("delta");
            if (delta != null) {
                stopReason = (String) delta.get("stop_reason");
            }
            Map<String, Object> deltaUsage = (Map<String, Object>) data.get("usage");
            if (deltaUsage != null) {
                usage.putAll(deltaUsage);
            }
        }

        MessageResponse build() {
            MessageResponse r = new MessageResponse();
            r.setId(id);
            r.setType("message");
            r.setRole("assistant");
            r.setContent(contentBlocks);
            r.setModel(model);
            r.setStopReason(stopReason);
            r.setUsage(usage);
            return r;
        }
    }

    public static class ApiException extends RuntimeException {
        private final int statusCode;
        private final String body;
        /** Response headers from the failed request (may be null for connection errors). */
        private final Map<String, String> responseHeaders;

        public ApiException(int statusCode, String body) {
            this(statusCode, body, null);
        }

        public ApiException(int statusCode, String body, Map<String, String> headers) {
            super("API error " + statusCode + ": " + body);
            this.statusCode = statusCode;
            this.body = body;
            this.responseHeaders = headers != null ? Collections.unmodifiableMap(new LinkedHashMap<>(headers)) : Map.of();
        }

        public int getStatusCode() { return statusCode; }
        public String getBody() { return body; }

        /**
         * Get a response header value (case-insensitive name lookup).
         * Returns null if the header is absent or no headers were captured.
         * Used by ApiRetryService to read Retry-After and x-should-retry headers.
         */
        public String getResponseHeader(String name) {
            if (responseHeaders == null || name == null) return null;
            String lower = name.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, String> entry : responseHeaders.entrySet()) {
                if (entry.getKey().toLowerCase(Locale.ROOT).equals(lower)) {
                    return entry.getValue();
                }
            }
            return null;
        }
    }
}
