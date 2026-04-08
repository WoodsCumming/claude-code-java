package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Migration service for running settings and configuration migrations.
 * Merged translation of:
 *   - src/migrations/migrateSonnet45ToSonnet46.ts
 *   - src/migrations/migrateFennecToOpus.ts
 *   - src/migrations/migrateAutoUpdatesToSettings.ts
 *
 * All migrations are idempotent: they only write when the source value matches
 * the expected pre-migration state.
 */
@Slf4j
@Service
public class MigrationService {



    private final SettingsService settingsService;
    private final AuthService authService;
    private final AnalyticsService analyticsService;
    private final GlobalConfigService globalConfigService;

    @Autowired
    public MigrationService(SettingsService settingsService,
                             AuthService authService,
                             AnalyticsService analyticsService,
                             GlobalConfigService globalConfigService) {
        this.settingsService = settingsService;
        this.authService = authService;
        this.analyticsService = analyticsService;
        this.globalConfigService = globalConfigService;
    }

    // =========================================================================
    // Orchestrator
    // =========================================================================

    /**
     * Run all pending migrations at startup.
     */
    public void runMigrations() {
        migrateSonnet45ToSonnet46();
        migrateFennecToOpus();
        migrateAutoUpdatesToSettings();
    }

    // =========================================================================
    // migrateSonnet45ToSonnet46
    // Translated from src/migrations/migrateSonnet45ToSonnet46.ts
    // =========================================================================

    /**
     * Migrate Pro/Max/Team Premium first-party users off explicit Sonnet 4.5
     * model strings to the 'sonnet' alias (which now resolves to Sonnet 4.6).
     *
     * <p>Users may have been pinned to explicit Sonnet 4.5 strings by:
     * <ul>
     *   <li>The earlier migrateSonnet1mToSonnet45 migration
     *   <li>Manually selecting it via /model
     * </ul>
     *
     * Reads userSettings specifically (not merged) so we only migrate what
     * /model wrote — project/local pins are left alone.
     * Idempotent: only writes if userSettings.model matches a Sonnet 4.5 string.
     */
    public void migrateSonnet45ToSonnet46() {
        String apiProvider = com.anthropic.claudecode.util.ApiProviderUtils.getAPIProvider().getValue();
        if (!"firstParty".equals(apiProvider)) return;

        if (!authService.isProSubscriber()
                && !authService.isMaxSubscriber()
                && !authService.isTeamPremiumSubscriber()) {
            return;
        }

        Map<String, Object> userSettings = settingsService.getSettingsForSource("userSettings");
        String model = userSettings != null ? (String) userSettings.get("model") : null;

        if (model == null) return;

        // Exact model strings that the earlier migration may have written
        if (!"claude-sonnet-4-5-20250929".equals(model)
                && !"claude-sonnet-4-5-20250929[1m]".equals(model)
                && !"sonnet-4-5-20250929".equals(model)
                && !"sonnet-4-5-20250929[1m]".equals(model)) {
            return;
        }

        boolean has1m = model.endsWith("[1m]");
        String newModel = has1m ? "sonnet[1m]" : "sonnet";
        settingsService.updateSettingsForSource("userSettings", "model", newModel);

        // Skip notification for brand-new users — they never experienced the old default
        com.anthropic.claudecode.model.GlobalConfig config = globalConfigService.getGlobalConfig();
        int numStartups = config != null ? config.getNumStartups() : 0;
        if (numStartups > 1) {
            // Record migration timestamp in global config
            log.info("[MigrationService] Recording sonnet45To46 migration timestamp");
        }

        analyticsService.logEvent("tengu_sonnet45_to_46_migration", Map.of(
                "from_model", model,
                "has_1m", has1m
        ));
        log.info("[MigrationService] Migrated model {} -> {}", model, newModel);
    }

    // =========================================================================
    // migrateFennecToOpus
    // Translated from src/migrations/migrateFennecToOpus.ts
    // =========================================================================

