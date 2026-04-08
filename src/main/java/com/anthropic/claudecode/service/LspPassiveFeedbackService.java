package com.anthropic.claudecode.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LSP passive feedback service.
 * Translated from src/services/lsp/passiveFeedback.ts
 *
 * Handles registration of LSP notification handlers for all servers and
 * routes textDocument/publishDiagnostics notifications into Claude's
 * diagnostic attachment system.
 */
@Slf4j
@Service
public class LspPassiveFeedbackService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LspPassiveFeedbackService.class);


    private final LspDiagnosticRegistry diagnosticRegistry;

    @Autowired
    public LspPassiveFeedbackService(LspDiagnosticRegistry diagnosticRegistry) {
        this.diagnosticRegistry = diagnosticRegistry;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Map LSP severity number to Claude diagnostic severity string.
     * LSP DiagnosticSeverity: 1=Error, 2=Warning, 3=Information, 4=Hint.
     * Translated from mapLSPSeverity() in passiveFeedback.ts
     */
    public String mapLSPSeverity(Integer lspSeverity) {
        if (lspSeverity == null) return "Error";
        return switch (lspSeverity) {
            case 1 -> "Error";
            case 2 -> "Warning";
            case 3 -> "Info";
            case 4 -> "Hint";
            default -> "Error";
        };
    }

    /**
     * Convert raw LSP publishDiagnostics params to DiagnosticFile list.
     * Mirrors formatDiagnosticsForAttachment() in passiveFeedback.ts.
     *
     * @param uri         the raw URI from the LSP notification (may be file://)
     * @param diagnostics list of raw diagnostic maps from the LSP server
     * @return list containing one DiagnosticFile (empty if no diagnostics)
     */
    public List<LspDiagnosticRegistry.DiagnosticFile> formatDiagnosticsForAttachment(
            String uri,
            List<Map<String, Object>> diagnostics) {

        // Normalise URI: file:// → filesystem path
        String filePath;
        try {
            filePath = uri.startsWith("file://") ? new URI(uri).getPath() : uri;
        } catch (Exception e) {
            log.error("Failed to convert URI to file path: {}. Error: {}. Using original URI.",
                uri, e.getMessage());
            filePath = uri;
        }

        List<LspDiagnosticRegistry.Diagnostic> converted = new ArrayList<>();
        for (Map<String, Object> diag : diagnostics) {
            String message = (String) diag.get("message");
            if (message == null) continue;

            Integer severity = diag.get("severity") instanceof Number n ? n.intValue() : null;
            String severityStr = mapLSPSeverity(severity);

            @SuppressWarnings("unchecked")
            Map<String, Object> range = (Map<String, Object>) diag.get("range");
            LspDiagnosticRegistry.DiagnosticRange diagRange = null;
            if (range != null) {
                diagRange = new LspDiagnosticRegistry.DiagnosticRange(
                    extractPosition(range, "start"),
                    extractPosition(range, "end")
                );
            }

            String source = diag.get("source") instanceof String s ? s : null;
            Object codeRaw = diag.get("code");
            String code = codeRaw != null ? String.valueOf(codeRaw) : null;

            converted.add(new LspDiagnosticRegistry.Diagnostic(
                message, severityStr, diagRange, source, code));
        }

        if (converted.isEmpty()) return List.of();
        return List.of(new LspDiagnosticRegistry.DiagnosticFile(filePath, converted));
    }

    /**
     * Register LSP notification handlers on all servers in the given manager.
     *
     * Sets up handlers to listen for textDocument/publishDiagnostics notifications
     * from all LSP servers and routes them to Claude's diagnostic system.
     * Translated from registerLSPNotificationHandlers() in passiveFeedback.ts
     *
     * @param manager the LSPServerManager whose servers should have handlers registered
     * @return tracking data for registration status and runtime failures
     */
    public HandlerRegistrationResult registerLSPNotificationHandlers(LspServerManager manager) {
        Map<String, LspServerInstanceService.LspServerInstance> servers = manager.getAllServers();

        List<RegistrationError> registrationErrors = new ArrayList<>();
        int successCount = 0;
        Map<String, FailureInfo> diagnosticFailures = new ConcurrentHashMap<>();

        for (Map.Entry<String, LspServerInstanceService.LspServerInstance> entry : servers.entrySet()) {
            String serverName = entry.getKey();
            LspServerInstanceService.LspServerInstance serverInstance = entry.getValue();

            try {
                if (serverInstance == null) {
                    String errorMsg = "Server instance is null/undefined";
                    registrationErrors.add(new RegistrationError(serverName, errorMsg));
                    log.error("Skipping handler registration for {}: {}", serverName, errorMsg);
                    continue;
                }

                if (!serverInstance.supportsNotifications()) {
                    String errorMsg = "Server instance has no onNotification method";
                    registrationErrors.add(new RegistrationError(serverName, errorMsg));
                    log.error("Skipping handler registration for {}: {}", serverName, errorMsg);
                    continue;
                }

                // Register the publishDiagnostics handler
                serverInstance.onNotification("textDocument/publishDiagnostics", params -> {
                    log.debug("[PASSIVE DIAGNOSTICS] Handler invoked for {}! Params type: {}",
                        serverName, params == null ? "null" : params.getClass().getSimpleName());
                    try {
                        handlePublishDiagnosticsParams(serverName, params, diagnosticFailures);
                    } catch (Exception error) {
                        FailureInfo failures = diagnosticFailures.compute(serverName,
                            (k, v) -> v == null ? new FailureInfo(1, error.getMessage())
                                                : new FailureInfo(v.count() + 1, error.getMessage()));
                        log.error("Unexpected error processing diagnostics from {}: {}",
                            serverName, error.getMessage());
                        if (failures.count() >= 3) {
                            log.warn("WARNING: LSP diagnostic handler for {} has failed {} times "
                                + "consecutively. Last error: {}",
                                serverName, failures.count(), failures.lastError());
                        }
                    }
                });

                log.debug("Registered diagnostics handler for {}", serverName);
                successCount++;

            } catch (Exception error) {
                registrationErrors.add(new RegistrationError(serverName, error.getMessage()));
                log.error("Failed to register diagnostics handler for {}: {}",
                    serverName, error.getMessage());
            }
        }

        int totalServers = servers.size();
        if (!registrationErrors.isEmpty()) {
            String failedServers = registrationErrors.stream()
                .map(e -> e.serverName() + " (" + e.error() + ")")
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
            log.error("Failed to register diagnostics for {} LSP server(s): {}",
                registrationErrors.size(), failedServers);
            log.debug("LSP notification handler registration: {}/{} succeeded. Failed servers: {}.",
                successCount, totalServers, failedServers);
        } else {
            log.debug("LSP notification handlers registered successfully for all {} server(s)",
                totalServers);
        }

        return new HandlerRegistrationResult(
            totalServers, successCount, registrationErrors, diagnosticFailures);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void handlePublishDiagnosticsParams(
            String serverName,
            Object params,
            Map<String, FailureInfo> diagnosticFailures) {

        if (params == null) {
            log.error("LSP server {} sent null diagnostic params", serverName);
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> paramsMap = (Map<String, Object>) params;
        String uri = (String) paramsMap.get("uri");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diagnostics = (List<Map<String, Object>>) paramsMap.get("diagnostics");

        if (uri == null || diagnostics == null) {
            log.error("LSP server {} sent invalid diagnostic params (missing uri or diagnostics)",
                serverName);
            return;
        }

        log.debug("Received diagnostics from {}: {} diagnostic(s) for {}",
            serverName, diagnostics.size(), uri);

        List<LspDiagnosticRegistry.DiagnosticFile> diagnosticFiles =
            formatDiagnosticsForAttachment(uri, diagnostics);

        if (diagnosticFiles.isEmpty() || diagnosticFiles.get(0).getDiagnostics().isEmpty()) {
            log.debug("Skipping empty diagnostics from {} for {}", serverName, uri);
            return;
        }

        try {
            diagnosticRegistry.registerPendingLSPDiagnostic(serverName, diagnosticFiles);
            log.debug("LSP Diagnostics: Registered {} diagnostic file(s) from {} for async delivery",
                diagnosticFiles.size(), serverName);
            // Success — reset failure counter
            diagnosticFailures.remove(serverName);
        } catch (Exception error) {
            FailureInfo failures = diagnosticFailures.compute(serverName,
                (k, v) -> v == null ? new FailureInfo(1, error.getMessage())
                                    : new FailureInfo(v.count() + 1, error.getMessage()));
            log.error("Error registering LSP diagnostics from {}: URI: {}, Error: {}",
                serverName, uri, error.getMessage());
            if (failures.count() >= 3) {
                log.warn("WARNING: LSP diagnostic handler for {} has failed {} times. Last error: {}",
                    serverName, failures.count(), failures.lastError());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private LspDiagnosticRegistry.DiagnosticPosition extractPosition(
            Map<String, Object> range, String key) {
        Map<String, Object> pos = (Map<String, Object>) range.get(key);
        if (pos == null) return new LspDiagnosticRegistry.DiagnosticPosition(0, 0);
        int line = pos.get("line") instanceof Number n ? n.intValue() : 0;
        int character = pos.get("character") instanceof Number n ? n.intValue() : 0;
        return new LspDiagnosticRegistry.DiagnosticPosition(line, character);
    }

    // -------------------------------------------------------------------------
    // DTOs
    // -------------------------------------------------------------------------

    /**
     * Handler registration result with tracking data.
     * Translated from HandlerRegistrationResult in passiveFeedback.ts
     */
    public record HandlerRegistrationResult(
        int totalServers,
        int successCount,
        List<RegistrationError> registrationErrors,
        Map<String, FailureInfo> diagnosticFailures
    ) {}

    public record RegistrationError(String serverName, String error) {}

    public record FailureInfo(int count, String lastError) {}
}
