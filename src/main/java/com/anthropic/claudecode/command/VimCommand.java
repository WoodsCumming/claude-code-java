package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.GlobalConfigService;
import com.anthropic.claudecode.service.AnalyticsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;

/**
 * Vim command for toggling between Vim and Normal editing modes.
 * Translated from src/commands/vim/vim.ts
 */
@Slf4j
@Component
@Command(
    name = "vim",
    description = "Toggle between Vim and Normal editing modes"
)
public class VimCommand implements Callable<Integer> {



    private final GlobalConfigService globalConfigService;
    private final AnalyticsService analyticsService;

    @Autowired
    public VimCommand(GlobalConfigService globalConfigService, AnalyticsService analyticsService) {
        this.globalConfigService = globalConfigService;
        this.analyticsService = analyticsService;
    }

    @Override
    public Integer call() {
        String currentMode = globalConfigService.getEditorMode();
        if (currentMode == null || currentMode.isEmpty() || "emacs".equals(currentMode)) {
            currentMode = "normal";
        }

        String newMode = "normal".equals(currentMode) ? "vim" : "normal";
        globalConfigService.setEditorMode(newMode);

        analyticsService.logEvent("tengu_editor_mode_changed", java.util.Map.of(
            "mode", newMode,
            "source", "command"
        ));

        String message = "Editor mode set to " + newMode + ". " +
            ("vim".equals(newMode)
                ? "Use Escape key to toggle between INSERT and NORMAL modes."
                : "Using standard (readline) keyboard bindings.");
        System.out.println(message);
        return 0;
    }
}
