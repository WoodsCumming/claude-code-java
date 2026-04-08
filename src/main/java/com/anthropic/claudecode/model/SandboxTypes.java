package com.anthropic.claudecode.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;
import java.util.Map;

/**
 * Sandbox configuration types.
 * Translated from src/entrypoints/sandboxTypes.ts
 *
 * This is the single source of truth for sandbox configuration types.
 * Both the SDK and the settings validation use these types.
 */
public class SandboxTypes {

    /**
     * Network configuration for sandbox.
     * Translated from SandboxNetworkConfigSchema in sandboxTypes.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SandboxNetworkConfig {
        private List<String> allowedDomains;
        /**
         * When true (and set in managed settings), only allowedDomains and
         * WebFetch allow rules from managed settings are respected.
         * User, project, local, and flag settings domains are ignored.
         */
        @JsonProperty("allowManagedDomainsOnly")
        private Boolean allowManagedDomainsOnly;
        /**
         * macOS only: Unix socket paths to allow.
         * Ignored on Linux (seccomp cannot filter by path).
         */
        @JsonProperty("allowUnixSockets")
        private List<String> allowUnixSockets;
        /**
         * If true, allow all Unix sockets (disables blocking on both platforms).
         */
        @JsonProperty("allowAllUnixSockets")
        private Boolean allowAllUnixSockets;
        /** Allow local port binding. */
        @JsonProperty("allowLocalBinding")
        private Boolean allowLocalBinding;
        /** HTTP proxy port for the sandbox. */
        private Integer httpProxyPort;
        /** SOCKS proxy port for the sandbox. */
        private Integer socksProxyPort;

