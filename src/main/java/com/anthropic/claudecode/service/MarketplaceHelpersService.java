package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Marketplace helpers service.
 * Translated from src/utils/plugins/marketplaceHelpers.ts
 *
 * Helper functions for marketplace operations.
 */
@Slf4j
@Service
public class MarketplaceHelpersService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MarketplaceHelpersService.class);


    /**
     * Format plugin failure details for display.
     * Translated from formatFailureDetails() in marketplaceHelpers.ts
     */
    public String formatFailureDetails(
            List<Map<String, String>> failures,
            boolean includeReasons) {

        int maxShow = 2;
        List<String> parts = new ArrayList<>();

        for (int i = 0; i < Math.min(failures.size(), maxShow); i++) {
            Map<String, String> failure = failures.get(i);
            String name = failure.get("name");
            String reason = failure.get("reason");
            String error = failure.get("error");

            if (includeReasons && (reason != null || error != null)) {
                String detail = reason != null ? reason : error;
                parts.add(name + " (" + detail + ")");
            } else {
                parts.add(name);
            }
        }

        if (failures.size() > maxShow) {
            int remaining = failures.size() - maxShow;
            parts.add("and " + remaining + " more");
        }

        return String.join("; ", parts);
    }
}
