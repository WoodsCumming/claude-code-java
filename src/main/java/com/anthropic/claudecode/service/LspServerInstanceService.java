package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * LSP server instance service.
 * Translated from src/services/lsp/LSPServerInstance.ts and LSPClient.ts
 *
 * <p>Manages the lifecycle of a single LSP server process. Provides state tracking,
 * health monitoring, and request forwarding. Supports manual restart with configurable
 * retry limits and crash recovery.</p>
 *
 * <p>State machine: stopped → starting → running; running → stopping → stopped;
 * any → error on failure; error → starting on retry.</p>
 */
@Slf4j
@Service
public class LspServerInstanceService {



    // ── Constants ─────────────────────────────────────────────────────────────

    /**
     * LSP error code for "content modified" — indicates the server's state changed
     * during request processing (e.g. rust-analyzer still indexing).
     * Translated from LSP_ERROR_CONTENT_MODIFIED in LSPServerInstance.ts
     */
    public static final int LSP_ERROR_CONTENT_MODIFIED = -32801;

    /**
     * Maximum retries for transient LSP errors (e.g. content-modified).
     * Actual delays via exponential backoff: 500 ms, 1 000 ms, 2 000 ms.
     */
    public static final int MAX_RETRIES_FOR_TRANSIENT_ERRORS = 3;

    /** Base delay for exponential backoff (ms). */
    public static final long RETRY_BASE_DELAY_MS = 500L;

    // ── Enums/Types ───────────────────────────────────────────────────────────

    /**
     * Possible states for an LSP server instance.
     * Translated from LspServerState in types.ts
     */
    public enum LspServerState {
        STOPPED, STARTING, RUNNING, STOPPING, ERROR
    }

    /**
     * Extended configuration for a scoped LSP server including runtime options.
     * Extends LspService.ScopedLspServerConfig with env, workspaceFolder, and timeout fields.
     * Translated from ScopedLspServerConfig (full shape) in types.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ScopedLspServerConfig {
        private String command;
        private List<String> args;
        private Map<String, String> env;
        /** Workspace root directory (falls back to cwd if null). */
        private String workspaceFolder;
        private Map<String, String> extensionToLanguage;
        private Map<String, Object> initializationOptions;
        private Integer maxRestarts;
        private Long startupTimeout;
        /** Not yet implemented — must be null. */
        private Boolean restartOnCrash;
        /** Not yet implemented — must be null. */
        private Long shutdownTimeout;

        /** Create from LspService.ScopedLspServerConfig (config-layer record). */
        public static ScopedLspServerConfig from(LspService.ScopedLspServerConfig config) {
            ScopedLspServerConfig c = new ScopedLspServerConfig();
            c.setCommand(config.command());
            c.setArgs(config.args());
            c.setExtensionToLanguage(config.extensionToLanguage());
            c.setMaxRestarts(config.maxRestarts());
            c.setWorkspaceFolder(config.workspaceRoot());
            return c;
        }

        public String getCommand() { return command; }
        public void setCommand(String v) { command = v; }
        public List<String> getArgs() { return args; }
        public void setArgs(List<String> v) { args = v; }
        public Map<String, String> getEnv() { return env; }
        public void setEnv(Map<String, String> v) { env = v; }
        public String getWorkspaceFolder() { return workspaceFolder; }
        public void setWorkspaceFolder(String v) { workspaceFolder = v; }
        public Map<String, String> getExtensionToLanguage() { return extensionToLanguage; }
        public void setExtensionToLanguage(Map<String, String> v) { extensionToLanguage = v; }
        public Map<String, Object> getInitializationOptions() { return initializationOptions; }
        public void setInitializationOptions(Map<String, Object> v) { initializationOptions = v; }
        public Integer getMaxRestarts() { return maxRestarts; }
        public void setMaxRestarts(Integer v) { maxRestarts = v; }
        public Long getStartupTimeout() { return startupTimeout; }
        public void setStartupTimeout(Long v) { startupTimeout = v; }
        public boolean isRestartOnCrash() { return restartOnCrash; }
        public void setRestartOnCrash(Boolean v) { restartOnCrash = v; }
        public Long getShutdownTimeout() { return shutdownTimeout; }
        public void setShutdownTimeout(Long v) { shutdownTimeout = v; }
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Create a new LspServerInstance for the given server name and config.
     * Translated from createLSPServerInstance() in LSPServerInstance.ts
     *
     * @param name   human-readable server identifier
     * @param config server configuration
     * @return a new {@link LspServerInstance} (not yet started)
     * @throws IllegalArgumentException if unimplemented config fields are set
     */
    public LspServerInstance createInstance(String name, ScopedLspServerConfig config) {
        if (config.getRestartOnCrash() != null) {
            throw new IllegalArgumentException(
                "LSP server '" + name + "': restartOnCrash is not yet implemented. Remove this field from the configuration.");
        }
        if (config.getShutdownTimeout() != null) {
            throw new IllegalArgumentException(
                "LSP server '" + name + "': shutdownTimeout is not yet implemented. Remove this field from the configuration.");
        }
        return new LspServerInstance(name, config);
    }

