package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Transport abstraction for replBridge. Covers exactly the surface that
 * replBridge uses against HybridTransport so the v1/v2 choice is confined
 * to the construction site.
 *
 * <ul>
 *   <li>v1: HybridTransport (WS reads + POST writes to Session-Ingress)</li>
 *   <li>v2: SSETransport (reads) + CCRClient (writes to CCR v2 /worker/*)</li>
 * </ul>
 *
 * The v2 write path goes through CCRClient.writeEvent → SerialBatchEventUploader,
 * NOT through SSETransport.write() — SSETransport.write() targets the
 * Session-Ingress POST URL shape, which is wrong for CCR v2.
 *
 * Translated from src/bridge/replBridgeTransport.ts
 */
@Slf4j
@Service
public class BridgeReplTransportService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BridgeReplTransportService.class);


    /**
     * Transport interface for replBridge.
     * Translated from the {@code ReplBridgeTransport} type in replBridgeTransport.ts
     */
    public interface ReplBridgeTransport {

        /** Send a single stdout message. */
        CompletableFuture<Void> write(Object message);

        /** Send a batch of stdout messages. */
        CompletableFuture<Void> writeBatch(List<Object> messages);

        /** Close the transport. */
        void close();

        /** True if the write path is ready (CCR initialized for v2; WS open for v1). */
        boolean isConnectedStatus();

        /** Human-readable state label for debug logging. */
        String getStateLabel();

        /** Register a handler for inbound data frames. */
        void setOnData(Consumer<String> callback);

        /**
         * Register a handler for transport close events.
         * The integer parameter is the close code (or null if not applicable).
         */
        void setOnClose(IntConsumer callback);

        /** Register a handler fired once the transport is ready for writes. */
        void setOnConnect(Runnable callback);

        /** Open the transport. */
        void connect();

        /**
         * High-water mark of the underlying read stream's SSE sequence numbers.
         * v1 always returns 0 — Session-Ingress WS doesn't use SSE sequence numbers.
         */
        int getLastSequenceNum();

        /**
         * Monotonic count of batches dropped via maxConsecutiveFailures.
         * v2 always returns 0 — the v2 write path doesn't set maxConsecutiveFailures.
         */
        int getDroppedBatchCount();

        /**
         * PUT /worker state (v2 only; v1 is a no-op).
         * {@code requires_action} tells the backend a permission prompt is pending.
         */
        void reportState(String state);

        /** PUT /worker external_metadata (v2 only; v1 is a no-op). */
        void reportMetadata(Map<String, Object> metadata);

        /**
         * POST /worker/events/{id}/delivery (v2 only; v1 is a no-op).
         *
         * @param eventId the CCR event ID
         * @param status  one of {@code "processing"} or {@code "processed"}
         */
        void reportDelivery(String eventId, DeliveryStatus status);

        /**
         * Drain the write queue before close() (v2 only; v1 resolves immediately).
         */
        CompletableFuture<Void> flush();
    }

    /**
     * Delivery status values for {@link ReplBridgeTransport#reportDelivery}.
     * Translated from the {@code 'processing' | 'processed'} union in replBridgeTransport.ts
     */
    public enum DeliveryStatus {
        PROCESSING("processing"),
        PROCESSED("processed");

        private final String value;

        DeliveryStatus(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    // =========================================================================
    // v1 adapter
    // =========================================================================

    /**
     * v1 adapter options.
     * Mirrors the {@code HybridTransport} surface used by replBridge.
     */
    public interface HybridTransportAdapter {
        CompletableFuture<Void> write(Object message);
        CompletableFuture<Void> writeBatch(List<Object> messages);
        void close();
        boolean isConnectedStatus();
        String getStateLabel();
        void setOnData(Consumer<String> callback);
        void setOnClose(IntConsumer callback);
        void setOnConnect(Runnable callback);
        CompletableFuture<Void> connect();
        int getDroppedBatchCount();
    }

    /**
     * Create a v1 ReplBridgeTransport wrapping a HybridTransport.
     *
     * <p>v1 adapter: HybridTransport already has the full surface. This is a
     * no-op wrapper that exists only so replBridge's {@code transport} variable
     * has a single type.
     *
     * Translated from {@code createV1ReplTransport()} in replBridgeTransport.ts
     */
    public static ReplBridgeTransport createV1ReplTransport(HybridTransportAdapter hybrid) {
        return new ReplBridgeTransport() {

            @Override
            public CompletableFuture<Void> write(Object message) {
                return hybrid.write(message);
            }

            @Override
            public CompletableFuture<Void> writeBatch(List<Object> messages) {
                return hybrid.writeBatch(messages);
            }

            @Override
            public void close() {
                hybrid.close();
            }

            @Override
            public boolean isConnectedStatus() {
                return hybrid.isConnectedStatus();
            }

            @Override
            public String getStateLabel() {
                return hybrid.getStateLabel();
            }

            @Override
            public void setOnData(Consumer<String> callback) {
                hybrid.setOnData(callback);
            }

            @Override
            public void setOnClose(IntConsumer callback) {
                hybrid.setOnClose(callback);
            }

            @Override
            public void setOnConnect(Runnable callback) {
                hybrid.setOnConnect(callback);
            }

            @Override
            public void connect() {
                hybrid.connect();
            }

            /**
             * v1 Session-Ingress WS doesn't use SSE sequence numbers.
             * Always return 0 so seq-num carryover logic in replBridge is a no-op for v1.
             */
            @Override
            public int getLastSequenceNum() {
                return 0;
            }

            @Override
            public int getDroppedBatchCount() {
                return hybrid.getDroppedBatchCount();
            }

            @Override
            public void reportState(String state) {
                // v1 no-op
            }

            @Override
            public void reportMetadata(Map<String, Object> metadata) {
                // v1 no-op
            }

            @Override
            public void reportDelivery(String eventId, DeliveryStatus status) {
                // v1 no-op
            }

            @Override
            public CompletableFuture<Void> flush() {
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    // =========================================================================
    // v2 adapter
    // =========================================================================

    /**
     * Options for creating a v2 ReplBridgeTransport.
     * Translated from the opts parameter of {@code createV2ReplTransport()} in replBridgeTransport.ts
     */
    public record V2TransportOptions(
            String sessionUrl,
            String ingressToken,
            String sessionId,
            /**
             * SSE sequence-number high-water mark from the previous transport.
             * Null means start from 0.
             */
            Integer initialSequenceNum,
            /**
             * Worker epoch from POST /bridge response. When provided, registration
             * is skipped. When null, registerWorker() is called.
             */
            Long epoch,
            /** CCRClient heartbeat interval in ms. Defaults to 20 000 ms. */
            Integer heartbeatIntervalMs,
            /** ±fraction per-beat jitter. Defaults to 0. */
            Double heartbeatJitterFraction,
            /**
             * When true, skip opening the SSE read stream — only the CCRClient
             * write path and heartbeat are activated.
             */
            boolean outboundOnly,
            /**
             * Per-instance auth header source. When non-null, CCRClient +
             * SSETransport read auth from this supplier instead of the
             * process-wide CLAUDE_CODE_SESSION_ACCESS_TOKEN env var.
             */
            java.util.function.Supplier<String> getAuthToken
    ) {}

    /**
     * Adapter interfaces for CCRClient and SSETransport, decoupled from their
     * concrete implementations so this service can be unit-tested.
     */
    public interface SseTransportAdapter {
        void setOnData(Consumer<String> callback);
        void setOnClose(IntConsumer callback);
        void setOnEvent(Consumer<SseEvent> callback);
        CompletableFuture<Void> connect();
        void close();
        boolean isConnectedStatus();
        boolean isClosedStatus();
        int getLastSequenceNum();
    }

    public record SseEvent(String eventId, String data) {}

    public interface CcrClientAdapter {
        CompletableFuture<Void> writeEvent(Object message);
        CompletableFuture<Void> initialize(long epoch);
        void close();
        void reportDelivery(String eventId, String status);
        void reportState(String state);
        void reportMetadata(Map<String, Object> metadata);
        CompletableFuture<Void> flush();
    }

    /**
     * Create a v2 ReplBridgeTransport wrapping SSETransport (reads) and
     * CCRClient (writes, heartbeat, state, delivery tracking).
     *
     * <p>Registration happens here (not in the caller) so the entire v2
     * handshake is one async step. registerWorker failure propagates —
     * replBridge will catch it and stay on the poll loop.
     *
     * Translated from {@code createV2ReplTransport()} in replBridgeTransport.ts
     *
     * @param sse    SSE read-stream adapter
     * @param ccr    CCR write/state adapter
     * @param opts   transport configuration
     * @param epoch  resolved worker epoch (from /bridge or registerWorker)
     */
    public static ReplBridgeTransport createV2ReplTransport(
            SseTransportAdapter sse,
            CcrClientAdapter ccr,
            V2TransportOptions opts,
            long epoch) {

        AtomicBoolean ccrInitialized = new AtomicBoolean(false);
        AtomicBoolean closed = new AtomicBoolean(false);
        // Holder for the onClose callback so it can be referenced inside lambdas
        @SuppressWarnings("unchecked")
        IntConsumer[] onCloseCbHolder = new IntConsumer[1];
        @SuppressWarnings("unchecked")
        Runnable[] onConnectCbHolder = new Runnable[1];

        // ACK 'processed' immediately alongside 'received'.
        // See comment in replBridgeTransport.ts regarding daemon restart phantom prompts.
        sse.setOnEvent(event -> {
            ccr.reportDelivery(event.eventId(), "received");
            ccr.reportDelivery(event.eventId(), "processed");
        });

        return new ReplBridgeTransport() {

            @Override
            public CompletableFuture<Void> write(Object message) {
                return ccr.writeEvent(message);
            }

            @Override
            public CompletableFuture<Void> writeBatch(List<Object> messages) {
                // SerialBatchEventUploader already batches internally; sequential
                // enqueue preserves order. Check closed between writes to avoid
                // sending partial batches after transport teardown.
                CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
                for (Object m : messages) {
                    chain = chain.thenCompose(ignored -> {
                        if (closed.get()) return CompletableFuture.completedFuture(null);
                        return ccr.writeEvent(m);
                    });
                }
                return chain;
            }

            @Override
            public void close() {
                closed.set(true);
                ccr.close();
                sse.close();
            }

            @Override
            public boolean isConnectedStatus() {
                // Write-readiness, not read-readiness.
                return ccrInitialized.get();
            }

            @Override
            public String getStateLabel() {
                if (sse.isClosedStatus()) return "closed";
                if (sse.isConnectedStatus()) return ccrInitialized.get() ? "connected" : "init";
                return "connecting";
            }

            @Override
            public void setOnData(Consumer<String> callback) {
                sse.setOnData(callback);
            }

            @Override
            public void setOnClose(IntConsumer callback) {
                onCloseCbHolder[0] = callback;
                // SSE reconnect-budget exhaustion fires onClose(0) — map to
                // 4092 so telemetry can distinguish it from HTTP-status closes.
                sse.setOnClose(code -> {
                    ccr.close();
                    callback.accept(code == 0 ? 4092 : code);
                });
            }

            @Override
            public void setOnConnect(Runnable callback) {
                onConnectCbHolder[0] = callback;
            }

            @Override
            public void connect() {
                if (!opts.outboundOnly()) {
                    // Fire-and-forget — SSETransport.connect() awaits the read
                    // loop and only resolves on stream close/error.
                    sse.connect();
                }

                ccr.initialize(epoch).thenAccept(ignored -> {
                    ccrInitialized.set(true);
                    log.debug("[bridge:repl] v2 transport ready for writes (epoch={}, sse={})",
                            epoch, sse.isConnectedStatus() ? "open" : "opening");
                    Runnable cb = onConnectCbHolder[0];
                    if (cb != null) cb.run();
                }).exceptionally(err -> {
                    log.error("[bridge:repl] CCR v2 initialize failed: {}", err.getMessage());
                    // Close transport resources and notify replBridge via onClose
                    // so the poll loop can retry on the next work dispatch.
                    ccr.close();
                    sse.close();
                    IntConsumer closeCb = onCloseCbHolder[0];
                    if (closeCb != null) closeCb.accept(4091); // 4091 = init failure
                    return null;
                });
            }

            @Override
            public int getLastSequenceNum() {
                return sse.getLastSequenceNum();
            }

            /** v2 write path (CCRClient) doesn't set maxConsecutiveFailures — no drops. */
            @Override
            public int getDroppedBatchCount() {
                return 0;
            }

            @Override
            public void reportState(String state) {
                ccr.reportState(state);
            }

            @Override
            public void reportMetadata(Map<String, Object> metadata) {
                ccr.reportMetadata(metadata);
            }

            @Override
            public void reportDelivery(String eventId, DeliveryStatus status) {
                ccr.reportDelivery(eventId, status.getValue());
            }

            @Override
            public CompletableFuture<Void> flush() {
                return ccr.flush();
            }
        };
    }
}
