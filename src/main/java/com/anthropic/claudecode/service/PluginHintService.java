package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.ClaudeCodeHints;
import com.anthropic.claudecode.util.PluginIdentifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Plugin hint recommendation service.
 * Translated from src/utils/plugins/hintRecommendation.ts
 *
 * Manages plugin installation hints triggered by CLI/SDK hint tags.
 */
@Slf4j
@Service
public class PluginHintService {



    private static final int MAX_SHOWN_PLUGINS = 100;
    private final Set<String> shownPluginsThisSession = new HashSet<>();

    /**
     * Maybe record a plugin hint.
     * Translated from maybeRecordPluginHint() in hintRecommendation.ts
     */
    public void maybeRecordPluginHint(ClaudeCodeHints.ClaudeCodeHint hint) {
        if (hint == null) return;

        String value = hint.getValue();
        if (value == null) return;

        PluginIdentifier.ParsedPluginIdentifier parsed = PluginIdentifier.parsePluginIdentifier(value);

        // Only show official marketplace plugins
        if (!PluginIdentifier.isOfficialMarketplaceName(parsed.marketplace())) {
            log.debug("Skipping non-official plugin hint: {}", value);
            return;
        }

        if (shownPluginsThisSession.size() >= MAX_SHOWN_PLUGINS) {
            return;
        }

        shownPluginsThisSession.add(value);
        log.debug("Recording plugin hint: {}", value);
    }

    /**
     * Get pending plugin hints.
     */
    public List<String> getPendingHints() {
        return new ArrayList<>(shownPluginsThisSession);
    }
}
