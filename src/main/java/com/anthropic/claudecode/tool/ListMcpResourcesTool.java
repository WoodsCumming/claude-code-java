package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import com.anthropic.claudecode.service.McpService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.Data;

/**
 * Tool to list MCP resources.
 * Translated from src/tools/ListMcpResourcesTool/ListMcpResourcesTool.ts
 */
@Slf4j
@Component
public class ListMcpResourcesTool extends AbstractTool<ListMcpResourcesTool.Input, ListMcpResourcesTool.Output> {



    public static final String TOOL_NAME = "ListMcpResources";

    private final McpService mcpService;

    @Autowired
    public ListMcpResourcesTool(McpService mcpService) {
        this.mcpService = mcpService;
    }

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public String getSearchHint() { return "list resources from connected MCP servers"; }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "server", Map.of(
                    "type", "string",
                    "description", "Optional server name to filter resources by"
                )
            )
        );
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
            Input args,
            ToolUseContext context,
            Tool.CanUseToolFn canUseTool,
            Message.AssistantMessage parentMessage,
            Consumer<Tool.ToolProgress> onProgress) {

        List<ResourceInfo> resources = new ArrayList<>();

        // Get tools from all MCP servers (simplified - would get resources in full impl)
        List<McpService.McpTool> tools = mcpService.getAllTools();
        for (McpService.McpTool tool : tools) {
            if (args.getServer() == null || args.getServer().equals(tool.getServerName())) {
                resources.add(new ResourceInfo(
                    "mcp://" + tool.getServerName() + "/" + tool.getName(),
                    tool.getName(),
                    "application/json",
                    tool.getDescription(),
                    tool.getServerName()
                ));
            }
        }

        return futureResult(Output.builder().resources(resources).build());
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Listing MCP resources");
    }

    @Override
    public boolean isReadOnly(Input input) { return true; }

    @Override
    public boolean isConcurrencySafe(Input input) { return true; }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        String text = content.getResources().isEmpty()
            ? "No MCP resources available"
            : content.getResources().size() + " resources found";
        return Map.of("type", "tool_result", "tool_use_id", toolUseId, "content", text);
    }

    public static class Input {
        private String server;
        public Input() {}
        public Input(String server) { this.server = server; }
        public String getServer() { return server; }
        public void setServer(String v) { server = v; }
        public static InputBuilder builder() { return new InputBuilder(); }
        public static class InputBuilder {
            private String server;
            public InputBuilder server(String v) { this.server = v; return this; }
            public Input build() { return new Input(server); }
        }
    }

    public static class Output {
        private List<ResourceInfo> resources = new java.util.ArrayList<>();
        public Output() {}
        public Output(List<ResourceInfo> resources) { this.resources = resources; }
        public List<ResourceInfo> getResources() { return resources; }
        public void setResources(List<ResourceInfo> v) { resources = v; }
        public static OutputBuilder builder() { return new OutputBuilder(); }
        public static class OutputBuilder {
            private List<ResourceInfo> resources;
            public OutputBuilder resources(List<ResourceInfo> v) { this.resources = v; return this; }
            public Output build() { return new Output(resources); }
        }
    }

    public static class ResourceInfo {
        private String uri;
        private String name;
        private String mimeType;
        private String description;
        private String server;
        public ResourceInfo() {}
        public ResourceInfo(String uri, String name, String mimeType, String description, String server) {
            this.uri = uri; this.name = name; this.mimeType = mimeType; this.description = description; this.server = server;
        }
        public String getUri() { return uri; }
        public void setUri(String v) { uri = v; }
        public void setName(String v) { name = v; }
        public String getMimeType() { return mimeType; }
        public void setMimeType(String v) { mimeType = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
    }
}
