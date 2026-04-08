package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.AnalyticsService;
import com.anthropic.claudecode.service.SettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Effort command for setting the model effort level.
 * Translated from src/commands/effort/index.ts and src/commands/effort/effort.tsx
 *
 * Maps to the TypeScript:
 *   export default {
 *     type: 'local-jsx',
 *     name: 'effort',
 *     description: 'Set effort level for model usage',
 *     argumentHint: '[low|medium|high|max|auto]',
 *     ...
 *   }
 *
 * The effort levels match the TypeScript EffortValue union type:
 * 'low' | 'medium' | 'high' | 'max' | 'auto'
 *
 * Env-override detection mirrors getEffortEnvOverride() / CLAUDE_CODE_EFFORT_LEVEL.
 */
@Slf4j
@Component
@Command(
    name = "effort",
    description = "Set effort level for model usage",
    optionListHeading = "%nOptions:%n",
    parameterListHeading = "%nParameters:%n"
)
public class EffortCommand implements Callable<Integer> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EffortCommand.class);


    /** Valid named effort levels (mirrors isEffortLevel() in effort.ts). */
    private static final List<String> NAMED_LEVELS = List.of("low", "medium", "high", "max");

    /** Arguments that trigger the help output (matches COMMON_HELP_ARGS in effort.tsx). */
    private static final List<String> HELP_ARGS = List.of("help", "-h", "--help");

    private static final String EFFORT_ENV_VAR = "CLAUDE_CODE_EFFORT_LEVEL";

    @Parameters(
        index = "0",
        description = "Effort level: low, medium, high, max, auto (omit to show current)",
        defaultValue = "",
        arity = "0..1"
    )
    private String effortArg;

    private final SettingsService settingsService;
    private final AnalyticsService analyticsService;

    @Autowired
    public EffortCommand(SettingsService settingsService, AnalyticsService analyticsService) {
        this.settingsService = settingsService;
        this.analyticsService = analyticsService;
    }

    @Override
    public Integer call() {
        String arg = effortArg == null ? "" : effortArg.trim();

        // Translated from: if (COMMON_HELP_ARGS.includes(args)) { onDone('Usage: /effort ...') }
        if (HELP_ARGS.contains(arg)) {
            printHelp();
            return 0;
        }

        // Translated from: if (!args || args === 'current' || args === 'status')
        if (arg.isEmpty() || "current".equals(arg) || "status".equals(arg)) {
            showCurrentEffort();
            return 0;
        }

        return executeEffort(arg);
    }

    // -------------------------------------------------------------------------
    // Translated from executeEffort() in effort.tsx
    // -------------------------------------------------------------------------

    private int executeEffort(String args) {
        String normalized = args.toLowerCase();

        // Translated from: if (normalized === 'auto' || normalized === 'unset')
        if ("auto".equals(normalized) || "unset".equals(normalized)) {
            return unsetEffortLevel();
        }

        // Translated from: if (!isEffortLevel(normalized))
        if (!NAMED_LEVELS.contains(normalized)) {
            System.out.println("Invalid argument: " + args + ". Valid options are: low, medium, high, max, auto");
            return 1;
        }

        return setEffortValue(normalized);
    }

    // -------------------------------------------------------------------------
    // Translated from setEffortValue() in effort.tsx
    // -------------------------------------------------------------------------

    private int setEffortValue(String effortValue) {
        // Translated from: updateSettingsForSource('userSettings', { effortLevel: persistable })
        try {
            var settings = new java.util.LinkedHashMap<>(settingsService.getUserSettings());
            settings.put("effortLevel", effortValue);
            settingsService.saveUserSettings(settings);
        } catch (Exception e) {
            System.out.println("Failed to set effort level: " + e.getMessage());
            return 1;
        }

        // Translated from: logEvent('tengu_effort_command', { effort: effortValue })
        analyticsService.logEvent("tengu_effort_command",
            new AnalyticsService.LogEventMetadata());

        // Translated from: getEffortEnvOverride() conflict detection
        String envOverride = System.getenv(EFFORT_ENV_VAR);
        if (envOverride != null && !envOverride.isBlank() && !envOverride.equalsIgnoreCase(effortValue)) {
            System.out.printf(
                "CLAUDE_CODE_EFFORT_LEVEL=%s overrides this session — clear it and %s takes over%n",
                envOverride, effortValue);
        } else {
            String description = getEffortDescription(effortValue);
            System.out.printf("Set effort level to %s: %s%n", effortValue, description);
        }

        return 0;
    }

    // -------------------------------------------------------------------------
    // Translated from unsetEffortLevel() in effort.tsx
    // -------------------------------------------------------------------------

    private int unsetEffortLevel() {
        try {
            var settings = new java.util.LinkedHashMap<>(settingsService.getUserSettings());
            settings.remove("effortLevel");
            settingsService.saveUserSettings(settings);
        } catch (Exception e) {
            System.out.println("Failed to set effort level: " + e.getMessage());
            return 1;
        }

        // Translated from: logEvent('tengu_effort_command', { effort: 'auto' })
        analyticsService.logEvent("tengu_effort_command",
            new AnalyticsService.LogEventMetadata());

        // Translated from: envOverride conflict detection when clearing
        String envOverride = System.getenv(EFFORT_ENV_VAR);
        if (envOverride != null && !envOverride.isBlank()
                && !"auto".equalsIgnoreCase(envOverride) && !"unset".equalsIgnoreCase(envOverride)) {
            System.out.printf(
                "Cleared effort from settings, but CLAUDE_CODE_EFFORT_LEVEL=%s still controls this session%n",
                envOverride);
        } else {
            System.out.println("Effort level set to auto");
        }

        return 0;
    }

    // -------------------------------------------------------------------------
    // Translated from showCurrentEffort() in effort.tsx
    // -------------------------------------------------------------------------

    private void showCurrentEffort() {
        String envOverride = System.getenv(EFFORT_ENV_VAR);

        // Translated from: envOverride === null → undefined (env explicitly set to 'none'/empty)
        if ("".equals(envOverride) || "none".equalsIgnoreCase(envOverride)) {
            System.out.println("Effort level: auto (env override disables effort)");
            return;
        }

        // env var takes precedence
        if (envOverride != null && !envOverride.isBlank()) {
            String description = getEffortDescription(envOverride);
            System.out.printf("Current effort level: %s (%s) [set by %s]%n",
                envOverride, description, EFFORT_ENV_VAR);
            return;
        }

        // Persisted setting
        String persisted = getCurrentPersistedEffort();
        if (persisted != null && !persisted.isBlank()) {
            String description = getEffortDescription(persisted);
            System.out.printf("Current effort level: %s (%s)%n", persisted, description);
        } else {
            System.out.println("Effort level: auto (using model default)");
        }
    }

    private String getCurrentPersistedEffort() {
        try {
            Object val = settingsService.getUserSettings().get("effortLevel");
            return val != null ? val.toString() : null;
        } catch (Exception e) {
            log.debug("Could not read effortLevel from settings: {}", e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Translated from getEffortValueDescription() in effort.ts
    // -------------------------------------------------------------------------

    /**
     * Return a human-readable description for an effort level.
     * Mirrors getEffortValueDescription() in src/utils/effort.ts.
     */
    private String getEffortDescription(String level) {
        return switch (level.toLowerCase()) {
            case "low"    -> "Quick, straightforward implementation";
            case "medium" -> "Balanced approach with standard testing";
            case "high"   -> "Comprehensive implementation with extensive testing";
            case "max"    -> "Maximum capability with deepest reasoning (Opus 4.6 only)";
            default       -> "Use the default effort level for your model";
        };
    }

    private void printHelp() {
        System.out.println("""
            Usage: /effort [low|medium|high|max|auto]

            Effort levels:
            - low: Quick, straightforward implementation
            - medium: Balanced approach with standard testing
            - high: Comprehensive implementation with extensive testing
            - max: Maximum capability with deepest reasoning (Opus 4.6 only)
            - auto: Use the default effort level for your model""");
    }
}