    // ── LspServerInstance ─────────────────────────────────────────────────────

    /**
     * Manages the lifecycle of a single LSP server process.
     * Translated from the object returned by createLSPServerInstance() in LSPServerInstance.ts
     */
    public static class LspServerInstance {

        @Getter private final String name;
        @Getter private final ScopedLspServerConfig config;

        // State (mirrors TS closure variables)
        private volatile LspServerState state = LspServerState.STOPPED;
        private volatile java.time.Instant startTime;
        private volatile RuntimeException lastError;
        private volatile int restartCount = 0;
        private volatile int crashRecoveryCount = 0;

        // Underlying client (equivalent to the LSPClient closure)
        private final LSPClient client;

        LspServerInstance(String name, ScopedLspServerConfig config) {
            this.name = name;
            this.config = config;
            // Crash handler propagates error state so ensureServerStarted can restart
            this.client = new LSPClient(name, error -> {
                state = LspServerState.ERROR;
                lastError = error;
                crashRecoveryCount++;
            });
        }

        // ── Accessors ──────────────────────────────────────────────────────

        public LspServerState getState() { return state; }
        public java.time.Instant getStartTime() { return startTime; }
        /** Returns true if this instance can receive LSP notification registrations. */
        public boolean supportsNotifications() {
            return client != null && state != LspServerState.STOPPED && state != LspServerState.ERROR;
        }
        public RuntimeException getLastError() { return lastError; }
        public int getRestartCount() { return restartCount; }

        /**
         * Check if server is healthy and ready for requests.
         * Translated from isHealthy() in LSPServerInstance.ts
         */
        public boolean isHealthy() {
            return state == LspServerState.RUNNING;
        }

        // ── Lifecycle ──────────────────────────────────────────────────────

        /**
         * Start the server and initialize it with workspace information.
         * If already running or starting, returns immediately.
         * Translated from start() in LSPServerInstance.ts
         *
         * @throws RuntimeException if server fails to start or initialize
         */
        public CompletableFuture<Void> start() {
            if (state == LspServerState.RUNNING || state == LspServerState.STARTING) {
                return CompletableFuture.completedFuture(null);
            }

            // Cap crash-recovery attempts
            int maxRestarts = config.getMaxRestarts() != null ? config.getMaxRestarts() : 3;
            if (state == LspServerState.ERROR && crashRecoveryCount > maxRestarts) {
                RuntimeException error = new RuntimeException(
                    "LSP server '" + name + "' exceeded max crash recovery attempts (" + maxRestarts + ")");
                lastError = error;
                log.error("{}", error.getMessage());
                return CompletableFuture.failedFuture(error);
            }

            return CompletableFuture.runAsync(() -> {
                try {
                    state = LspServerState.STARTING;
                    log.debug("Starting LSP server instance: {}", name);

                    List<String> args = config.getArgs() != null ? config.getArgs() : List.of();
                    Map<String, String> env = config.getEnv();
                    String cwd = config.getWorkspaceFolder();

                    client.start(config.getCommand(), args, env, cwd).get();

                    // Build initialize params
                    String workspaceFolder = cwd != null ? cwd : System.getProperty("user.dir");
                    String workspaceUri = java.nio.file.Paths.get(workspaceFolder).toUri().toString();

                    Map<String, Object> initParams = buildInitializeParams(workspaceFolder, workspaceUri);

                    CompletableFuture<Map<String, Object>> initFuture = client.initialize(initParams);

                    if (config.getStartupTimeout() != null) {
                        initFuture.get(config.getStartupTimeout(), TimeUnit.MILLISECONDS);
                    } else {
                        initFuture.get();
                    }

                    state = LspServerState.RUNNING;
                    startTime = java.time.Instant.now();
                    crashRecoveryCount = 0;
                    log.debug("LSP server instance started: {}", name);

                } catch (Exception e) {
                    client.stop();
                    state = LspServerState.ERROR;
                    lastError = new RuntimeException("LSP server '" + name + "' failed to start: " + e.getMessage(), e);
                    log.error("LSP server {} failed to start", name, e);
                    throw lastError;
                }
            });
        }

