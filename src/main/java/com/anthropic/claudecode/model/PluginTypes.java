package com.anthropic.claudecode.model;

import lombok.Builder;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Plugin-related types.
 * Translated from src/types/plugin.ts
 *
 * Covers:
 *   - BuiltinPluginDefinition  — a built-in plugin that ships with the CLI
 *   - PluginRepository         — repository metadata for a marketplace plugin
 *   - PluginConfig             — per-project plugin configuration
 *   - LoadedPlugin             — a fully-loaded plugin instance
 *   - PluginComponent          — component kind enum
 *   - PluginError              — sealed discriminated-union of error types
 *   - PluginLoadResult         — enabled/disabled/errors from a load pass
 *   - getPluginErrorMessage()  — display-message helper
 */
public final class PluginTypes {

    // =========================================================================
    // BuiltinPluginDefinition
    // =========================================================================

    /**
     * Definition for a built-in plugin that ships with the CLI.
     * Corresponds to TypeScript: type BuiltinPluginDefinition
     */
    @Data
    @lombok.Builder
    
    public static class BuiltinPluginDefinition {
        /** Plugin name (used in the {@code {name}@builtin} identifier). */
        private String name;
        /** Description shown in the /plugin UI. */
        private String description;
        /** Optional version string. */
        private String version;
        /**
         * Whether this plugin is available based on system capabilities.
         * Unavailable plugins are hidden entirely.
         */
        private BooleanSupplier isAvailable;
        /** Default enabled state before the user sets a preference (defaults to true). */
        private Boolean defaultEnabled;
        // skills, hooks, mcpServers omitted — represented in full model separately
    }

    // =========================================================================
    // PluginRepository
    // =========================================================================

    /**
     * Repository metadata for a marketplace-sourced plugin.
     * Corresponds to TypeScript: type PluginRepository
     */
    @Data
    @lombok.Builder
    
    public static class PluginRepository {
        private String url;
        private String branch;
        private String lastUpdated;  // optional ISO timestamp
        private String commitSha;    // optional
    }

    // =========================================================================
    // PluginConfig
    // =========================================================================

    /**
     * Per-project plugin configuration (stored in settings).
     * Corresponds to TypeScript: type PluginConfig
     */
    @Data
    @lombok.Builder
    
    public static class PluginConfig {
        /** Repository identifier → PluginRepository mapping. */
        private Map<String, PluginRepository> repositories;
    }

    // =========================================================================
    // PluginManifest (inline representation)
    // =========================================================================

    /**
     * Plugin manifest — the parsed contents of a plugin's manifest file.
     * Corresponds to TypeScript: type PluginManifest (from utils/plugins/schemas.ts)
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PluginManifest {
        private String name;
        private String description;
        private String version;
        /** MCP servers specification - can be String path, List, or Map. */
        private Object mcpServers;
        /** Channel definitions. */
        private java.util.List<Object> channels;
        /** LSP servers. */
        private java.util.Map<String, Object> lspServers;
        /** Dependencies. */
        private Object dependencies;
        /** User configuration schema. */
        private java.util.Map<String, Object> userConfig;
        /** Agent definitions. */
        private java.util.List<Object> agents;

