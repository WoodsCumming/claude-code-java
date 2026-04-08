package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.AgentsLoaderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * Command definition for 'agents'.
 * Translated from src/commands/agents/index.ts
 *
 * <p>In the TypeScript source this is a lazy-loaded local-jsx command with no
 * availability restriction (available on all surfaces). It manages agent
 * configurations via the lazy-loaded agents.js implementation.
 */
@Slf4j
@Component
@Command(
    name = "agents",
    description = "Manage agent configurations"
)
public class AgentsCommand implements Callable<Integer> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentsCommand.class);


    /**
     * Command type.
     * Translated from: type: 'local-jsx' in index.ts
     */
    public static final String TYPE = "local-jsx";

    private final AgentsLoaderService agentsLoaderService;

    @Autowired
    public AgentsCommand(AgentsLoaderService agentsLoaderService) {
        this.agentsLoaderService = agentsLoaderService;
    }

    @Override
    public Integer call() {
        log.info("Running agents command");
        try {
            agentsLoaderService.loadAndRunAgentsUI().join();
            return 0;
        } catch (Exception e) {
            log.error("agents command failed: {}", e.getMessage(), e);
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
