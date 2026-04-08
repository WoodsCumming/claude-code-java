package com.anthropic.claudecode.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * MCP WebSocket transport service.
 * Translated from src/utils/mcpWebSocketTransport.ts
 *
 * Wraps a Java WebSocket connection with the MCP JSON-RPC message protocol.
 * Each transport instance handles exactly one WebSocket connection.
 */
@Slf4j
@Service
public class McpWebSocketTransportService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(McpWebSocketTransportService.class);


    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // WebSocket readyState constants
    private static final int WS_CONNECTING = 0;
    private static final int WS_OPEN = 1;

    // =========================================================================
    // Transport interface (mirrors MCP SDK Transport)
    // =========================================================================

    public interface McpTransport {
        CompletableFuture<Void> start();
        CompletableFuture<Void> close();
        CompletableFuture<Void> send(JsonNode message);
        void setOnClose(Runnable handler);
        void setOnError(Consumer<Exception> handler);
        void setOnMessage(Consumer<JsonNode> handler);
    }

    // =========================================================================
    // WebSocketTransport implementation
    // =========================================================================

    /**
     * MCP transport backed by a Java NIO WebSocket.
     * Translated from WebSocketTransport class in mcpWebSocketTransport.ts
     */
    public static class WebSocketMcpTransport implements McpTransport, WebSocket.Listener {

        private final String url;
        private final HttpClient httpClient;
        private volatile WebSocket ws = null;
        private volatile boolean started = false;
        private final CompletableFuture<Void> openedFuture = new CompletableFuture<>();
        private final StringBuilder messageBuffer = new StringBuilder();

        private Runnable onCloseHandler;
        private Consumer<Exception> onErrorHandler;
        private Consumer<JsonNode> onMessageHandler;

        public WebSocketMcpTransport(String url, HttpClient httpClient) {
            this.url = url;
            this.httpClient = httpClient;
        }

        // -----------------------------------------------------------------------
        // McpTransport
        // -----------------------------------------------------------------------

        @Override
        public CompletableFuture<Void> start() {
            if (started) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Start can only be called once per transport."));
            }
            started = true;

            return httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create(url), this)
                    .thenCompose(socket -> {
                        this.ws = socket;
                        return openedFuture;
                    })
                    .thenRun(() -> {
                        if (ws == null) {
                            log.error("mcp_websocket_start_not_opened");
                            throw new IllegalStateException("WebSocket is not open. Cannot start transport.");
                        }
                        log.debug("McpWebSocketTranspor, connected to {}", url);
                    });
        }

        @Override
        public CompletableFuture<Void> close() {
            if (ws == null) return CompletableFuture.completedFuture(null);
            return ws.sendClose(WebSocket.NORMAL_CLOSURE, "closing")
                    .thenRun(() -> handleCloseCleanup())
                    .exceptionally(ex -> {
                        handleCloseCleanup();
                        return null;
                    });
        }

        @Override
        public CompletableFuture<Void> send(JsonNode message) {
            if (ws == null) {
                log.error("mcp_websocket_send_not_opened");
                return CompletableFuture.failedFuture(
                        new IllegalStateException("WebSocket is not open. Cannot send message."));
            }
            String json;
            try {
                json = OBJECT_MAPPER.writeValueAsString(message);
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
            return ws.sendText(json, true)
                    .thenRun(() -> {})
                    .exceptionally(ex -> {
                        handleError(ex instanceof Exception ex2 ? ex2 : new RuntimeException(ex));
                        throw ex instanceof RuntimeException re ? re : new RuntimeException(ex);
                    });
        }

        @Override
        public void setOnClose(Runnable handler) {
            this.onCloseHandler = handler;
        }

        @Override
        public void setOnError(Consumer<Exception> handler) {
            this.onErrorHandler = handler;
        }

        @Override
        public void setOnMessage(Consumer<JsonNode> handler) {
            this.onMessageHandler = handler;
        }

        // -----------------------------------------------------------------------
        // WebSocket.Listener
        // -----------------------------------------------------------------------

        @Override
        public void onOpen(WebSocket webSocket) {
            log.debug("McpWebSocketTransport: connection opened");
            openedFuture.complete(null);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messageBuffer.append(data);
            if (last) {
                String rawMessage = messageBuffer.toString();
                messageBuffer.setLength(0);
                try {
                    JsonNode node = OBJECT_MAPPER.readTree(rawMessage);
                    if (onMessageHandler != null) {
                        onMessageHandler.accept(node);
                    }
                } catch (Exception e) {
                    handleError(e);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            // Convert binary frame to UTF-8 string
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            try {
                JsonNode node = OBJECT_MAPPER.readTree(text);
                if (onMessageHandler != null) {
                    onMessageHandler.accept(node);
                }
            } catch (Exception e) {
                handleError(e);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.debug("McpWebSocketTransport: connection closed (status={})", statusCode);
            handleCloseCleanup();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.error("mcp_websocket_connect_fail");
            Exception ex = error instanceof Exception e ? e : new RuntimeException(error);
            if (!openedFuture.isDone()) {
                openedFuture.completeExceptionally(ex);
            }
            handleError(ex);
        }

        // -----------------------------------------------------------------------
        // Helpers
        // -----------------------------------------------------------------------

        private void handleError(Exception error) {
            log.error("mcp_websocket_message_fail: {}", error.getMessage());
            if (onErrorHandler != null) {
                onErrorHandler.accept(error);
            }
        }

        private void handleCloseCleanup() {
            if (onCloseHandler != null) {
                onCloseHandler.run();
            }
        }
    }

    // =========================================================================
    // Factory method
    // =========================================================================

    /**
     * Create a new MCP WebSocket transport connected to the given URL.
     * Translated from new WebSocketTransport(ws) in mcpWebSocketTransport.ts
     *
     * @param url       WebSocket endpoint URL (ws:// or wss://)
     * @return an unstarted McpTransport; call start() to open the connection
     */
    public McpTransport createTransport(String url) {
        HttpClient client = HttpClient.newBuilder()
                .build();
        return new WebSocketMcpTransport(url, client);
    }

    /**
     * Create a new MCP WebSocket transport using a shared HttpClient.
     */
    public McpTransport createTransport(String url, HttpClient httpClient) {
        return new WebSocketMcpTransport(url, httpClient);
    }
}
