package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.AuthService;
import com.anthropic.claudecode.service.ModelService;
import com.anthropic.claudecode.service.McpService;
import com.anthropic.claudecode.util.ApiProviderUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;

/**
 * Status command for showing Claude Code status.
 * Translated from src/commands/status/
 */
@Slf4j
@Component
@Command(
    name = "status",
    description = "Show Claude Code status including version, model, account, API connectivity, and tool statuses"
)
public class StatusCommand implements Callable<Integer> {



    @Value("${app.version:unknown}")
    private String version;

    private final AuthService authService;
    private final ModelService modelService;
    private final McpService mcpService;

    @Autowired
    public StatusCommand(AuthService authService,
                          ModelService modelService,
                          McpService mcpService) {
        this.authService = authService;
        this.modelService = modelService;
        this.mcpService = mcpService;
    }

    @Override
    public Integer call() {
        System.out.println("Claude Code Status");
        System.out.println("==================");
        System.out.println("Version: " + version);
        System.out.println("Model: " + modelService.renderModelName(modelService.getMainLoopModel()));
        System.out.println("API Provider: " + ApiProviderUtils.getAPIProvider().getValue());

        // Auth status
        if (authService.isClaudeAISubscriber()) {
            System.out.println("Auth: Claude.ai subscriber (" + authService.getSubscriptionType() + ")");
        } else if (authService.getApiKey() != null) {
            System.out.println("Auth: API key");
        } else {
            System.out.println("Auth: Not authenticated");
        }

        // MCP status
        int mcpServerCount = mcpService.getConnectedServerCount();
        if (mcpServerCount > 0) {
            System.out.println("MCP Servers: " + mcpServerCount + " connected");
        } else {
            System.out.println("MCP Servers: None configured");
        }

        return 0;
    }
}
