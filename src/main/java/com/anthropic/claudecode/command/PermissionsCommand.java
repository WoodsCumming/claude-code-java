package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.PermissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Permissions command for managing allow and deny tool permission rules.
 *
 * Translated from src/commands/permissions/index.ts and src/commands/permissions/permissions.tsx
 *
 * TypeScript original:
 *   - name: "permissions", aliases: ["allowed-tools"]
 *   - Opens a local-jsx dialog for interactively managing allow/deny rules per tool
 *   - Rules can be scoped to local, project, or user settings
 *
 * Java translation:
 *   - Default (no sub-command): display all current allow/deny rules
 *   - allow  <tool>   : add a tool to the allow list
 *   - deny   <tool>   : add a tool to the deny list
 *   - remove <tool>   : remove a rule for a tool
 *   - reset           : clear all custom permission rules
 */
@Slf4j
@Component
@Command(
    name = "permissions",
    aliases = {"allowed-tools"},
    description = "Manage allow & deny tool permission rules",
    mixinStandardHelpOptions = true,
    subcommands = {
        PermissionsCommand.AllowCommand.class,
        PermissionsCommand.DenyCommand.class,
        PermissionsCommand.RemoveCommand.class,
        PermissionsCommand.ResetCommand.class,
    }
)
public class PermissionsCommand implements Callable<Integer> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PermissionsCommand.class);


    private final PermissionService permissionService;

    @Autowired
    public PermissionsCommand(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /** Default: display current permission rules. */
    @Override
    public Integer call() {
        List<String> allowed = permissionService.getAllowedTools();
        List<String> denied = permissionService.getDeniedTools();

        System.out.println("Tool Permissions:");

        System.out.println("\nAllowed tools:");
        if (allowed.isEmpty()) {
            System.out.println("  (none explicitly allowed — using default rules)");
        } else {
            allowed.forEach(t -> System.out.println("  + " + t));
        }

        System.out.println("\nDenied tools:");
        if (denied.isEmpty()) {
            System.out.println("  (none explicitly denied)");
        } else {
            denied.forEach(t -> System.out.println("  - " + t));
        }

        System.out.println("\nUse /permissions allow <tool> or /permissions deny <tool> to add rules.");
        System.out.println("Use /permissions reset to clear all custom rules.");
        return 0;
    }

    // -----------------------------------------------------------------
    // Sub-commands
    // -----------------------------------------------------------------

    @Component
    @Command(name = "allow", description = "Add a tool to the allow list")
    public static class AllowCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Tool name or pattern to allow")
        private String tool;

        @Option(names = {"--scope", "-s"},
                description = "Permission scope: local, project, user (default: local)")
        private String scope = "local";

        private final PermissionService permissionService;

        @Autowired
        public AllowCommand(PermissionService permissionService) {
            this.permissionService = permissionService;
        }

        @Override
        public Integer call() {
            try {
                permissionService.allowTool(tool, scope);
                System.out.printf("Allowed tool '%s' (scope: %s)%n", tool, scope);
                return 0;
            } catch (Exception e) {
                System.out.println("Failed to allow tool: " + e.getMessage());
                return 1;
            }
        }
    }

    @Component
    @Command(name = "deny", description = "Add a tool to the deny list")
    public static class DenyCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Tool name or pattern to deny")
        private String tool;

        @Option(names = {"--scope", "-s"},
                description = "Permission scope: local, project, user (default: local)")
        private String scope = "local";

        private final PermissionService permissionService;

        @Autowired
        public DenyCommand(PermissionService permissionService) {
            this.permissionService = permissionService;
        }

        @Override
        public Integer call() {
            try {
                permissionService.denyTool(tool, scope);
                System.out.printf("Denied tool '%s' (scope: %s)%n", tool, scope);
                return 0;
            } catch (Exception e) {
                System.out.println("Failed to deny tool: " + e.getMessage());
                return 1;
            }
        }
    }

    @Component
    @Command(name = "remove", description = "Remove a permission rule for a tool")
    public static class RemoveCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Tool name or pattern to remove")
        private String tool;

        private final PermissionService permissionService;

        @Autowired
        public RemoveCommand(PermissionService permissionService) {
            this.permissionService = permissionService;
        }

        @Override
        public Integer call() {
            try {
                permissionService.removeToolRule(tool);
                System.out.printf("Removed permission rule for '%s'%n", tool);
                return 0;
            } catch (Exception e) {
                System.out.println("Failed to remove rule: " + e.getMessage());
                return 1;
            }
        }
    }

    @Component
    @Command(name = "reset", description = "Reset all custom permission rules to defaults")
    public static class ResetCommand implements Callable<Integer> {

        private final PermissionService permissionService;

        @Autowired
        public ResetCommand(PermissionService permissionService) {
            this.permissionService = permissionService;
        }

        @Override
        public Integer call() {
            try {
                permissionService.resetAllRules();
                System.out.println("All custom permission rules have been reset.");
                return 0;
            } catch (Exception e) {
                System.out.println("Failed to reset rules: " + e.getMessage());
                return 1;
            }
        }
    }
}
