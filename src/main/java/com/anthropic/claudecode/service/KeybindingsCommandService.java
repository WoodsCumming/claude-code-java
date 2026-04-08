package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;

/**
 * /keybindings slash-command service.
 * Translated from src/commands/keybindings/keybindings.ts
 *
 * Creates the user's keybindings file (if it does not yet exist, using a
 * generated template) and then opens it in the system editor.  The command
 * is gated behind a feature flag; when keybinding customization is not
 * enabled, a short "preview" message is returned instead.
 */
@Slf4j
@Service
public class KeybindingsCommandService {



    // ---------------------------------------------------------------------------
    // Dependencies
    // ---------------------------------------------------------------------------

    private final KeybindingsService keybindingsService;
    private final PromptEditorService promptEditorService;

    @Autowired
    public KeybindingsCommandService(KeybindingsService keybindingsService,
                                      PromptEditorService promptEditorService) {
        this.keybindingsService = keybindingsService;
        this.promptEditorService = promptEditorService;
    }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Execute the /keybindings command.
     * Mirrors {@code call()} in keybindings.ts.
     *
     * @return A {@link KeybindingsCommandResult} describing the outcome.
     */
    public CompletableFuture<KeybindingsCommandResult> call() {
        return CompletableFuture.supplyAsync(() -> {

            // Feature-flag guard
            if (!keybindingsService.isKeybindingCustomizationEnabled()) {
                return KeybindingsCommandResult.text(
                    "Keybinding customization is not enabled. " +
                    "This feature is currently in preview."
                );
            }

            String keybindingsPath = keybindingsService.getKeybindingsPath();

            // Ensure parent directory exists
            try {
                Path path = Path.of(keybindingsPath);
                Files.createDirectories(path.getParent());
            } catch (IOException e) {
                log.error("Failed to create keybindings directory", e);
                throw new RuntimeException(
                    "Failed to create keybindings directory: " + e.getMessage(), e
                );
            }

            // Write the template only if the file does not already exist.
            // This mirrors the TypeScript 'wx' (exclusive-create) flag which
            // fails with EEXIST when the file is already present.
            boolean fileExists = false;
            try {
                String template = keybindingsService.generateKeybindingsTemplate();
                Files.writeString(
                    Path.of(keybindingsPath),
                    template,
                    StandardOpenOption.CREATE_NEW   // fails if file already exists
                );
                log.info("Created keybindings template at {}", keybindingsPath);
            } catch (FileAlreadyExistsException e) {
                fileExists = true;
                log.debug("Keybindings file already exists at {}", keybindingsPath);
            } catch (IOException e) {
                log.error("Failed to write keybindings template", e);
                throw new RuntimeException(
                    "Failed to write keybindings template: " + e.getMessage(), e
                );
            }

            // Open the file in the user's configured editor
            PromptEditorService.EditorResult editorResult =
                promptEditorService.editFileInEditor(keybindingsPath);

            if (editorResult.hasError()) {
                String verb = fileExists ? "Opened" : "Created";
                return KeybindingsCommandResult.text(
                    verb + " " + keybindingsPath +
                    ". Could not open in editor: " + editorResult.error()
                );
            }

            String message = fileExists
                ? "Opened " + keybindingsPath + " in your editor."
                : "Created " + keybindingsPath + " with template. Opened in your editor.";

            return KeybindingsCommandResult.text(message);
        });
    }

    // ---------------------------------------------------------------------------
    // Result type
    // ---------------------------------------------------------------------------

    /**
     * Holds the text output of a /keybindings invocation.
     *
     * @param value The message to display to the user.
     */
    public record KeybindingsCommandResult(String value) {
        static KeybindingsCommandResult text(String value) {
            return new KeybindingsCommandResult(value);
        }

        /** Convenience accessor matching the TypeScript {@code { type, value }} shape. */
        public String type() {
            return "text";
        }
    }
}
