package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.PermissionResult;
import com.anthropic.claudecode.model.ToolUseContext;
import com.anthropic.claudecode.util.BashUtils;
import com.anthropic.claudecode.util.ShellQuoteUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Bash command helpers service.
 * Translated from src/tools/BashTool/bashCommandHelpers.ts
 *
 * Provides helper functions for bash command processing and permission checking.
 */
@Slf4j
@Service
public class BashCommandHelpersService {



    private static final Pattern CD_PATTERN = Pattern.compile("^cd\\s+");
    private static final Pattern GIT_PATTERN = Pattern.compile("^git\\s+");

    private final BashPermissionService bashPermissionService;
    private final BashSecurityService bashSecurityService;

    @Autowired
    public BashCommandHelpersService(BashPermissionService bashPermissionService,
                                      BashSecurityService bashSecurityService) {
        this.bashPermissionService = bashPermissionService;
        this.bashSecurityService = bashSecurityService;
    }

    /**
     * Check if a command is a normalized cd command.
     * Translated from isNormalizedCdCommand() in bashCommandHelpers.ts
     */
    public boolean isNormalizedCdCommand(String command) {
        if (command == null) return false;
        return CD_PATTERN.matcher(command.trim()).find();
    }

    /**
     * Check if a command is a normalized git command.
     * Translated from isNormalizedGitCommand() in bashCommandHelpers.ts
     */
    public boolean isNormalizedGitCommand(String command) {
        if (command == null) return false;
        return GIT_PATTERN.matcher(command.trim()).find();
    }

    /**
     * Get permission result for a bash command.
     * Translated from getPermissionResultForCommand() in bashCommandHelpers.ts
     */
    public CompletableFuture<PermissionResult> getPermissionResultForCommand(
            String command,
            ToolUseContext context) {

        // First check security
        BashSecurityService.SecurityValidationResult security =
            bashSecurityService.validateBashCommand(command);

        if (!security.isSafe()) {
            PermissionResult denied = PermissionResult.DenyDecision.builder()
                .message("Command rejected for security reasons: " + security.getMessage())
                .build();
            return CompletableFuture.completedFuture(denied);
        }

        // Delegate to bash permission service (extract ToolPermissionContext from context)
        com.anthropic.claudecode.model.ToolPermissionContext permContext =
            context != null && context.getOptions() != null
                ? null // ToolPermissionContext not directly accessible
                : null;
        return bashPermissionService.checkPermission(command, permContext);
    }
}