        public List<String> getAllowedDomains() { return allowedDomains; }
        public void setAllowedDomains(List<String> v) { allowedDomains = v; }
        public boolean isAllowManagedDomainsOnly() { return allowManagedDomainsOnly; }
        public void setAllowManagedDomainsOnly(Boolean v) { allowManagedDomainsOnly = v; }
        public List<String> getAllowUnixSockets() { return allowUnixSockets; }
        public void setAllowUnixSockets(List<String> v) { allowUnixSockets = v; }
        public boolean isAllowAllUnixSockets() { return allowAllUnixSockets; }
        public void setAllowAllUnixSockets(Boolean v) { allowAllUnixSockets = v; }
        public boolean isAllowLocalBinding() { return allowLocalBinding; }
        public void setAllowLocalBinding(Boolean v) { allowLocalBinding = v; }
        public Integer getHttpProxyPort() { return httpProxyPort; }
        public void setHttpProxyPort(Integer v) { httpProxyPort = v; }
        public Integer getSocksProxyPort() { return socksProxyPort; }
        public void setSocksProxyPort(Integer v) { socksProxyPort = v; }
    }

    /**
     * Filesystem configuration for sandbox.
     * Translated from SandboxFilesystemConfigSchema in sandboxTypes.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SandboxFilesystemConfig {
        /**
         * Additional paths to allow writing within the sandbox.
         * Merged with paths from Edit(...) allow permission rules.
         */
        private List<String> allowWrite;
        /**
         * Additional paths to deny writing within the sandbox.
         * Merged with paths from Edit(...) deny permission rules.
         */
        private List<String> denyWrite;
        /**
         * Additional paths to deny reading within the sandbox.
         * Merged with paths from Read(...) deny permission rules.
         */
        private List<String> denyRead;
        /**
         * Paths to re-allow reading within denyRead regions.
         * Takes precedence over denyRead for matching paths.
         */
        private List<String> allowRead;
        /**
         * When true (set in managed settings), only allowRead paths from
         * policySettings are used.
         */
        @JsonProperty("allowManagedReadPathsOnly")
        private Boolean allowManagedReadPathsOnly;

        public List<String> getAllowWrite() { return allowWrite; }
        public void setAllowWrite(List<String> v) { allowWrite = v; }
        public List<String> getDenyWrite() { return denyWrite; }
        public void setDenyWrite(List<String> v) { denyWrite = v; }
        public List<String> getDenyRead() { return denyRead; }
        public void setDenyRead(List<String> v) { denyRead = v; }
        public List<String> getAllowRead() { return allowRead; }
        public void setAllowRead(List<String> v) { allowRead = v; }
        public boolean isAllowManagedReadPathsOnly() { return allowManagedReadPathsOnly; }
        public void setAllowManagedReadPathsOnly(Boolean v) { allowManagedReadPathsOnly = v; }
    }

    /**
     * Complete sandbox settings.
     * Translated from SandboxSettingsSchema in sandboxTypes.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SandboxSettings {
        /** Whether sandboxing is enabled. */
        private Boolean enabled;
        /**
         * Exit with an error at startup if sandbox.enabled is true but the sandbox
         * cannot start (missing dependencies, unsupported platform, or platform not
         * in enabledPlatforms). When false (default), a warning is shown and
         * commands run unsandboxed.
         */
        private Boolean failIfUnavailable;
        /**
         * Auto-approve Bash commands when the sandbox is active.
         */
        private Boolean autoAllowBashIfSandboxed;
        /**
         * Allow commands to run outside the sandbox via the dangerouslyDisableSandbox
         * parameter. When false, the dangerouslyDisableSandbox parameter is completely
         * ignored and all commands must run sandboxed. Default: true.
         */
        private Boolean allowUnsandboxedCommands;
        private SandboxNetworkConfig network;
        private SandboxFilesystemConfig filesystem;
        /**
         * Map of violation type to list of exempted patterns.
         * Translated from ignoreViolations: z.record(z.string(), z.array(z.string()))
         */
        private Map<String, List<String>> ignoreViolations;
        private Boolean enableWeakerNestedSandbox;
        /**
         * macOS only: Allow access to com.apple.trustd.agent in the sandbox.
         * Needed for Go-based CLI tools to verify TLS certificates when using
         * httpProxyPort with a MITM proxy and custom CA.
         * Reduces security — opens a potential data exfiltration vector. Default: false.
         */
        private Boolean enableWeakerNetworkIsolation;
        private List<String> excludedCommands;
        /** Custom ripgrep configuration for bundled ripgrep support. */
        private RipgrepConfig ripgrep;

        @Data
        @lombok.NoArgsConstructor
        @lombok.AllArgsConstructor
        public static class RipgrepConfig {
            private String command;
            private List<String> args;

        public String getCommand() { return command; }
        public void setCommand(String v) { command = v; }
        public List<String> getArgs() { return args; }
        public void setArgs(List<String> v) { args = v; }
        }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(Boolean v) { enabled = v; }
        public boolean isFailIfUnavailable() { return failIfUnavailable; }
        public void setFailIfUnavailable(Boolean v) { failIfUnavailable = v; }
        public boolean isAutoAllowBashIfSandboxed() { return autoAllowBashIfSandboxed; }
        public void setAutoAllowBashIfSandboxed(Boolean v) { autoAllowBashIfSandboxed = v; }
        public boolean isAllowUnsandboxedCommands() { return allowUnsandboxedCommands; }
        public void setAllowUnsandboxedCommands(Boolean v) { allowUnsandboxedCommands = v; }
        public SandboxNetworkConfig getNetwork() { return network; }
        public void setNetwork(SandboxNetworkConfig v) { network = v; }
        public SandboxFilesystemConfig getFilesystem() { return filesystem; }
        public void setFilesystem(SandboxFilesystemConfig v) { filesystem = v; }
        public Map<String, List<String>> getIgnoreViolations() { return ignoreViolations; }
        public void setIgnoreViolations(Map<String, List<String>> v) { ignoreViolations = v; }
        public boolean isEnableWeakerNestedSandbox() { return enableWeakerNestedSandbox; }
        public void setEnableWeakerNestedSandbox(Boolean v) { enableWeakerNestedSandbox = v; }
        public boolean isEnableWeakerNetworkIsolation() { return enableWeakerNetworkIsolation; }
        public void setEnableWeakerNetworkIsolation(Boolean v) { enableWeakerNetworkIsolation = v; }
        public List<String> getExcludedCommands() { return excludedCommands; }
        public void setExcludedCommands(List<String> v) { excludedCommands = v; }
        public RipgrepConfig getRipgrep() { return ripgrep; }
        public void setRipgrep(RipgrepConfig v) { ripgrep = v; }
    }

    /**
     * Ignore violations configuration (typed alias).
     * Translated from SandboxIgnoreViolations in sandboxTypes.ts
     */
    public static class SandboxIgnoreViolations extends java.util.LinkedHashMap<String, List<String>> {
        public SandboxIgnoreViolations() { super(); }
    }
}
