package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Tool search tool for discovering deferred tools.
 * Translated from src/tools/ToolSearchTool/ToolSearchTool.ts
 *
 * Allows the model to search for and load deferred tools.
 */
@Slf4j
@Component
public class ToolSearchTool extends AbstractTool<ToolSearchTool.Input, ToolSearchTool.Output> {



    public static final String TOOL_NAME = "ToolSearch";
    private static final int DEFAULT_MAX_RESULTS = 5;

    private List<Tool<?, ?>> allTools;

    public ToolSearchTool() {
    }

    @Autowired
    public void setAllTools(@Lazy List<Tool<?, ?>> allTools) {
        this.allTools = allTools;
    }

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "query", Map.of(
                    "type", "string",
                    "description", "Query to find deferred tools. Use \"select:<tool_name>\" for direct selection, or keywords to search."
                ),
                "max_results", Map.of(
                    "type", "integer",
                    "description", "Maximum number of results to return (default: 5)",
                    "default", 5
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

        String query = args.getQuery();
        int maxResults = args.getMaxResults() != null ? args.getMaxResults() : DEFAULT_MAX_RESULTS;

        // Search for matching tools
        List<String> matches = searchTools(query, maxResults);

        return futureResult(Output.builder()
            .matches(matches)
            .query(query)
            .totalDeferredTools(allTools.size())
            .build());
    }

    private List<String> searchTools(String query, int maxResults) {
        if (query == null || query.isBlank()) return List.of();

        // Direct selection: "select:<tool_name>"
        if (query.startsWith("select:")) {
            String toolName = query.substring(7).trim();
            return allTools.stream()
                .filter(t -> t.getName().equalsIgnoreCase(toolName))
                .map(Tool::getName)
                .limit(maxResults)
                .collect(Collectors.toList());
        }

        // Keyword search
        String lowerQuery = query.toLowerCase();
        return allTools.stream()
            .filter(t -> {
                String name = t.getName().toLowerCase();
                String hint = t.getSearchHint() != null ? t.getSearchHint().toLowerCase() : "";
                return name.contains(lowerQuery) || hint.contains(lowerQuery);
            })
            .map(Tool::getName)
            .limit(maxResults)
            .collect(Collectors.toList());
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Searching for tools: " + input.getQuery());
    }

    @Override
    public boolean isReadOnly(Input input) { return true; }

    @Override
    public boolean isConcurrencySafe(Input input) { return true; }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        String text = content.getMatches().isEmpty()
            ? "No tools found matching: " + content.getQuery()
            : "Found " + content.getMatches().size() + " tools: " + String.join(", ", content.getMatches());
        return Map.of("type", "tool_result", "tool_use_id", toolUseId, "content", text);
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Input {
        private String query;
        private Integer maxResults;
    
        public Integer getMaxResults() { return maxResults; }
    
        public String getQuery() { return query; }
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Output {
        private List<String> matches;
        private String query;
        private int totalDeferredTools;
        private List<String> pendingMcpServers;
    
        public List<String> getMatches() { return matches; }
    
        public String getQuery() { return query; }
    
        public static OutputBuilder builder() { return new OutputBuilder(); }
        public static class OutputBuilder {
            private List<String> matches;
            private String query;
            private int totalDeferredTools;
            private List<String> pendingMcpServers;
            public OutputBuilder matches(List<String> v) { this.matches = v; return this; }
            public OutputBuilder query(String v) { this.query = v; return this; }
            public OutputBuilder totalDeferredTools(int v) { this.totalDeferredTools = v; return this; }
            public OutputBuilder pendingMcpServers(List<String> v) { this.pendingMcpServers = v; return this; }
            public Output build() {
                Output o = new Output();
                o.matches = matches;
                o.query = query;
                o.totalDeferredTools = totalDeferredTools;
                o.pendingMcpServers = pendingMcpServers;
                return o;
            }
        }
    }
}
