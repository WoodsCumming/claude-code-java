package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.Optional;

/**
 * Advisor tool service (stub for external builds).
 * Translated from src/utils/advisor.ts
 *
 * The advisor is an ant-only feature that provides additional AI guidance.
 */
@Slf4j
@Service
public class AdvisorService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AdvisorService.class);


    /**
     * Check if the advisor is enabled.
     * Translated from isAdvisorEnabled() in advisor.ts
     */
    public boolean isAdvisorEnabled() {
        if (EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_DISABLE_ADVISOR_TOOL"))) {
            return false;
        }
        // Advisor is ant-only
        return false;
    }

    /**
     * Check if user can configure the advisor.
     * Translated from canUserConfigureAdvisor() in advisor.ts
     */
    public boolean canUserConfigureAdvisor() {
        return false;
    }

    /**
     * Check if a model supports the advisor tool.
     * Translated from modelSupportsAdvisor() in advisor.ts
     */
    public boolean modelSupportsAdvisor(String model) {
        if (model == null) return false;
        String m = model.toLowerCase();
        return m.contains("opus-4-6") || m.contains("sonnet-4-6");
    }

    /**
     * Check if a model is a valid advisor model.
     * Translated from isValidAdvisorModel() in advisor.ts
     */
    public boolean isValidAdvisorModel(String model) {
        if (model == null) return false;
        String m = model.toLowerCase();
        return m.contains("sonnet") || m.contains("haiku");
    }

    /**
     * Get the initial advisor setting.
     * Translated from getInitialAdvisorSetting() in advisor.ts
     */
    public boolean getInitialAdvisorSetting() {
        return false;
    }
}