        /**
         * Stop the server gracefully.
         * Translated from stop() in LSPServerInstance.ts
         */
        public CompletableFuture<Void> stop() {
            if (state == LspServerState.STOPPED || state == LspServerState.STOPPING) {
                return CompletableFuture.completedFuture(null);
            }

            return CompletableFuture.runAsync(() -> {
                try {
                    state = LspServerState.STOPPING;
                    client.stop().get(5, TimeUnit.SECONDS);
                    state = LspServerState.STOPPED;
                    log.debug("LSP server instance stopped: {}", name);
                } catch (Exception e) {
                    state = LspServerState.ERROR;
                    lastError = new RuntimeException("LSP server '" + name + "' failed to stop: " + e.getMessage(), e);
                    log.error("LSP server {} failed to stop", name, e);
                    throw lastError;
                }
            });
        }

        /**
         * Manually restart the server (stop then start).
         * Increments restartCount. Translated from restart() in LSPServerInstance.ts
         */
        public CompletableFuture<Void> restart() {
            int maxRestarts = config.getMaxRestarts() != null ? config.getMaxRestarts() : 3;
            if (restartCount >= maxRestarts) {
                RuntimeException error = new RuntimeException(
                    "LSP server '" + name + "' exceeded manual restart limit (" + maxRestarts + ")");
                lastError = error;
                return CompletableFuture.failedFuture(error);
            }
            restartCount++;
            return stop().thenCompose(v -> start());
        }

        /**
         * Send an LSP request to the server with transient-error retry.
         * Translated from sendRequest() in LSPServerInstance.ts
         */
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<T> sendRequest(String method, Object params, Class<T> type) {
            return CompletableFuture.supplyAsync(() -> {
                int attempt = 0;
                while (true) {
                    try {
                        return (T) client.sendRequest(method, params).get();
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        boolean isContentModified = cause != null
                            && cause.getMessage() != null
                            && cause.getMessage().contains(String.valueOf(LSP_ERROR_CONTENT_MODIFIED));
                        if (isContentModified && attempt < MAX_RETRIES_FOR_TRANSIENT_ERRORS) {
                            attempt++;
                            long delay = RETRY_BASE_DELAY_MS * (1L << (attempt - 1));
                            log.debug("LSP content-modified error on {}.{}, retry {}/{} after {}ms",
                                name, method, attempt, MAX_RETRIES_FOR_TRANSIENT_ERRORS, delay);
                            try { Thread.sleep(delay); } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException("LSP request interrupted", ie);
                            }
                        } else {
                            throw new RuntimeException("LSP request '" + method + "' failed on " + name, e);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("LSP request interrupted", e);
                    }
                }
            });
        }

        /**
         * Send an LSP notification (fire-and-forget).
         * Translated from sendNotification() in LSPServerInstance.ts
         */
        public CompletableFuture<Void> sendNotification(String method, Object params) {
            return client.sendNotification(method, params);
        }

        /**
         * Register a handler for notifications from the server.
         * Translated from onNotification() in LSPServerInstance.ts
         */
        public void onNotification(String method, Consumer<Object> handler) {
            client.onNotification(method, handler);
        }

        /**
         * Register a handler for requests from the server.
         * Translated from onRequest() in LSPServerInstance.ts
         */
        public void onRequest(String method, Function<Object, Object> handler) {
            client.onRequest(method, params -> handler.apply(params));
        }

        // ── Initialize params ──────────────────────────────────────────────

