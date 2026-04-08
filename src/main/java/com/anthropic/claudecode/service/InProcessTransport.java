package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * In-process linked transport pair for MCP communication.
 * Translated from src/services/mcp/InProcessTransport.ts
 *
 * Allows running an MCP server and client in the same process.
 */
@Slf4j
public class InProcessTransport {



    private InProcessTransport peer;
    private volatile boolean closed = false;
    private volatile Consumer<Map<String, Object>> onMessage;
    private volatile Runnable onClose;
    private volatile Consumer<Throwable> onError;

    private InProcessTransport() {}

    /**
     * Create a linked transport pair.
     * Translated from createLinkedTransportPair() in InProcessTransport.ts
     */
    public static InProcessTransport[] createLinkedTransportPair() {
        InProcessTransport a = new InProcessTransport();
        InProcessTransport b = new InProcessTransport();
        a.peer = b;
        b.peer = a;
        return new InProcessTransport[]{a, b};
    }

    /**
     * Send a message.
     * Translated from InProcessTransport.send() in InProcessTransport.ts
     */
    public CompletableFuture<Void> send(Map<String, Object> message) {
        if (closed) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RuntimeException("Transport is closed"));
            return failed;
        }

        // Deliver asynchronously
        return CompletableFuture.runAsync(() -> {
            if (peer != null && peer.onMessage != null) {
                peer.onMessage.accept(message);
            }
        });
    }

    /**
     * Close the transport.
     * Translated from InProcessTransport.close() in InProcessTransport.ts
     */
    public CompletableFuture<Void> close() {
        if (closed) return CompletableFuture.completedFuture(null);

        closed = true;
        if (onClose != null) onClose.run();

        if (peer != null && !peer.closed) {
            peer.closed = true;
            if (peer.onClose != null) peer.onClose.run();
        }

        return CompletableFuture.completedFuture(null);
    }

    public void setOnMessage(Consumer<Map<String, Object>> handler) { this.onMessage = handler; }
    public void setOnClose(Runnable handler) { this.onClose = handler; }
    public void setOnError(Consumer<Throwable> handler) { this.onError = handler; }
    public boolean isClosed() { return closed; }
}