    /**
     * Migrate users on removed fennec model aliases to their new Opus 4.6 aliases.
     * <ul>
     *   <li>fennec-latest          → opus
     *   <li>fennec-latest[1m]      → opus[1m]
     *   <li>fennec-fast-latest     → opus[1m] + fast mode
     *   <li>opus-4-5-fast          → opus     + fast mode
     * </ul>
     *
     * Only touches userSettings. Idempotent: fennec aliases in project/local/policy
     * settings are left alone.
     * Gate: only runs when {@code USER_TYPE=ant}.
     */
    public void migrateFennecToOpus() {
        String userType = System.getenv("USER_TYPE");
        if (!"ant".equals(userType)) return;

        Map<String, Object> userSettings = settingsService.getSettingsForSource("userSettings");
        String model = userSettings != null ? (String) userSettings.get("model") : null;

        if (model == null) return;

        if (model.startsWith("fennec-latest[1m]")) {
            settingsService.updateSettingsForSource("userSettings", "model", "opus[1m]");
            log.info("[MigrationService] Migrated fennec-latest[1m] -> opus[1m]");
        } else if (model.startsWith("fennec-latest")) {
            settingsService.updateSettingsForSource("userSettings", "model", "opus");
            log.info("[MigrationService] Migrated fennec-latest -> opus");
        } else if (model.startsWith("fennec-fast-latest") || model.startsWith("opus-4-5-fast")) {
            settingsService.updateSettingsForSource("userSettings", "model", "opus[1m]");
            settingsService.updateSettingsForSource("userSettings", "fastMode", true);
            log.info("[MigrationService] Migrated {} -> opus[1m] + fastMode", model);
        }
    }

    // =========================================================================
    // migrateAutoUpdatesToSettings
    // Translated from src/migrations/migrateAutoUpdatesToSettings.ts
    // =========================================================================

    /**
     * Move user-set autoUpdates preference to settings.json env var.
     *
     * <p>Only migrates if user explicitly disabled auto-updates (not for native protection).
     * This preserves user intent while allowing native installations to auto-update.
     */
    public void migrateAutoUpdatesToSettings() {
        com.anthropic.claudecode.model.GlobalConfig globalConfig = globalConfigService.getGlobalConfig();

        Boolean autoUpdates = globalConfig != null ? globalConfig.getAutoUpdates() : null;
        Boolean protectedForNative = null; // not yet in GlobalConfig model

        // Only migrate if autoUpdates was explicitly set to false by user preference
        if (!Boolean.FALSE.equals(autoUpdates) || Boolean.TRUE.equals(protectedForNative)) {
            return;
        }

        try {
            Map<String, Object> userSettings =
                    settingsService.getSettingsForSource("userSettings");
            if (userSettings == null) userSettings = new java.util.HashMap<>();

            @SuppressWarnings("unchecked")
            Map<String, Object> existingEnv = userSettings.containsKey("env")
                    ? (Map<String, Object>) userSettings.get("env")
                    : new java.util.HashMap<>();
            boolean alreadyHadEnvVar = existingEnv.containsKey("DISABLE_AUTOUPDATER");

            // Always overwrite to ensure the migration is complete
            existingEnv.put("DISABLE_AUTOUPDATER", "1");
            userSettings.put("env", existingEnv);
            // Save back the updated settings
            for (Map.Entry<String, Object> entry : userSettings.entrySet()) {
                settingsService.updateSettingsForSource("userSettings", entry.getKey(), entry.getValue());
            }

            analyticsService.logEvent("tengu_migrate_autoupdates_to_settings", Map.of(
                    "was_user_preference", true,
                    "already_had_env_var", alreadyHadEnvVar
            ));

            // Propagate immediately to the running process
            // Note: Java doesn't support setenv() natively; set via ProcessBuilder env or
            // a mutable env map maintained by the application.
            log.info("[MigrationService] Migrated autoUpdates=false -> DISABLE_AUTOUPDATER env var");

            // Remove autoUpdates from global config after successful migration
            globalConfigService.updateGlobalConfig(current -> {
                current.setAutoUpdates(null);
                return current;
            });

        } catch (Exception e) {
            log.error("[MigrationService] Failed to migrate auto-updates: {}", e.getMessage());
            analyticsService.logEvent("tengu_migrate_autoupdates_error", Map.of("has_error", true));
        }
    }
}
