package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import com.anthropic.claudecode.service.McpService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import lombok.Data;

/**
 * Tool to read MCP resources.
 * Translated from src/tools/ReadMcpResourceTool/ReadMcpResourceTool.ts
 */
@Slf4j
@Component
public class ReadMcpResourceTool extends AbstractTool<ReadMcpResourceTool.Input, ReadMcpResourceTool.Output> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ReadMcpResourceTool.class);


    public static final String TOOL_NAME = "ReadMcpResource";

    private final McpService mcpService;

    @Autowired
    public ReadMcpResourceTool(McpService mcpService) {
        this.mcpService = mcpService;
    }

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "server", Map.of("type", "string", "description", "The MCP server name"),
                "uri", Map.of("type", "string", "description", "The resource URI to read")
            ),
            "required", List.of("server", "uri")
        );
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
            Input args,
            ToolUseContext context,
            Tool.CanUseToolFn canUseTool,
            Message.AssistantMessage parentMessage,
            Consumer<Tool.ToolProgress> onProgress) {

        // In a real implementation, this would call the MCP server's resource endpoint
        return futureResult(Output.builder()
            .contents(List.of(new ResourceContent(
                args.getUri(),
                "text/plain",
                "Resource content from " + args.getServer(),
                null
            )))
            .build());
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Reading MCP resource: " + input.getUri());
    }

    @Override
    public boolean isReadOnly(Input input) { return true; }

    @Override
    public boolean isConcurrencySafe(Input input) { return true; }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        String text = content.getContents().isEmpty()
            ? "No content"
            : content.getContents().get(0).getText() != null
                ? content.getContents().get(0).getText()
                : "Binary content saved to: " + content.getContents().get(0).getBlobSavedTo();
        return Map.of("type", "tool_result", "tool_use_id", toolUseId, "content", text);
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Input {
        private String server;
        private String uri;
    
        public String getServer() { return server; }
    
        public String getUri() { return uri; }
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Output {
        private List<ResourceContent> contents;
    
        public static OutputBuilder builder() { return new OutputBuilder(); }
        public static class OutputBuilder {
            private List<ResourceContent> contents;
            public OutputBuilder contents(List<ResourceContent> v) { this.contents = v; return this; }
            public Output build() {
                Output o = new Output();
                o.contents = contents;
                return o;
            }
        }
    
        public List<ResourceContent> getContents() { return contents; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ResourceContent {
        private String uri;
        private String mimeType;
        private String text;
        private String blobSavedTo;

        public String getUri() { return uri; }
        public void setUri(String v) { uri = v; }
        public String getMimeType() { return mimeType; }
        public void setMimeType(String v) { mimeType = v; }
        public String getText() { return text; }
        public void setText(String v) { text = v; }
        public String getBlobSavedTo() { return blobSavedTo; }
        public void setBlobSavedTo(String v) { blobSavedTo = v; }
    
    }
}
