package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.HookEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Frontmatter hooks registration service.
 * Translated from src/utils/hooks/registerFrontmatterHooks.ts
 *
 * Registers hooks from agent/skill frontmatter into session-scoped hooks.
 */
@Slf4j
@Service
public class FrontmatterHooksService {



    private final HookService hookService;

    @Autowired
    public FrontmatterHooksService(HookService hookService) {
        this.hookService = hookService;
    }

    /**
     * Register hooks from frontmatter into session-scoped hooks.
     * Translated from registerFrontmatterHooks() in registerFrontmatterHooks.ts
     */
    public void registerFrontmatterHooks(
            String sessionId,
            Map<String, List<Map<String, Object>>> hooksSettings,
            String sourceName,
            boolean isAgent) {

        if (hooksSettings == null || hooksSettings.isEmpty()) {
            return;
        }

        int hookCount = 0;

        for (HookEvent event : HookEvent.values()) {
            List<Map<String, Object>> matchers = hooksSettings.get(event.getValue());
            if (matchers == null || matchers.isEmpty()) continue;

            // For agents, convert Stop hooks to SubagentStop
            HookEvent targetEvent = event;
            if (isAgent && event == HookEvent.STOP) {
                targetEvent = HookEvent.SUBAGENT_STOP;
                log.debug("Converting Stop hook to SubagentStop for {} (subagents trigger SubagentStop)", sourceName);
            }

            for (Map<String, Object> matcherConfig : matchers) {
                String matcher = (String) matcherConfig.getOrDefault("matcher", "");
                List<Map<String, Object>> hooksArray = (List<Map<String, Object>>) matcherConfig.get("hooks");

                if (hooksArray == null || hooksArray.isEmpty()) continue;

                for (Map<String, Object> hook : hooksArray) {
                    hookService.registerSessionHook(sessionId, targetEvent.getValue(), matcher, hook);
                    hookCount++;
                }
            }
        }

        log.debug("Registered {} hooks from frontmatter of {}", hookCount, sourceName);
    }
}
