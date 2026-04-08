package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * MCP server approval service.
 * Translated from src/services/mcpServerApproval.tsx
 *
 * Handles approval dialogs for pending MCP project servers.
 */
@Slf4j
@Service
public class McpServerApprovalService {



    private final McpConfigService mcpConfigService;

    @Autowired
    public McpServerApprovalService(McpConfigService mcpConfigService) {
        this.mcpConfigService = mcpConfigService;
    }

    /**
     * Handle MCP JSON server approvals.
     * Translated from handleMcpjsonServerApprovals() in mcpServerApproval.tsx
     */
    public CompletableFuture<Void> handleMcpjsonServerApprovals(String projectPath) {
        return CompletableFuture.runAsync(() -> {
            Map<String, com.anthropic.claudecode.model.McpServerConfig> servers =
                mcpConfigService.getAllMcpServers(projectPath);

            List<String> pendingServers = new ArrayList<>();
            for (String serverName : servers.keySet()) {
                // Check if server needs approval
                // In full implementation, would check project config
                log.debug("Checking approval for MCP server: {}", serverName);
            }

            if (pendingServers.isEmpty()) {
                log.debug("No pending MCP servers to approve");
                return;
            }

            // In interactive mode, would show approval dialog
            log.info("Pending MCP servers for approval: {}", pendingServers);
        });
    }

    /**
     * Approve an MCP server.
     */
    public void approveServer(String serverName, String projectPath) {
        log.info("Approved MCP server: {}", serverName);
    }

    /**
     * Deny an MCP server.
     */
    public void denyServer(String serverName, String projectPath) {
        log.info("Denied MCP server: {}", serverName);
    }
}
