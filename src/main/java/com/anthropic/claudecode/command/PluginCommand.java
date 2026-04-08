package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.PluginService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Plugin management CLI command.
 *
 * Note: The TypeScript source for src/commands/plugins/index.ts does not exist in
 * the analysed codebase. This Java class is an idiomatic translation of the plugin
 * management pattern inferred from adjacent plugin-service code.
 *
 * Provides sub-commands: install, uninstall, enable, disable, list, update
 */
@Slf4j
@Component
@Command(
    name = "plugin",
    description = "Manage Claude Code plugins",
    mixinStandardHelpOptions = true,
    subcommands = {
        PluginCommand.InstallCommand.class,
        PluginCommand.UninstallCommand.class,
        PluginCommand.EnableCommand.class,
        PluginCommand.DisableCommand.class,
        PluginCommand.ListCommand.class,
        PluginCommand.UpdateCommand.class,
    }
)
public class PluginCommand implements Runnable {



    @Override
    public void run() {
        System.out.println("Use 'claude plugin --help' for available sub-commands.");
    }

    // -----------------------------------------------------------------
    // install
    // -----------------------------------------------------------------

    @Component
    @Command(name = "install", description = "Install a plugin")
    public static class InstallCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Plugin identifier (name or name@marketplace)")
        private String plugin;

        @Option(names = {"--scope"}, description = "Installation scope: user, project, local (default: user)")
        private String scope = "user";

        private final PluginService pluginService;

        @Autowired
        public InstallCommand(PluginService pluginService) {
            this.pluginService = pluginService;
        }

        @Override
        public Integer call() {
            log.info("Installing plugin '{}' with scope '{}'", plugin, scope);
            PluginService.PluginOperationResult result = pluginService.installPlugin(plugin, scope);
            System.out.println(result.message());
            return result.success() ? 0 : 1;
        }
    }

    // -----------------------------------------------------------------
    // uninstall
    // -----------------------------------------------------------------

    @Component
    @Command(name = "uninstall", description = "Uninstall a plugin")
    public static class UninstallCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Plugin name to uninstall")
        private String plugin;

        private final PluginService pluginService;

        @Autowired
        public UninstallCommand(PluginService pluginService) {
            this.pluginService = pluginService;
        }

        @Override
        public Integer call() {
            log.info("Uninstalling plugin '{}'", plugin);
            PluginService.PluginOperationResult result = pluginService.uninstallPlugin(plugin);
            System.out.println(result.message());
            return result.success() ? 0 : 1;
        }
    }

    // -----------------------------------------------------------------
    // enable
    // -----------------------------------------------------------------

    @Component
    @Command(name = "enable", description = "Enable a previously disabled plugin")
    public static class EnableCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Plugin name to enable")
        private String plugin;

        private final PluginService pluginService;

        @Autowired
        public EnableCommand(PluginService pluginService) {
            this.pluginService = pluginService;
        }

        @Override
        public Integer call() {
            PluginService.PluginOperationResult result = pluginService.enablePlugin(plugin);
            System.out.println(result.message());
            return result.success() ? 0 : 1;
        }
    }

    // -----------------------------------------------------------------
    // disable
    // -----------------------------------------------------------------

    @Component
    @Command(name = "disable", description = "Disable a plugin without removing it")
    public static class DisableCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Plugin name to disable")
        private String plugin;

        private final PluginService pluginService;

        @Autowired
        public DisableCommand(PluginService pluginService) {
            this.pluginService = pluginService;
        }

        @Override
        public Integer call() {
            PluginService.PluginOperationResult result = pluginService.disablePlugin(plugin);
            System.out.println(result.message());
            return result.success() ? 0 : 1;
        }
    }

    // -----------------------------------------------------------------
    // list
    // -----------------------------------------------------------------

    @Component
    @Command(name = "list", description = "List all installed plugins")
    public static class ListCommand implements Callable<Integer> {

        private final PluginService pluginService;

        @Autowired
        public ListCommand(PluginService pluginService) {
            this.pluginService = pluginService;
        }

        @Override
        public Integer call() {
            List<PluginService.PluginInfo> plugins = pluginService.listInstalledPlugins();
            if (plugins.isEmpty()) {
                System.out.println("No plugins installed.");
            } else {
                System.out.println("Installed plugins:");
                for (PluginService.PluginInfo p : plugins) {
                    String status = p.isEnabled() ? "[enabled] " : "[disabled]";
                    String version = p.getVersion() != null ? " v" + p.getVersion() : "";
                    String desc = p.getDescription() != null ? " — " + p.getDescription() : "";
                    System.out.printf("  %s %s%s%s%n", status, p.getName(), version, desc);
                }
            }
            return 0;
        }
    }

    // -----------------------------------------------------------------
    // update
    // -----------------------------------------------------------------

    @Component
    @Command(name = "update", description = "Update one or all plugins to their latest versions")
    public static class UpdateCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Plugin name to update (omit to update all)", arity = "0..1")
        private String plugin;

        private final PluginService pluginService;

        @Autowired
        public UpdateCommand(PluginService pluginService) {
            this.pluginService = pluginService;
        }

        @Override
        public Integer call() {
            if (plugin != null && !plugin.isBlank()) {
                log.info("Updating plugin '{}'", plugin);
                PluginService.PluginOperationResult result = pluginService.updatePlugin(plugin);
                System.out.println(result.message());
                return result.success() ? 0 : 1;
            } else {
                log.info("Updating all plugins");
                PluginService.PluginOperationResult result = pluginService.updateAllPlugins();
                System.out.println(result.message());
                return result.success() ? 0 : 1;
            }
        }
    }
}
