package com.anthropic.claudecode.command;

import com.anthropic.claudecode.config.ClaudeCodeConfig;
import com.anthropic.claudecode.service.AuthService;
import com.anthropic.claudecode.util.EnvUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;

/**
 * Doctor command for diagnosing Claude Code installation.
 * Translated from src/commands/doctor/
 */
@Slf4j
@Component
@Command(
    name = "doctor",
    description = "Diagnose and verify your Claude Code installation and settings"
)
public class DoctorCommand implements Callable<Integer> {



    private final ClaudeCodeConfig config;
    private final AuthService authService;

    @Autowired
    public DoctorCommand(ClaudeCodeConfig config, AuthService authService) {
        this.config = config;
        this.authService = authService;
    }

    @Override
    public Integer call() {
        System.out.println("Claude Code Doctor");
        System.out.println("==================");

        // Check authentication
        System.out.println("\nAuthentication:");
        if (authService.isClaudeAISubscriber()) {
            System.out.println("  ✓ Authenticated with Claude.ai");
        } else if (authService.getApiKey() != null) {
            System.out.println("  ✓ API key configured");
        } else {
            System.out.println("  ✗ No authentication configured");
            System.out.println("    Run 'claude login' to authenticate");
        }

        // Check model
        System.out.println("\nModel:");
        System.out.println("  Model: " + config.getModel());

        // Check config directory
        System.out.println("\nConfiguration:");
        System.out.println("  Config dir: " + EnvUtils.getClaudeConfigHomeDir());

        System.out.println("\nDiagnosis complete.");
        return 0;
    }
}