        public String getName() { return name; }
        public void setName(String v) { name = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
        public String getVersion() { return version; }
        public void setVersion(String v) { version = v; }
        public Object getMcpServers() { return mcpServers; }
        public void setMcpServers(Object v) { mcpServers = v; }
        public Object getDependencies() { return dependencies; }
        public void setDependencies(Object v) { dependencies = v; }
        public java.util.List<Object> getChannels() { return channels; }
        public void setChannels(java.util.List<Object> v) { channels = v; }
        public java.util.Map<String, Object> getLspServers() { return lspServers; }
        public void setLspServers(java.util.Map<String, Object> v) { lspServers = v; }
        public java.util.Map<String, Object> getUserConfig() { return userConfig; }
        public void setUserConfig(java.util.Map<String, Object> v) { userConfig = v; }
        public java.util.List<Object> getAgents() { return agents; }
        public void setAgents(java.util.List<Object> v) { agents = v; }

        public static PluginManifestBuilder builder() { return new PluginManifestBuilder(); }

        public static class PluginManifestBuilder {
            private final PluginManifest m = new PluginManifest();
            public PluginManifestBuilder name(String v) { m.name = v; return this; }
            public PluginManifestBuilder description(String v) { m.description = v; return this; }
            public PluginManifestBuilder version(String v) { m.version = v; return this; }
            public PluginManifestBuilder mcpServers(Object v) { m.mcpServers = v; return this; }
            public PluginManifestBuilder dependencies(Object v) { m.dependencies = v; return this; }
            public PluginManifestBuilder channels(java.util.List<Object> v) { m.channels = v; return this; }
            public PluginManifestBuilder lspServers(java.util.Map<String, Object> v) { m.lspServers = v; return this; }
            public PluginManifestBuilder userConfig(java.util.Map<String, Object> v) { m.userConfig = v; return this; }
            public PluginManifestBuilder agents(java.util.List<Object> v) { m.agents = v; return this; }
            public PluginManifest build() { return m; }
        }
    }

    // =========================================================================
    // LoadedPlugin
    // =========================================================================

    /**
     * A fully-loaded plugin instance, ready for use by the CLI.
     * Corresponds to TypeScript: type LoadedPlugin
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class LoadedPlugin {
        private String name;
        private PluginManifest manifest;
        private String path;
        /** Plugin identifier string, e.g. {@code my-plugin@builtin}. */
        private String source;
        /** Repository identifier, usually same as source. */
        private String repository;
        private Boolean enabled;
        /** True for built-in plugins that ship with the CLI. */
        private Boolean isBuiltin;
        /** Git commit SHA for version pinning. */
        private String sha;
        private String commandsPath;
        private List<String> commandsPaths;
        /** Metadata for named commands from object-mapping format. */
        private Map<String, Object> commandsMetadata;
        private String agentsPath;
        private List<String> agentsPaths;
        private String skillsPath;
        private List<String> skillsPaths;
        private String outputStylesPath;
        private List<String> outputStylesPaths;
        private Object hooksConfig;         // HooksSettings
        private Map<String, Object> mcpServers;
        private Map<String, Object> lspServers;
        private Map<String, Object> settings;
        /** Convenience field for version (mirrors PluginManifest.version). */
        private String version;
        /** Convenience field for description (mirrors PluginManifest.description). */
        private String description;
        /** List of plugin IDs this plugin depends on. */
        private java.util.List<String> dependencies;
        /** Whether plugin needs a refresh. */
        private Boolean needsRefresh;

        /** Check if plugin is enabled (null = enabled by default). */
        public boolean isEnabled() {
            return !Boolean.FALSE.equals(enabled);
        }
    
