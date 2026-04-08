package com.anthropic.claudecode.util;

import java.util.*;

/**
 * AI gateway detection utilities.
 * Translated from src/services/api/logging.ts gateway detection
 */
public class GatewayDetector {

    public enum KnownGateway {
        LITELLM("litellm"),
        HELICONE("helicone"),
        PORTKEY("portkey"),
        CLOUDFLARE_AI_GATEWAY("cloudflare-ai-gateway"),
        KONG("kong"),
        BRAINTRUST("braintrust"),
        DATABRICKS("databricks");

        private final String value;
        KnownGateway(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    private static final Map<KnownGateway, List<String>> GATEWAY_FINGERPRINTS = Map.of(
        KnownGateway.LITELLM, List.of("x-litellm-"),
        KnownGateway.HELICONE, List.of("helicone-"),
        KnownGateway.PORTKEY, List.of("x-portkey-"),
        KnownGateway.CLOUDFLARE_AI_GATEWAY, List.of("cf-aig-"),
        KnownGateway.KONG, List.of("x-kong-"),
        KnownGateway.BRAINTRUST, List.of("x-bt-")
    );

    private static final Map<KnownGateway, List<String>> GATEWAY_HOST_SUFFIXES = Map.of(
        KnownGateway.DATABRICKS, List.of(
            ".cloud.databricks.com",
            ".azuredatabricks.net",
            ".gcp.databricks.com"
        )
    );

    /**
     * Detect the AI gateway from response headers or base URL.
     * Translated from detectGateway() in logging.ts
     */
    public static Optional<KnownGateway> detectGateway(
            Map<String, String> headers,
            String baseUrl) {

        if (headers != null) {
            for (Map.Entry<KnownGateway, List<String>> entry : GATEWAY_FINGERPRINTS.entrySet()) {
                for (String prefix : entry.getValue()) {
                    if (headers.keySet().stream().anyMatch(h -> h.toLowerCase().startsWith(prefix))) {
                        return Optional.of(entry.getKey());
                    }
                }
            }
        }

        if (baseUrl != null) {
            for (Map.Entry<KnownGateway, List<String>> entry : GATEWAY_HOST_SUFFIXES.entrySet()) {
                for (String suffix : entry.getValue()) {
                    if (baseUrl.contains(suffix)) {
                        return Optional.of(entry.getKey());
                    }
                }
            }
        }

        return Optional.empty();
    }

    private GatewayDetector() {}
}