        @SuppressWarnings("unchecked")
        private Map<String, Object> buildInitializeParams(String workspaceFolder, String workspaceUri) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("processId", ProcessHandle.current().pid());
            params.put("initializationOptions",
                config.getInitializationOptions() != null ? config.getInitializationOptions() : Map.of());

            // workspaceFolders (LSP 3.16+)
            params.put("workspaceFolders", List.of(Map.of(
                "uri", workspaceUri,
                "name", java.nio.file.Paths.get(workspaceFolder).getFileName().toString()
            )));

            // Deprecated but needed by some servers
            params.put("rootPath", workspaceFolder);
            params.put("rootUri", workspaceUri);

            // Client capabilities
            Map<String, Object> capabilities = new LinkedHashMap<>();
            capabilities.put("workspace", Map.of(
                "configuration", false,
                "workspaceFolders", false
            ));
            Map<String, Object> textDocument = new LinkedHashMap<>();
            textDocument.put("synchronization", Map.of(
                "dynamicRegistration", false,
                "willSave", false,
                "willSaveWaitUntil", false,
                "didSave", true
            ));
            textDocument.put("publishDiagnostics", Map.of(
                "relatedInformation", true,
                "tagSupport", Map.of("valueSet", List.of(1, 2)),
                "versionSupport", false,
                "codeDescriptionSupport", true,
                "dataSupport", false
            ));
            textDocument.put("hover", Map.of(
                "dynamicRegistration", false,
                "contentFormat", List.of("markdown", "plaintext")
            ));
            textDocument.put("definition", Map.of(
                "dynamicRegistration", false,
                "linkSupport", true
            ));
            textDocument.put("references", Map.of("dynamicRegistration", false));
            capabilities.put("textDocument", textDocument);
            capabilities.put("general", Map.of("positionEncodings", List.of("utf-16")));
            params.put("capabilities", capabilities);
            return params;
        }
    }

    // ── LSPClient (inner) ──────────────────────────────────────────────────────

    /**
     * Low-level JSON-RPC 2.0 client communicating with an LSP server via stdio.
     * Translated from createLSPClient() in LSPClient.ts
     */
    static class LSPClient {

        @Getter private final String serverName;

        private final Consumer<RuntimeException> onCrash;

        // Process state
        private Process process;
        private volatile boolean isStopping = false;
        private volatile boolean startFailed = false;
        private volatile RuntimeException startError;

        // Capabilities from initialize response
        private volatile Map<String, Object> capabilities;

        // JSON-RPC I/O
        private OutputStream stdin;
        private InputStream stdout;
        private final AtomicLong nextId = new AtomicLong(1);
        private final Map<Long, CompletableFuture<Object>> pendingRequests = new ConcurrentHashMap<>();

        // Handlers — queued if connection not yet ready
        private final List<PendingHandler> pendingNotificationHandlers = new ArrayList<>();
        private final List<PendingHandler> pendingRequestHandlers = new ArrayList<>();
        private final Map<String, Consumer<Object>> notificationHandlers = new ConcurrentHashMap<>();
        private final Map<String, Consumer<Object>> requestHandlers = new ConcurrentHashMap<>();

        private Thread readerThread;

        private record PendingHandler(String method, Consumer<Object> handler) {}

        LSPClient(String serverName, Consumer<RuntimeException> onCrash) {
            this.serverName = serverName;
            this.onCrash = onCrash;
        }

        // ── Lifecycle ──────────────────────────────────────────────────────

        public CompletableFuture<Void> start(
                String command,
                List<String> args,
                Map<String, String> env,
                String cwd) {

            return CompletableFuture.runAsync(() -> {
                try {
                    List<String> cmd = new ArrayList<>();
                    cmd.add(command);
                    if (args != null) cmd.addAll(args);

                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    if (cwd != null) pb.directory(new java.io.File(cwd));
                    if (env != null) pb.environment().putAll(env);
                    pb.redirectErrorStream(false);

                    process = pb.start();
                    stdin = process.getOutputStream();
                    stdout = process.getInputStream();

                    // Drain stderr
                    final Process thisProc = process;
                    Thread.ofVirtual().name("lsp-stderr-" + serverName).start(() -> {
                        try (var err = thisProc.getErrorStream()) {
                            byte[] buf = new byte[4096];
                            int n;
                            while ((n = err.read(buf)) != -1) {
                                String line = new String(buf, 0, n).trim();
                                if (!line.isEmpty()) log.debug("[LSP {}] {}", serverName, line);
                            }
                        } catch (IOException ignored) {}
                    });

                    // Watch for unexpected exit
                    Thread.ofVirtual().name("lsp-watcher-" + serverName).start(() -> {
                        try {
                            int code = thisProc.waitFor();
                            if (code != 0 && !isStopping) {
                                RuntimeException err = new RuntimeException(
                                    "LSP server " + serverName + " crashed (exit code " + code + ")");
                                log.error("{}", err.getMessage());
                                if (onCrash != null) onCrash.accept(err);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });

                    startReaderThread();
                    applyPendingHandlers();

                    log.debug("LSP client started for {}", serverName);
                } catch (Exception e) {
                    startFailed = true;
                    startError = new RuntimeException(
                        "LSP client start failed for " + serverName + ": " + e.getMessage(), e);
                    throw startError;
                }
            });
        }

        public CompletableFuture<Map<String, Object>> initialize(Map<String, Object> params) {
            checkStartFailed();
            return sendRequest("initialize", params)
                .thenApply(result -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> res = (Map<String, Object>) result;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> caps = (Map<String, Object>) res.getOrDefault("capabilities", Map.of());
                    capabilities = caps;
                    sendNotification("initialized", Map.of());
                    log.debug("LSP server {} initialized", serverName);
                    return res;
                });
        }

        public CompletableFuture<Object> sendRequest(String method, Object params) {
            checkStartFailed();
            if (process == null) throw new IllegalStateException("LSP client not started: " + serverName);

            long id = nextId.getAndIncrement();
            CompletableFuture<Object> future = new CompletableFuture<>();
            pendingRequests.put(id, future);

            try {
                Map<String, Object> req = new LinkedHashMap<>();
                req.put("jsonrpc", "2.0");
                req.put("id", id);
                req.put("method", method);
                req.put("params", params);
                writeMessage(req);
            } catch (Exception e) {
                pendingRequests.remove(id);
                future.completeExceptionally(new RuntimeException(
                    "LSP request '" + method + "' on " + serverName + " failed: " + e.getMessage(), e));
            }
            return future;
        }

        public CompletableFuture<Void> sendNotification(String method, Object params) {
            if (process == null || startFailed) return CompletableFuture.completedFuture(null);
            return CompletableFuture.runAsync(() -> {
                try {
                    Map<String, Object> msg = new LinkedHashMap<>();
                    msg.put("jsonrpc", "2.0");
                    msg.put("method", method);
                    if (params != null) msg.put("params", params);
                    writeMessage(msg);
                } catch (Exception e) {
                    log.debug("LSP notification '{}' on {} failed: {}", method, serverName, e.getMessage());
                }
            });
        }

        public CompletableFuture<Void> stop() {
            isStopping = true;
            return CompletableFuture.runAsync(() -> {
                try {
                    if (process != null && process.isAlive()) {
                        try { sendRequest("shutdown", Map.of()).get(5, TimeUnit.SECONDS); } catch (Exception ignored) {}
                        try { sendNotification("exit", Map.of()).get(2, TimeUnit.SECONDS); } catch (Exception ignored) {}
                    }
                } finally {
                    pendingRequests.values().forEach(f ->
                        f.completeExceptionally(new RuntimeException("LSP client stopping")));
                    pendingRequests.clear();
                    if (readerThread != null) { readerThread.interrupt(); readerThread = null; }
                    if (process != null) { process.destroyForcibly(); process = null; }
                    isStopping = false;
                    log.debug("LSP client stopped for {}", serverName);
                }
            });
        }

        public void onNotification(String method, Consumer<Object> handler) {
            if (process == null) {
                synchronized (pendingNotificationHandlers) {
                    pendingNotificationHandlers.add(new PendingHandler(method, handler));
                }
                return;
            }
            checkStartFailed();
            notificationHandlers.put(method, handler);
        }

        public void onRequest(String method, Consumer<Object> handler) {
            if (process == null) {
                synchronized (pendingRequestHandlers) {
                    pendingRequestHandlers.add(new PendingHandler(method, handler));
                }
                return;
            }
            checkStartFailed();
            requestHandlers.put(method, handler);
        }

        // ── Internal ───────────────────────────────────────────────────────

        private void checkStartFailed() {
            if (startFailed) throw startError != null ? startError
                : new RuntimeException("LSP client failed for " + serverName);
        }

        private void applyPendingHandlers() {
            synchronized (pendingNotificationHandlers) {
                pendingNotificationHandlers.forEach(h -> notificationHandlers.put(h.method(), h.handler()));
                pendingNotificationHandlers.clear();
            }
            synchronized (pendingRequestHandlers) {
                pendingRequestHandlers.forEach(h -> requestHandlers.put(h.method(), h.handler()));
                pendingRequestHandlers.clear();
            }
        }

        private void startReaderThread() {
            readerThread = Thread.ofVirtual().name("lsp-reader-" + serverName).start(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted() && process != null && process.isAlive()) {
                        Map<String, Object> msg = readMessage();
                        if (msg == null) break;
                        dispatch(msg);
                    }
                } catch (IOException e) {
                    if (!isStopping) log.debug("LSP reader error for {}: {}", serverName, e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        private synchronized void writeMessage(Map<String, Object> msg) throws IOException {
            String json = toJson(msg);
            byte[] content = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String header = "Content-Length: " + content.length + "\r\n\r\n";
            stdin.write(header.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            stdin.write(content);
            stdin.flush();
        }

        private Map<String, Object> readMessage() throws IOException, InterruptedException {
            StringBuilder headerBuf = new StringBuilder();
            while (true) {
                int b = stdout.read();
                if (b == -1) return null;
                headerBuf.append((char) b);
                if (headerBuf.toString().endsWith("\r\n\r\n")) break;
            }
            int contentLength = 0;
            for (String line : headerBuf.toString().split("\r\n")) {
                if (line.startsWith("Content-Length:")) {
                    contentLength = Integer.parseInt(line.substring("Content-Length:".length()).trim());
                }
            }
            if (contentLength <= 0) return null;
            byte[] body = stdout.readNBytes(contentLength);
            return parseJsonForDispatch(new String(body, java.nio.charset.StandardCharsets.UTF_8));
        }

        @SuppressWarnings("unchecked")
        private void dispatch(Map<String, Object> msg) {
            Object id = msg.get("id");
            String method = (String) msg.get("method");

            if (id != null && method == null) {
                long reqId = ((Number) id).longValue();
                CompletableFuture<Object> future = pendingRequests.remove(reqId);
                if (future != null) {
                    if (msg.containsKey("error")) {
                        future.completeExceptionally(new RuntimeException("LSP error: " + msg.get("error")));
                    } else {
                        future.complete(msg.get("result"));
                    }
                }
            } else if (method != null) {
                Object params = msg.get("params");
                if (id == null) {
                    Consumer<Object> handler = notificationHandlers.get(method);
                    if (handler != null) handler.accept(params);
                } else {
                    Consumer<Object> handler = requestHandlers.get(method);
                    if (handler != null) handler.accept(params);
                }
            }
        }

        // ── Minimal JSON helpers ────────────────────────────────────────────

        private static String toJson(Object obj) {
            if (obj == null) return "null";
            if (obj instanceof String s) return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            if (obj instanceof Number || obj instanceof Boolean) return obj.toString();
            if (obj instanceof Map<?, ?> map) {
                StringJoiner sj = new StringJoiner(",", "{", "}");
                map.forEach((k, v) -> sj.add(toJson(k.toString()) + ":" + toJson(v)));
                return sj.toString();
            }
            if (obj instanceof Collection<?> col) {
                StringJoiner sj = new StringJoiner(",", "[", "]");
                col.forEach(v -> sj.add(toJson(v)));
                return sj.toString();
            }
            return "\"" + obj + "\"";
        }

        private static Map<String, Object> parseJsonForDispatch(String json) {
            Map<String, Object> map = new LinkedHashMap<>();
            java.util.regex.Matcher idM = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*([0-9]+)").matcher(json);
            if (idM.find()) map.put("id", Long.parseLong(idM.group(1)));
            java.util.regex.Matcher methM = java.util.regex.Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            if (methM.find()) map.put("method", methM.group(1));
            if (json.contains("\"result\"")) map.put("result", json);
            if (json.contains("\"error\""))  map.put("error",  json);
            if (json.contains("\"params\"")) map.put("params", json);
            return map;
        }
    }
}
