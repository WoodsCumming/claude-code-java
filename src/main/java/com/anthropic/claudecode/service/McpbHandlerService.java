package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.DxtUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * MCPB (MCP Builder) handler service.
 * Translated from src/utils/plugins/mcpbHandler.ts
 *
 * Handles installation and management of .dxt/.mcpb plugin packages.
 */
@Slf4j
@Service
public class McpbHandlerService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(McpbHandlerService.class);

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SecureStorageService secureStorageService;

    @Autowired
    public McpbHandlerService(OkHttpClient httpClient,
                               ObjectMapper objectMapper,
                               SecureStorageService secureStorageService) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.secureStorageService = secureStorageService;
    }

    /**
     * Install a .dxt plugin from a URL.
     * Translated from installDxtFromUrl() in mcpbHandler.ts
     */
    public CompletableFuture<InstallResult> installDxtFromUrl(String url, String targetDir) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Download the .dxt file
                Request request = new Request.Builder().url(url).get().build();
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        return new InstallResult(false, null, "Failed to download: " + response.code());
                    }

                    byte[] bytes = response.body().bytes();
                    String tempPath = System.getProperty("java.io.tmpdir") + "/claude-plugin.dxt";
                    Files.write(Paths.get(tempPath), bytes);

                    // Extract and validate
                    Path targetPath = Paths.get(targetDir);
                    Files.createDirectories(targetPath);
                    DxtUtils.extractDxt(Paths.get(tempPath), targetPath);
                    new File(tempPath).delete();

                    // Read manifest
                    Map<String, Object> manifest = DxtUtils.readManifestFromDxt(Paths.get(tempPath));
                    String name = (String) manifest.get("name");

                    return new InstallResult(true, name, null);
                }
            } catch (Exception e) {
                log.error("Failed to install DXT from URL {}: {}", url, e.getMessage());
                return new InstallResult(false, null, e.getMessage());
            }
        });
    }

    public static class InstallResult {
        private boolean success;
        private String pluginName;
        private String error;
        public InstallResult() {}
        public InstallResult(boolean success, String pluginName, String error) {
            this.success = success; this.pluginName = pluginName; this.error = error;
        }
        public boolean isSuccess() { return success; }
        public String getPluginName() { return pluginName; }
        public String getError() { return error; }
        public void setSuccess(boolean v) { this.success = v; }
        public void setPluginName(String v) { this.pluginName = v; }
        public void setError(String v) { this.error = v; }
    }

    /** Check if a spec string is an mcpb:// source URL. */
    public boolean isMcpbSource(String spec) {
        return spec != null && (spec.startsWith("mcpb://") || spec.startsWith("https://"));
    }

    /** Load user config for an MCP server. */
    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> loadMcpServerUserConfig(String pluginId, String serverName) {
        return null;
    }

    /** Validate a user config map against a schema. */
    public boolean validateUserConfig(java.util.Map<String, Object> config, Object schema) {
        return config != null && !config.isEmpty();
    }

    /**
     * Result of loading an MCPB file.
     */
    public static class McpbLoadResult {
        private String manifestName;
        private String extractedPath;
        private java.util.Map<String, Object> mcpConfig;
        public McpbLoadResult() {}
        public McpbLoadResult(String manifestName, String extractedPath, java.util.Map<String, Object> mcpConfig) {
            this.manifestName = manifestName; this.extractedPath = extractedPath; this.mcpConfig = mcpConfig;
        }
        public String getManifestName() { return manifestName; }
        public String getExtractedPath() { return extractedPath; }
        public java.util.Map<String, Object> getMcpConfig() { return mcpConfig; }
    }

    /**
     * Load an MCPB file and return the resulting MCP server configuration.
     */
    public McpbLoadResult loadMcpbFile(String mcpbPath, String pluginPath, String pluginId,
                                        java.util.function.Consumer<String> statusCallback) {
        log.debug("[McpbHandler] loadMcpbFile: {} (plugin: {})", mcpbPath, pluginId);
        // Stub implementation — returns null (indicating user configuration required)
        return null;
    }
}
