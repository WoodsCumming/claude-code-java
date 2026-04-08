package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * MCP component service — facade for the MCP UI component sub-system.
 * Translated from src/components/mcp/index.ts
 *
 * The TypeScript source is a barrel re-export file that surfaces the public
 * API of the MCP component package. In the Java layer these responsibilities
 * are distributed across the individual MCP services; this class acts as a
 * single-entry-point documentation anchor and registration point for the
 * exported component types.
 *
 * <p>Exported component identifiers (matching TypeScript exports):
 * <ul>
 *   <li>MCPAgentServerMenu</li>
 *   <li>MCPListPanel</li>
 *   <li>MCPReconnect</li>
 *   <li>MCPRemoteServerMenu</li>
 *   <li>MCPSettings</li>
 *   <li>MCPStdioServerMenu</li>
 *   <li>MCPToolDetailView</li>
 *   <li>MCPToolListView</li>
 * </ul>
 *
 * <p>Exported types (see {@link McpComponentTypes}):
 * <ul>
 *   <li>AgentMcpServerInfo</li>
 *   <li>MCPViewState</li>
 *   <li>ServerInfo</li>
 * </ul>
 */
@Slf4j
@Service
public class McpComponentService {



    /**
     * MCP UI view states.
     * Corresponds to MCPViewState in types.ts
     */
    public enum MCPViewState {
        LIST,
        TOOL_DETAIL,
        SETTINGS,
        AGENT_SERVER_MENU,
        REMOTE_SERVER_MENU,
        STDIO_SERVER_MENU
    }

    /**
     * Server connection info for display in the MCP UI.
     * Corresponds to ServerInfo in types.ts
     */
    public record ServerInfo(
        String name,
        String status,
        String transport,
        String url
    ) {}

    /**
     * Agent-specific MCP server info, extending ServerInfo.
     * Corresponds to AgentMcpServerInfo in types.ts
     */
    public record AgentMcpServerInfo(
        String name,
        String status,
        String transport,
        String url,
        String agentId
    ) {}
}
