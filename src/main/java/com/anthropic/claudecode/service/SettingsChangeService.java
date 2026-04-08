package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * Settings change detection and notification service.
 *
 * Translated from:
 *   - src/hooks/useSettingsChange.ts        — React hook wiring subscriber to changeDetector
 *   - src/utils/settings/changeDetector.ts  — fan-out / settingsChangeDetector
 *   - src/utils/settings/applySettingsChange.ts — applying the change to app state
 *
 * <h3>TypeScript → Java mapping</h3>
 * <pre>
 * useSettingsChange(onChange)         → subscribeToSettingsChange(source, onChange)
 * settingsChangeDetector.subscribe()  → SettingsService.subscribeToSettingsChanges()
 * getSettings_DEPRECATED()            → SettingsService.getMergedSettings(cwd)
 * source (SettingSource)              → String source constant
 * </pre>
 *
 * The TypeScript hook avoids re-reading settings inside each subscriber because
 * the changeDetector.fanOut already reset the cache before calling subscribers.
 * The Java port follows the same contract: {@link SettingsService#notifySettingsChanged}
 * clears the cache before broadcasting, so subscribers receive a fresh snapshot
 * but do not each trigger a separate disk read.
 */
@Slf4j
@Service
public class SettingsChangeService {



    // SettingSource constants (mirrors src/utils/settings/constants.ts)
    public static final String SOURCE_USER_SETTINGS    = "userSettings";
    public static final String SOURCE_PROJECT_SETTINGS = "projectSettings";
    public static final String SOURCE_LOCAL_SETTINGS   = "localSettings";

    private final SettingsService settingsService;

    @Autowired
    public SettingsChangeService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    // =========================================================================
    // useSettingsChange() equivalent
    // =========================================================================

    /**
     * Subscribe to settings changes for a specific source.
     *
     * Mirrors {@code useSettingsChange(onChange)} in useSettingsChange.ts.
     *
     * The callback fires with the source identifier and the freshly-loaded
     * settings map whenever the underlying settings file changes.  The cache
     * has already been cleared by {@link SettingsService#notifySettingsChanged}
     * before the callback is invoked — matching the TypeScript behaviour where
     * {@code changeDetector.fanOut} resets the cache before calling subscribers.
     *
     * @param onChange callback invoked with (source, newSettings)
     * @return a {@link Runnable} that unsubscribes when called (matches
     *         the cleanup return value of the React useEffect inside the hook)
     */
    public Runnable subscribeToSettingsChange(
            BiConsumer<String, Map<String, Object>> onChange) {

        // Wrap SettingsService's raw-map listener so the caller receives the
        // source string alongside the updated settings.
        // The source is embedded in the notification event; here we detect it
        // by comparing which file changed (delegated to the caller's context).
        // When no source context is available we report SOURCE_USER_SETTINGS as
        // a safe default, matching the TypeScript fallback.
        return settingsService.subscribeToSettingsChanges(updatedSettings -> {
            String source = resolveChangedSource();
            log.debug("Settings change detected: source={}", source);
            try {
                onChange.accept(source, updatedSettings);
            } catch (Exception e) {
                log.warn("Settings change callback threw: {}", e.getMessage());
            }
        });
    }

    /**
     * Notify all subscribers that settings have changed.
     *
     * This is the Java equivalent of calling {@code settingsChangeDetector.notifyChange(source)}
     * in TypeScript — typically invoked by the file-watcher when a settings file
     * is modified on disk.
     *
     * @param source  one of the SOURCE_* constants
     * @param cwd     working directory whose settings changed
     */
    public void notifySettingsChanged(String source, String cwd) {
        log.debug("Notifying settings change: source={}, cwd={}", source, cwd);
        settingsService.notifySettingsChanged(cwd);
    }

    /**
     * Apply a settings change from a given source.
     *
     * Translated from {@code applySettingsChange(source, cwd)} in
     * applySettingsChange.ts.  Re-reads merged settings and returns a future
     * that resolves when the in-memory state has been updated.
     *
     * @param source the settings source that changed
     * @param cwd    working directory whose settings changed
     * @return a {@link CompletableFuture} that completes after the change is applied
     */
    public CompletableFuture<Void> applySettingsChange(String source, String cwd) {
        return CompletableFuture.runAsync(() -> {
            log.debug("Applying settings change: source={}, cwd={}", source, cwd);
            String projectPath = cwd != null ? cwd : System.getProperty("user.dir");
            // Notifying will clear the cache and broadcast to all registered listeners
            settingsService.notifySettingsChanged(projectPath);
            log.debug("Settings change applied: source={}", source);
        });
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Determine which settings source triggered the current notification.
     *
     * In TypeScript the source is passed explicitly through the fan-out chain.
     * In Java the file-watcher (not yet fully implemented) would carry the source
     * through {@link #notifySettingsChanged(String, String)}.  Until the watcher
     * propagates the source we return the user settings source as a safe default.
     */
    private String resolveChangedSource() {
        // TODO: propagate source through the file-watcher notification chain
        return SOURCE_USER_SETTINGS;
    }
}
