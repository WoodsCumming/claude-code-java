package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Computer use host adapter service.
 * Translated from src/utils/computerUse/hostAdapter.ts
 *
 * Adapts the computer use MCP server to the CLI environment.
 */
@Slf4j
@Service
public class ComputerUseHostAdapterService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ComputerUseHostAdapterService.class);


    public static final String COMPUTER_USE_MCP_SERVER_NAME = "computer-use";

    private final ComputerUseService computerUseService;
    private final ComputerUseExecutorService executorService;

    @Autowired
    public ComputerUseHostAdapterService(ComputerUseService computerUseService,
                                          ComputerUseExecutorService executorService) {
        this.computerUseService = computerUseService;
        this.executorService = executorService;
    }

    /**
     * Check if computer use is available in this environment.
     */
    public boolean isAvailable() {
        return computerUseService.isComputerUseEnabled();
    }

    /**
     * Get the computer use configuration.
     */
    public ComputerUseService.ComputerUseConfig getConfig() {
        return computerUseService.getConfig();
    }
}
