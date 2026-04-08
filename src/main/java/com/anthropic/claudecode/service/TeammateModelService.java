package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.ApiProviderUtils;
import com.anthropic.claudecode.util.ModelConfigs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Teammate model service.
 * Translated from src/utils/swarm/teammateModel.ts
 *
 * Provides model selection for teammate agents.
 */
@Slf4j
@Service
public class TeammateModelService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TeammateModelService.class);


    /**
     * Get the hardcoded teammate model fallback.
     * Translated from getHardcodedTeammateModelFallback() in teammateModel.ts
     *
     * When the user has never set teammateDefaultModel, new teammates use Opus 4.6.
     */
    public String getHardcodedTeammateModelFallback() {
        ApiProviderUtils.ApiProvider provider = ApiProviderUtils.getAPIProvider();
        ModelConfigs.ModelConfig config = ModelConfigs.CLAUDE_OPUS_4_6;

        return switch (provider) {
            case BEDROCK -> config.bedrock();
            case VERTEX -> config.vertex();
            case FOUNDRY -> config.foundry();
            default -> config.firstParty();
        };
    }
}
