package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.PermissionResult;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.*;

/**
 * Bash security validation service.
 * Translated from src/tools/BashTool/bashSecurity.ts
 *
 * Validates bash commands for security issues.
 */
@Slf4j
@Service
public class BashSecurityService {



    private static final List<Pattern> COMMAND_SUBSTITUTION_PATTERNS = List.of(
        Pattern.compile("<\\("),
        Pattern.compile(">\\("),
        Pattern.compile("=\\("),
        Pattern.compile("(?:^|[\\s;&|])=[a-zA-Z_]"),
        Pattern.compile("\\$\\("),
        Pattern.compile("\\$\\{"),
        Pattern.compile("\\$\\["),
        Pattern.compile("~\\["),
        Pattern.compile("\\(e:"),
        Pattern.compile("\\(\\+"),
        Pattern.compile("\\}\\s*always\\s*\\{"),
        Pattern.compile("<#")
    );

    private static final List<String> COMMAND_SUBSTITUTION_MESSAGES = List.of(
        "process substitution <()",
        "process substitution >()",
        "Zsh process substitution =()",
        "Zsh equals expansion (=cmd)",
        "$() command substitution",
        "${} parameter substitution",
        "$[] legacy arithmetic expansion",
        "Zsh-style parameter expansion",
        "Zsh-style glob qualifiers",
        "Zsh glob qualifier with command execution",
        "Zsh always block (try/always construct)",
        "PowerShell comment syntax"
    );

    /**
     * Validate a bash command for security issues.
     * Translated from validateBashCommand() in bashSecurity.ts
     */
    public SecurityValidationResult validateBashCommand(String command) {
        if (command == null || command.isBlank()) {
            return new SecurityValidationResult(true, null);
        }

        // Check for command substitution patterns
        for (int i = 0; i < COMMAND_SUBSTITUTION_PATTERNS.size(); i++) {
            if (COMMAND_SUBSTITUTION_PATTERNS.get(i).matcher(command).find()) {
                return new SecurityValidationResult(false,
                    "Command contains " + COMMAND_SUBSTITUTION_MESSAGES.get(i));
            }
        }

        return new SecurityValidationResult(true, null);
    }

    /**
     * Check if a command has dangerous patterns.
     * Translated from validateDangerousPatterns() in bashSecurity.ts
     */
    public Optional<String> checkDangerousPatterns(String command) {
        if (command == null) return Optional.empty();

        // Check for backtick command substitution
        if (command.contains("`")) {
            return Optional.of("backtick command substitution");
        }

        return Optional.empty();
    }

    @Data
    public static class SecurityValidationResult {
        private boolean safe;
        private String message;

        public boolean isSafe() { return safe; }
        public void setSafe(boolean v) { safe = v; }
        public String getMessage() { return message; }
        public void setMessage(String v) { message = v; }
    

        public SecurityValidationResult() {}
        public SecurityValidationResult(boolean safe, String message) {
            this.safe = safe;
            this.message = message;
        }
    }
}
