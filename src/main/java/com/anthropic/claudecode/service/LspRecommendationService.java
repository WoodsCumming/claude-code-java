package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.GlobalConfig;
import com.anthropic.claudecode.util.BinaryCheck;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * LSP plugin recommendation service.
 * Translated from src/utils/plugins/lspRecommendation.ts
 *
 * Scans installed marketplaces for LSP plugins and recommends plugins based on
 * file extensions, but ONLY when the LSP binary is already installed on the system.
 *
 * Limitation: Can only detect LSP plugins that declare their servers inline in the
 * marketplace entry.  Plugins with separate .lsp.json files are not detectable
 * until after installation.
 */
@Slf4j
@Service
public class LspRecommendationService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LspRecommendationService.class);


    /** Maximum number of times a user can ignore recommendations before we stop showing them. */
    private static final int MAX_IGNORED_COUNT = 5;

    private final MarketplaceManagerService marketplaceManager;
    private final InstalledPluginsManagerService installedPluginsManager;
    private final GlobalConfigService globalConfigService;
    private final BinaryCheck binaryCheck;

    @Autowired
    public LspRecommendationService(
            MarketplaceManagerService marketplaceManager,
            InstalledPluginsManagerService installedPluginsManager,
            GlobalConfigService globalConfigService,
            BinaryCheck binaryCheck) {
        this.marketplaceManager = marketplaceManager;
        this.installedPluginsManager = installedPluginsManager;
        this.globalConfigService = globalConfigService;
        this.binaryCheck = binaryCheck;
    }

    // -------------------------------------------------------------------------
    // DTOs
    // -------------------------------------------------------------------------

    /**
     * LSP plugin recommendation returned to the caller.
     * Translated from LspPluginRecommendation in lspRecommendation.ts
     */
    public record LspPluginRecommendation(
        String pluginId,        // "plugin-name@marketplace-name"
        String pluginName,      // Human-readable plugin name
        String marketplaceName, // Marketplace name
        String description,     // Plugin description (may be null)
        boolean isOfficial,     // From official marketplace?
        List<String> extensions,// File extensions this plugin supports
        String command          // LSP server command (e.g. "typescript-language-server")
    ) {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Find matching LSP plugins for a file path.
     *
     * Returns recommendations for plugins that:
     *  1. Support the file's extension
     *  2. Have their LSP binary installed on the system
     *  3. Are not already installed
     *  4. Are not in the user's "never suggest" list
     *
     * Results are sorted with official marketplace plugins first.
     * Translated from getMatchingLspPlugins() in lspRecommendation.ts
     *
     * @param filePath path to the file to find LSP plugins for
     * @return list of matching plugin recommendations (empty if none or disabled)
     */
    public CompletableFuture<List<LspPluginRecommendation>> getMatchingLspPlugins(String filePath) {
        if (isLspRecommendationsDisabled()) {
            log.debug("[lspRecommendation] Recommendations are disabled");
            return CompletableFuture.completedFuture(List.of());
        }

        String ext = getExtension(filePath);
        if (ext.isEmpty()) {
            log.debug("[lspRecommendation] No file extension found");
            return CompletableFuture.completedFuture(List.of());
        }

        log.debug("[lspRecommendation] Looking for LSP plugins for {}", ext);

        return getLspPluginsFromMarketplaces().thenCompose(allLspPlugins -> {
            GlobalConfig config = globalConfigService.getGlobalConfig();
            List<String> neverPlugins = config.getLspRecommendationNeverPlugins() != null
                ? config.getLspRecommendationNeverPlugins() : List.of();

            List<Map.Entry<String, LspPluginInfo>> matchingPlugins = new ArrayList<>();
            for (Map.Entry<String, LspPluginInfo> entry : allLspPlugins.entrySet()) {
                String pluginId = entry.getKey();
                LspPluginInfo info = entry.getValue();

                if (!info.extensions().contains(ext)) continue;
                if (neverPlugins.contains(pluginId)) {
                    log.debug("[lspRecommendation] Skipping {} (in never suggest list)", pluginId);
                    continue;
                }
                if (installedPluginsManager.isPluginInstalled(pluginId)) {
                    log.debug("[lspRecommendation] Skipping {} (already installed)", pluginId);
                    continue;
                }
                matchingPlugins.add(entry);
            }

            // Check binary availability asynchronously
            List<CompletableFuture<Optional<Map.Entry<String, LspPluginInfo>>>> binaryChecks =
                new ArrayList<>();

            for (Map.Entry<String, LspPluginInfo> entry : matchingPlugins) {
                binaryChecks.add(
                    binaryCheck.isBinaryInstalled(entry.getValue().command())
                        .thenApply(exists -> {
                            if (exists) {
                                log.debug("[lspRecommendation] Binary '{}' found for {}",
                                    entry.getValue().command(), entry.getKey());
                                return Optional.of(entry);
                            }
                            log.debug("[lspRecommendation] Skipping {} (binary '{}' not found)",
                                entry.getKey(), entry.getValue().command());
                            return Optional.<Map.Entry<String, LspPluginInfo>>empty();
                        })
                );
            }

            return CompletableFuture.allOf(binaryChecks.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<Map.Entry<String, LspPluginInfo>> pluginsWithBinary = new ArrayList<>();
                    for (var f : binaryChecks) {
                        f.join().ifPresent(pluginsWithBinary::add);
                    }

                    // Sort: official marketplaces first
                    pluginsWithBinary.sort((a, b) -> {
                        if (a.getValue().isOfficial() && !b.getValue().isOfficial()) return -1;
                        if (!a.getValue().isOfficial() && b.getValue().isOfficial()) return 1;
                        return 0;
                    });

                    return pluginsWithBinary.stream()
                        .map(e -> new LspPluginRecommendation(
                            e.getKey(),
                            e.getValue().entry().getName(),
                            e.getValue().marketplaceName(),
                            e.getValue().entry().getDescription(),
                            e.getValue().isOfficial(),
                            new ArrayList<>(e.getValue().extensions()),
                            e.getValue().command()
                        ))
                        .toList();
                });
        });
    }

    /**
     * Add a plugin to the "never suggest" list.
     * Translated from addToNeverSuggest() in lspRecommendation.ts
     */
    public void addToNeverSuggest(String pluginId) {
        globalConfigService.updateGlobalConfig(currentConfig -> {
            List<String> current = currentConfig.getLspRecommendationNeverPlugins() != null
                ? new ArrayList<>(currentConfig.getLspRecommendationNeverPlugins())
                : new ArrayList<>();
            if (!current.contains(pluginId)) {
                current.add(pluginId);
                currentConfig.setLspRecommendationNeverPlugins(current);
            }
            return currentConfig;
        });
        log.debug("[lspRecommendation] Added {} to never suggest", pluginId);
    }

    /**
     * Increment the ignored recommendation count.
     * After MAX_IGNORED_COUNT ignores, recommendations are disabled.
     * Translated from incrementIgnoredCount() in lspRecommendation.ts
     */
    public void incrementIgnoredCount() {
        globalConfigService.updateGlobalConfig(currentConfig -> {
            int current = currentConfig.getLspRecommendationIgnoredCount() != null
                ? currentConfig.getLspRecommendationIgnoredCount() : 0;
            currentConfig.setLspRecommendationIgnoredCount(current + 1);
            return currentConfig;
        });
        log.debug("[lspRecommendation] Incremented ignored count");
    }

    /**
     * Check if LSP recommendations are disabled.
     * Disabled when:
     *  - User explicitly disabled via config
     *  - User has ignored MAX_IGNORED_COUNT recommendations
     * Translated from isLspRecommendationsDisabled() in lspRecommendation.ts
     */
    public boolean isLspRecommendationsDisabled() {
        GlobalConfig config = globalConfigService.getGlobalConfig();
        return Boolean.TRUE.equals(config.getLspRecommendationDisabled())
            || (config.getLspRecommendationIgnoredCount() != null
                && config.getLspRecommendationIgnoredCount() >= MAX_IGNORED_COUNT);
    }

    /**
     * Reset the ignored count (useful if user re-enables recommendations).
     * Translated from resetIgnoredCount() in lspRecommendation.ts
     */
    public void resetIgnoredCount() {
        globalConfigService.updateGlobalConfig(currentConfig -> {
            Integer currentCount = currentConfig.getLspRecommendationIgnoredCount();
            if (currentCount != null && currentCount > 0) {
                currentConfig.setLspRecommendationIgnoredCount(0);
            }
            return currentConfig;
        });
        log.debug("[lspRecommendation] Reset ignored count");
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Get all LSP plugins from all installed marketplaces.
     * Translated from getLspPluginsFromMarketplaces() in lspRecommendation.ts
     */
    private CompletableFuture<Map<String, LspPluginInfo>> getLspPluginsFromMarketplaces() {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, LspPluginInfo> result = new LinkedHashMap<>();
            try {
                Map<String, Object> marketplacesConfig =
                    marketplaceManager.loadKnownMarketplacesConfig();

                for (String marketplaceName : marketplacesConfig.keySet()) {
                    try {
                        List<MarketplaceManagerService.PluginMarketplaceEntry> entries =
                            marketplaceManager.getMarketplaceEntries(marketplaceName);
                        boolean isOfficial = marketplaceManager.isOfficialMarketplace(marketplaceName);

                        for (MarketplaceManagerService.PluginMarketplaceEntry entry : entries) {
                            if (entry.getLspServers() == null) continue;
                            LspInfo lspInfo = extractLspInfoFromManifest(entry.getLspServers());
                            if (lspInfo == null) continue;
                            String pluginId = entry.getName() + "@" + marketplaceName;
                            result.put(pluginId, new LspPluginInfo(
                                entry, marketplaceName, lspInfo.extensions(), lspInfo.command(), isOfficial));
                        }
                    } catch (Exception e) {
                        log.debug("[lspRecommendation] Failed to load marketplace {}: {}",
                            marketplaceName, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.debug("[lspRecommendation] Failed to load marketplaces config: {}", e.getMessage());
            }
            return result;
        });
    }

    /**
     * Extract LSP info (extensions and command) from inline lspServers config.
     * NOTE: Can only read inline configs, not external .lsp.json files.
     * Translated from extractLspInfoFromManifest() in lspRecommendation.ts
     */
    @SuppressWarnings("unchecked")
    private LspInfo extractLspInfoFromManifest(Object lspServers) {
        if (lspServers == null) return null;

        // String path — not readable from marketplace
        if (lspServers instanceof String) {
            log.debug("[lspRecommendation] Skipping string path lspServers");
            return null;
        }

        if (lspServers instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String) continue; // skip string paths in arrays
                if (item instanceof Map<?, ?> m) {
                    LspInfo info = extractFromServerConfigRecord((Map<String, Object>) m);
                    if (info != null) return info;
                }
            }
            return null;
        }

        if (lspServers instanceof Map<?, ?> m) {
            return extractFromServerConfigRecord((Map<String, Object>) m);
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private LspInfo extractFromServerConfigRecord(Map<String, Object> serverConfigs) {
        Set<String> extensions = new LinkedHashSet<>();
        String command = null;

        for (Map.Entry<String, Object> entry : serverConfigs.entrySet()) {
            Object config = entry.getValue();
            if (!(config instanceof Map<?, ?> cfgMap)) continue;
            Map<String, Object> cfg = (Map<String, Object>) cfgMap;

            if (command == null && cfg.get("command") instanceof String s) {
                command = s;
            }

            Object extMapping = cfg.get("extensionToLanguage");
            if (extMapping instanceof Map<?, ?> m) {
                for (Object key : m.keySet()) {
                    if (key instanceof String ext) {
                        extensions.add(ext.toLowerCase());
                    }
                }
            }
        }

        if (command == null || extensions.isEmpty()) return null;
        return new LspInfo(extensions, command);
    }

    private static String getExtension(String filePath) {
        if (filePath == null) return "";
        String name = Paths.get(filePath).getFileName() != null
            ? Paths.get(filePath).getFileName().toString() : filePath;
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot).toLowerCase() : "";
    }

    // -------------------------------------------------------------------------
    // Internal types
    // -------------------------------------------------------------------------

    private record LspInfo(Set<String> extensions, String command) {}

    private record LspPluginInfo(
        MarketplaceManagerService.PluginMarketplaceEntry entry,
        String marketplaceName,
        Set<String> extensions,
        String command,
        boolean isOfficial
    ) {}
}
