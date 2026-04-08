package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import com.anthropic.claudecode.service.OAuthService;
import lombok.extern.slf4j.Slf4j;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * MCP authentication tool.
 * Translated from src/tools/McpAuthTool/McpAuthTool.ts
 *
 * Pseudo-tool for MCP servers that need authentication.
 */
@Slf4j
public class McpAuthTool extends AbstractTool<McpAuthTool.Input, McpAuthTool.Output> {



    private final String serverName;
    private final String serverUrl;
    private final OAuthService oauthService;

    public McpAuthTool(String serverName, String serverUrl, OAuthService oauthService) {
        this.serverName = serverName;
        this.serverUrl = serverUrl;
        this.oauthService = oauthService;
    }

    @Override
    public String getName() {
        return "mcp__" + serverName + "__authenticate";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of("type", "object", "properties", Map.of(), "additionalProperties", false);
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
            Input args,
            ToolUseContext context,
            Tool.CanUseToolFn canUseTool,
            Message.AssistantMessage parentMessage,
            Consumer<Tool.ToolProgress> onProgress) {

        try {
            String codeChallenge = java.util.UUID.randomUUID().toString().replace("-", "");
            String state = java.util.UUID.randomUUID().toString();
            OAuthService.AuthUrlResult authUrl = new OAuthService.AuthUrlResult(
                oauthService.buildAuthUrl(new OAuthService.BuildAuthUrlOptions(
                    codeChallenge, state, 0, true, true, null, null, null, null)),
                codeChallenge, state, "urn:ietf:wg:oauth:2.0:oob");
            return futureResult(Output.builder()
                .status("auth_url")
                .message("Please authenticate with " + serverName)
                .authUrl(authUrl.url())
                .build());
        } catch (Exception e) {
            return futureResult(Output.builder()
                .status("error")
                .message("Authentication failed: " + e.getMessage())
                .build());
        }
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture(
            "Authenticate with MCP server: " + serverName
        );
    }

    @Override
    public boolean isReadOnly(Input input) { return false; }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        String text = content.getMessage();
        if (content.getAuthUrl() != null) {
            text += "\n\nAuthorization URL: " + content.getAuthUrl();
        }
        return Map.of("type", "tool_result", "tool_use_id", toolUseId, "content", text);
    }

    @lombok.Data @lombok.Builder
    public static class Input {}

    public static class Output {
        private String status; // "auth_url" | "unsupported" | "error"
        private String message;
        private String authUrl;
        public Output() {}
        public String getStatus() { return status; }
        public void setStatus(String v) { status = v; }
        public String getMessage() { return message; }
        public void setMessage(String v) { message = v; }
        public String getAuthUrl() { return authUrl; }
        public void setAuthUrl(String v) { authUrl = v; }
        public static OutputBuilder builder() { return new OutputBuilder(); }
        public static class OutputBuilder {
            private String status; private String message; private String authUrl;
            public OutputBuilder status(String v) { this.status = v; return this; }
            public OutputBuilder message(String v) { this.message = v; return this; }
            public OutputBuilder authUrl(String v) { this.authUrl = v; return this; }
            public Output build() { Output o = new Output(); o.status = status; o.message = message; o.authUrl = authUrl; return o; }
        }
    }
}
