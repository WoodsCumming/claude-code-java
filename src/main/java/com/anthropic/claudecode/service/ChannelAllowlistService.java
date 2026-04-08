package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Approved channel plugins allowlist service.
 * Translated from src/services/mcp/channelAllowlist.ts
 *
 * --channels plugin:name@marketplace entries only register if {marketplace, plugin}
 * is on this list. server: entries always fail (schema is plugin-only). The
 * --dangerously-load-development-channels flag bypasses for both kinds.
 * Lives in GrowthBook so it can be updated without a release.
 *
 * Plugin-level granularity: if a plugin is approved, all its channel servers are.
 * Per-server gating was overengineering — a plugin that sprouts a malicious second
 * server is already compromised, and per-server entries would break on harmless
 * plugin refactors.
 */
@Slf4j
@Service
public class ChannelAllowlistService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChannelAllowlistService.class);


    /** An entry in the channel allowlist keyed on marketplace + plugin. */
    public static class ChannelAllowlistEntry {
        private String marketplace;
        private String plugin;
        private List<Object> channels;

        public ChannelAllowlistEntry() {}
        public ChannelAllowlistEntry(String marketplace, String plugin) {
            this.marketplace = marketplace; this.plugin = plugin;
        }
        public String getMarketplace() { return marketplace; }
        public void setMarketplace(String v) { marketplace = v; }
        public String getPlugin() { return plugin; }
        public void setPlugin(String v) { plugin = v; }
        public List<Object> getChannels() { return channels; }
        public void setChannels(List<Object> v) { channels = v; }
    }

    private final GrowthBookService growthBookService;

    @Autowired
    public ChannelAllowlistService(GrowthBookService growthBookService) {
        this.growthBookService = growthBookService;
    }

    /**
     * Return the current channel allowlist from GrowthBook feature "tengu_harbor_ledger".
     * Returns an empty list if the feature value cannot be parsed.
     */
    public List<ChannelAllowlistEntry> getChannelAllowlist() {
        try {
            @SuppressWarnings("unchecked")
            List<java.util.Map<String, String>> raw =
                    growthBookService.getFeatureValueCached("tengu_harbor_ledger", List.of());
            if (raw == null) return List.of();
            return raw.stream()
                    .filter(m -> m.containsKey("marketplace") && m.containsKey("plugin"))
                    .map(m -> new ChannelAllowlistEntry(m.get("marketplace"), m.get("plugin")))
                    .toList();
        } catch (Exception e) {
            log.debug("Failed to parse channel allowlist: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Overall channels on/off. Checked before any per-server gating —
     * when false, --channels is a no-op and no handlers register.
     * Default false; GrowthBook 5-min refresh.
     */
    public boolean isChannelsEnabled() {
        return growthBookService.getFeatureValueCached("tengu_harbor", false);
    }

    /**
     * Pure allowlist check keyed off the connection's pluginSource — for UI
     * pre-filtering so the IDE only shows "Enable channel?" for servers that will
     * actually pass the gate. Not a security boundary: channel_enable still runs
     * the full gate.
     *
     * Returns false for null pluginSource (non-plugin server — can never match the
     * {marketplace, plugin}-keyed ledger) and for sources without '@'
     * (builtin/inline — same reason).
     */
    public boolean isChannelAllowlisted(String pluginSource) {
        if (pluginSource == null || !pluginSource.contains("@")) return false;
        int atIdx = pluginSource.lastIndexOf('@');
        String name = pluginSource.substring(0, atIdx);
        String marketplace = pluginSource.substring(atIdx + 1);
        if (marketplace.isBlank()) return false;
        return getChannelAllowlist().stream()
                .anyMatch(e -> e.getPlugin().equals(name) && e.getMarketplace().equals(marketplace));
    }
}
