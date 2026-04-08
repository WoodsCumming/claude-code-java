package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * SDK MCP Transport Bridge.
 * Translated from src/services/mcp/SdkControlTransport.ts
 *
 * <p>Implements a transport bridge that allows MCP servers running in the SDK
 * process to communicate with the Claude Code CLI process through control
 * messages.</p>
 *
 * <h2>Architecture</h2>
 * <p>Unlike regular MCP servers that run as separate processes, SDK MCP servers
 * run in-process within the SDK. This requires a special transport mechanism to
 * bridge communication between the CLI process (MCP client) and the SDK process
 * (MCP server).</p>
 *
 * <h2>Message Flow</h2>
 * <h3>CLI → SDK (via {@link SdkControlClientTransport})</h3>
 * <ol>
 *   <li>CLI's MCP Client calls a tool → sends JSONRPC request to client transport</li>
 *   <li>Transport wraps message in control request with server_name and request_id</li>
 *   <li>Control request is sent via stdout to the SDK process</li>
 *   <li>SDK's StructuredIO receives the control response and routes it back</li>
 *   <li>Transport unwraps the response and returns it to the MCP Client</li>
 * </ol>
 *
 * <h3>SDK → CLI (via {@link SdkControlServerTransport})</h3>
 * <ol>
 *   <li>Query receives control request with MCP message and calls transport.onmessage</li>
 *   <li>MCP server processes the message and calls transport.send() with response</li>
 *   <li>Transport calls sendMcpMessage callback with the response</li>
 *   <li>Query's callback resolves the pending promise with the response</li>
 * </ol>
 */
@Slf4j
@Service
public class SdkControlTransportService {



    // ── Types ─────────────────────────────────────────────────────────────────

    /**
     * Callback that sends an MCP message to the named SDK server and returns the response.
     * Translated from SendMcpMessageCallback in SdkControlTransport.ts
     *
     * @param <M> message type (typically {@code Map<String,Object>} for JSON-RPC)
     */
    @FunctionalInterface
    public interface SendMcpMessageCallback<M> {
        CompletableFuture<M> send(String serverName, M message);
    }

    // ── Factory methods ───────────────────────────────────────────────────────

    /**
     * Create a CLI-side transport for an SDK MCP server.
     * Translated from new SdkControlClientTransport(serverName, sendMcpMessage) in SdkControlTransport.ts
     */
    public <M> SdkControlClientTransport<M> createClientTransport(
            String serverName,
            SendMcpMessageCallback<M> sendMcpMessage) {
        return new SdkControlClientTransport<>(serverName, sendMcpMessage);
    }

    /**
     * Create an SDK-side transport for an SDK MCP server.
     * Translated from new SdkControlServerTransport(sendMcpMessage) in SdkControlTransport.ts
     */
    public <M> SdkControlServerTransport<M> createServerTransport(Consumer<M> sendMcpMessage) {
        return new SdkControlServerTransport<>(sendMcpMessage);
    }

    // ── CLI-side transport ────────────────────────────────────────────────────

    /**
     * CLI-side transport for SDK MCP servers.
     * Translated from SdkControlClientTransport in SdkControlTransport.ts
     *
     * <p>Used in the CLI process to bridge communication between the CLI's MCP
     * Client and the SDK process. Converts MCP protocol messages into control
     * requests that are forwarded through stdout/stdin to the SDK process.</p>
     */
    public static class SdkControlClientTransport<M> {

        private volatile boolean isClosed = false;
        private final String serverName;
        private final SendMcpMessageCallback<M> sendMcpMessage;

        /** Callback invoked when a response message arrives from the SDK. */
        public volatile Consumer<M> onMessage;
        /** Callback invoked when the transport is closed. */
        public volatile Runnable onClose;
        /** Callback invoked on transport error. */
        public volatile Consumer<Throwable> onError;

        SdkControlClientTransport(String serverName, SendMcpMessageCallback<M> sendMcpMessage) {
            this.serverName = serverName;
            this.sendMcpMessage = sendMcpMessage;
        }

        /** No-op: CLI-side transport needs no initialisation. Translated from start(). */
        public CompletableFuture<Void> start() {
            return CompletableFuture.completedFuture(null);
        }

        /**
         * Send a message to the SDK MCP server and forward the response back
         * via {@link #onMessage}.
         * Translated from send() in SdkControlClientTransport.
         */
        public CompletableFuture<Void> send(M message) {
            if (isClosed) {
                return CompletableFuture.failedFuture(new IllegalStateException("Transport is closed"));
            }

            return sendMcpMessage.send(serverName, message)
                    .thenAccept(response -> {
                        Consumer<M> handler = onMessage;
                        if (handler != null) {
                            handler.accept(response);
                        }
                    });
        }

        /**
         * Close the transport.
         * Translated from close() in SdkControlClientTransport.
         */
        public CompletableFuture<Void> close() {
            if (isClosed) return CompletableFuture.completedFuture(null);
            isClosed = true;
            Runnable closeHandler = onClose;
            if (closeHandler != null) closeHandler.run();
            return CompletableFuture.completedFuture(null);
        }

        public boolean isClosed() { return isClosed; }
        public String getServerName() { return serverName; }
    }

    // ── SDK-side transport ────────────────────────────────────────────────────

    /**
     * SDK-side transport for SDK MCP servers.
     * Translated from SdkControlServerTransport in SdkControlTransport.ts
     *
     * <p>Used in the SDK process to bridge communication between control requests
     * coming from the CLI and the actual MCP server running in the SDK process.
     * Acts as a pass-through that forwards messages to the MCP server and sends
     * responses back via a callback.</p>
     *
     * <p>Note: Query handles all request/response correlation and async flow.</p>
     */
    public static class SdkControlServerTransport<M> {

        private volatile boolean isClosed = false;
        private final Consumer<M> sendMcpMessage;

        /** Callback invoked when a message arrives from the CLI for the MCP server. */
        public volatile Consumer<M> onMessage;
        /** Callback invoked when the transport is closed. */
        public volatile Runnable onClose;
        /** Callback invoked on transport error. */
        public volatile Consumer<Throwable> onError;

        SdkControlServerTransport(Consumer<M> sendMcpMessage) {
            this.sendMcpMessage = sendMcpMessage;
        }

        /** No-op initialisation. Translated from start(). */
        public CompletableFuture<Void> start() {
            return CompletableFuture.completedFuture(null);
        }

        /**
         * Pass the MCP server response back through the callback to the CLI.
         * Translated from send() in SdkControlServerTransport.
         */
        public CompletableFuture<Void> send(M message) {
            if (isClosed) {
                return CompletableFuture.failedFuture(new IllegalStateException("Transport is closed"));
            }
            sendMcpMessage.accept(message);
            return CompletableFuture.completedFuture(null);
        }

        /**
         * Close the transport.
         * Translated from close() in SdkControlServerTransport.
         */
        public CompletableFuture<Void> close() {
            if (isClosed) return CompletableFuture.completedFuture(null);
            isClosed = true;
            Runnable closeHandler = onClose;
            if (closeHandler != null) closeHandler.run();
            return CompletableFuture.completedFuture(null);
        }

    }
}
