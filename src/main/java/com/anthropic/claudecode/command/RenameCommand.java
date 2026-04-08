package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.SessionNameGeneratorService;
import com.anthropic.claudecode.service.SessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Rename command for renaming the current conversation.
 *
 * Translated from src/commands/rename/index.ts and src/commands/rename/rename.ts
 *
 * TypeScript original behaviour:
 *   - immediate: true  (runs without opening a full-screen UI panel)
 *   - No name given: auto-generate a title from the conversation history using an
 *     LLM call; if the conversation is empty, returns an error message
 *   - Name given: set the session title to exactly that string
 *
 * Java translation:
 *   - No arg: call SessionNameGeneratorService.generateSessionName() to auto-derive
 *     a name from the current conversation messages
 *   - With arg: set the session name directly
 */
@Slf4j
@Component
@Command(
    name = "rename",
    description = "Rename the current conversation"
)
public class RenameCommand implements Callable<Integer> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RenameCommand.class);


    /** New name for the session; omit to auto-generate from conversation context. */
    @Parameters(index = "0", description = "New name for the session (omit to auto-generate)", arity = "0..1")
    private String name;

    private final SessionService sessionService;
    private final SessionNameGeneratorService sessionNameGeneratorService;

    @Autowired
    public RenameCommand(SessionService sessionService,
                         SessionNameGeneratorService sessionNameGeneratorService) {
        this.sessionService = sessionService;
        this.sessionNameGeneratorService = sessionNameGeneratorService;
    }

    @Override
    public Integer call() {
        if (name == null || name.isBlank()) {
            return autoGenerateName();
        }
        return applyName(name.trim());
    }

    /**
     * Auto-generate a session name from the current conversation messages.
     * Mirrors the TypeScript behaviour where no name arg triggers an LLM call.
     */
    private int autoGenerateName() {
        try {
            Optional<String> generated = sessionNameGeneratorService
                .generateSessionName(sessionService.getCurrentMessages())
                .get();

            if (generated.isEmpty()) {
                System.out.println(
                    "Could not generate a name: no conversation context yet. Usage: /rename <name>"
                );
                return 1;
            }
            return applyName(generated.get());
        } catch (Exception e) {
            log.error("Failed to generate session name", e);
            System.out.println("Failed to generate session name. Usage: /rename <name>");
            return 1;
        }
    }

    private int applyName(String newName) {
        sessionService.setSessionName(newName);
        System.out.println("Session renamed to: " + newName);
        return 0;
    }
}
