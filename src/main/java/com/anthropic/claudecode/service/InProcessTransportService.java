package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * In-process linked transport pair for running an MCP server and client in the
 * same process without spawning a subprocess.
 * Translated from src/services/mcp/InProcessTransport.ts
 *
 * <p>{@code send()} on one side delivers to {@code onMessage} on the other.
 * {@code close()} on either side calls {@code onClose} on both.</p>
 */
@Slf4j
@Service
public class InProcessTransportService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InProcessTransportService.class);


    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Creates a pair of linked transports for in-process MCP communication.
     * Messages sent on one transport are delivered to the other's {@code onMessage}.
     * Translated from createLinkedTransportPair() in InProcessTransport.ts
     *
     * @param <M> message type (typically {@code Map<String,Object>} for JSON-RPC)
     * @return [clientTransport, serverTransport] as a {@link TransportPair}
     */
    public static <M> TransportPair<M> createLinkedTransportPair() {
        InProcessTransport<M> a = new InProcessTransport<>();
        InProcessTransport<M> b = new InProcessTransport<>();
        a.setPeer(b);
        b.setPeer(a);
        return new TransportPair<>(a, b);
    }

    /**
     * Holds the two ends of a linked transport pair.
     * Translated from the [Transport, Transport] tuple in createLinkedTransportPair().
     */
    public record TransportPair<M>(InProcessTransport<M> client, InProcessTransport<M> server) {}

    // ── Transport ─────────────────────────────────────────────────────────────

    /**
     * One end of an in-process linked transport pair.
     * Translated from InProcessTransport (the private class) in InProcessTransport.ts
     *
     * <p>Mirrors the MCP SDK {@code Transport} interface contract:
     * <ul>
     *   <li>{@link #start()} – no-op; always completes immediately</li>
     *   <li>{@link #send(Object)} – delivers the message to the peer's {@code onMessage}
     *       asynchronously (using a virtual thread to avoid stack-depth issues)</li>
     *   <li>{@link #close()} – marks both sides closed and calls both {@code onClose}</li>
     * </ul>
     * </p>
     */
    public static class InProcessTransport<M> {

        private InProcessTransport<M> peer;
        private volatile boolean closed = false;

        /** Callback invoked when a message arrives from the peer. */
        public volatile Consumer<M> onMessage;
        /** Callback invoked when this transport is closed. */
        public volatile Runnable onClose;
        /** Callback invoked on error. */
        public volatile Consumer<Throwable> onError;

        /** @internal Set by the factory — links this transport to its peer. */
        void setPeer(InProcessTransport<M> peer) {
            this.peer = peer;
        }

        /**
         * No-op start (matches MCP SDK {@code Transport.start()}).
         * Translated from start() in InProcessTransport.ts
         */
        public CompletableFuture<Void> start() {
            return CompletableFuture.completedFuture(null);
        }

        /**
         * Send a message to the peer asynchronously.
         * Translated from send() in InProcessTransport.ts
         *
         * <p>Delivery is performed on a virtual thread to avoid stack-depth issues
         * with synchronous request/response cycles — mirrors the {@code queueMicrotask}
         * call in the TypeScript original.</p>
         */
        public CompletableFuture<Void> send(M message) {
            if (closed) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Transport is closed"));
            }

            // Deliver to the other side asynchronously
            return CompletableFuture.runAsync(() -> {
                InProcessTransport<M> p = peer;
                if (p != null) {
                    Consumer<M> handler = p.onMessage;
                    if (handler != null) {
                        handler.accept(message);
                    }
                }
            });
        }

        /**
         * Close the transport and notify both sides.
         * Translated from close() in InProcessTransport.ts
         *
         * <p>Closing either side also closes the peer if it is still open.</p>
         */
        public CompletableFuture<Void> close() {
            if (closed) return CompletableFuture.completedFuture(null);
            closed = true;

            return CompletableFuture.runAsync(() -> {
                Runnable closeHandler = onClose;
                if (closeHandler != null) closeHandler.run();

                // Close the peer if it hasn't already closed
                InProcessTransport<M> p = peer;
                if (p != null && !p.closed) {
                    p.closed = true;
                    Runnable peerClose = p.onClose;
                    if (peerClose != null) peerClose.run();
                }
            });
        }

        public boolean isClosed() { return closed; }
    }
}
