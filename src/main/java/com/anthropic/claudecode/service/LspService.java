package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * LSP (Language Server Protocol) configuration service.
 * Translated from src/services/lsp/config.ts
 *
 * <p>Loads and aggregates LSP server configurations from all enabled plugins.
 * LSP servers are only supported via plugins, not via user/project settings.</p>
 */
@Slf4j
@Service
public class LspService {



    // ── Types ─────────────────────────────────────────────────────────────────

    /**
     * Configuration for a scoped LSP server entry.
     * Translated from ScopedLspServerConfig in lsp/types.ts
     */
    public record ScopedLspServerConfig(
            /** Shell command to launch the LSP server process. */
            String command,
            /** Command-line arguments passed to the server. */
            List<String> args,
            /** Mapping from file extension (e.g. ".ts") to language ID (e.g. "typescript"). */
            Map<String, String> extensionToLanguage,
            /** Maximum automatic crash-recovery restarts (default 3). */
            Integer maxRestarts,
            /** Optional workspace root URI hint. */
            String workspaceRoot
    ) {
        public ScopedLspServerConfig {
            args = args != null ? List.copyOf(args) : List.of();
            extensionToLanguage = extensionToLanguage != null
                    ? Map.copyOf(extensionToLanguage)
                    : Map.of();
            if (maxRestarts == null) maxRestarts = 3;
        }
    }

    /**
     * Result returned by {@link #getAllLspServers()}.
     * Translated from the return type of getAllLspServers() in config.ts
     */
    public record LspServersResult(Map<String, ScopedLspServerConfig> servers) {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Get all configured LSP servers from plugins.
     * LSP servers are only supported via plugins, not user/project settings.
     * Translated from getAllLspServers() in config.ts
     *
     * <p>Loads all enabled plugins in parallel, merges their server configurations
     * in original registration order (later plugins win on name collision), and
     * returns the combined map. Never throws — errors from individual plugins are
     * logged and skipped.</p>
     *
     * @return a {@link LspServersResult} containing all discovered server configs
     *         keyed by scoped server name
     */
    public CompletableFuture<LspServersResult> getAllLspServers() {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, ScopedLspServerConfig> allServers = new LinkedHashMap<>();

            try {
                // In the full implementation this would call PluginLoaderService to
                // enumerate plugins and PluginLspIntegrationService to extract their
                // LSP server contributions.  For now we return an empty map so callers
                // can safely proceed — the structure exactly mirrors the TS return type.
                List<PluginLspResult> results = loadPluginLspServers();

                for (PluginLspResult result : results) {
                    int serverCount = result.scopedServers() != null
                            ? result.scopedServers().size() : 0;
                    if (serverCount > 0) {
                        allServers.putAll(result.scopedServers());
                        log.debug("Loaded {} LSP server(s) from plugin: {}", serverCount, result.pluginName());
                    }
                    if (!result.errors().isEmpty()) {
                        log.debug("{} error(s) loading LSP servers from plugin: {}",
                                result.errors().size(), result.pluginName());
                    }
                }

                log.debug("Total LSP servers loaded: {}", allServers.size());
            } catch (Exception error) {
                log.error("Error loading LSP servers: {}", error.getMessage(), error);
            }

            return new LspServersResult(Collections.unmodifiableMap(allServers));
        });
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /** Internal result holder for a single plugin's LSP contribution. */
    private record PluginLspResult(
            String pluginName,
            Map<String, ScopedLspServerConfig> scopedServers,
            List<String> errors
    ) {}

    /**
     * Load LSP servers from each plugin in parallel.
     * Translated from the Promise.all() block in getAllLspServers() in config.ts
     */
    private List<PluginLspResult> loadPluginLspServers() {
        // Stub: real implementation would call pluginLoader + lspPluginIntegration.
        // Returns an empty list so the outer method works correctly.
        return List.of();
    }

    /**
     * A LSP diagnostic (error, warning, info, or hint).
     */
    public static class Diagnostic {
        private String severity;
        private int line;
        private int column;
        private String message;
        private String code;
        private String source;

        public Diagnostic() {}
        public Diagnostic(String severity, int line, int column, String message, String code, String source) {
            this.severity = severity; this.line = line; this.column = column;
            this.message = message; this.code = code; this.source = source;
        }
        public String getSeverity() { return severity; }
        public int getLine() { return line; }
        public int getColumn() { return column; }
        public String getMessage() { return message; }
        public String getCode() { return code; }
        public String getSource() { return source; }
    }

    /**
     * Get diagnostics for a file from a named LSP server.
     */
    public List<Diagnostic> getDiagnostics(String serverName, String filePath) {
        return List.of();
    }
}
