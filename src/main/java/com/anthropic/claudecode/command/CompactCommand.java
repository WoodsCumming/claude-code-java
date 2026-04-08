package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.CompactService;
import com.anthropic.claudecode.service.SessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;

/**
 * Compact command for clearing conversation history with summary.
 * Translated from src/commands/compact/
 */
@Slf4j
@Component
@Command(
    name = "compact",
    description = "Clear conversation history but keep a summary in context"
)
public class CompactCommand implements Callable<Integer> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CompactCommand.class);


    @Parameters(index = "0", description = "Optional custom summarization instructions", arity = "0..1")
    private String instructions;

    private final CompactService compactService;
    private final SessionService sessionService;

    @Autowired
    public CompactCommand(CompactService compactService, SessionService sessionService) {
        this.compactService = compactService;
        this.sessionService = sessionService;
    }

    @Override
    public Integer call() {
        System.out.println("Compacting conversation...");
        // In full implementation, would compact the current session
        return 0;
    }
}
