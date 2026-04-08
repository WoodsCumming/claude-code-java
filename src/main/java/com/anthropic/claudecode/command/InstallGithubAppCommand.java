package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.SetupGitHubActionsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Command definition for 'install-github-app'.
 * Translated from src/commands/install-github-app/index.ts
 *
 * <p>Registers the "Set up Claude GitHub Actions for a repository" command.
 * In the TypeScript source this is a lazy-loaded local-jsx command available
 * on the 'claude-ai' and 'console' surfaces, and disabled when the
 * {@code DISABLE_INSTALL_GITHUB_APP_COMMAND} environment variable is truthy.
 */
@Slf4j
@Component
@Command(
    name = "install-github-app",
    description = "Set up Claude GitHub Actions for a repository"
)
public class InstallGithubAppCommand implements Callable<Integer> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InstallGithubAppCommand.class);


    /**
     * Availability surfaces: "claude-ai" and "console".
     * Translated from: availability: ['claude-ai', 'console'] in index.ts
     */
    public static final String[] AVAILABILITY = {"claude-ai", "console"};

    /**
     * Whether the command is enabled.
     * Translated from: isEnabled: () => !isEnvTruthy(process.env.DISABLE_INSTALL_GITHUB_APP_COMMAND)
     */
    @Option(
        names = {"--dry-run"},
        description = "Preview the GitHub Actions setup without making changes",
        defaultValue = "false"
    )
    private boolean dryRun;

    private final SetupGitHubActionsService setupGitHubActionsService;

    @Autowired
    public InstallGithubAppCommand(SetupGitHubActionsService setupGitHubActionsService) {
        this.setupGitHubActionsService = setupGitHubActionsService;
    }

    /**
     * Check whether the command is enabled in the current environment.
     * Translated from: isEnabled: () => !isEnvTruthy(process.env.DISABLE_INSTALL_GITHUB_APP_COMMAND)
     */
    public static boolean isEnabled() {
        String val = System.getenv("DISABLE_INSTALL_GITHUB_APP_COMMAND");
        if (val == null || val.isBlank()) return true;
        return !val.equalsIgnoreCase("true")
            && !val.equals("1")
            && !val.equalsIgnoreCase("yes");
    }

    @Override
    public Integer call() {
        if (!isEnabled()) {
            log.debug("install-github-app command is disabled via DISABLE_INSTALL_GITHUB_APP_COMMAND");
            System.err.println("Error: install-github-app command is disabled.");
            return 1;
        }

        log.info("Setting up Claude GitHub Actions for repository (dryRun={})", dryRun);

        try {
            setupGitHubActionsService.setupGitHubActions(dryRun).join();
            return 0;
        } catch (Exception e) {
            log.error("Failed to set up GitHub Actions: {}", e.getMessage(), e);
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
