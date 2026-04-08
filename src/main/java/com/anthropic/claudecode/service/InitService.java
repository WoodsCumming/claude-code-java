package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.StartupProfiler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.concurrent.CompletableFuture;

/**
 * Initialization service for Claude Code startup.
 * Translated from src/entrypoints/init.ts
 *
 * Handles all initialization tasks on startup.
 */
@Slf4j
@Service
public class InitService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InitService.class);


    private final CaCertsConfigService caCertsConfigService;
    private final PolicyLimitsService policyLimitsService;
    private final RemoteManagedSettingsService remoteManagedSettingsService;
    private final OAuthService oauthService;
    private final GracefulShutdownService gracefulShutdownService;

    @Autowired
    public InitService(CaCertsConfigService caCertsConfigService,
                        PolicyLimitsService policyLimitsService,
                        RemoteManagedSettingsService remoteManagedSettingsService,
                        OAuthService oauthService,
                        GracefulShutdownService gracefulShutdownService) {
        this.caCertsConfigService = caCertsConfigService;
        this.policyLimitsService = policyLimitsService;
        this.remoteManagedSettingsService = remoteManagedSettingsService;
        this.oauthService = oauthService;
        this.gracefulShutdownService = gracefulShutdownService;
    }

    /**
     * Initialize Claude Code.
     * Translated from init() in init.ts
     */
    public CompletableFuture<Void> init() {
        return CompletableFuture.runAsync(() -> {
            StartupProfiler.profileCheckpoint("init_start");

            // Apply CA certs
            caCertsConfigService.applyCACertsFromSettings();
            StartupProfiler.profileCheckpoint("ca_certs_applied");

            // Initialize policy limits
            policyLimitsService.initializePolicyLimits();
            StartupProfiler.profileCheckpoint("policy_limits_initialized");

            // Initialize remote managed settings
            remoteManagedSettingsService.initialize();
            StartupProfiler.profileCheckpoint("remote_settings_initialized");

            // Populate OAuth account info if needed
            oauthService.getOrganizationUUID();
            StartupProfiler.profileCheckpoint("oauth_initialized");

            log.info("Claude Code initialized");
        });
    }
}
