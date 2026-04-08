package com.anthropic.claudecode.service;

import com.anthropic.claudecode.client.AnthropicClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import lombok.Data;

/**
 * Side query service for internal API calls.
 * Translated from src/utils/sideQuery.ts
 *
 * Makes API calls for internal purposes (classifiers, summaries, etc.)
 * without affecting the main conversation context.
 */
@Slf4j
@Service
public class SideQueryService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SideQueryService.class);


    private final AnthropicClient anthropicClient;

    @Autowired
    public SideQueryService(AnthropicClient anthropicClient) {
        this.anthropicClient = anthropicClient;
    }

    /**
     * Execute a side query.
     * Translated from sideQuery() in sideQuery.ts
     */
    public CompletableFuture<String> sideQuery(SideQueryOptions options) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<Map<String, Object>> systemBlocks = new ArrayList<>();
                if (options.getSystem() != null) {
                    systemBlocks.add(Map.of("type", "text", "text", options.getSystem()));
                }

                AnthropicClient.MessageRequest request = AnthropicClient.MessageRequest.builder()
                    .model(options.getModel())
                    .maxTokens(options.getMaxTokens() > 0 ? options.getMaxTokens() : 1024)
                    .system(systemBlocks)
                    .messages(options.getMessages())
                    .build();

                AnthropicClient.MessageResponse response = anthropicClient
                    .createMessage(request)
                    .get();

                // Extract text from response
                if (response.getContent() == null) return "";
                StringBuilder sb = new StringBuilder();
                for (Map<String, Object> block : response.getContent()) {
                    if ("text".equals(block.get("type"))) {
                        sb.append(block.get("text"));
                    }
                }
                return sb.toString();

            } catch (Exception e) {
                throw new RuntimeException("Side query failed: " + e.getMessage(), e);
            }
        });
    }

    @Data
    public static class SideQueryOptions {
        private String model;
        private String system;
        private List<Map<String, Object>> messages;
        private List<Map<String, Object>> tools;
        private int maxTokens;
        private int maxRetries;
        private Double temperature;
        private boolean skipSystemPromptPrefix;
        private Map<String, Object> outputFormat;
        private String querySource;

        public String getModel() { return model; }
        public void setModel(String v) { model = v; }
        public String getSystem() { return system; }
        public void setSystem(String v) { system = v; }
        public List<Map<String, Object>> getMessages() { return messages; }
        public void setMessages(List<Map<String, Object>> v) { messages = v; }
        public List<Map<String, Object>> getTools() { return tools; }
        public void setTools(List<Map<String, Object>> v) { tools = v; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int v) { maxTokens = v; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int v) { maxRetries = v; }
        public Double getTemperature() { return temperature; }
        public void setTemperature(Double v) { temperature = v; }
        public boolean isSkipSystemPromptPrefix() { return skipSystemPromptPrefix; }
        public void setSkipSystemPromptPrefix(boolean v) { skipSystemPromptPrefix = v; }
        public Map<String, Object> getOutputFormat() { return outputFormat; }
        public void setOutputFormat(Map<String, Object> v) { outputFormat = v; }
        public String getQuerySource() { return querySource; }
        public void setQuerySource(String v) { querySource = v; }

        public static SideQueryOptionsBuilder builder() { return new SideQueryOptionsBuilder(); }

        public static class SideQueryOptionsBuilder {
            private final SideQueryOptions opts = new SideQueryOptions();
            public SideQueryOptionsBuilder model(String v) { opts.model = v; return this; }
            public SideQueryOptionsBuilder system(String v) { opts.system = v; return this; }
            public SideQueryOptionsBuilder messages(List<Map<String, Object>> v) { opts.messages = v; return this; }
            public SideQueryOptionsBuilder tools(List<Map<String, Object>> v) { opts.tools = v; return this; }
            public SideQueryOptionsBuilder maxTokens(int v) { opts.maxTokens = v; return this; }
            public SideQueryOptionsBuilder maxRetries(int v) { opts.maxRetries = v; return this; }
            public SideQueryOptionsBuilder temperature(Double v) { opts.temperature = v; return this; }
            public SideQueryOptionsBuilder skipSystemPromptPrefix(boolean v) { opts.skipSystemPromptPrefix = v; return this; }
            public SideQueryOptionsBuilder outputFormat(Map<String, Object> v) { opts.outputFormat = v; return this; }
            public SideQueryOptionsBuilder querySource(String v) { opts.querySource = v; return this; }
            public SideQueryOptions build() { return opts; }
        }
    }
}
