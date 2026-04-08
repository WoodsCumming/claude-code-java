package com.anthropic.claudecode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/**
 * Structured I/O Service.
 * Translated from src/cli/structuredIO.ts
 *
 * Provides a structured way to read and write SDK messages from stdio,
 * capturing the SDK protocol. Reads newline-delimited JSON messages from an
 * input source and routes them to the appropriate handler; writes messages as
 * NDJSON to stdout.
 *
 * <p>Key responsibilities:
 * <ul>
 *   <li>Parsing incoming NDJSON lines into typed message objects</li>
 *   <li>Routing {@code control_response} messages to pending {@link PendingRequest} futures</li>
 *   <li>Sending {@code control_request} messages and awaiting responses</li>
 *   <li>Providing {@code createCanUseTool} / {@code createHookCallback} factories</li>
 * </ul>
 */
@Slf4j
@Service
public class StructuredIoService {

    // Maximum number of resolved tool_use IDs to track to prevent duplicates.
    private static final int MAX_RESOLVED_TOOL_USE_IDS = 1000;

    public static final String SANDBOX_NETWORK_ACCESS_TOOL_NAME = "SandboxNetworkAccess";

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // ---------------------------------------------------------------------------
    // Message types (sealed hierarchy)
    // ---------------------------------------------------------------------------

    /**
     * Sealed hierarchy for all inbound NDJSON messages.
     * Translated from the union of StdinMessage | SDKMessage in controlTypes.ts
     */
    public sealed interface InboundMessage
            permits InboundMessage.UserMessage,
                    InboundMessage.AssistantMessage,
                    InboundMessage.SystemMessage,
                    InboundMessage.ControlRequestMessage,
                    InboundMessage.ControlResponseMessage {

        String type();

        record UserMessage(String sessionId, Map<String, Object> message, String parentToolUseId)
                implements InboundMessage {
            @Override public String type() { return "user"; }
        }

        record AssistantMessage(Map<String, Object> message)
                implements InboundMessage {
            @Override public String type() { return "assistant"; }
        }

        record SystemMessage(Map<String, Object> message)
                implements InboundMessage {
            @Override public String type() { return "system"; }
        }

        record ControlRequestMessage(String requestId, Map<String, Object> request)
                implements InboundMessage {
            @Override public String type() { return "control_request"; }
        }

        record ControlResponseMessage(String uuid, Map<String, Object> response)
                implements InboundMessage {
            @Override public String type() { return "control_response"; }
        }
    }

    /**
     * A pending outbound control request waiting for a response from the SDK host.
     */
    public static class PendingRequest {
        private final CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        private final Map<String, Object> request;

        PendingRequest(Map<String, Object> request) {
            this.request = Collections.unmodifiableMap(new LinkedHashMap<>(request));
        }

        /** The originating {@code control_request} message. */
        public Map<String, Object> getRequest() { return request; }

        CompletableFuture<Map<String, Object>> getFuture() { return future; }
    }

    // ---------------------------------------------------------------------------
    // StructuredIO instance
    // ---------------------------------------------------------------------------

    /**
     * A single structured-IO session.
     * Create via {@link StructuredIoService#createSession(java.io.InputStream, boolean)}.
     */
    public static class StructuredIo {


        private final BlockingQueue<String> inputQueue = new LinkedBlockingQueue<>();
        private final Map<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
        private final Set<String> resolvedToolUseIds = Collections.newSetFromMap(
                new LinkedHashMap<>() {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                        return size() > MAX_RESOLVED_TOOL_USE_IDS;
                    }
                });

        private volatile boolean inputClosed = false;
        private final boolean replayUserMessages;

        // Outbound stream — both sendRequest() and callers enqueue here
        private final BlockingQueue<Map<String, Object>> outbound = new LinkedBlockingQueue<>();

        // Prepended lines to inject before the next real input line
        private final Deque<String> prependedLines = new ArrayDeque<>();

        // Callbacks
        private Consumer<Map<String, Object>> unexpectedResponseCallback;
        private Consumer<Map<String, Object>> onControlRequestSent;
        private Consumer<String> onControlRequestResolved;

        // The reader task
        private final ExecutorService readerExecutor = Executors.newSingleThreadExecutor(
                r -> new Thread(r, "structured-io-reader"));
        private final CompletableFuture<Void> readerDone;

        StructuredIo(InputStream input, boolean replayUserMessages) {
            this.replayUserMessages = replayUserMessages;
            this.readerDone = CompletableFuture.runAsync(
                    () -> readInputLoop(new BufferedReader(new InputStreamReader(input))),
                    readerExecutor);
        }

