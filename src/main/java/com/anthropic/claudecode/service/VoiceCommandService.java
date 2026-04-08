package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Command service for the /voice toggle command.
 * Translated from src/commands/voice/voice.ts
 *
 * Toggles voice mode on or off after running a series of pre-flight checks:
 * <ol>
 *   <li>Voice kill-switch / auth guard</li>
 *   <li>Recording availability (microphone)</li>
 *   <li>Voice stream API key</li>
 *   <li>Audio recording tool dependencies (e.g. SoX)</li>
 *   <li>Microphone OS permission probe</li>
 * </ol>
 */
@Slf4j
@Service
public class VoiceCommandService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VoiceCommandService.class);


    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final int LANG_HINT_MAX_SHOWS = 2;

    // -------------------------------------------------------------------------
    // Command result types
    // -------------------------------------------------------------------------

    /** Mirrors the TypeScript {@code LocalCommandResult} union. */
    public sealed interface CommandResult permits CommandResult.TextResult, CommandResult.SkipResult {
        record TextResult(String value) implements CommandResult {}
        record SkipResult() implements CommandResult {}
    }

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final VoiceService voiceService;
    private final VoiceStreamSTTService voiceStreamSTTService;
    private final SettingsService settingsService;
    private final GlobalConfigService globalConfigService;

    @Autowired
    public VoiceCommandService(
            VoiceService voiceService,
            VoiceStreamSTTService voiceStreamSTTService,
            SettingsService settingsService,
            GlobalConfigService globalConfigService) {
        this.voiceService = voiceService;
        this.voiceStreamSTTService = voiceStreamSTTService;
        this.settingsService = settingsService;
        this.globalConfigService = globalConfigService;
    }

    // -------------------------------------------------------------------------
    // Platform helper
    // -------------------------------------------------------------------------

    private static String getOs() {
        return System.getProperty("os.name", "").toLowerCase();
    }

    // -------------------------------------------------------------------------
    // call()
    // -------------------------------------------------------------------------

    /**
     * Execute the /voice command (toggle voice mode on or off).
     * Translated from call() in voice.ts
     */
    public CompletableFuture<CommandResult> call() {
        return CompletableFuture.supplyAsync(() -> {
            // --- Guard: kill-switch / auth check ---
            if (!voiceStreamSTTService.isVoiceModeEnabled()) {
                if (!voiceStreamSTTService.isAnthropicAuthEnabled()) {
                    return new CommandResult.TextResult(
                            "Voice mode requires a Claude.ai account. Please run /login to sign in.");
                }
                return new CommandResult.TextResult("Voice mode is not available.");
            }

            boolean isCurrentlyEnabled = settingsService.isVoiceEnabled();

            // --- Toggle OFF ---
            if (isCurrentlyEnabled) {
                boolean updated = settingsService.setVoiceEnabled(false);
                if (!updated) {
                    return new CommandResult.TextResult(
                            "Failed to update settings. Check your settings file for syntax errors.");
                }
                log.info("[voice] Voice mode disabled");
                return new CommandResult.TextResult("Voice mode disabled.");
            }

            // --- Toggle ON: pre-flight checks ---

            // 1. Recording availability (microphone hardware check)
            VoiceService.RecordingAvailability recording;
            try {
                recording = voiceService.checkRecordingAvailability().get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                recording = new VoiceService.RecordingAvailability(false, "Error checking recording availability");
            }
            if (!recording.available()) {
                String reason = recording.reason() != null
                        ? recording.reason()
                        : "Voice mode is not available in this environment.";
                return new CommandResult.TextResult(reason);
            }

            // 2. API key / stream availability
            if (!voiceStreamSTTService.isVoiceStreamAvailable()) {
                return new CommandResult.TextResult(
                        "Voice mode requires a Claude.ai account. Please run /login to sign in.");
            }

            // 3. Audio recording tool dependencies (SoX etc.)
            VoiceService.VoiceDependencyResult deps;
            try {
                deps = voiceService.checkVoiceDependencies().get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                deps = new VoiceService.VoiceDependencyResult(false, java.util.List.of("sox"), null);
            }
            if (!deps.available()) {
                String hint = deps.installCommand() != null
                        ? "\nInstall audio recording tools? Run: " + deps.installCommand()
                        : "\nInstall SoX manually for audio recording.";
                return new CommandResult.TextResult("No audio recording tool found." + hint);
            }

            // 4. OS microphone permission probe
            boolean micGranted = voiceService.requestMicrophonePermission();
            if (!micGranted) {
                String guidance;
                String os = getOs();
                if (os.contains("win")) {
                    guidance = "Settings \u2192 Privacy \u2192 Microphone";
                } else if (os.contains("linux")) {
                    guidance = "your system\u2019s audio settings";
                } else {
                    guidance = "System Settings \u2192 Privacy & Security \u2192 Microphone";
                }
                return new CommandResult.TextResult(
                        "Microphone access is denied. To enable it, go to " + guidance
                                + ", then run /voice again.");
            }

            // --- All checks passed — enable voice ---
            boolean updated = settingsService.setVoiceEnabled(true);
            if (!updated) {
                return new CommandResult.TextResult(
                        "Failed to update settings. Check your settings file for syntax errors.");
            }
            log.info("[voice] Voice mode enabled");

            // Build the confirmation message with optional language hint
            String key = globalConfigService.getShortcutDisplay("voice:pushToTalk", "Space");
            String sttCode = voiceStreamSTTService.normalizeLanguageForSTT(
                    settingsService.getLanguage());
            String fellBackFrom = voiceStreamSTTService.getFellBackFrom(
                    settingsService.getLanguage());

            GlobalConfigService.VoiceLangHintState hintState =
                    globalConfigService.getVoiceLangHintState();
            boolean langChanged = !sttCode.equals(hintState.lastLanguage());
            int priorCount = langChanged ? 0 : hintState.shownCount();
            boolean showHint = fellBackFrom == null && priorCount < LANG_HINT_MAX_SHOWS;

            String langNote = "";
            if (fellBackFrom != null) {
                langNote = " Note: \"" + fellBackFrom
                        + "\" is not a supported dictation language; using English. Change it via /config.";
            } else if (showHint) {
                langNote = " Dictation language: " + sttCode + " (/config to change).";
            }

            if (langChanged || showHint) {
                globalConfigService.updateVoiceLangHintState(
                        priorCount + (showHint ? 1 : 0), sttCode);
            }

            return new CommandResult.TextResult(
                    "Voice mode enabled. Hold " + key + " to record." + langNote);
        });
    }
}
