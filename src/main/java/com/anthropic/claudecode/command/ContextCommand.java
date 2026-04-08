package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.ContextAnalysisService;
import com.anthropic.claudecode.service.SessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;

/**
 * Context command for showing context window usage.
 * Translated from src/commands/context/context-noninteractive.ts
 */
@Slf4j
@Component
@Command(
    name = "context",
    description = "Show current context usage"
)
public class ContextCommand implements Callable<Integer> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ContextCommand.class);

    private final ContextAnalysisService contextAnalysisService;
    private final SessionService sessionService;

    @Autowired
    public ContextCommand(ContextAnalysisService contextAnalysisService,
                          SessionService sessionService) {
        this.contextAnalysisService = contextAnalysisService;
        this.sessionService = sessionService;
    }

    @Override
    public Integer call() {
        try {
            String report = contextAnalysisService.formatContextReport(
                sessionService.getCurrentMessages()
            );
            System.out.println(report);
            return 0;
        } catch (Exception e) {
            log.error("Failed to analyze context", e);
            System.out.println("Failed to analyze context: " + e.getMessage());
            return 1;
        }
    }
}
