package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import com.anthropic.claudecode.service.McpService;
import lombok.extern.slf4j.Slf4j;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * MCP (Model Context Protocol) tool wrapper.
 * Translated from src/tools/MCPTool/MCPTool.ts
 *
 * Wraps MCP server tools to make them available in the conversation.
 * Each MCP tool is dynamically created based on the server's tool definitions.
 */
@Slf4j
public class McpTool extends AbstractTool<Map<String, Object>, String> {



    private final String toolName;
    private final String serverName;
    private final String description;
    private final Map<String, Object> inputSchema;
    private final McpService mcpService;

    public McpTool(
            String serverName,
            String toolName,
            String description,
            Map<String, Object> inputSchema,
            McpService mcpService) {
        this.serverName = serverName;
        this.toolName = toolName;
        this.description = description;
        this.inputSchema = inputSchema;
        this.mcpService = mcpService;
    }

    @Override
    public String getName() {
        // MCP tools are named as "mcp__serverName__toolName"
        return "mcp__" + serverName + "__" + toolName;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return inputSchema != null ? inputSchema : Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public CompletableFuture<ToolResult<String>> call(
            Map<String, Object> args,
            ToolUseContext context,
            Tool.CanUseToolFn canUseTool,
            Message.AssistantMessage parentMessage,
            Consumer<Tool.ToolProgress> onProgress) {

        return mcpService.callTool(serverName, toolName, args)
            .thenApply(result -> {
                String output = result != null ? result.toString() : "";
                return this.result(output);
            })
            .exceptionally(e -> {
                log.error("MCP tool {} failed: {}", getName(), e.getMessage());
                throw new RuntimeException("MCP tool failed: " + e.getMessage(), e);
            });
    }

    @Override
    public CompletableFuture<String> description(Map<String, Object> input, DescriptionOptions options) {
        return CompletableFuture.completedFuture(
            description != null ? description : "MCP tool: " + toolName
        );
    }

    @Override
    public boolean isReadOnly(Map<String, Object> input) { return false; }

    @Override
    public String userFacingName(Map<String, Object> input) {
        return serverName + ": " + toolName;
    }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(String content, String toolUseId) {
        return Map.of(
            "type", "tool_result",
            "tool_use_id", toolUseId,
            "content", content != null ? content : ""
        );
    }

    @Override
    public CompletableFuture<PermissionResult> checkPermissions(
            Map<String, Object> input,
            ToolUseContext context) {
        // MCP tools require permission check
        return CompletableFuture.completedFuture(
            PermissionResult.PassthroughDecision.builder()
                .message("MCP tool requires permission.")
                .build()
        );
    }
}
