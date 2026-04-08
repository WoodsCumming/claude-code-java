package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Handles the special internal VSCode MCP for bidirectional communication.
 * Translated from src/services/mcp/vscodeSdkMcp.ts
 *
 * <p>Sets up the {@code claude-vscode} MCP client for sending file update
 * notifications to VS Code and receiving log-event notifications from VS Code.
 * ANT-only: only active when {@code USER_TYPE=ant}.</p>
 */
@Slf4j
@Service
public class VscodeSdkMcpService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VscodeSdkMcpService.class);


    // ── Types ─────────────────────────────────────────────────────────────────

    /**
     * Represents a connected MCP server client handle.
     * Translated from ConnectedMCPServer / MCPServerConnection in mcp/types.ts
     */
    public interface McpServerConnection {
        String getName();
        String getType(); // "connected" | "error" | ...
        McpClientHandle getClient();
    }

    /**
     * Thin handle for sending notifications to an MCP server.
     * Translated from the client property on ConnectedMCPServer.
     */
    public interface McpClientHandle {
        /** Send a JSON-RPC notification (fire-and-forget). */
        CompletableFuture<Void> notification(String method, Map<String, Object> params);
        /** Register a handler for incoming notifications matching the given schema. */
        void setNotificationHandler(String method, Consumer<Map<String, Object>> handler);
    }

    /**
     * Tri-state for the auto-mode experiment gate.
     * Translated from AutoModeEnabledState in vscodeSdkMcp.ts
     */
    public enum AutoModeEnabledState {
        ENABLED, DISABLED, OPT_IN;

        public String toWireValue() {
            return switch (this) {
                case ENABLED  -> "enabled";
                case DISABLED -> "disabled";
                case OPT_IN   -> "opt-in";
            };
        }

        public static Optional<AutoModeEnabledState> fromWireValue(String v) {
            return switch (v) {
                case "enabled"  -> Optional.of(ENABLED);
                case "disabled" -> Optional.of(DISABLED);
                case "opt-in"   -> Optional.of(OPT_IN);
                default         -> Optional.empty();
            };
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    /** Cached reference to the connected VSCode MCP client. */
    private volatile McpServerConnection vscodeMcpClient = null;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Send a {@code file_updated} notification to the VS Code MCP server.
     * Translated from notifyVscodeFileUpdated() in vscodeSdkMcp.ts
     *
     * <p>No-op unless {@code USER_TYPE=ant} and the vscode MCP client is connected.</p>
     *
     * @param filePath   absolute path of the modified file
     * @param oldContent previous file content, or {@code null} for new files
     * @param newContent new file content, or {@code null} for deleted files
     */
    public void notifyVscodeFileUpdated(String filePath, String oldContent, String newContent) {
        String userType = System.getenv("USER_TYPE");
        if (!"ant".equals(userType) || vscodeMcpClient == null) {
            return;
        }

        vscodeMcpClient.getClient()
                .notification("file_updated", Map.of(
                        "filePath",   filePath,
                        "oldContent", oldContent != null ? oldContent : "",
                        "newContent", newContent != null ? newContent : ""))
                .exceptionally(error -> {
                    log.debug("[VSCode] Failed to send file_updated notification: {}", error.getMessage());
                    return null;
                });
    }

    /**
     * Set up the special internal VSCode MCP for bidirectional communication.
     * Translated from setupVscodeSdkMcp() in vscodeSdkMcp.ts
     *
     * <p>Finds the {@code claude-vscode} client in {@code sdkClients}, stores a
     * reference, registers the {@code log_event} notification handler, and sends
     * the initial {@code experiment_gates} notification with current feature-flag
     * values.</p>
     *
     * @param sdkClients list of all SDK MCP server connections
     */
    public void setupVscodeSdkMcp(java.util.List<McpServerConnection> sdkClients) {
        Optional<McpServerConnection> found = sdkClients.stream()
                .filter(c -> "claude-vscode".equals(c.getName()) && "connected".equals(c.getType()))
                .findFirst();

        if (found.isEmpty()) return;

        McpServerConnection client = found.get();
        vscodeMcpClient = client;

        // Register log_event notification handler
        // Translated from client.client.setNotificationHandler(LogEventNotificationSchema, ...)
        client.getClient().setNotificationHandler("log_event", notification -> {
            String eventName = (String) notification.getOrDefault("eventName", "");
            Object eventData  = notification.getOrDefault("eventData", Map.of());
            // In the full implementation this would call logEvent("tengu_vscode_" + eventName, eventData)
            log.debug("[VSCode] log_event: tengu_vscode_{} data={}", eventName, eventData);
        });

        // Send experiment gates to VSCode immediately
        // Translated from the gates block + client.client.notification('experiment_gates', ...) call
        Map<String, Object> gates = buildExperimentGates();
        client.getClient()
                .notification("experiment_gates", Map.of("gates", gates))
                .exceptionally(error -> {
                    log.debug("[VSCode] Failed to send experiment_gates: {}", error.getMessage());
                    return null;
                });
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Build the experiment-gate map sent to VS Code on setup.
     * Translated from the {@code gates} literal in setupVscodeSdkMcp().
     *
     * <p>In the full implementation, each value is read from a GrowthBook / Statsig
     * cache. Here we use conservative defaults so callers compile cleanly.</p>
     */
    private Map<String, Object> buildExperimentGates() {
        java.util.LinkedHashMap<String, Object> gates = new java.util.LinkedHashMap<>();

        // Feature gates (boolean) — conservative defaults
        gates.put("tengu_vscode_review_upsell", false);
        gates.put("tengu_vscode_onboarding",    false);
        // Browser support
        gates.put("tengu_quiet_fern",           false);
        // In-band OAuth via claude_authenticate (vs. extension-native PKCE)
        gates.put("tengu_vscode_cc_auth",       false);

        // Tri-state auto-mode: omit if unknown so VSCode fails closed.
        // Translated from the autoModeState !== undefined guard in setupVscodeSdkMcp().
        readAutoModeEnabledState().ifPresent(state ->
                gates.put("tengu_auto_mode_state", state.toWireValue()));

        return gates;
    }

    /**
     * Read the auto-mode enabled state from the feature-flag cache.
     * Translated from readAutoModeEnabledState() in vscodeSdkMcp.ts
     */
    private Optional<AutoModeEnabledState> readAutoModeEnabledState() {
        // In the full implementation this reads from getFeatureValue_CACHED_MAY_BE_STALE(
        // 'tengu_auto_mode_config', {}).enabled
        return Optional.empty();
    }
}
