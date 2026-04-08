package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.*;
import com.anthropic.claudecode.util.BashUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Bash command permission checking service.
 * Translated from src/tools/BashTool/bashPermissions.ts
 *
 * Checks if bash commands are allowed based on permission rules.
 */
@Slf4j
@Service
public class BashPermissionService {



    /**
     * Check if a bash command has permission to run.
     * Translated from bashToolHasPermission() in bashPermissions.ts
     */
    public CompletableFuture<PermissionResult> checkPermission(
            String command,
            ToolPermissionContext permissionContext) {

        if (command == null || command.isBlank()) {
            return CompletableFuture.completedFuture(
                PermissionResult.DenyDecision.builder()
                    .message("Empty command")
                    .build()
            );
        }

        PermissionMode mode = permissionContext.getMode();

        // Bypass mode: allow everything
        if (mode == PermissionMode.BYPASS_PERMISSIONS) {
            return CompletableFuture.completedFuture(
                PermissionResult.AllowDecision.builder().build()
            );
        }

        // Check always-deny rules
        if (matchesAnyRule(command, permissionContext.getAlwaysDenyRules())) {
            return CompletableFuture.completedFuture(
                PermissionResult.DenyDecision.builder()
                    .message("Command matches deny rule")
                    .build()
            );
        }

        // Check always-allow rules
        if (matchesAnyRule(command, permissionContext.getAlwaysAllowRules())) {
            return CompletableFuture.completedFuture(
                PermissionResult.AllowDecision.builder().build()
            );
        }

        // Check for dangerous commands
        if (isDangerousCommand(command)) {
            return CompletableFuture.completedFuture(
                PermissionResult.AskDecision.builder()
                    .message("This command may be dangerous: " + command)
                    .build()
            );
        }

        // Default: allow
        return CompletableFuture.completedFuture(
            PermissionResult.AllowDecision.builder().build()
        );
    }

    /**
     * Match a command against rules.
     * Translated from matchWildcardPattern() in shellRuleMatching.ts
     */
    public boolean matchesAnyRule(String command, Map<String, List<String>> rules) {
        if (rules == null || rules.isEmpty()) return false;

        for (Map.Entry<String, List<String>> entry : rules.entrySet()) {
            List<String> patterns = entry.getValue();
            if (patterns == null) continue;

            for (String pattern : patterns) {
                if (commandMatchesPattern(command, pattern)) return true;
            }
        }

        return false;
    }

    private boolean commandMatchesPattern(String command, String pattern) {
        if (pattern == null) return false;
        if ("*".equals(pattern)) return true;

        // Extract base command
        String baseCmd = BashUtils.getBaseCommand(command);

        // Check if pattern is "Bash(pattern)"
        if (pattern.startsWith("Bash(") && pattern.endsWith(")")) {
            String innerPattern = pattern.substring(5, pattern.length() - 1);
            return BashUtils.matchWildcardPattern(command, innerPattern)
                || BashUtils.matchWildcardPattern(baseCmd, innerPattern);
        }

        return BashUtils.matchWildcardPattern(command, pattern)
            || BashUtils.matchWildcardPattern(baseCmd, pattern);
    }

    private boolean isDangerousCommand(String command) {
        if (command == null) return false;
        String lower = command.toLowerCase();

        // Check for potentially dangerous patterns
        return lower.contains("rm -rf")
            || lower.contains("rm -fr")
            || lower.contains("> /dev/")
            || lower.contains("mkfs")
            || lower.contains("dd if=")
            || lower.contains(":(){ :|:& };:") // fork bomb
            || lower.contains("chmod -R 777 /");
    }

    /**
     * Extract permission rule prefix from a command.
     * Translated from permissionRuleExtractPrefix() in bashPermissions.ts
     */
    public String extractPermissionRulePrefix(String command) {
        if (command == null) return "";
        String baseCmd = BashUtils.getBaseCommand(command);
        return "Bash(" + baseCmd + " *)";
    }
}
