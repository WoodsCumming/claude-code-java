package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import com.anthropic.claudecode.service.OAuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Remote trigger tool for managing scheduled remote agents.
 * Translated from src/tools/RemoteTriggerTool/RemoteTriggerTool.ts
 *
 * Manages scheduled remote agent triggers via the Claude.ai API.
 */
@Slf4j
@Component
public class RemoteTriggerTool extends AbstractTool<RemoteTriggerTool.Input, RemoteTriggerTool.Output> {



    public static final String TOOL_NAME = "RemoteTrigger";
    private static final String TRIGGERS_BETA = "ccr-triggers-2026-01-30";
    private static final String BASE_URL = "https://api.anthropic.com/v1/code/triggers";

    private final OAuthService oauthService;
    private final ObjectMapper objectMapper;

    @Autowired
    public RemoteTriggerTool(OAuthService oauthService, ObjectMapper objectMapper) {
        this.oauthService = oauthService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public String getSearchHint() { return "manage scheduled remote agent triggers"; }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "action", Map.of(
                    "type", "string",
                    "enum", List.of("list", "get", "create", "update", "run"),
                    "description", "The action to perform"
                ),
                "trigger_id", Map.of(
                    "type", "string",
                    "description", "Required for get, update, and run"
                ),
                "body", Map.of(
                    "type", "object",
                    "description", "JSON body for create and update"
                )
            ),
            "required", List.of("action")
        );
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
            Input args,
            ToolUseContext context,
            Tool.CanUseToolFn canUseTool,
            Message.AssistantMessage parentMessage,
            Consumer<Tool.ToolProgress> onProgress) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = buildUrl(args.getAction(), args.getTriggerId());
                String method = getHttpMethod(args.getAction());

                OAuthService.OAuthTokens tokens = oauthService.getCurrentTokens();
                if (tokens == null) {
                    return result(Output.builder()
                        .status(401)
                        .json("{\"error\": \"Not authenticated\"}")
                        .build());
                }

                HttpClient client = HttpClient.newHttpClient();
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + tokens.getAccessToken())
                    .header("Content-Type", "application/json")
                    .header("anthropic-beta", TRIGGERS_BETA);

                if ("GET".equals(method)) {
                    requestBuilder.GET();
                } else if ("POST".equals(method)) {
                    String body = args.getBody() != null
                        ? objectMapper.writeValueAsString(args.getBody())
                        : "{}";
                    requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body));
                }

                HttpResponse<String> response = client.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

                return result(Output.builder()
                    .status(response.statusCode())
                    .json(response.body())
                    .build());

            } catch (Exception e) {
                log.error("Remote trigger failed: {}", e.getMessage());
                throw new RuntimeException("Remote trigger failed: " + e.getMessage(), e);
            }
        });
    }

    private String buildUrl(String action, String triggerId) {
        if (triggerId != null && !triggerId.isEmpty()) {
            return BASE_URL + "/" + triggerId + ("run".equals(action) ? "/run" : "");
        }
        return BASE_URL;
    }

    private String getHttpMethod(String action) {
        return switch (action) {
            case "list", "get" -> "GET";
            case "create", "update", "run" -> "POST";
            default -> "GET";
        };
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Remote trigger: " + input.getAction());
    }

    @Override
    public boolean isReadOnly(Input input) {
        return "list".equals(input.getAction()) || "get".equals(input.getAction());
    }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        return Map.of("type", "tool_result", "tool_use_id", toolUseId, "content", content.getJson());
    }

    @Override
    public int getMaxResultSizeChars() { return 100_000; }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Input {
        private String action;
        private String triggerId;
        private Map<String, Object> body;
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Output {
        private int status;
        private String json;
    }
}