        public String getName() { return name; }
        public void setName(String v) { name = v; }
        public PluginManifest getManifest() { return manifest; }
        public void setManifest(PluginManifest v) { manifest = v; }
        public String getPath() { return path; }
        public void setPath(String v) { path = v; }
        public String getSource() { return source; }
        public void setSource(String v) { source = v; }
        public String getRepository() { return repository; }
        public void setRepository(String v) { repository = v; }
        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean v) { enabled = v; }
        public Boolean getIsBuiltin() { return isBuiltin; }
        public void setIsBuiltin(Boolean v) { isBuiltin = v; }
        public String getSha() { return sha; }
        public void setSha(String v) { sha = v; }
        public String getCommandsPath() { return commandsPath; }
        public String getAgentsPath() { return agentsPath; }
        public void setAgentsPath(String v) { agentsPath = v; }
        public String getVersion() { return version; }
        public void setVersion(String v) { version = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
        public java.util.List<String> getDependencies() { return dependencies; }
        public void setDependencies(java.util.List<String> v) { dependencies = v; }

        public static LoadedPluginBuilder builder() { return new LoadedPluginBuilder(); }
        public static class LoadedPluginBuilder {
            private String name;
            private PluginManifest manifest;
            private String path;
            private String source;
            private String repository;
            private Boolean enabled;
            private Boolean isBuiltin;
            private String sha;
            private String commandsPath;
            private List<String> commandsPaths;
            private Map<String, Object> commandsMetadata;
            private String agentsPath;
            private List<String> agentsPaths;
            private String skillsPath;
            private List<String> skillsPaths;
            private String outputStylesPath;
            private List<String> outputStylesPaths;
            private Object hooksConfig;
            private Map<String, Object> mcpServers;
            private Map<String, Object> lspServers;
            private Map<String, Object> settings;
            private String version;
            private String description;
            private Boolean needsRefresh;
            public LoadedPluginBuilder name(String v) { this.name = v; return this; }
            public LoadedPluginBuilder manifest(PluginManifest v) { this.manifest = v; return this; }
            public LoadedPluginBuilder path(String v) { this.path = v; return this; }
            public LoadedPluginBuilder source(String v) { this.source = v; return this; }
            public LoadedPluginBuilder repository(String v) { this.repository = v; return this; }
            public LoadedPluginBuilder enabled(Boolean v) { this.enabled = v; return this; }
            public LoadedPluginBuilder isBuiltin(Boolean v) { this.isBuiltin = v; return this; }
            public LoadedPluginBuilder sha(String v) { this.sha = v; return this; }
            public LoadedPluginBuilder commandsPath(String v) { this.commandsPath = v; return this; }
            public LoadedPluginBuilder commandsPaths(List<String> v) { this.commandsPaths = v; return this; }
            public LoadedPluginBuilder commandsMetadata(Map<String, Object> v) { this.commandsMetadata = v; return this; }
            public LoadedPluginBuilder agentsPath(String v) { this.agentsPath = v; return this; }
            public LoadedPluginBuilder agentsPaths(List<String> v) { this.agentsPaths = v; return this; }
            public LoadedPluginBuilder skillsPath(String v) { this.skillsPath = v; return this; }
            public LoadedPluginBuilder skillsPaths(List<String> v) { this.skillsPaths = v; return this; }
            public LoadedPluginBuilder outputStylesPath(String v) { this.outputStylesPath = v; return this; }
            public LoadedPluginBuilder outputStylesPaths(List<String> v) { this.outputStylesPaths = v; return this; }
            public LoadedPluginBuilder hooksConfig(Object v) { this.hooksConfig = v; return this; }
            public LoadedPluginBuilder mcpServers(Map<String, Object> v) { this.mcpServers = v; return this; }
            public LoadedPluginBuilder lspServers(Map<String, Object> v) { this.lspServers = v; return this; }
            public LoadedPluginBuilder settings(Map<String, Object> v) { this.settings = v; return this; }
            public LoadedPluginBuilder version(String v) { this.version = v; return this; }
            public LoadedPluginBuilder description(String v) { this.description = v; return this; }
            public LoadedPluginBuilder needsRefresh(Boolean v) { this.needsRefresh = v; return this; }
            public LoadedPlugin build() {
                LoadedPlugin o = new LoadedPlugin();
                o.name = name;
                o.manifest = manifest;
                o.path = path;
                o.source = source;
                o.repository = repository;
                o.enabled = enabled;
                o.isBuiltin = isBuiltin;
                o.sha = sha;
                o.commandsPath = commandsPath;
                o.commandsPaths = commandsPaths;
                o.commandsMetadata = commandsMetadata;
                o.agentsPath = agentsPath;
                o.agentsPaths = agentsPaths;
                o.skillsPath = skillsPath;
                o.skillsPaths = skillsPaths;
                o.outputStylesPath = outputStylesPath;
                o.outputStylesPaths = outputStylesPaths;
                o.hooksConfig = hooksConfig;
                o.mcpServers = mcpServers;
                o.lspServers = lspServers;
                o.settings = settings;
                o.version = version;
                o.description = description;
                o.needsRefresh = needsRefresh;
                return o;
            }
        }
    
        public Map<String, Object> getMcpServers() { return mcpServers; }
    
        public String getOutputStylesPath() { return outputStylesPath; }
    
        public List<String> getOutputStylesPaths() { return outputStylesPaths; }
    
        public void setMcpServers(Map<String, Object> v) { mcpServers = v; }
    }

    // =========================================================================
    // PluginComponent
    // =========================================================================

    /**
     * Component kind within a plugin.
     * Corresponds to TypeScript: type PluginComponent
     */
    public enum PluginComponent {
        COMMANDS("commands"),
        AGENTS("agents"),
        SKILLS("skills"),
        HOOKS("hooks"),
        OUTPUT_STYLES("output-styles");

        private final String value;

        PluginComponent(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    // =========================================================================
    // PluginError — sealed discriminated union
    // =========================================================================

    /**
     * Discriminated union of plugin error types.
     * Corresponds to TypeScript: type PluginError
     *
     * Each record is a separate concrete type; pattern-matching with
     * {@code instanceof} or a switch expression mirrors the TypeScript
     * {@code switch (error.type)} pattern.
     */
    public sealed interface PluginError permits
            PluginTypes.PathNotFoundError,
            PluginTypes.GitAuthFailedError,
            PluginTypes.GitTimeoutError,
            PluginTypes.NetworkError,
            PluginTypes.ManifestParseError,
            PluginTypes.ManifestValidationError,
            PluginTypes.PluginNotFoundError,
            PluginTypes.MarketplaceNotFoundError,
            PluginTypes.MarketplaceLoadFailedError,
            PluginTypes.McpConfigInvalidError,
            PluginTypes.McpServerSuppressedDuplicateError,
            PluginTypes.LspConfigInvalidError,
            PluginTypes.HookLoadFailedError,
            PluginTypes.ComponentLoadFailedError,
            PluginTypes.McpbDownloadFailedError,
            PluginTypes.McpbExtractFailedError,
            PluginTypes.McpbInvalidManifestError,
            PluginTypes.LspServerStartFailedError,
            PluginTypes.LspServerCrashedError,
            PluginTypes.LspRequestTimeoutError,
            PluginTypes.LspRequestFailedError,
            PluginTypes.MarketplaceBlockedByPolicyError,
            PluginTypes.DependencyUnsatisfiedError,
            PluginTypes.PluginCacheMissError,
            PluginTypes.GenericError {

        String type();
        String source();
    }

    public record PathNotFoundError(String source, String plugin, String path,
                                    PluginComponent component) implements PluginError {
        public String type() { return "path-not-found"; }
    }

    public enum GitAuthType { SSH("ssh"), HTTPS("https");
        private final String v; GitAuthType(String v){this.v=v;} public String getValue(){return v;}
    }

    public record GitAuthFailedError(String source, String plugin, String gitUrl,
                                     GitAuthType authType) implements PluginError {
        public String type() { return "git-auth-failed"; }
    }

    public enum GitOperation { CLONE, PULL }

    public record GitTimeoutError(String source, String plugin, String gitUrl,
                                  GitOperation operation) implements PluginError {
        public String type() { return "git-timeout"; }
    }

    public record NetworkError(String source, String plugin, String url,
                               String details) implements PluginError {
        public String type() { return "network-error"; }
    }

    public record ManifestParseError(String source, String plugin, String manifestPath,
                                     String parseError) implements PluginError {
        public String type() { return "manifest-parse-error"; }
    }

    public record ManifestValidationError(String source, String plugin, String manifestPath,
                                          List<String> validationErrors) implements PluginError {
        public String type() { return "manifest-validation-error"; }
    }

    public record PluginNotFoundError(String source, String pluginId,
                                      String marketplace) implements PluginError {
        public String type() { return "plugin-not-found"; }
    }

    public record MarketplaceNotFoundError(String source, String marketplace,
                                           List<String> availableMarketplaces) implements PluginError {
        public String type() { return "marketplace-not-found"; }
    }

    public record MarketplaceLoadFailedError(String source, String marketplace,
                                             String reason) implements PluginError {
        public String type() { return "marketplace-load-failed"; }
    }

    public record McpConfigInvalidError(String source, String plugin, String serverName,
                                        String validationError) implements PluginError {
        public String type() { return "mcp-config-invalid"; }
    }

    public record McpServerSuppressedDuplicateError(String source, String plugin,
                                                    String serverName,
                                                    String duplicateOf) implements PluginError {
        public String type() { return "mcp-server-suppressed-duplicate"; }
    }

    public record LspConfigInvalidError(String source, String plugin, String serverName,
                                        String validationError) implements PluginError {
        public String type() { return "lsp-config-invalid"; }
    }

    public record HookLoadFailedError(String source, String plugin, String hookPath,
                                      String reason) implements PluginError {
        public String type() { return "hook-load-failed"; }
    }

    public record ComponentLoadFailedError(String source, String plugin,
                                           PluginComponent component,
                                           String path, String reason) implements PluginError {
        public String type() { return "component-load-failed"; }
    }

    public record McpbDownloadFailedError(String source, String plugin, String url,
                                          String reason) implements PluginError {
        public String type() { return "mcpb-download-failed"; }
    }

    public record McpbExtractFailedError(String source, String plugin, String mcpbPath,
                                         String reason) implements PluginError {
        public String type() { return "mcpb-extract-failed"; }
    }

    public record McpbInvalidManifestError(String source, String plugin, String mcpbPath,
                                           String validationError) implements PluginError {
        public String type() { return "mcpb-invalid-manifest"; }
    }

    public record LspServerStartFailedError(String source, String plugin, String serverName,
                                            String reason) implements PluginError {
        public String type() { return "lsp-server-start-failed"; }
    }

    public record LspServerCrashedError(String source, String plugin, String serverName,
                                        Integer exitCode, String signal) implements PluginError {
        public String type() { return "lsp-server-crashed"; }
    }

    public record LspRequestTimeoutError(String source, String plugin, String serverName,
                                         String method, long timeoutMs) implements PluginError {
        public String type() { return "lsp-request-timeout"; }
    }

    public record LspRequestFailedError(String source, String plugin, String serverName,
                                        String method, String error) implements PluginError {
        public String type() { return "lsp-request-failed"; }
    }

    public record MarketplaceBlockedByPolicyError(String source, String plugin,
                                                  String marketplace,
                                                  Boolean blockedByBlocklist,
                                                  List<String> allowedSources) implements PluginError {
        public String type() { return "marketplace-blocked-by-policy"; }
    }

    public enum DependencyUnsatisfiedReason {
        NOT_ENABLED("not-enabled"), NOT_FOUND("not-found");
        private final String v; DependencyUnsatisfiedReason(String v){this.v=v;}
    }

    public record DependencyUnsatisfiedError(String source, String plugin, String dependency,
                                             DependencyUnsatisfiedReason reason) implements PluginError {
        public String type() { return "dependency-unsatisfied"; }
    }

    public record PluginCacheMissError(String source, String plugin,
                                       String installPath) implements PluginError {
        public String type() { return "plugin-cache-miss"; }
    }

    public record GenericError(String source, String plugin, String error) implements PluginError {
        public String type() { return "generic-error"; }
    }

    // =========================================================================
    // PluginLoadResult
    // =========================================================================

    /**
     * Result of a plugin load pass.
     * Corresponds to TypeScript: type PluginLoadResult
     */
    public record PluginLoadResult(
            List<LoadedPlugin> enabled,
            List<LoadedPlugin> disabled,
            List<PluginError> errors
    ) {}

    // =========================================================================
    // getPluginErrorMessage
    // =========================================================================

    /**
     * Get a human-readable display message for any PluginError.
     * Corresponds to TypeScript: function getPluginErrorMessage(error): string
     */
    public static String getPluginErrorMessage(PluginError error) {
        return switch (error) {
            case GenericError e -> e.error();
            case PathNotFoundError e ->
                    "Path not found: " + e.path() + " (" + e.component().getValue() + ")";
            case GitAuthFailedError e ->
                    "Git authentication failed (" + e.authType().getValue() + "): " + e.gitUrl();
            case GitTimeoutError e ->
                    "Git " + e.operation().name().toLowerCase(Locale.ROOT) + " timeout: " + e.gitUrl();
            case NetworkError e ->
                    "Network error: " + e.url() + (e.details() != null ? " - " + e.details() : "");
            case ManifestParseError e ->
                    "Manifest parse error: " + e.parseError();
            case ManifestValidationError e ->
                    "Manifest validation failed: " + String.join(", ", e.validationErrors());
            case PluginNotFoundError e ->
                    "Plugin " + e.pluginId() + " not found in marketplace " + e.marketplace();
            case MarketplaceNotFoundError e ->
                    "Marketplace " + e.marketplace() + " not found";
            case MarketplaceLoadFailedError e ->
                    "Marketplace " + e.marketplace() + " failed to load: " + e.reason();
            case McpConfigInvalidError e ->
                    "MCP server " + e.serverName() + " invalid: " + e.validationError();
            case McpServerSuppressedDuplicateError e -> {
                String dup = e.duplicateOf().startsWith("plugin:")
                        ? "server provided by plugin \"" +
                          e.duplicateOf().substring("plugin:".length()) + "\""
                        : "already-configured \"" + e.duplicateOf() + "\"";
                yield "MCP server \"" + e.serverName() + "\" skipped — same command/URL as " + dup;
            }
            case HookLoadFailedError e -> "Hook load failed: " + e.reason();
            case ComponentLoadFailedError e ->
                    e.component().getValue() + " load failed from " + e.path() + ": " + e.reason();
            case McpbDownloadFailedError e ->
                    "Failed to download MCPB from " + e.url() + ": " + e.reason();
            case McpbExtractFailedError e ->
                    "Failed to extract MCPB " + e.mcpbPath() + ": " + e.reason();
            case McpbInvalidManifestError e ->
                    "MCPB manifest invalid at " + e.mcpbPath() + ": " + e.validationError();
            case LspConfigInvalidError e ->
                    "Plugin \"" + e.plugin() + "\" has invalid LSP server config for \"" +
                    e.serverName() + "\": " + e.validationError();
            case LspServerStartFailedError e ->
                    "Plugin \"" + e.plugin() + "\" failed to start LSP server \"" +
                    e.serverName() + "\": " + e.reason();
            case LspServerCrashedError e -> e.signal() != null
                    ? "Plugin \"" + e.plugin() + "\" LSP server \"" + e.serverName() +
                      "\" crashed with signal " + e.signal()
                    : "Plugin \"" + e.plugin() + "\" LSP server \"" + e.serverName() +
                      "\" crashed with exit code " + (e.exitCode() != null ? e.exitCode() : "unknown");
            case LspRequestTimeoutError e ->
                    "Plugin \"" + e.plugin() + "\" LSP server \"" + e.serverName() +
                    "\" timed out on " + e.method() + " request after " + e.timeoutMs() + "ms";
            case LspRequestFailedError e ->
                    "Plugin \"" + e.plugin() + "\" LSP server \"" + e.serverName() +
                    "\" " + e.method() + " request failed: " + e.error();
            case MarketplaceBlockedByPolicyError e -> Boolean.TRUE.equals(e.blockedByBlocklist())
                    ? "Marketplace '" + e.marketplace() + "' is blocked by enterprise policy"
                    : "Marketplace '" + e.marketplace() + "' is not in the allowed marketplace list";
            case DependencyUnsatisfiedError e -> {
                String hint = e.reason() == DependencyUnsatisfiedReason.NOT_ENABLED
                        ? "disabled — enable it or remove the dependency"
                        : "not found in any configured marketplace";
                yield "Dependency \"" + e.dependency() + "\" is " + hint;
            }
            case PluginCacheMissError e ->
                    "Plugin \"" + e.plugin() + "\" not cached at " + e.installPath() +
                    " — run /plugins to refresh";
        };
    }

    private PluginTypes() {}
}
