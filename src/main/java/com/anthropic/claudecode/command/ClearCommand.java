package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.SessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;

/**
 * Clear command for clearing conversation history.
 * Translated from src/commands/clear/
 */
@Slf4j
@Component
@Command(
    name = "clear",
    aliases = {"reset", "new"},
    description = "Clear conversation history and free up context"
)
public class ClearCommand implements Callable<Integer> {



    private final SessionService sessionService;

    @Autowired
    public ClearCommand(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public Integer call() {
        // Start a new session
        sessionService.startSession();
        System.out.println("Conversation cleared. Starting fresh session.");
        return 0;
    }
}
