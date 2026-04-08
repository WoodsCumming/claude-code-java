package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Command service for the /rewind (message selector) command.
 * Translated from src/commands/rewind/rewind.ts
 *
 * Opens the interactive message selector so the user can rewind the
 * conversation to a previous checkpoint.  Returns a {@code skip} result
 * so that no new message is appended to the conversation thread.
 */
@Slf4j
@Service
public class RewindCommandService {



    // -------------------------------------------------------------------------
    // Command result types
    // -------------------------------------------------------------------------

    /** Mirrors the TypeScript {@code LocalCommandResult} union used by rewind. */
    public sealed interface CommandResult permits CommandResult.TextResult, CommandResult.SkipResult {
        record TextResult(String value) implements CommandResult {}
        record SkipResult() implements CommandResult {}
    }

    // -------------------------------------------------------------------------
    // Context / callback interface
    // -------------------------------------------------------------------------

    /**
     * Callback interface supplied by the session layer.
     * Translated from the {@code ToolUseContext} interface in TypeScript,
     * specifically the {@code openMessageSelector} function property.
     */
    @FunctionalInterface
    public interface MessageSelectorOpener {
        void open();
    }

    // -------------------------------------------------------------------------
    // call()
    // -------------------------------------------------------------------------

    /**
     * Execute the /rewind command.
     * Translated from call() in rewind.ts
     *
     * If a {@link MessageSelectorOpener} is provided it is invoked to surface
     * the interactive message-selection UI.  Either way a {@link CommandResult.SkipResult}
     * is returned so that no text is appended to the conversation.
     *
     * @param args             raw command arguments (unused)
     * @param selectorOpener   callback that opens the message selector, or {@code null}
     */
    public CompletableFuture<CommandResult> call(String args, MessageSelectorOpener selectorOpener) {
        return CompletableFuture.supplyAsync(() -> {
            if (selectorOpener != null) {
                try {
                    selectorOpener.open();
                } catch (Exception e) {
                    log.warn("[rewind] Could not open message selector: {}", e.getMessage());
                }
            }
            // Return skip — no message appended to the conversation
            return new CommandResult.SkipResult();
        });
    }

    /**
     * Convenience overload when no selector opener is available.
     *
     * @param args raw command arguments (unused)
     */
    public CompletableFuture<CommandResult> call(String args) {
        return call(args, null);
    }
}
