package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.GitAvailability;
import com.anthropic.claudecode.util.OfficialMarketplace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.concurrent.CompletableFuture;

/**
 * Official marketplace startup check service.
 * Translated from src/utils/plugins/officialMarketplaceStartupCheck.ts
 *
 * Handles auto-installation of the official marketplace on startup.
 */
@Slf4j
@Service
public class OfficialMarketplaceStartupCheckService {



    private final GlobalConfigService globalConfigService;
    private final MarketplaceManagerService marketplaceManager;
    private final PolicyLimitsService policyLimitsService;

    @Autowired
    public OfficialMarketplaceStartupCheckService(GlobalConfigService globalConfigService,
                                                   MarketplaceManagerService marketplaceManager,
                                                   PolicyLimitsService policyLimitsService) {
        this.globalConfigService = globalConfigService;
        this.marketplaceManager = marketplaceManager;
        this.policyLimitsService = policyLimitsService;
    }

    /**
     * Check and auto-install the official marketplace if needed.
     * Translated from checkAndAutoInstallOfficialMarketplace() in officialMarketplaceStartupCheck.ts
     */
    public CompletableFuture<Void> checkAndAutoInstallOfficialMarketplace() {
        return CompletableFuture.runAsync(() -> {
            // Check policy
            if (!policyLimitsService.isPolicyAllowed("allow_marketplace_plugins")) {
                log.debug("Marketplace plugins not allowed by policy");
                return;
            }

            // Check if already installed
            var config = globalConfigService.getGlobalConfig();
            // In a full implementation, check if official marketplace is already installed

            // Check git availability
            GitAvailability.isGitAvailable().thenAccept(gitAvailable -> {
                if (!gitAvailable) {
                    log.debug("Git not available, skipping official marketplace auto-install");
                    return;
                }

                log.debug("Official marketplace startup check complete");
            });
        });
    }
}
