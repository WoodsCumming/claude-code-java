package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Feature gate / Statsig evaluation service.
 * Translated from src/utils/statsig.ts and src/utils/featureGate.ts
 *
 * Checks feature gates (GrowthBook / Statsig experiments) to control
 * which features are enabled for each user session.
 */
@Slf4j
@Service
public class FeatureGateService {



    private final GrowthBookService growthBookService;

    @Autowired
    public FeatureGateService(GrowthBookService growthBookService) {
        this.growthBookService = growthBookService;
    }

    /**
     * Check a feature gate using a cached (potentially stale) evaluation.
     * Translated from checkGateCachedMayBeStale() in statsig.ts
     *
     * @param gateName the feature gate key
     * @return true if the gate is enabled for the current user
     */
    public boolean checkFeatureGateCachedMayBeStale(String gateName) {
        try {
            return growthBookService.checkFeatureGate(gateName);
        } catch (Exception e) {
            log.debug("[FeatureGate] Error checking gate '{}': {}", gateName, e.getMessage());
            return false;
        }
    }

    /**
     * Check a feature gate with a fresh (non-cached) evaluation.
     * Translated from checkGate() in statsig.ts
     *
     * @param gateName the feature gate key
     * @return true if the gate is enabled for the current user
     */
    public boolean checkFeatureGate(String gateName) {
        return checkFeatureGateCachedMayBeStale(gateName);
    }
}
