package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.KeybindingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;

/**
 * Keybindings command for managing keybinding configuration.
 * Translated from src/commands/keybindings/keybindings.ts
 */
@Slf4j
@Component
@Command(
    name = "keybindings",
    description = "Open or create your keybindings configuration file"
)
public class KeybindingsCommand implements Callable<Integer> {



    private final KeybindingsService keybindingsService;

    @Autowired
    public KeybindingsCommand(KeybindingsService keybindingsService) {
        this.keybindingsService = keybindingsService;
    }

    @Override
    public Integer call() {
        if (!keybindingsService.isKeybindingCustomizationEnabled()) {
            System.out.println("Keybinding customization is not enabled. This feature is currently in preview.");
            return 0;
        }

        String keybindingsPath = keybindingsService.getKeybindingsPath();
        boolean fileExists = keybindingsService.ensureKeybindingsFile();

        String result = keybindingsService.openInEditor(keybindingsPath);
        if (result != null) {
            System.out.println((fileExists ? "Opened" : "Created") + " " + keybindingsPath
                + ". Could not open in editor: " + result);
        } else {
            System.out.println(fileExists
                ? "Opened " + keybindingsPath + " in your editor."
                : "Created " + keybindingsPath + " with template. Opened in your editor.");
        }
        return 0;
    }
}
