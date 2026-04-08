package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.ProductConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Attribution service for git commit attribution.
 * Translated from src/utils/attribution.ts
 *
 * Generates attribution text for git commits made with Claude Code.
 */
@Slf4j
@Service
public class AttributionService {



    /**
     * Get attribution texts for a session.
     * Translated from getAttributionTexts() in attribution.ts
     */
    public AttributionTexts getAttributionTexts(String model, String sessionId) {
        String productUrl = ProductConstants.PRODUCT_URL;
        String coAuthoredBy = "Co-Authored-By: Claude <noreply@anthropic.com>";
        String generatedWith = "Generated with [Claude Code](" + productUrl + ")";
        String commit = coAuthoredBy + "\n" + generatedWith;

        return new AttributionTexts(commit, coAuthoredBy, generatedWith);
    }

    /** Overload with no args. */
    public AttributionTexts getAttributionTexts() {
        return getAttributionTexts(null, null);
    }

    /** Get enhanced PR attribution text. */
    public String getEnhancedPRAttribution() {
        return "🤖 Generated with [Claude Code](https://claude.ai/claude-code)";
    }

    public record AttributionTexts(
        String commit,
        String coAuthoredBy,
        String generatedWith
    ) {}
}
