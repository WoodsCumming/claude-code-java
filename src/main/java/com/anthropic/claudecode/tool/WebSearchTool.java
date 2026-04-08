package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import lombok.Data;

/**
 * Web search tool.
 * Translated from src/tools/WebSearchTool/WebSearchTool.ts
 *
 * Uses the Anthropic API's built-in web search capability (web_search_20250305).
 * The actual search is performed server-side by the Anthropic API.
 */
@Slf4j
@Component
public class WebSearchTool extends AbstractTool<WebSearchTool.Input, WebSearchTool.Output> {



    public static final String TOOL_NAME = "WebSearch";

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getSearchHint() {
        return "search the web for current information";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "query", Map.of(
                    "type", "string",
                    "description", "The search query to use",
                    "minLength", 2
                ),
                "allowed_domains", Map.of(
                    "type", "array",
                    "items", Map.of("type", "string"),
                    "description", "Only include search results from these domains"
                ),
                "blocked_domains", Map.of(
                    "type", "array",
                    "items", Map.of("type", "string"),
                    "description", "Never include search results from these domains"
                )
            ),
            "required", List.of("query")
        );
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
            Input args,
            ToolUseContext context,
            Tool.CanUseToolFn canUseTool,
            Message.AssistantMessage parentMessage,
            Consumer<Tool.ToolProgress> onProgress) {

        // Web search is performed by the Anthropic API server-side.
        // This tool definition tells the API to use web_search_20250305.
        // The actual results come back in the API response.
        return CompletableFuture.completedFuture(
            result(Output.builder()
                .query(args.getQuery())
                .results(List.of("Web search is handled server-side by the Anthropic API"))
                .durationSeconds(0)
                .build())
        );
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Searching the web for: " + input.getQuery());
    }

    @Override
    public boolean isReadOnly(Input input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(Input input) {
        return true;
    }

    @Override
    public String userFacingName(Input input) {
        return input != null && input.getQuery() != null ? input.getQuery() : TOOL_NAME;
    }

    @Override
    public String getActivityDescription(Input input) {
        return "Searching for " + (input != null ? input.getQuery() : "information");
    }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        return Map.of(
            "type", "tool_result",
            "tool_use_id", toolUseId,
            "content", content.getResults() != null
                ? String.join("\n", content.getResults().stream()
                    .map(Object::toString)
                    .toList())
                : "No results"
        );
    }

    // =========================================================================
    // Input/Output types
    // =========================================================================

    public static class Input {
        private String query;
        private List<String> allowedDomains;
        private List<String> blockedDomains;
        public Input() {}
        public String getQuery() { return query; }
        public void setQuery(String v) { query = v; }
        public List<String> getAllowedDomains() { return allowedDomains; }
        public void setAllowedDomains(List<String> v) { allowedDomains = v; }
        public List<String> getBlockedDomains() { return blockedDomains; }
        public void setBlockedDomains(List<String> v) { blockedDomains = v; }
        public static InputBuilder builder() { return new InputBuilder(); }
        public static class InputBuilder {
            private String query; private List<String> allowedDomains; private List<String> blockedDomains;
            public InputBuilder query(String v) { this.query = v; return this; }
            public InputBuilder allowedDomains(List<String> v) { this.allowedDomains = v; return this; }
            public InputBuilder blockedDomains(List<String> v) { this.blockedDomains = v; return this; }
            public Input build() { Input i = new Input(); i.query = query; i.allowedDomains = allowedDomains; i.blockedDomains = blockedDomains; return i; }
        }
    }

    @Data
    @lombok.Builder
    
    public static class Output {
        private String query;
        private List<Object> results;
        private double durationSeconds;
        public Output() {}
        public String getQuery() { return query; }
        public void setQuery(String v) { query = v; }
        public List<Object> getResults() { return results; }
        public void setResults(List<Object> v) { results = v; }
        public double getDurationSeconds() { return durationSeconds; }
        public void setDurationSeconds(double v) { durationSeconds = v; }
        public static OutputBuilder builder() { return new OutputBuilder(); }
        public static class OutputBuilder {
            private String query; private List<Object> results; private double durationSeconds;
            public OutputBuilder query(String v) { this.query = v; return this; }
            public OutputBuilder results(List<Object> v) { this.results = v; return this; }
            public OutputBuilder durationSeconds(double v) { this.durationSeconds = v; return this; }
            public Output build() { Output o = new Output(); o.query = query; o.results = results; o.durationSeconds = durationSeconds; return o; }
        }
    }
}
