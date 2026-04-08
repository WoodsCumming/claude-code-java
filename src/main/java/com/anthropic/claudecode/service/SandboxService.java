package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import lombok.Data;

/**
 * Sandbox service for managing sandboxed execution environments.
 * Translated from src/utils/sandbox/sandbox-adapter.ts
 *
 * Provides an adapter between Claude Code's settings system and
 * the sandbox runtime for restricting filesystem and network access.
 */
@Slf4j
@Service
public class SandboxService {



    private boolean sandboxEnabled = false;
    private SandboxConfig currentConfig;

    /**
     * Check if sandbox mode is enabled.
     */
    public boolean isSandboxEnabled() {
        return sandboxEnabled;
    }

    /**
     * Enable sandbox with the given configuration.
     */
    public void enableSandbox(SandboxConfig config) {
        this.currentConfig = config;
        this.sandboxEnabled = true;
        log.info("Sandbox enabled with config: {}", config);
    }

    /**
     * Disable sandbox mode.
     */
    public void disableSandbox() {
        this.sandboxEnabled = false;
        this.currentConfig = null;
        log.info("Sandbox disabled");
    }

    /**
     * Get the current sandbox configuration.
     */
    public SandboxConfig getConfig() {
        return currentConfig;
    }

    /**
     * Check if a filesystem path is allowed by the sandbox.
     */
    public boolean isPathAllowed(String path, boolean isWrite) {
        if (!sandboxEnabled || currentConfig == null) return true;

        if (isWrite && currentConfig.getFsWriteRestrictions() != null) {
            return isPathInAllowlist(path, currentConfig.getFsWriteRestrictions());
        }
        if (!isWrite && currentConfig.getFsReadRestrictions() != null) {
            return isPathInAllowlist(path, currentConfig.getFsReadRestrictions());
        }
        return true;
    }

    /**
     * Check if a network host is allowed by the sandbox.
     */
    public boolean isNetworkHostAllowed(String host) {
        if (!sandboxEnabled || currentConfig == null) return true;
        if (currentConfig.getNetworkRestrictions() == null) return true;

        List<String> allowedHosts = currentConfig.getNetworkRestrictions();
        return allowedHosts.stream().anyMatch(pattern ->
            host.equals(pattern) || host.endsWith("." + pattern));
    }

    private boolean isPathInAllowlist(String path, List<String> allowlist) {
        return allowlist.stream().anyMatch(allowed ->
            path.startsWith(allowed));
    }

    @Data
    @lombok.Builder
    
    public static class SandboxConfig {
        private List<String> fsReadRestrictions;
        private List<String> fsWriteRestrictions;
        private List<String> networkRestrictions;
        private boolean ignoreViolations;
    
        public boolean isIgnoreViolations() { return ignoreViolations; }
        public void setIgnoreViolations(boolean v) { ignoreViolations = v; }
        public List<String> getFsReadRestrictions() { return fsReadRestrictions; }
        public void setFsReadRestrictions(List<String> v) { fsReadRestrictions = v; }
        public List<String> getFsWriteRestrictions() { return fsWriteRestrictions; }
        public void setFsWriteRestrictions(List<String> v) { fsWriteRestrictions = v; }
        public List<String> getNetworkRestrictions() { return networkRestrictions; }
        public void setNetworkRestrictions(List<String> v) { networkRestrictions = v; }
    }
}
