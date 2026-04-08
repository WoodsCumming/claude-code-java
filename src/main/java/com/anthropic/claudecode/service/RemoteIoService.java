package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.SessionIngressAuth;
import com.anthropic.claudecode.util.TransportUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

/**
 * Remote I/O Service.
 * Translated from src/cli/remoteIO.ts
 *
 * Bidirectional streaming for SDK mode with session tracking.
 * Extends {@link StructuredIoService.StructuredIo} by routing all I/O through
 * a remote transport (WebSocket or SSE+POST) instead of plain stdio.
 *
 * <p>On construction:
 * <ol>
 *   <li>Selects the appropriate transport via {@link TransportUtils#getTransportForUrl}</li>
 *   <li>Wires data callbacks so transport frames feed the parent StructuredIO's input pipe</li>
 *   <li>Optionally initialises a CCR v2 client for heartbeats and delivery ACKs</li>
 *   <li>Starts the transport connection</li>
 *   <li>Starts a keep-alive timer when running in bridge mode</li>
 * </ol>
 */
@Slf4j
@Service
public class RemoteIoService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RemoteIoService.class);


    private static final ObjectMapper objectMapper = new ObjectMapper();

    // ---------------------------------------------------------------------------
    // RemoteIO instance
    // ---------------------------------------------------------------------------

    /**
     * A live remote-IO session that feeds incoming transport frames into the
     * structured-IO layer and writes outbound messages through the transport.
     */
    public static class RemoteIo extends StructuredIoService.StructuredIo {

        // Thread-local used to pass the PipedOutputStream reference through super()
        private static final ThreadLocal<PipedOutputStream> LAST_OUTPUT = new ThreadLocal<>();

        private static PipedOutputStream makeOutput() {
            PipedOutputStream po = new PipedOutputStream();
            LAST_OUTPUT.set(po);
            return po;
        }

        private final URL url;
        // Transport abstraction (either WebSocket or SSE)
        private final TransportHandle transport;
        private final boolean isBridge;
        private final boolean isDebug;
        private volatile ScheduledFuture<?> keepAliveTimer = null;
        private final ScheduledExecutorService scheduler;

        // Piped streams connecting the transport's data callback to the StructuredIO reader
        private final PipedOutputStream pipeOut;
        private final PipedInputStream pipeIn;

        RemoteIo(
                String streamUrl,
                boolean replayUserMessages,
                StructuredIoService structuredIoService,
                SseTransportService sseTransportService,
                WebSocketTransportService wsTransportService,
                ScheduledExecutorService scheduler,
                Iterable<String> initialPrompt) throws IOException {

            // Java requires super() as the first statement.
            // We use the PipedOutputStream-based constructor which creates the connected
            // PipedInputStream internally. We create a matching pair here for our own reference.
            super(makeOutput(), replayUserMessages);
            PipedOutputStream po = LAST_OUTPUT.get();
            LAST_OUTPUT.remove();
            PipedInputStream pi = new PipedInputStream();
            this.pipeOut = po;
            this.pipeIn = pi;
            this.scheduler = scheduler;
            this.url = toUrl(streamUrl);
            this.isBridge = "bridge".equals(System.getenv("CLAUDE_CODE_ENVIRONMENT_KIND"));
            this.isDebug = isDebugMode();

            // Build initial auth headers
            Map<String, String> headers = new LinkedHashMap<>();
            Optional<String> sessionToken = Optional.ofNullable(SessionIngressAuth.getSessionIngressAuthToken());
            if (sessionToken.isPresent()) {
                headers.put("Authorization", "Bearer " + sessionToken.get());
            } else {
                log.error("[remote-io] No session ingress token available");
            }
            String erVersion = System.getenv("CLAUDE_CODE_ENVIRONMENT_RUNNER_VERSION");
            if (erVersion != null && !erVersion.isBlank()) {
                headers.put("x-environment-runner-version", erVersion);
            }

            // Refresh-headers supplier — re-reads the session token dynamically
            Supplier<Map<String, String>> refreshHeaders = () -> {
                Map<String, String> h = new LinkedHashMap<>();
                java.util.Optional.ofNullable(SessionIngressAuth.getSessionIngressAuthToken())
                        .filter(t -> !t.isBlank())
                        .ifPresent(t -> h.put("Authorization", "Bearer " + t));
                String freshErVersion = System.getenv("CLAUDE_CODE_ENVIRONMENT_RUNNER_VERSION");
                if (freshErVersion != null && !freshErVersion.isBlank()) {
                    h.put("x-environment-runner-version", freshErVersion);
                }
                return h;
            };

            String sessionId = getSessionId();

            // Resolve which transport to use
            TransportUtils.TransportDescriptor descriptor =
                    TransportUtils.getTransportForUrl(this.url, headers, sessionId, refreshHeaders);

            this.transport = switch (descriptor) {
                case TransportUtils.TransportDescriptor.SseDescriptor sse -> {
                    SseTransportService.SseTransport t = sseTransportService.createTransport(
                            sse.sseUrl(), sse.headers(), sse.sessionId(), sse.refreshHeaders());
                    yield new SseTransportHandle(t);
                }
                case TransportUtils.TransportDescriptor.WebSocketDescriptor ws -> {
                    WebSocketTransportService.WebSocketTransportOptions opts =
                            new WebSocketTransportService.WebSocketTransportOptions(true, isBridge);
                    WebSocketTransportService.WebSocketTransport t = wsTransportService.createTransport(
                            ws.wsUrl(), ws.headers(), ws.sessionId(), ws.refreshHeaders(), opts);
                    yield new WsTransportHandle(t);
                }
            };

            // Wire data callback: transport frames → pipe → StructuredIO reader
            transport.setOnData(data -> {
                try {
                    pipeOut.write(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    pipeOut.flush();
                } catch (IOException e) {
                    log.debug("[remote-io] pipe write failed: {}", e.getMessage());
                }
                if (isBridge && isDebug) {
                    String line = data.endsWith("\n") ? data : data + "\n";
                    System.out.print(line);
                    System.out.flush();
                }
            });

            // Wire close callback: close the pipe so the StructuredIO reader terminates
            transport.setOnClose(code -> {
                try {
                    pipeOut.close();
                } catch (IOException ignored) {}
            });

            // Start the transport
            transport.connect();

            // Keep-alive timer (bridge mode only)
            if (isBridge) {
                long keepAliveIntervalMs = getSessionKeepaliveIntervalMs();
                if (keepAliveIntervalMs > 0) {
                    this.keepAliveTimer = scheduler.scheduleAtFixedRate(() -> {
                        log.debug("[remote-io] keep_alive sent");
                        write(Map.of("type", "keep_alive")).exceptionally(err -> {
                            log.debug("[remote-io] keep_alive write failed: {}", err.getMessage());
                            return null;
                        });
                    }, keepAliveIntervalMs, keepAliveIntervalMs, TimeUnit.MILLISECONDS);
                }
            }

            // If an initial prompt is provided, write it to the pipe
            if (initialPrompt != null) {
                CompletableFuture.runAsync(() -> {
                    for (String chunk : initialPrompt) {
                        try {
                            String normalised = chunk.replaceAll("\n$", "") + "\n";
                            pipeOut.write(normalised.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            pipeOut.flush();
                        } catch (IOException e) {
                            log.debug("[remote-io] initial prompt write failed: {}", e.getMessage());
                        }
                    }
                });
            }
        }

        // ---------------------------------------------------------------------------
        // Write — routes through the transport (not plain stdout)
        // ---------------------------------------------------------------------------

        /**
         * Send a message through the transport.
         * In bridge mode, control_request messages are always echoed to stdout.
         * Translated from write() in remoteIO.ts
         */
        @Override
        public CompletableFuture<Void> write(Map<String, Object> message) {
            CompletableFuture<Void> writeFuture = transport.write(message);
            if (isBridge) {
                String type = (String) message.get("type");
                if ("control_request".equals(type) || isDebug) {
                    try {
                        String json = objectMapper.writeValueAsString(message);
                        System.out.println(json);
                        System.out.flush();
                    } catch (Exception e) {
                        log.error("[remote-io] Failed to echo message to stdout", e);
                    }
                }
            }
            return writeFuture;
        }

        // ---------------------------------------------------------------------------
        // Close
        // ---------------------------------------------------------------------------

        /**
         * Close the transport and the input pipe.
         * Translated from close() in remoteIO.ts
         */
        public void close() {
            if (keepAliveTimer != null) {
                keepAliveTimer.cancel(false);
                keepAliveTimer = null;
            }
            transport.close();
            try {
                pipeOut.close();
            } catch (IOException ignored) {}
        }

        // ---------------------------------------------------------------------------
        // Helpers
        // ---------------------------------------------------------------------------

        private static URL toUrl(String streamUrl) {
            try {
                return new URL(streamUrl);
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("Invalid stream URL: " + streamUrl, e);
            }
        }

        private static String getSessionId() {
            // Delegate to bootstrap state when available; use env var as fallback
            String id = System.getenv("CLAUDE_CODE_SESSION_ID");
            return id != null ? id : "";
        }

        private static boolean isDebugMode() {
            return TransportUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_DEBUG"));
        }

        private static long getSessionKeepaliveIntervalMs() {
            // Default 120s; override via env var
            String val = System.getenv("CLAUDE_CODE_SESSION_KEEPALIVE_INTERVAL_MS");
            if (val != null) {
                try { return Long.parseLong(val); } catch (NumberFormatException ignored) {}
            }
            return 120_000L;
        }
    }

    // ---------------------------------------------------------------------------
    // Transport handle abstraction
    // ---------------------------------------------------------------------------

    /**
     * Thin adapter so RemoteIO can talk to either an SSE transport or a WebSocket
     * transport through the same interface.
     */
    private interface TransportHandle {
        void setOnData(java.util.function.Consumer<String> callback);
        void setOnClose(java.util.function.Consumer<Integer> callback);
        CompletableFuture<Void> connect();
        CompletableFuture<Void> write(Map<String, Object> message);
        void close();
    }

    private static class SseTransportHandle implements TransportHandle {
        private final SseTransportService.SseTransport transport;
        SseTransportHandle(SseTransportService.SseTransport t) { this.transport = t; }

        @Override public void setOnData(java.util.function.Consumer<String> cb) { transport.setOnData(cb); }
        @Override public void setOnClose(java.util.function.Consumer<Integer> cb) { transport.setOnClose(cb); }
        @Override public CompletableFuture<Void> connect() { return transport.connect(); }
        @Override public CompletableFuture<Void> write(Map<String, Object> msg) { return transport.write(msg); }
        @Override public void close() { transport.close(); }
    }

    private static class WsTransportHandle implements TransportHandle {
        private final WebSocketTransportService.WebSocketTransport transport;
        WsTransportHandle(WebSocketTransportService.WebSocketTransport t) { this.transport = t; }

        @Override public void setOnData(java.util.function.Consumer<String> cb) { transport.setOnData(cb); }
        @Override public void setOnClose(java.util.function.Consumer<Integer> cb) { transport.setOnClose(cb); }
        @Override public CompletableFuture<Void> connect() { return transport.connect(); }
        @Override public CompletableFuture<Void> write(Map<String, Object> msg) { return transport.write(msg); }
        @Override public void close() { transport.close(); }
    }

    // ---------------------------------------------------------------------------
    // Dependencies
    // ---------------------------------------------------------------------------

    private final StructuredIoService structuredIoService;
    private final SseTransportService sseTransportService;
    private final WebSocketTransportService wsTransportService;
    private final ScheduledExecutorService scheduler;

    public RemoteIoService(
            StructuredIoService structuredIoService,
            SseTransportService sseTransportService,
            WebSocketTransportService wsTransportService,
            ScheduledExecutorService scheduler) {
        this.structuredIoService = structuredIoService;
        this.sseTransportService = sseTransportService;
        this.wsTransportService = wsTransportService;
        this.scheduler = scheduler;
    }

    // ---------------------------------------------------------------------------
    // Factory
    // ---------------------------------------------------------------------------

    /**
     * Create a new {@link RemoteIo} session connected to the given stream URL.
     *
     * @param streamUrl          WebSocket or HTTP URL for the remote session endpoint
     * @param replayUserMessages when true, control_response messages are propagated upstream
     * @param initialPrompt      optional NDJSON lines to inject as the first user turn
     * @return a connected {@link RemoteIo} ready for read/write
     * @throws IOException if the internal pipe cannot be created
     */
    public RemoteIo createSession(
            String streamUrl,
            boolean replayUserMessages,
            Iterable<String> initialPrompt) throws IOException {
        return new RemoteIo(streamUrl, replayUserMessages,
                structuredIoService, sseTransportService, wsTransportService,
                scheduler, initialPrompt);
    }

    /** Create a session without replay and without an initial prompt. */
    public RemoteIo createSession(String streamUrl) throws IOException {
        return createSession(streamUrl, false, null);
    }
}
