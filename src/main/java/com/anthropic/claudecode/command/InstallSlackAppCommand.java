package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.InstallSlackAppService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * Command definition for 'install-slack-app'.
 * Translated from src/commands/install-slack-app/index.ts
 *
 * <p>In the TypeScript source this is a lazy-loaded synchronous local command
 * available only on the 'claude-ai' surface, with supportsNonInteractive set to
 * false. It opens the Slack marketplace page for the Claude app.
 */
@Slf4j
@Component
@Command(
    name = "install-slack-app",
    description = "Install the Claude Slack app"
)
public class InstallSlackAppCommand implements Callable<Integer> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InstallSlackAppCommand.class);


    /**
     * Availability surfaces.
     * Translated from: availability: ['claude-ai'] in index.ts
     */
    public static final String[] AVAILABILITY = {"claude-ai"};

    /**
     * Non-interactive sessions are not supported.
     * Translated from: supportsNonInteractive: false in index.ts
     */
    public static final boolean SUPPORTS_NON_INTERACTIVE = false;

    private final InstallSlackAppService installSlackAppService;

    @Autowired
    public InstallSlackAppCommand(InstallSlackAppService installSlackAppService) {
        this.installSlackAppService = installSlackAppService;
    }

    @Override
    public Integer call() {
        log.info("Running install-slack-app command");
        try {
            String result = installSlackAppService.installSlackApp().join();
            System.out.println(result);
            return 0;
        } catch (Exception e) {
            log.error("Failed to install Slack app: {}", e.getMessage(), e);
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
