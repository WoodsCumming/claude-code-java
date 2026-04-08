package com.anthropic.claudecode.model;

import java.util.*;

/**
 * Empty/zero-initialized usage object.
 * Translated from src/services/api/emptyUsage.ts
 *
 * Used as a default usage value when no API response is available.
 */
public class EmptyUsage {

    public static final Map<String, Object> EMPTY_USAGE = Map.ofEntries(
        Map.entry("input_tokens", 0),
        Map.entry("cache_creation_input_tokens", 0),
        Map.entry("cache_read_input_tokens", 0),
        Map.entry("output_tokens", 0),
        Map.entry("server_tool_use", Map.of(
            "web_search_requests", 0,
            "web_fetch_requests", 0
        )),
        Map.entry("service_tier", "standard"),
        Map.entry("cache_creation", Map.of(
            "ephemeral_1h_input_tokens", 0,
            "ephemeral_5m_input_tokens", 0
        )),
        Map.entry("inference_geo", ""),
        Map.entry("iterations", List.of())
    );

    private EmptyUsage() {}
}
