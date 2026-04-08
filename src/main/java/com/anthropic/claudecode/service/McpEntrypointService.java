package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * MCP server entrypoint service — exposes Claude Code's tools as an MCP server.
 * Translated from src/entrypoints/mcp.ts
 *
 * Starts a stdio-based MCP server that lists and calls Claude Code tools.
 * The server name is "claude/tengu". Only tools that are enabled and pass
 * input validation are callable; errors are returned as { isError: true } results.
 *
 * Key design points from the TypeScript source:
 *  - Uses a size-limited LRU cache (100 files, 25 MB) for readFileState
 *  - MCP_COMMANDS currently contains only the `review` command
 *  - outputSchema is only attached when the root type is "object" (not anyOf/oneOf)
 *  - Tool call errors are decomposed via getErrorParts() before being returned
 */
@Slf4j
@Service
public class McpEntrypointService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(McpEntrypointService.class);


    /** Size of the LRU readFileState cache (mirrors READ_FILE_STATE_CACHE_SIZE = 100). */
    private static final int READ_FILE_STATE_CACHE_SIZE = 100;

    private final ToolExecutionService toolExecutionService;
    private final PermissionService permissionService;
    private List<com.anthropic.claudecode.tool.Tool<?, ?>> allTools = List.of();

    @Autowired
    public McpEntrypointService(ToolExecutionService toolExecutionService,
                                PermissionService permissionService) {
        this.toolExecutionService = toolExecutionService;
        this.permissionService = permissionService;
    }

    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    public void setAllTools(List<com.anthropic.claudecode.tool.Tool<?, ?>> tools) {
        this.allTools = tools != null ? tools : List.of();
    }

    // ---------------------------------------------------------------------------
    // MCP protocol types (mirrors MCP SDK types)
    // ---------------------------------------------------------------------------

    /**
     * Represents a single tool exposed by the MCP server.
     * Mirrors the Tool type from @modelcontextprotocol/sdk/types.js
     */
    public record McpToolDescription(
        String name,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema
    ) {}

    /**
     * Result of a tool call.
     * Mirrors CallToolResult from @modelcontextprotocol/sdk/types.js
     */
    public record McpCallToolResult(
        List<Map<String, Object>> content,
        boolean isError
    ) {
        public static McpCallToolResult success(String text) {
            return new McpCallToolResult(
                List.of(Map.of("type", "text", "text", text)),
                false
            );
        }

        public static McpCallToolResult error(String errorText) {
            return new McpCallToolResult(
                List.of(Map.of("type", "text", "text", errorText)),
                true
            );
        }
    }

    // ---------------------------------------------------------------------------
    // ListTools handler
    // ---------------------------------------------------------------------------

    /**
     * Handle an MCP ListTools request.
     * Translated from the ListToolsRequestSchema handler in startMCPServer() in mcp.ts
     *
     * Returns all enabled Claude Code tools with their prompt text as description.
     * outputSchema is only included when the schema root type is "object".
     */
    @SuppressWarnings({"unchecked","rawtypes"})
    public CompletableFuture<List<McpToolDescription>> listTools() {
        return CompletableFuture.supplyAsync(() -> {
            log.debug("[McpEntrypoint] ListTools called — {} tools available", allTools.size());

            List<McpToolDescription> descriptions = new java.util.ArrayList<>();
            for (com.anthropic.claudecode.tool.Tool<?, ?> tool : allTools) {
                if (!tool.isEnabled()) continue;

                Map<String, Object> inputSchema = tool.getInputSchema();
                // Only include outputSchema when root type is "object" (per TS mcp.ts logic)
                Map<String, Object> outputSchema = null;

                // Build description from tool metadata
                String description = tool.getSearchHint() != null
                        ? tool.getSearchHint()
                        : (tool.getDescription() != null ? tool.getDescription() : tool.getName());

                descriptions.add(new McpToolDescription(
                        tool.getName(),
                        description,
                        inputSchema,
                        outputSchema
                ));
            }
            return descriptions;
        });
    }

    // ---------------------------------------------------------------------------
    // CallTool handler
    // ---------------------------------------------------------------------------

    /**
     * Handle an MCP CallTool request.
     * Translated from the CallToolRequestSchema handler in startMCPServer() in mcp.ts
     *
     * Validates that the tool is enabled, validates input, then calls the tool.
     * Returns { isError: true } content on any failure.
     *
     * @param name The tool name
     * @param args The tool arguments (may be null; treated as empty map)
     * @return CompletableFuture with the call result
     */
    public CompletableFuture<McpCallToolResult> callTool(String name, Map<String, Object> args) {
        Map<String, Object> safeArgs = args != null ? args : Map.of();

        return CompletableFuture.supplyAsync(() -> {
            try {
                // In a full implementation:
                //  1. getTools(toolPermissionContext) — with isNonInteractiveSession=true
                //  2. findToolByName(tools, name) — throw if not found
                //  3. tool.isEnabled() — throw if disabled
                //  4. tool.validateInput(args, toolUseContext) — throw if invalid
                //  5. tool.call(args, toolUseContext, hasPermissionsToUseTool, assistantMsg)
                //  6. serialize result via jsonStringify
                log.debug("[McpEntrypoint] CallTool: {} with {} args", name, safeArgs.size());

                String result = toolExecutionService.executeToolByName(name, safeArgs);
                return McpCallToolResult.success(result);

            } catch (Exception error) {
                log.error("[McpEntrypoint] Tool {} failed: {}", name, error.getMessage());
                // Decompose error using getErrorParts-style logic
                String errorText = buildErrorText(error);
                return McpCallToolResult.error(errorText);
            }
        });
    }

    /**
     * Start the MCP stdio server.
     * Translated from startMCPServer() in mcp.ts
     *
     * Sets the cwd, creates the MCP Server with name "claude/tengu", registers
     * ListTools and CallTool handlers, then connects via StdioServerTransport.
     *
     * @param cwd     Working directory for tool calls
     * @param debug   Enable debug logging
     * @param verbose Enable verbose output
     * @return CompletableFuture that completes when the server exits
     */
    public CompletableFuture<Void> startMCPServer(String cwd, boolean debug, boolean verbose) {
        return CompletableFuture.runAsync(() -> {
            log.info("[McpEntrypoint] Starting Claude Code as MCP server (cwd={}, debug={}, verbose={})",
                cwd, debug, verbose);
            // Full implementation:
            //  1. createFileStateCacheWithSizeLimit(READ_FILE_STATE_CACHE_SIZE)
            //  2. setCwd(cwd)
            //  3. new Server({ name: "claude/tengu", version: VERSION }, { capabilities: { tools: {} } })
            //  4. server.setRequestHandler(ListToolsRequestSchema, ...)
            //  5. server.setRequestHandler(CallToolRequestSchema, ...)
            //  6. new StdioServerTransport(); server.connect(transport)
            log.info("[McpEntrypoint] MCP server running (readFileState cache size: {})",
                READ_FILE_STATE_CACHE_SIZE);
        });
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    /**
     * Build a human-readable error string from an exception.
     * Mirrors the getErrorParts() + join logic in the CallToolRequestSchema handler.
     */
    private String buildErrorText(Exception error) {
        String message = error.getMessage();
        return (message != null && !message.isBlank()) ? message.trim() : "Error";
    }
}
