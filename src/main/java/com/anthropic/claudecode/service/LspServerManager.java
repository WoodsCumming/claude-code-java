package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LSP server manager.
 * Translated from src/services/lsp/LSPServerManager.ts
 *
 * Manages multiple LSP server instances and routes requests based on file extensions.
 * Provides file synchronization (didOpen/didChange/didSave/didClose) to the appropriate
 * LSP server for each file.
 */
@Slf4j
@Service
public class LspServerManager {



    private final LspServerInstanceService lspServerInstanceService;
    private final LspService lspService;

    /** All configured server instances keyed by server name. */
    private final Map<String, LspServerInstanceService.LspServerInstance> servers =
            new ConcurrentHashMap<>();

    /** File extension → list of server names that handle it (e.g. ".ts" → ["typescript-lsp"]). */
    private final Map<String, List<String>> extensionMap = new ConcurrentHashMap<>();

    /**
     * Track which files are open on which servers (file URI → server name).
     * Translated from openedFiles Map in createLSPServerManager() closure.
     */
    private final Map<String, String> openedFiles = new ConcurrentHashMap<>();

    private volatile boolean initialized = false;

    @Autowired
    public LspServerManager(LspServerInstanceService lspServerInstanceService, LspService lspService) {
        this.lspServerInstanceService = lspServerInstanceService;
        this.lspService = lspService;
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Initialize the manager by loading all configured LSP servers.
     * Translated from initialize() in LSPServerManager.ts
     *
     * @throws RuntimeException if configuration loading fails
     */
    public CompletableFuture<Void> initialize() {
        return lspService.getAllLspServers()
            .thenApply(result -> result.servers())
            .thenCompose(rawConfigs -> CompletableFuture.runAsync(() -> {
            // Convert LspService.ScopedLspServerConfig → LspServerInstanceService.ScopedLspServerConfig
            Map<String, LspServerInstanceService.ScopedLspServerConfig> serverConfigs = new LinkedHashMap<>();
            rawConfigs.forEach((name, cfg) ->
                serverConfigs.put(name, LspServerInstanceService.ScopedLspServerConfig.from(cfg)));

            log.debug("[LSP SERVER MANAGER] getAllLspServers returned {} server(s)", serverConfigs.size());

            for (Map.Entry<String, LspServerInstanceService.ScopedLspServerConfig> entry : serverConfigs.entrySet()) {
                String serverName = entry.getKey();
                LspServerInstanceService.ScopedLspServerConfig config = entry.getValue();
                try {
                    if (config.getCommand() == null || config.getCommand().isBlank()) {
                        throw new IllegalArgumentException("Server " + serverName + " missing required 'command' field");
                    }
                    if (config.getExtensionToLanguage() == null || config.getExtensionToLanguage().isEmpty()) {
                        throw new IllegalArgumentException(
                            "Server " + serverName + " missing required 'extensionToLanguage' field");
                    }

                    // Map file extensions → server name
                    for (String ext : config.getExtensionToLanguage().keySet()) {
                        String normalized = ext.startsWith(".") ? ext.toLowerCase() : ("." + ext).toLowerCase();
                        extensionMap.computeIfAbsent(normalized, k -> new ArrayList<>()).add(serverName);
                    }

                    // Create server instance
                    LspServerInstanceService.LspServerInstance instance =
                        lspServerInstanceService.createInstance(serverName, config);

                    // Register workspace/configuration handler — returns null for each item
                    // as per TypeScript source comment: "mirrors the TS workspace/configuration
                    // handler that returns null for each item"
                    instance.onRequest("workspace/configuration", params -> null);

                    servers.put(serverName, instance);
                    log.debug("Registered LSP server '{}' for extensions: {}",
                        serverName, config.getExtensionToLanguage().keySet());

                } catch (Exception e) {
                    log.error("Failed to initialize LSP server '{}': {}", serverName, e.getMessage(), e);
                    // Continue with other servers — don't fail entire initialization
                }
            }

            initialized = true;
            log.debug("LSP manager initialized with {} server(s)", servers.size());
        }));
    }

    /**
     * Shutdown all running servers and clear state.
     * Translated from shutdown() in LSPServerManager.ts
     */
    public CompletableFuture<Void> shutdown() {
        List<CompletableFuture<Void>> stopFutures = new ArrayList<>();
        for (LspServerInstanceService.LspServerInstance instance : servers.values()) {
            stopFutures.add(instance.stop().exceptionally(e -> {
                log.warn("Error stopping LSP server {}: {}", instance.getName(), e.getMessage());
                return null;
            }));
        }

        return CompletableFuture.allOf(stopFutures.toArray(new CompletableFuture[0]))
            .thenRun(() -> {
                servers.clear();
                extensionMap.clear();
                openedFiles.clear();
                initialized = false;
                log.debug("LSP server manager shutdown complete");
            });
    }

    // =========================================================================
    // Server routing
    // =========================================================================

    /**
     * Get the LSP server instance for a given file path.
     * Translated from getServerForFile() in LSPServerManager.ts
     *
     * @return the matching instance, or null if none is configured for this file's extension
     */
    public LspServerInstanceService.LspServerInstance getServerForFile(String filePath) {
        String ext = getExtension(filePath);
        if (ext.isEmpty()) return null;
        List<String> serverNames = extensionMap.get(ext.toLowerCase());
        if (serverNames == null || serverNames.isEmpty()) return null;
        return servers.get(serverNames.get(0));
    }

    /**
     * Ensure the appropriate LSP server is started for the given file.
     * Translated from ensureServerStarted() in LSPServerManager.ts
     *
     * @return CompletableFuture resolving to the server instance, or null if unsupported
     */
    public CompletableFuture<LspServerInstanceService.LspServerInstance> ensureServerStarted(String filePath) {
        LspServerInstanceService.LspServerInstance instance = getServerForFile(filePath);
        if (instance == null) return CompletableFuture.completedFuture(null);

        // Restart error-state servers on next use
        if (instance.getState() == LspServerInstanceService.LspServerState.ERROR) {
            log.debug("LSP: server for '{}' is in error state, attempting restart", filePath);
            return instance.restart().thenApply(v -> instance);
        }

        if (!instance.isHealthy()) {
            return instance.start().thenApply(v -> instance);
        }
        return CompletableFuture.completedFuture(instance);
    }

    /**
     * Send a request to the appropriate LSP server for the given file.
     * Translated from sendRequest() in LSPServerManager.ts
     *
     * @return CompletableFuture resolving to the response, or empty if no server handles the file
     */
    public <T> CompletableFuture<Optional<T>> sendRequest(String filePath, String method, Object params) {
        return ensureServerStarted(filePath).thenCompose(instance -> {
            if (instance == null) return CompletableFuture.completedFuture(Optional.empty());
            return instance.<T>sendRequest(method, params, null)
                .thenApply(Optional::ofNullable)
                .exceptionally(ex -> {
                    log.error("LSP request '{}' failed for '{}': {}", method, filePath, ex.getMessage());
                    return Optional.empty();
                });
        });
    }

    /**
     * Get all running server instances.
     * Translated from getAllServers() in LSPServerManager.ts
     */
    public Map<String, LspServerInstanceService.LspServerInstance> getAllServers() {
        return Collections.unmodifiableMap(servers);
    }

    public boolean isInitialized() { return initialized; }

    // =========================================================================
    // File synchronization (textDocument/did* notifications)
    // =========================================================================

    /**
     * Synchronize a file open to the LSP server (sends didOpen notification).
     * Translated from openFile() in LSPServerManager.ts
     */
    public CompletableFuture<Void> openFile(String filePath, String content) {
        return ensureServerStarted(filePath).thenCompose(instance -> {
            if (instance == null) return CompletableFuture.completedFuture(null);

            String uri = filePathToUri(filePath);
            String languageId = getLanguageId(instance, filePath);

            Map<String, Object> textDocument = new LinkedHashMap<>();
            textDocument.put("uri", uri);
            textDocument.put("languageId", languageId);
            textDocument.put("version", 1);
            textDocument.put("text", content);

            openedFiles.put(uri, instance.getName());
            return instance.sendNotification("textDocument/didOpen", Map.of("textDocument", textDocument));
        });
    }

    /**
     * Synchronize a file change to the LSP server (sends didChange notification).
     * Translated from changeFile() in LSPServerManager.ts
     */
    public CompletableFuture<Void> changeFile(String filePath, String content) {
        return ensureServerStarted(filePath).thenCompose(instance -> {
            if (instance == null) return CompletableFuture.completedFuture(null);

            String uri = filePathToUri(filePath);
            Map<String, Object> textDocument = new LinkedHashMap<>();
            textDocument.put("uri", uri);
            textDocument.put("version", System.currentTimeMillis());

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("textDocument", textDocument);
            params.put("contentChanges", List.of(Map.of("text", content)));

            return instance.sendNotification("textDocument/didChange", params);
        });
    }

    /**
     * Synchronize a file save to the LSP server (sends didSave notification).
     * Translated from saveFile() in LSPServerManager.ts
     */
    public CompletableFuture<Void> saveFile(String filePath) {
        LspServerInstanceService.LspServerInstance instance = getServerForFile(filePath);
        if (instance == null || !instance.isHealthy()) return CompletableFuture.completedFuture(null);

        String uri = filePathToUri(filePath);
        return instance.sendNotification("textDocument/didSave", Map.of("textDocument", Map.of("uri", uri)));
    }

    /**
     * Synchronize a file close to the LSP server (sends didClose notification).
     * Translated from closeFile() in LSPServerManager.ts
     */
    public CompletableFuture<Void> closeFile(String filePath) {
        LspServerInstanceService.LspServerInstance instance = getServerForFile(filePath);
        if (instance == null || !instance.isHealthy()) return CompletableFuture.completedFuture(null);

        String uri = filePathToUri(filePath);
        openedFiles.remove(uri);
        return instance.sendNotification("textDocument/didClose", Map.of("textDocument", Map.of("uri", uri)));
    }

    /**
     * Check if a file is already open on a compatible LSP server.
     * Translated from isFileOpen() in LSPServerManager.ts
     */
    public boolean isFileOpen(String filePath) {
        return openedFiles.containsKey(filePathToUri(filePath));
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /** Extract the file extension including the dot (e.g. ".ts"). */
    private static String getExtension(String filePath) {
        if (filePath == null) return "";
        int dot = filePath.lastIndexOf('.');
        if (dot < 0 || dot == filePath.length() - 1) return "";
        // Handle hidden files like .gitignore — no extension
        int slash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        if (dot <= slash + 1) return "";
        return filePath.substring(dot); // includes dot, e.g. ".ts"
    }

    private static String getLanguageId(
            LspServerInstanceService.LspServerInstance instance,
            String filePath) {
        String ext = getExtension(filePath).toLowerCase();
        Map<String, String> extToLang = instance.getConfig().getExtensionToLanguage();
        if (extToLang != null && extToLang.containsKey(ext)) {
            return extToLang.get(ext);
        }
        // Fallback: derive from extension
        return ext.isEmpty() ? "plaintext" : ext.substring(1);
    }

    private static String filePathToUri(String filePath) {
        try {
            return Paths.get(filePath).toUri().toString();
        } catch (Exception e) {
            return "file://" + filePath.replace("\\", "/");
        }
    }
}