        /**
         * Constructor that accepts a PipedOutputStream/PipedInputStream pair.
         * The PipedInputStream is used for reading; the PipedOutputStream reference
         * is stored by the caller via the out[] holder.
         */
        protected StructuredIo(java.io.PipedOutputStream pipeOut, boolean replayUserMessages) throws IOException {
            this(new java.io.PipedInputStream(pipeOut, 65536), replayUserMessages);
        }

        // ---------------------------------------------------------------------------
        // Input reading loop
        // ---------------------------------------------------------------------------

        private void readInputLoop(BufferedReader reader) {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    String prepended;
                    synchronized (prependedLines) {
                        prepended = prependedLines.pollFirst();
                    }
                    while (prepended != null) {
                        handleIncomingLine(prepended.stripTrailing());
                        synchronized (prependedLines) {
                            prepended = prependedLines.pollFirst();
                        }
                    }
                    handleIncomingLine(line);
                }
            } catch (IOException e) {
                log.debug("StructuredIO: Input stream ended: {}", e.getMessage());
            } finally {
                inputClosed = true;
                // Reject all pending requests
                for (PendingRequest pr : pendingRequests.values()) {
                    pr.getFuture().completeExceptionally(
                            new IllegalStateException("Tool permission stream closed before response received"));
                }
                pendingRequests.clear();
            }
        }

        /**
         * Queue a user turn to be yielded before the next message from the real input.
         * Translated from prependUserMessage() in structuredIO.ts
         */
        public void prependUserMessage(String content) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "user");
            msg.put("session_id", "");
            Map<String, Object> msgBody = new LinkedHashMap<>();
            msgBody.put("role", "user");
            msgBody.put("content", content);
            msg.put("message", msgBody);
            msg.put("parent_tool_use_id", null);
            try {
                synchronized (prependedLines) {
                    prependedLines.addLast(objectMapper.writeValueAsString(msg) + "\n");
                }
            } catch (Exception e) {
                log.error("StructuredIO: Failed to serialize prepended user message", e);
            }
        }

        // ---------------------------------------------------------------------------
        // Line processing
        // ---------------------------------------------------------------------------

        /**
         * Parse and route a single NDJSON line.
         * Translated from processLine() in structuredIO.ts
         */
        @SuppressWarnings("unchecked")
        private void handleIncomingLine(String line) {
            if (line == null || line.isBlank()) return;

            Map<String, Object> rawMsg;
            try {
                rawMsg = objectMapper.readValue(line, Map.class);
            } catch (Exception e) {
                log.error("Error parsing streaming input line: {}: {}", line, e.getMessage());
                return;
            }

            // Normalize keys (camelCase ↔ snake_case compat shim)
            rawMsg = normalizeControlMessageKeys(rawMsg);

            String type = (String) rawMsg.get("type");
            if (type == null) return;

            switch (type) {
                case "keep_alive" -> { /* silently ignore */ }
                case "update_environment_variables" -> {
                    Map<String, String> variables = (Map<String, String>) rawMsg.getOrDefault("variables", Map.of());
                    for (Map.Entry<String, String> entry : variables.entrySet()) {
                        // Note: Java cannot modify system env at runtime; log for awareness.
                        log.debug("[structuredIO] update_environment_variables key={}", entry.getKey());
                    }
                }
                case "control_response" -> handleControlResponse(rawMsg);
                case "user", "control_request", "assistant", "system" -> routeMessage(rawMsg);
                default -> log.warn("Ignoring unknown message type: {}", type);
            }
        }

        @SuppressWarnings("unchecked")
        private void handleControlResponse(Map<String, Object> rawMsg) {
            Object uuidObj = rawMsg.get("uuid");
            String uuid = uuidObj instanceof String s ? s : null;

            Map<String, Object> response = (Map<String, Object>) rawMsg.get("response");
            if (response == null) return;
            String requestId = (String) response.get("request_id");
            if (requestId == null) return;

            PendingRequest pending = pendingRequests.remove(requestId);
            if (pending == null) {
                // Check if already resolved (duplicate delivery)
                Object respPayload = "success".equals(response.get("subtype")) ? response.get("response") : null;
                String toolUseId = respPayload instanceof Map<?, ?> m ? (String) m.get("toolUseID") : null;
                if (toolUseId != null && resolvedToolUseIds.contains(toolUseId)) {
                    log.debug("Ignoring duplicate control_response for already-resolved toolUseID={} request_id={}",
                            toolUseId, requestId);
                    return;
                }
                if (unexpectedResponseCallback != null) {
                    unexpectedResponseCallback.accept(rawMsg);
                }
                return;
            }

            trackResolvedToolUseId(pending.getRequest());

            // Notify bridge that SDK consumer resolved the request
            Map<String, Object> req = pending.getRequest();
            Map<String, Object> innerReq = (Map<String, Object>) req.get("request");
            if (innerReq != null && "can_use_tool".equals(innerReq.get("subtype"))
                    && onControlRequestResolved != null) {
                onControlRequestResolved.accept(requestId);
            }

            String subtype = (String) response.get("subtype");
            if ("error".equals(subtype)) {
                pending.getFuture().completeExceptionally(
                        new RuntimeException((String) response.getOrDefault("error", "Unknown error")));
            } else {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) response.getOrDefault("response", Map.of());
                pending.getFuture().complete(result);
            }

            // Propagate when replay is enabled
            if (replayUserMessages) {
                routeMessage(rawMsg);
            }
        }

        private void routeMessage(Map<String, Object> rawMsg) {
            // Push the parsed message into the inbound queue for consumers
            log.info("cli_stdin_message_parsed type={}", rawMsg.get("type"));
            inputQueue.add(objectMapper.convertValue(rawMsg, Object.class) != null
                    ? serializeBack(rawMsg) : serializeBack(rawMsg));
        }

        private String serializeBack(Map<String, Object> rawMsg) {
            try {
                return objectMapper.writeValueAsString(rawMsg);
            } catch (Exception e) {
                return "{}";
            }
        }

        // ---------------------------------------------------------------------------
        // Resolve tracking
        // ---------------------------------------------------------------------------

        @SuppressWarnings("unchecked")
        private void trackResolvedToolUseId(Map<String, Object> request) {
            Map<String, Object> innerReq = (Map<String, Object>) request.get("request");
            if (innerReq == null) return;
            if ("can_use_tool".equals(innerReq.get("subtype"))) {
                String toolUseId = (String) innerReq.get("tool_use_id");
                if (toolUseId != null) {
                    resolvedToolUseIds.add(toolUseId);
                }
            }
        }

        // ---------------------------------------------------------------------------
        // Write
        // ---------------------------------------------------------------------------

        /**
         * Write a message to stdout as NDJSON.
         * Translated from write() in structuredIO.ts
         */
        public CompletableFuture<Void> write(Map<String, Object> message) {
            return CompletableFuture.runAsync(() -> {
                try {
                    String json = objectMapper.writeValueAsString(message);
                    System.out.println(json);
                    System.out.flush();
                } catch (Exception e) {
                    log.error("StructuredIO: Failed to write message", e);
                }
            });
        }

        // ---------------------------------------------------------------------------
        // sendRequest — request/response over control protocol
        // ---------------------------------------------------------------------------

        /**
         * Send a control request and await the response.
         * Translated from sendRequest() in structuredIO.ts
         *
         * @param request    the inner request payload (subtype + fields)
         * @param signal     optional cancellation future; complete exceptionally to cancel
         * @param requestId  optional explicit request ID (UUID generated if null)
         * @return future that resolves with the response payload map
         */
        public CompletableFuture<Map<String, Object>> sendRequest(
                Map<String, Object> request,
                CompletableFuture<?> signal,
                String requestId) {

            if (requestId == null) {
                requestId = java.util.UUID.randomUUID().toString();
            }

            if (inputClosed) {
                return CompletableFuture.failedFuture(new IllegalStateException("Stream closed"));
            }
            if (signal != null && signal.isDone() && signal.isCompletedExceptionally()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Request aborted"));
            }

            Map<String, Object> message = new LinkedHashMap<>();
            message.put("type", "control_request");
            message.put("request_id", requestId);
            message.put("request", request);

            PendingRequest pending = new PendingRequest(message);
            pendingRequests.put(requestId, pending);
            outbound.add(message);

            String subtype = (String) request.get("subtype");
            if ("can_use_tool".equals(subtype) && onControlRequestSent != null) {
                onControlRequestSent.accept(message);
            }

            String finalRequestId = requestId;
            if (signal != null) {
                signal.whenComplete((v, ex) -> {
                    if (ex != null) {
                        // Abort: enqueue cancel and reject the future
                        Map<String, Object> cancel = Map.of(
                                "type", "control_cancel_request",
                                "request_id", finalRequestId);
                        outbound.add(cancel);
                        PendingRequest pr = pendingRequests.remove(finalRequestId);
                        if (pr != null) {
                            trackResolvedToolUseId(pr.getRequest());
                            pr.getFuture().completeExceptionally(new CancellationException("Request aborted"));
                        }
                    }
                });
            }

            return pending.getFuture()
                    .whenComplete((v, ex) -> pendingRequests.remove(finalRequestId));
        }

        // ---------------------------------------------------------------------------
        // Inject control response (used by the bridge)
        // ---------------------------------------------------------------------------

        /**
         * Inject a control_response to resolve a pending permission request.
         * Translated from injectControlResponse() in structuredIO.ts
         */
        @SuppressWarnings("unchecked")
        public void injectControlResponse(Map<String, Object> response) {
            Map<String, Object> responsePayload = (Map<String, Object>) response.get("response");
            if (responsePayload == null) return;
            String requestId = (String) responsePayload.get("request_id");
            if (requestId == null) return;

            PendingRequest pending = pendingRequests.remove(requestId);
            if (pending == null) return;

            trackResolvedToolUseId(pending.getRequest());

            // Cancel the SDK consumer's canUseTool callback
            Map<String, Object> cancel = Map.of("type", "control_cancel_request", "request_id", requestId);
            write(cancel);

            String subtype = (String) responsePayload.get("subtype");
            if ("error".equals(subtype)) {
                pending.getFuture().completeExceptionally(
                        new RuntimeException((String) responsePayload.getOrDefault("error", "Unknown error")));
            } else {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) responsePayload.getOrDefault("response", Map.of());
                pending.getFuture().complete(result);
            }
        }

        // ---------------------------------------------------------------------------
        // Pending permission requests
        // ---------------------------------------------------------------------------

        /**
         * Get all pending can_use_tool permission requests.
         * Translated from getPendingPermissionRequests() in structuredIO.ts
         */
        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> getPendingPermissionRequests() {
            List<Map<String, Object>> result = new ArrayList<>();
            for (PendingRequest pr : pendingRequests.values()) {
                Map<String, Object> req = pr.getRequest();
                Map<String, Object> inner = (Map<String, Object>) req.get("request");
                if (inner != null && "can_use_tool".equals(inner.get("subtype"))) {
                    result.add(req);
                }
            }
            return result;
        }

        // ---------------------------------------------------------------------------
        // Callback setters
        // ---------------------------------------------------------------------------

        public void setUnexpectedResponseCallback(Consumer<Map<String, Object>> callback) {
            this.unexpectedResponseCallback = callback;
        }

        public void setOnControlRequestSent(Consumer<Map<String, Object>> callback) {
            this.onControlRequestSent = callback;
        }

        public void setOnControlRequestResolved(Consumer<String> callback) {
            this.onControlRequestResolved = callback;
        }

        // ---------------------------------------------------------------------------
        // Outbound queue (drained by the transport layer)
        // ---------------------------------------------------------------------------

        /**
         * Drain all pending outbound messages.
         * The transport layer polls this queue and writes messages to the wire.
         */
        public List<Map<String, Object>> drainOutbound() {
            List<Map<String, Object>> drained = new ArrayList<>();
            outbound.drainTo(drained);
            return drained;
        }

        /**
         * Wait for the reader task to finish.
         */
        public CompletableFuture<Void> awaitClosed() {
            return readerDone;
        }

        // ---------------------------------------------------------------------------
        // Flush (no-op here; overridden by RemoteIoService)
        // ---------------------------------------------------------------------------

        /** Flush pending internal events. No-op for non-remote IO. */
        public CompletableFuture<Void> flushInternalEvents() {
            return CompletableFuture.completedFuture(null);
        }

        /** Internal-event queue depth. Zero for plain StructuredIO. */
        public int getInternalEventsPending() {
            return 0;
        }
    }

    // ---------------------------------------------------------------------------
    // Key normalisation (camelCase ↔ snake_case compat shim)
    // ---------------------------------------------------------------------------

    /**
     * Minimal normalisation of known control-message key variants.
     * Translated from normalizeControlMessageKeys() in controlMessageCompat.ts
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> normalizeControlMessageKeys(Map<String, Object> msg) {
        if (msg == null) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>(msg);
        // Remap camelCase keys to snake_case where the TS side uses both forms
        remapKey(out, "requestId", "request_id");
        remapKey(out, "sessionId", "session_id");
        remapKey(out, "parentToolUseId", "parent_tool_use_id");
        return out;
    }

    private static void remapKey(Map<String, Object> map, String from, String to) {
        if (map.containsKey(from) && !map.containsKey(to)) {
            map.put(to, map.remove(from));
        }
    }

    // ---------------------------------------------------------------------------
    // Factory
    // ---------------------------------------------------------------------------

    /**
     * Create a new {@link StructuredIo} session backed by the given input stream.
     *
     * @param input              raw byte stream of NDJSON messages (e.g. stdin)
     * @param replayUserMessages when true, control_response messages are propagated
     *                           to consumers in addition to resolving futures
     */
    public StructuredIo createSession(InputStream input, boolean replayUserMessages) {
        return new StructuredIo(input, replayUserMessages);
    }

    /** Create a session without replay. */
    public StructuredIo createSession(InputStream input) {
        return createSession(input, false);
    }
}
