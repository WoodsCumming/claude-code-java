package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import lombok.Data;

/**
 * Web fetch tool for fetching and processing URL content.
 * Translated from src/tools/WebFetchTool/WebFetchTool.ts
 *
 * Fetches content from a URL and processes it with a prompt using Claude.
 */
@Slf4j
@Component
public class WebFetchTool extends AbstractTool<WebFetchTool.Input, WebFetchTool.Output> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WebFetchTool.class);


    public static final String TOOL_NAME = "WebFetch";
    private static final int MAX_CONTENT_BYTES = 5 * 1024 * 1024; // 5MB
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getSearchHint() {
        return "fetch and extract content from a URL";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "url", Map.of(
                    "type", "string",
                    "format", "uri",
                    "description", "The URL to fetch content from"
                ),
                "prompt", Map.of(
                    "type", "string",
                    "description", "The prompt to run on the fetched content"
                )
            ),
            "required", List.of("url", "prompt")
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
            long startTime = System.currentTimeMillis();
            try {
                // Upgrade HTTP to HTTPS
                String url = upgradeToHttps(args.getUrl());

                // Fetch the URL
                FetchResult fetchResult = fetchUrl(url);

                long durationMs = System.currentTimeMillis() - startTime;

                Output output = Output.builder()
                    .bytes(fetchResult.getBytes())
                    .code(fetchResult.getStatusCode())
                    .codeText(fetchResult.getStatusText())
                    .result(fetchResult.getContent())
                    .durationMs(durationMs)
                    .url(url)
                    .build();

                return result(output);

            } catch (Exception e) {
                log.error("Web fetch failed: {}", e.getMessage());
                throw new RuntimeException("Web fetch failed: " + e.getMessage(), e);
            }
        });
    }

    private String upgradeToHttps(String url) {
        if (url.startsWith("http://")) {
            return "https://" + url.substring(7);
        }
        return url;
    }

    private FetchResult fetchUrl(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(java.time.Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS))
            .build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", "claude-code/2.1.88 Java/21")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .timeout(java.time.Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS))
            .GET()
            .build();

        HttpResponse<byte[]> response = client.send(request,
            HttpResponse.BodyHandlers.ofByteArray());

        byte[] body = response.body();
        int bytes = body != null ? Math.min(body.length, MAX_CONTENT_BYTES) : 0;
        String content = body != null
            ? new String(body, 0, bytes, java.nio.charset.StandardCharsets.UTF_8)
            : "";

        // Convert HTML to plain text (basic implementation)
        content = htmlToText(content);

        return new FetchResult(bytes, response.statusCode(), getStatusText(response.statusCode()), content);
    }

    private String htmlToText(String html) {
        if (html == null) return "";
        // Basic HTML to text conversion
        String text = html
            .replaceAll("<script[^>]*>.*?</script>", " ") // Remove scripts
            .replaceAll("<style[^>]*>.*?</style>", " ")   // Remove styles
            .replaceAll("<[^>]+>", " ")                     // Remove tags
            .replaceAll("&amp;", "&")
            .replaceAll("&lt;", "<")
            .replaceAll("&gt;", ">")
            .replaceAll("&quot;", "\"")
            .replaceAll("&nbsp;", " ")
            .replaceAll("\\s+", " ")
            .trim();

        // Limit length
        if (text.length() > 100_000) {
            text = text.substring(0, 100_000) + "... [truncated]";
        }

        return text;
    }

    private String getStatusText(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 500 -> "Internal Server Error";
            default -> "Unknown";
        };
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        try {
            String hostname = new URL(input.getUrl()).getHost();
            return CompletableFuture.completedFuture("Fetching content from " + hostname);
        } catch (Exception e) {
            return CompletableFuture.completedFuture("Fetching content from URL");
        }
    }

    @Override
    public boolean isReadOnly(Input input) { return true; }

    @Override
    public boolean isConcurrencySafe(Input input) { return true; }

    @Override
    public String userFacingName(Input input) {
        if (input == null || input.getUrl() == null) return TOOL_NAME;
        try {
            return new URL(input.getUrl()).getHost();
        } catch (Exception e) {
            return input.getUrl();
        }
    }

    @Override
    public String getActivityDescription(Input input) {
        return "Fetching " + (input != null ? input.getUrl() : "URL");
    }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        return Map.of(
            "type", "tool_result",
            "tool_use_id", toolUseId,
            "content", content.getResult() != null ? content.getResult() : ""
        );
    }

    @Override
    public int getMaxResultSizeChars() { return 100_000; }

    // =========================================================================
    // Inner types
    // =========================================================================

    @Data
    private static class FetchResult {
        private int bytes;
        private int statusCode;
        private String statusText;
        private String content;
    
        public int getBytes() { return bytes; }
    
        public String getContent() { return content; }
    
        public int getStatusCode() { return statusCode; }
    
        public String getStatusText() { return statusText; }
    

        public FetchResult() {}
        public FetchResult(int bytes, int statusCode, String statusText, String content) {
            this.bytes = bytes;
            this.statusCode = statusCode;
            this.statusText = statusText;
            this.content = content;
        }
    }

    @Data
    @lombok.Builder
    
    public static class Input {
        private String url;
        private String prompt;
    
        public String getUrl() { return url; }
    }

    @Data
    @lombok.Builder
    
    public static class Output {
        private int bytes;
        private int code;
        private String codeText;
        private String result;
        private long durationMs;
        private String url;
    
        public String getResult() { return result; }
    }
}
