package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.ClaudeCodeHints;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Plugin hint recommendation service.
 * Translated from src/utils/plugins/hintRecommendation.ts
 *
 * Manages plugin recommendations triggered by <claude-code-hint /> tags.
 */
@Slf4j
@Service
public class PluginHintRecommendationService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PluginHintRecommendationService.class);


    private final GlobalConfigService globalConfigService;
    private final AnalyticsService analyticsService;

    @Autowired
    public PluginHintRecommendationService(GlobalConfigService globalConfigService,
                                            AnalyticsService analyticsService) {
        this.globalConfigService = globalConfigService;
        this.analyticsService = analyticsService;
    }

    /**
     * Process a plugin hint from tool output.
     * Translated from processPluginHint() in hintRecommendation.ts
     */
    public Optional<String> processPluginHint(
            String toolOutput,
            String sourceCommand) {

        List<ClaudeCodeHints.ClaudeCodeHint> hints =
            ClaudeCodeHints.extractClaudeCodeHints(toolOutput, sourceCommand);

        if (hints.isEmpty()) return Optional.empty();

        ClaudeCodeHints.ClaudeCodeHint hint = hints.get(0);

        // Check if hint was already shown
        if (wasHintShown(hint.getValue())) return Optional.empty();

        // Record the hint
        recordHintShown(hint.getValue());

        analyticsService.logEvent("tengu_plugin_hint_shown", Map.of(
            "plugin_id", hint.getValue(),
            "source_command", sourceCommand
        ));

        return Optional.of(hint.getValue());
    }

    private boolean wasHintShown(String pluginId) {
        var config = globalConfigService.getGlobalConfig();
        // In a full implementation, check config.claudeCodeHints
        return false;
    }

    private void recordHintShown(String pluginId) {
        // In a full implementation, update config.claudeCodeHints
        log.debug("Plugin hint shown: {}", pluginId);
    }
}
