package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Command registration descriptor for the /sandbox command.
 * Translated from src/commands/sandbox-toggle/index.ts
 *
 * The /sandbox command is a local-jsx command that lets users configure
 * sandboxing settings interactively.  Its description is dynamically
 * computed from the current sandbox state (enabled/disabled, auto-allow,
 * fallback, policy lock, and dependency health).
 */
@Slf4j
@Service
public class SandboxToggleService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SandboxToggleService.class);


    // -------------------------------------------------------------------------
    // Command metadata
    // -------------------------------------------------------------------------

    public static final String COMMAND_NAME = "sandbox";
    public static final String COMMAND_TYPE = "local-jsx";
    public static final String ARGUMENT_HINT = "exclude \"command pattern\"";
    public static final boolean IMMEDIATE = true;

    // -------------------------------------------------------------------------
    // Figure characters (mirrors the `figures` npm package used in TS)
    // -------------------------------------------------------------------------

    private static final String FIGURE_TICK    = "\u2714";  // ✔
    private static final String FIGURE_CIRCLE  = "\u25cb";  // ○
    private static final String FIGURE_WARNING = "\u26a0";  // ⚠

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final SandboxService sandboxService;

    @Autowired
    public SandboxToggleService(SandboxService sandboxService) {
        this.sandboxService = sandboxService;
    }

    // -------------------------------------------------------------------------
    // Dynamic properties  (mirrors the getter pattern in TypeScript)
    // -------------------------------------------------------------------------

    /**
     * Dynamically compute the human-readable description shown in the command
     * palette entry.
     * Translated from the {@code get description()} accessor in sandbox-toggle/index.ts
     */
    public String getDescription() {
        boolean currentlyEnabled = sandboxService.isSandboxEnabled();
        boolean hasDeps          = checkDependencies();
        boolean autoAllow        = isAutoAllowBashIfSandboxedEnabled();
        boolean allowUnsandboxed = areUnsandboxedCommandsAllowed();
        boolean isLocked         = areSandboxSettingsLockedByPolicy();

        // Icon: warning when deps are missing, tick when enabled, circle when disabled
        String icon;
        if (!hasDeps) {
            icon = FIGURE_WARNING;
        } else {
            icon = currentlyEnabled ? FIGURE_TICK : FIGURE_CIRCLE;
        }

        // Status text
        String statusText;
        if (currentlyEnabled) {
            statusText = autoAllow ? "sandbox enabled (auto-allow)" : "sandbox enabled";
            if (allowUnsandboxed) {
                statusText += ", fallback allowed";
            }
        } else {
            statusText = "sandbox disabled";
        }

        if (isLocked) {
            statusText += " (managed)";
        }

        return icon + " " + statusText + " (\u23ce to configure)";
    }

    /**
     * Whether the command should be hidden from the command palette.
     * Translated from the {@code get isHidden()} accessor in sandbox-toggle/index.ts
     *
     * Hidden when the current platform does not support sandboxing or is not
     * in the enabled-platforms list.
     */
    public boolean isHidden() {
        return !isSupportedPlatform() || !isPlatformInEnabledList();
    }

    // -------------------------------------------------------------------------
    // Command descriptor
    // -------------------------------------------------------------------------

    /**
     * Returns a snapshot of the command descriptor for the command registry.
     * Mirrors the {@code Command} object exported from {@code index.ts}.
     */
    public CommandDescriptor getDescriptor() {
        return new CommandDescriptor(
                COMMAND_NAME,
                COMMAND_TYPE,
                getDescription(),
                ARGUMENT_HINT,
                IMMEDIATE,
                isHidden()
        );
    }

    // -------------------------------------------------------------------------
    // Sandbox state helpers
    // Translated from SandboxManager statics in sandbox-adapter.ts
    // -------------------------------------------------------------------------

    /**
     * Whether auto-allow for Bash commands inside the sandbox is enabled.
     * Translated from {@code SandboxManager.isAutoAllowBashIfSandboxedEnabled()} in sandbox-adapter.ts
     */
    public boolean isAutoAllowBashIfSandboxedEnabled() {
        SandboxService.SandboxConfig cfg = sandboxService.getConfig();
        // Auto-allow is implicitly disabled when no config is present
        return cfg != null && cfg.isIgnoreViolations();
    }

    /**
     * Whether unsandboxed commands (fallback) are allowed.
     * Translated from {@code SandboxManager.areUnsandboxedCommandsAllowed()} in sandbox-adapter.ts
     */
    public boolean areUnsandboxedCommandsAllowed() {
        SandboxService.SandboxConfig cfg = sandboxService.getConfig();
        if (cfg == null) return false;
        // Unsandboxed fallback is represented by an empty network restriction list
        List<String> net = cfg.getNetworkRestrictions();
        return net == null || net.isEmpty();
    }

    /**
     * Whether sandbox settings are locked by policy.
     * Translated from {@code SandboxManager.areSandboxSettingsLockedByPolicy()} in sandbox-adapter.ts
     */
    public boolean areSandboxSettingsLockedByPolicy() {
        // Placeholder — wire to PolicyLimitsService when policy integration is available
        return false;
    }

    /**
     * Check that all required sandbox dependencies are present.
     * Translated from {@code SandboxManager.checkDependencies()} in sandbox-adapter.ts
     *
     * @return {@code true} when no dependency errors are detected
     */
    public boolean checkDependencies() {
        // Sandbox support requires the OS-level sandbox tool to be installed.
        // A simple heuristic: check for the platform-native binary.
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return hasCommand("sandbox-exec");
        }
        if (os.contains("linux")) {
            return hasCommand("bwrap") || hasCommand("firejail");
        }
        // Windows: not currently supported
        return false;
    }

    /**
     * Whether the current platform supports sandboxing at all.
     * Translated from {@code SandboxManager.isSupportedPlatform()} in sandbox-adapter.ts
     */
    public boolean isSupportedPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("mac") || os.contains("linux");
    }

    /**
     * Whether the current platform is in the sandbox-enabled list.
     * Translated from {@code SandboxManager.isPlatformInEnabledList()} in sandbox-adapter.ts
     */
    public boolean isPlatformInEnabledList() {
        // Mirrors the upstream enabled-platforms list; currently macOS and Linux.
        return isSupportedPlatform();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private boolean hasCommand(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("which", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
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
            String argumentHint,
            boolean immediate,
            boolean hidden
    ) {}
}
