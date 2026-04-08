package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Analytics sink killswitch service.
 * Translated from src/services/analytics/sinkKillswitch.ts
 *
 * Allows disabling individual analytics sinks via GrowthBook dynamic config.
 *
 * NOTE: Must NOT be called from inside is1PEventLoggingEnabled() —
 * growthBook initialization calls that, so a lookup here would recurse.
 * Call at per-event dispatch sites instead.
 */
@Slf4j
@Service
public class SinkKillswitchService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SinkKillswitchService.class);


    // Mangled name: per-sink analytics killswitch
    // Translated from SINK_KILLSWITCH_CONFIG_NAME in sinkKillswitch.ts
    private static final String SINK_KILLSWITCH_CONFIG_NAME = "tengu_frond_boric";

    /**
     * Analytics sink names.
     * Translated from SinkName type in sinkKillswitch.ts
     */
    public enum SinkName {
        DATADOG("datadog"),
        FIRST_PARTY("firstParty");

        private final String value;

        SinkName(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    private final GrowthBookService growthBookService;

    @Autowired
    public SinkKillswitchService(GrowthBookService growthBookService) {
        this.growthBookService = growthBookService;
    }

    /**
     * Check if a given analytics sink is killed (disabled via GrowthBook config).
     *
     * Shape of config: {@code { datadog?: boolean, firstParty?: boolean }}
     * A value of {@code true} stops all dispatch to that sink.
     * Fail-open: missing/malformed config = sink stays on.
     *
     * Translated from isSinkKilled() in sinkKillswitch.ts
     */
    @SuppressWarnings("unchecked")
    public boolean isSinkKilled(SinkName sink) {
        try {
            Object raw = growthBookService.getDynamicConfig(SINK_KILLSWITCH_CONFIG_NAME, Map.of());
            // Fail-open: a cached JSON null leaks through instead of falling back to {}
            if (!(raw instanceof Map<?, ?> config)) {
                return false;
            }
            Object value = ((Map<String, Object>) config).get(sink.getValue());
            return Boolean.TRUE.equals(value);
        } catch (Exception e) {
            // Fail-open: any error means the sink stays on
            return false;
        }
    }
}
