package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * CA certificates configuration service.
 * Translated from src/utils/caCertsConfig.ts
 *
 * Applies CA certificate settings early in initialization before any TLS connections.
 */
@Slf4j
@Service
public class CaCertsConfigService {



    private final SettingsService settingsService;
    private final GlobalConfigService globalConfigService;

    @Autowired
    public CaCertsConfigService(SettingsService settingsService,
                                  GlobalConfigService globalConfigService) {
        this.settingsService = settingsService;
        this.globalConfigService = globalConfigService;
    }

    /**
     * Apply CA certificate settings from settings.json.
     * Translated from applyCACertsFromSettings() in caCertsConfig.ts
     *
     * Sets the javax.net.ssl.trustStore system property if configured.
     */
    public void applyCACertsFromSettings() {
        // Check settings for custom CA certs
        Map<String, Object> userSettings = settingsService.getUserSettings();
        String customCACerts = (String) userSettings.get("caCertsPath");

        if (customCACerts == null || customCACerts.isBlank()) {
            // Check global config
            var config = globalConfigService.getGlobalConfig();
            // No custom CA certs configured
            return;
        }

        // Apply the custom CA certs path
        System.setProperty("javax.net.ssl.trustStore", customCACerts);
        log.debug("Applied custom CA certs from settings: {}", customCACerts);
    }
}
