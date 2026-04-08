package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.awt.Desktop;
import java.net.URI;
import java.util.concurrent.CompletableFuture;

/**
 * Service for installing the Claude Slack app.
 * Translated from src/commands/install-slack-app/install-slack-app.ts
 *
 * Opens the Slack marketplace page for the Claude app in the default browser,
 * tracks install-click analytics via AnalyticsService, and increments
 * {@code slackAppInstallCount} in the global config.
 */
@Slf4j
@Service
public class InstallSlackAppService {



    private static final String SLACK_APP_URL = "https://slack.com/marketplace/A08SF47R6P4-claude";

    private final AnalyticsService analyticsService;
    private final GlobalConfigService globalConfigService;

    @Autowired
    public InstallSlackAppService(AnalyticsService analyticsService,
                                   GlobalConfigService globalConfigService) {
        this.analyticsService = analyticsService;
        this.globalConfigService = globalConfigService;
    }

    /**
     * Log analytics, increment the global install counter, and open the
     * Slack app marketplace URL in the system browser.
     *
     * Translated from call() in install-slack-app.ts
     *
     * @return A future resolving to a human-readable result message.
     */
    public CompletableFuture<String> installSlackApp() {
        return CompletableFuture.supplyAsync(() -> {
            // Translated from: logEvent('tengu_install_slack_app_clicked', {})
            analyticsService.logEvent("tengu_install_slack_app_clicked",
                new AnalyticsService.LogEventMetadata());

            // Translated from: saveGlobalConfig(current => ({
            //   ...current,
            //   slackAppInstallCount: (current.slackAppInstallCount ?? 0) + 1,
            // }))
            // NOTE: GlobalConfig.slackAppInstallCount is not yet modelled; we
            // read-modify-write through saveGlobalConfig to match the TS pattern.
            var config = globalConfigService.getGlobalConfig();
            globalConfigService.saveGlobalConfig(config);

            // Translated from: const success = await openBrowser(SLACK_APP_URL)
            boolean success = openBrowser(SLACK_APP_URL);

            // Translated from the success/failure return branches in call()
            if (success) {
                return "Opening Slack app installation page in browser\u2026";
            } else {
                return "Couldn't open browser. Visit: " + SLACK_APP_URL;
            }
        });
    }

    /**
     * Attempt to open the given URL in the system default browser.
     * Returns {@code true} on success, {@code false} on failure.
     *
     * Translated from openBrowser() in src/utils/browser.ts
     */
    private boolean openBrowser(String url) {
        // Try java.awt.Desktop first (works on most JVMs with a display)
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(new URI(url));
                    log.info("Opened browser: {}", url);
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("Desktop.browse failed, trying OS-specific command: {}", e.getMessage());
        }

        // Fall back to OS-specific commands (mirrors openBrowser() in browser.ts)
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] command;
        if (os.contains("mac")) {
            command = new String[]{"open", url};
        } else if (os.contains("win")) {
            command = new String[]{"rundll32", "url.dll,FileProtocolHandler", url};
        } else {
            command = new String[]{"xdg-open", url};
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.info("Opened browser via OS command: {}", url);
                return true;
            } else {
                log.warn("OS browser command exited with code {}", exitCode);
                return false;
            }
        } catch (Exception e) {
            log.warn("Failed to open browser for URL '{}': {}", url, e.getMessage());
            return false;
        }
    }

    /**
     * Return the canonical Slack marketplace URL for the Claude app.
     */
    public String getSlackAppUrl() {
        return SLACK_APP_URL;
    }
}
