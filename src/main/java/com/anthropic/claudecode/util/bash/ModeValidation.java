package com.anthropic.claudecode.util.bash;

import com.anthropic.claudecode.model.PermissionMode;
import com.anthropic.claudecode.model.PermissionResult;
import com.anthropic.claudecode.model.ToolPermissionContext;
import com.anthropic.claudecode.util.BashUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;

/**
 * Mode-based permission validation for bash commands.
 *
 * Checks if commands should be handled differently based on the current
 * permission mode. Currently handles Accept Edits mode for filesystem commands,
 * but designed to be extended for other modes.
 *
 * Translated from src/tools/BashTool/modeValidation.ts
 */
@Slf4j
public final class ModeValidation {



    /**
     * Filesystem commands that are auto-allowed in Accept Edits mode.
     */
    private static final Set<String> ACCEPT_EDITS_ALLOWED_COMMANDS = Set.of(
            "mkdir", "touch", "rm", "rmdir", "mv", "cp", "sed"
    );

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Checks if commands should be handled differently based on the current
     * permission mode.
     *
     * @param command               the bash command string
     * @param toolPermissionContext context containing mode and permissions
     * @return
     *   <ul>
     *     <li>{@code allow} if the current mode permits auto-approval</li>
     *     <li>{@code ask} if the command needs approval in current mode</li>
     *     <li>{@code passthrough} if no mode-specific handling applies</li>
     *   </ul>
     */
    public static PermissionResult checkPermissionMode(
            String command, ToolPermissionContext toolPermissionContext) {

        PermissionMode mode = toolPermissionContext.getMode();

        // Skip if in bypass mode (handled elsewhere)
        if (mode == PermissionMode.BYPASS_PERMISSIONS) {
            return PermissionResult.PassthroughDecision.builder()
                    .message("Bypass mode is handled in main permission flow")
                    .build();
        }

        // Skip if in dontAsk mode (handled in main permission flow)
        if (mode == PermissionMode.DONT_ASK) {
            return PermissionResult.PassthroughDecision.builder()
                    .message("DontAsk mode is handled in main permission flow")
                    .build();
        }

        List<String> commands = BashUtils.splitCommand(command);

        for (String cmd : commands) {
            PermissionResult result = validateCommandForMode(cmd, toolPermissionContext);

            // If any command triggers mode-specific behaviour, return that result
            if (!"passthrough".equals(result.getBehavior())) {
                return result;
            }
        }

        return PermissionResult.PassthroughDecision.builder()
                .message("No mode-specific validation required")
                .build();
    }

    /**
     * Returns the set of commands that are auto-allowed for the given mode.
     *
     * @param mode the permission mode
     * @return immutable set of auto-allowed command names (may be empty)
     */
    public static Set<String> getAutoAllowedCommands(PermissionMode mode) {
        return mode == PermissionMode.ACCEPT_EDITS ? ACCEPT_EDITS_ALLOWED_COMMANDS : Set.of();
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private static PermissionResult validateCommandForMode(
            String cmd, ToolPermissionContext toolPermissionContext) {

        String trimmedCmd = cmd.trim();
        String[] parts = trimmedCmd.split("\\s+", 2);
        String baseCmd = parts.length > 0 ? parts[0] : "";

        if (baseCmd.isEmpty()) {
            return PermissionResult.PassthroughDecision.builder()
                    .message("Base command not found")
                    .build();
        }

        // In Accept Edits mode, auto-allow filesystem operations
        if (toolPermissionContext.getMode() == PermissionMode.ACCEPT_EDITS
                && ACCEPT_EDITS_ALLOWED_COMMANDS.contains(baseCmd)) {
            return PermissionResult.AllowDecision.builder()
                    .updatedInput(java.util.Map.of("command", cmd))
                    .decisionReason(new PermissionResult.PermissionDecisionReason.ModeReason(
                            PermissionMode.ACCEPT_EDITS))
                    .build();
        }

        return PermissionResult.PassthroughDecision.builder()
                .message("No mode-specific handling for '" + baseCmd + "' in "
                        + toolPermissionContext.getMode() + " mode")
                .build();
    }

    private ModeValidation() {}
}
