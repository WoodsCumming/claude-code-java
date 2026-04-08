package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Command registration descriptor for the /web-setup (remote-setup) command.
 * Translated from src/commands/remote-setup/index.ts
 *
 * The /web-setup command sets up Claude Code on the web by connecting the
 * user's GitHub account.  It is:
 * <ul>
 *   <li>Only available on the {@code claude-ai} platform.</li>
 *   <li>Enabled only when the {@code tengu_cobalt_lantern} feature flag is on
 *       AND the {@code allow_remote_sessions} policy is permitted.</li>
 *   <li>Hidden when {@code allow_remote_sessions} is not permitted.</li>
 * </ul>
 */
@Slf4j
@Service
public class RemoteSetupService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RemoteSetupService.class);


    // -------------------------------------------------------------------------
    // Command metadata
    // -------------------------------------------------------------------------

    public static final String COMMAND_NAME = "web-setup";
    public static final String COMMAND_TYPE = "local-jsx";
    public static final String COMMAND_DESCRIPTION =
            "Setup Claude Code on the web (requires connecting your GitHub account)";

    /** Platforms on which this command is available. */
    public static final String[] AVAILABILITY = {"claude-ai"};

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final GrowthBookService growthBookService;
    private final PolicyLimitsService policyLimitsService;

    @Autowired
    public RemoteSetupService(GrowthBookService growthBookService,
                              PolicyLimitsService policyLimitsService) {
        this.growthBookService = growthBookService;
        this.policyLimitsService = policyLimitsService;
    }

    // -------------------------------------------------------------------------
    // Dynamic properties  (mirrors the getter pattern in TypeScript)
    // -------------------------------------------------------------------------

    /**
     * Whether the command is currently enabled.
     * Translated from {@code isEnabled()} in remote-setup/index.ts
     *
     * Requires both the {@code tengu_cobalt_lantern} feature flag and the
     * {@code allow_remote_sessions} policy to be active.
     */
    public boolean isEnabled() {
        boolean featureFlag = growthBookService
                .getFeatureValueCachedMayBeStale("tengu_cobalt_lantern", false);
        boolean policyAllowed = policyLimitsService.isPolicyAllowed("allow_remote_sessions");
        return featureFlag && policyAllowed;
    }

    /**
     * Whether the command should be hidden from the command palette.
     * Translated from the {@code get isHidden()} accessor in remote-setup/index.ts
     *
     * Hidden whenever the {@code allow_remote_sessions} policy is not permitted.
     */
    public boolean isHidden() {
        return !policyLimitsService.isPolicyAllowed("allow_remote_sessions");
    }

    // -------------------------------------------------------------------------
    // Command descriptor
    // -------------------------------------------------------------------------

    /**
     * Returns the full command descriptor for the command registry.
     * Mirrors the {@code Command} object exported from {@code index.ts}.
     */
    public CommandDescriptor getDescriptor() {
        return new CommandDescriptor(
                COMMAND_NAME,
                COMMAND_TYPE,
                COMMAND_DESCRIPTION,
                AVAILABILITY,
                isEnabled(),
                isHidden()
        );
    }

    // -------------------------------------------------------------------------
    // Records
    // -------------------------------------------------------------------------

    /**
     * Immutable snapshot of a command's registry metadata.
     * Mirrors the {@code Command} type from {@code commands.ts}.
     */
    public record CommandDescriptor(
            String name,
            String type,
            String description,
            String[] availability,
            boolean enabled,
            boolean hidden
    ) {}
}
