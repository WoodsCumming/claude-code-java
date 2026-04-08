package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.PluginDirectories;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Marketplace manager service.
 * Translated from src/utils/plugins/marketplaceManager.ts
 *
 * Manages plugin marketplace sources and caching.
 */
@Slf4j
@Service
public class MarketplaceManagerService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MarketplaceManagerService.class);


    private static final String KNOWN_MARKETPLACES_FILE = "known_marketplaces.json";
    private static final String MARKETPLACES_DIR = "marketplaces";

    private final ObjectMapper objectMapper;

    @Autowired
    public MarketplaceManagerService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Get a plugin by ID from the marketplace.
     * Translated from getPluginById() in marketplaceManager.ts
     */
    public Optional<Map<String, Object>> getPluginById(String pluginId) {
        // Simplified - would search all marketplaces
        return Optional.empty();
    }

    /**
     * Get the marketplace by name.
     * Translated from getMarketplace() in marketplaceManager.ts
     */
    public Optional<Map<String, Object>> getMarketplace(String marketplaceName) {
        return Optional.empty();
    }

    /**
     * Load known marketplaces config.
     * Translated from loadKnownMarketplacesConfig() in marketplaceManager.ts
     */
    public Map<String, Object> loadKnownMarketplacesConfig() {
        String path = PluginDirectories.getPluginsDir() + "/" + KNOWN_MARKETPLACES_FILE;
        File file = new File(path);

        if (!file.exists()) return Map.of();

        try {
            return objectMapper.readValue(file, Map.class);
        } catch (Exception e) {
            log.debug("Could not load known marketplaces: {}", e.getMessage());
            return Map.of();
        }
    }

    // -------------------------------------------------------------------------
    // Plugin marketplace entry
    // -------------------------------------------------------------------------

    /**
     * A single entry in a plugin marketplace.
     * Translated from PluginMarketplaceEntry in marketplaceManager.ts
     */
    public static class PluginMarketplaceEntry {
        private final String name;
        private final String description;
        private final Object lspServers;

        public PluginMarketplaceEntry(String name, String description, Object lspServers) {
            this.name = name;
            this.description = description;
            this.lspServers = lspServers;
        }

        public String getName() { return name; }
        public String getDescription() { return description; }
        public Object getLspServers() { return lspServers; }
    }

    /**
     * Get all plugin entries for the given marketplace.
     * Translated from getMarketplaceEntries() in marketplaceManager.ts
     */
    @SuppressWarnings("unchecked")
    public List<PluginMarketplaceEntry> getMarketplaceEntries(String marketplaceName) {
        List<PluginMarketplaceEntry> entries = new ArrayList<>();
        String marketplacesDir = PluginDirectories.getPluginsDir() + "/" + MARKETPLACES_DIR;
        File dir = new File(marketplacesDir, marketplaceName);
        if (!dir.isDirectory()) return entries;

        File indexFile = new File(dir, "index.json");
        if (!indexFile.exists()) return entries;

        try {
            Map<String, Object> data = objectMapper.readValue(indexFile, Map.class);
            Object plugins = data.get("plugins");
            if (plugins instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        Map<String, Object> pluginMap = (Map<String, Object>) m;
                        String name = pluginMap.get("name") instanceof String s ? s : null;
                        String desc = pluginMap.get("description") instanceof String d ? d : null;
                        Object lspServers = pluginMap.get("lspServers");
                        if (name != null) {
                            entries.add(new PluginMarketplaceEntry(name, desc, lspServers));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not load marketplace entries for {}: {}", marketplaceName, e.getMessage());
        }
        return entries;
    }

    /**
     * Return true if the given marketplace is the official Anthropic marketplace.
     * Translated from isOfficialMarketplace() in marketplaceManager.ts
     */
    public boolean isOfficialMarketplace(String marketplaceName) {
        return "claude-code-marketplace".equals(marketplaceName);
    }

    /**
     * Fetch marketplace from URL.
     */
    public CompletableFuture<Optional<Map<String, Object>>> fetchMarketplace(String url) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(10))
                    .build();

                HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    Map<String, Object> data = objectMapper.readValue(response.body(), Map.class);
                    return Optional.of(data);
                }

                return Optional.empty();

            } catch (Exception e) {
                log.debug("Could not fetch marketplace from {}: {}", url, e.getMessage());
                return Optional.empty();
            }
        });
    }

    // =========================================================================
    // Stub methods for marketplace diff/reconcile/cache operations
    // =========================================================================

    public java.util.List<String> getDeclaredMarketplaces() {
        return java.util.List.of();
    }

    public MarketplaceDiff diffMarketplaces(java.util.List<String> declared, Map<String, Object> materialized) {
        return new MarketplaceDiff(java.util.List.of(), java.util.List.of(), java.util.List.of());
    }

    public ReconcileResult reconcileMarketplaces(java.util.function.Consumer<ReconcileEvent> listener) {
        return new ReconcileResult(java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of());
    }

    public void clearMarketplacesCache() {
        log.debug("clearMarketplacesCache called");
    }

    /** Diff of declared vs materialized marketplaces. */
    public record MarketplaceDiff(
        java.util.List<String> missing,
        java.util.List<SourceChangedEntry> sourceChanged,
        java.util.List<String> removed) {}

    /** Entry in sourceChanged list. */
    public record SourceChangedEntry(String name, String newSource) {}

    /** Result of reconcileMarketplaces. */
    public record ReconcileResult(
        java.util.List<String> installed,
        java.util.List<String> updated,
        java.util.List<String> failed,
        java.util.List<String> upToDate) {}

    /** Event emitted during reconcile. */
    public record ReconcileEvent(String type, String name, String error) {}

    /** Result of finding a plugin in marketplaces. */
    public record PluginSearchResult(String name, String marketplace, String version, Object manifest) {}

    /** Find a plugin in all configured marketplaces. */
    public PluginSearchResult findPluginInMarketplaces(String pluginId) {
        log.debug("findPluginInMarketplaces: {}", pluginId);
        return null;
    }

    /** Find a plugin in settings. */
    public PluginSearchResult findPluginInSettings(String pluginId) {
        log.debug("findPluginInSettings: {}", pluginId);
        return null;
    }

    /** Resolve the latest version for a plugin. */
    public String resolveLatestVersion(PluginSearchResult found) {
        if (found == null) return null;
        return found.version();
    }
}
