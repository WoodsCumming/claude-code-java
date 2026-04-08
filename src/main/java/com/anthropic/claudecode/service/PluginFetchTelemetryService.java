package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Plugin fetch telemetry service.
 * Translated from src/utils/plugins/fetchTelemetry.ts
 *
 * Tracks plugin/marketplace network fetches for telemetry.
 */
@Slf4j
@Service
public class PluginFetchTelemetryService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PluginFetchTelemetryService.class);


    private final AnalyticsService analyticsService;

    @Autowired
    public PluginFetchTelemetryService(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    /**
     * Track a marketplace fetch.
     * Translated from trackMarketplaceFetch() in fetchTelemetry.ts
     */
    public void trackMarketplaceFetch(String marketplace, String source, boolean success) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("marketplace", marketplace);
        props.put("source", source);
        props.put("success", success);

        analyticsService.logEvent("tengu_marketplace_fetch", props);
    }

    /**
     * Track a plugin install fetch.
     * Translated from trackPluginInstallFetch() in fetchTelemetry.ts
     */
    public void trackPluginInstallFetch(String pluginId, String source, boolean success) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("plugin_id", pluginId);
        props.put("source", source);
        props.put("success", success);

        analyticsService.logEvent("tengu_plugin_install_fetch", props);
    }
}
