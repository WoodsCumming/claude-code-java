package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Command service for the /thinkback-play command.
 * Translated from src/commands/thinkback-play/thinkback-play.ts
 *
 * <p>Plays the thinkback animation using the installed thinkback plugin.
 * Looks up the plugin installation path from the installed-plugins registry,
 * resolves the skill directory, and delegates to {@link ThinkbackAnimationService}
 * to run the animation.
 */
@Slf4j
@Service
public class ThinkbackPlayCommandService {



    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /** Official plugin marketplace name. */
    private static final String OFFICIAL_MARKETPLACE_NAME = "claude-code-marketplace";

    /** Internal marketplace name used for Anthropic employees. */
    private static final String INTERNAL_MARKETPLACE_NAME = "claude-code-marketplace";

    /** The name of the skill within the thinkback plugin. */
    private static final String SKILL_NAME = "thinkback";

    // -------------------------------------------------------------------------
    // Command result type
    // -------------------------------------------------------------------------

    /** Mirrors the TypeScript {@code LocalCommandResult} union. */
    public sealed interface CommandResult permits CommandResult.TextResult, CommandResult.SkipResult {
        record TextResult(String value) implements CommandResult {}
        record SkipResult() implements CommandResult {}
    }

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final InstalledPluginsService installedPluginsService;
    private final ThinkbackAnimationService thinkbackAnimationService;

    @Autowired
    public ThinkbackPlayCommandService(
            InstalledPluginsService installedPluginsService,
            ThinkbackAnimationService thinkbackAnimationService) {
        this.installedPluginsService = installedPluginsService;
        this.thinkbackAnimationService = thinkbackAnimationService;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the plugin ID for the thinkback plugin.
     * Translated from {@code getPluginId()} in thinkback-play.ts
     *
     * <p>Internal (ant) users use the internal marketplace; all others use the
     * official marketplace.
     */
    private String getPluginId() {
        String marketplaceName = "ant".equals(System.getenv("USER_TYPE"))
                ? INTERNAL_MARKETPLACE_NAME
                : OFFICIAL_MARKETPLACE_NAME;
        return SKILL_NAME + "@" + marketplaceName;
    }

    // -------------------------------------------------------------------------
    // Execution
    // -------------------------------------------------------------------------

    /**
     * Execute the /thinkback-play command.
     * Translated from {@code call()} in thinkback-play.ts
     *
     * <ol>
     *   <li>Loads the installed-plugins v2 registry.</li>
     *   <li>Looks up the thinkback plugin installation entry.</li>
     *   <li>Resolves the skill directory path.</li>
     *   <li>Delegates to {@link ThinkbackAnimationService#playAnimation(Path)} to run the animation.</li>
     * </ol>
     *
     * @return a {@link CompletableFuture} resolving to the {@link CommandResult}
     */
    public CompletableFuture<CommandResult> call() {
        return CompletableFuture.supplyAsync(() -> {
            // Load v2 plugin registry
            InstalledPluginsService.PluginsV2Data v2Data = installedPluginsService.loadInstalledPluginsV2();
            String pluginId = getPluginId();
            List<InstalledPluginsService.PluginInstallation> installations =
                    v2Data.plugins().get(pluginId);

            // Guard: plugin not installed
            if (installations == null || installations.isEmpty()) {
                log.warn("[thinkback-play] Plugin '{}' is not installed", pluginId);
                return new CommandResult.TextResult(
                        "Thinkback plugin not installed. Run /think-back first to install it.");
            }

            // Guard: missing install path
            InstalledPluginsService.PluginInstallation firstInstall = installations.get(0);
            if (firstInstall == null || firstInstall.installPath() == null) {
                log.warn("[thinkback-play] Plugin '{}' installation path not found", pluginId);
                return new CommandResult.TextResult(
                        "Thinkback plugin installation path not found.");
            }

            // Resolve skill directory
            Path skillDir = firstInstall.installPath().resolve("skills").resolve(SKILL_NAME);
            log.info("[thinkback-play] Playing animation from skill dir: {}", skillDir);

            // Delegate to animation service
            ThinkbackAnimationService.AnimationResult result =
                    thinkbackAnimationService.playAnimation(skillDir);
            return new CommandResult.TextResult(result.message());
        });
    }
}
