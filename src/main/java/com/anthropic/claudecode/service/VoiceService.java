package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Voice service: audio recording for push-to-talk voice input.
 * Translated from src/services/voice.ts
 *
 * Recording uses the SoX {@code rec} command or {@code arecord} (ALSA) on Linux
 * as fallbacks when a native audio module is unavailable. Only SoX / arecord
 * fallbacks are implemented here because the native NAPI module is Node-specific.
 *
 * Platform support:
 * <ul>
 *   <li>macOS – SoX (brew install sox)</li>
 *   <li>Linux  – arecord (ALSA utils) or SoX</li>
 *   <li>Windows – not supported via this service</li>
 * </ul>
 */
@Slf4j
@Service
public class VoiceService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VoiceService.class);


    // ── Constants ─────────────────────────────────────────────────────────────

    private static final int RECORDING_SAMPLE_RATE = 16_000;
    private static final int RECORDING_CHANNELS = 1;

    /** SoX silence detection: stop after this duration of silence. */
    private static final String SILENCE_DURATION_SECS = "2.0";
    private static final String SILENCE_THRESHOLD = "3%";

    // ── State ─────────────────────────────────────────────────────────────────

    private final AtomicBoolean nativeRecordingActive = new AtomicBoolean(false);
    private final AtomicReference<Process> activeRecorder = new AtomicReference<>(null);

    // ── Dependency check ──────────────────────────────────────────────────────

    /**
     * Check whether a command exists on PATH by attempting to spawn it with
     * {@code --version}.
     * Translated from hasCommand() in voice.ts
     */
    public boolean hasCommand(String cmd) {
        try {
            Process p = new ProcessBuilder(cmd, "--version")
                    .redirectErrorStream(true)
                    .start();
            p.waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Recording availability check result ───────────────────────────────────

    /**
     * Result of {@link #checkVoiceDependencies()}.
     * Translated from the return type of checkVoiceDependencies() in voice.ts
     */
    public record VoiceDependencyResult(boolean available, List<String> missing, String installCommand) {}

    /**
     * Result of {@link #checkRecordingAvailability()}.
     * Translated from RecordingAvailability in voice.ts
     */
    public record RecordingAvailability(boolean available, String reason) {}

    /**
     * Package manager information used to suggest install commands.
     * Translated from PackageManagerInfo in voice.ts
     */
    public record PackageManagerInfo(String cmd, List<String> args, String displayCommand) {}

    // ── Dependency detection ──────────────────────────────────────────────────

    /**
     * Detect the system package manager to suggest how to install SoX.
     * Translated from detectPackageManager() in voice.ts
     */
    public PackageManagerInfo detectPackageManager() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            if (hasCommand("brew")) {
                return new PackageManagerInfo("brew", List.of("install", "sox"), "brew install sox");
            }
            return null;
        }
        if (os.contains("linux") || os.contains("nix") || os.contains("nux")) {
            if (hasCommand("apt-get")) {
                return new PackageManagerInfo("sudo", List.of("apt-get", "install", "-y", "sox"), "sudo apt-get install sox");
            }
            if (hasCommand("dnf")) {
                return new PackageManagerInfo("sudo", List.of("dnf", "install", "-y", "sox"), "sudo dnf install sox");
            }
            if (hasCommand("pacman")) {
                return new PackageManagerInfo("sudo", List.of("pacman", "-S", "--noconfirm", "sox"), "sudo pacman -S sox");
            }
        }
        return null;
    }

    /**
     * Check whether all required voice dependencies are installed.
     * Translated from checkVoiceDependencies() in voice.ts
     *
     * <p>On Linux, {@code arecord} is a valid fallback. On Windows, no fallback
     * is supported. On macOS / Linux without ALSA, SoX is required.</p>
     */
    public CompletableFuture<VoiceDependencyResult> checkVoiceDependencies() {
        return CompletableFuture.supplyAsync(() -> {
            String os = System.getProperty("os.name", "").toLowerCase();

            // Windows has no supported fallback
            if (os.contains("win")) {
                return new VoiceDependencyResult(
                        false,
                        List.of("Voice mode requires a native audio module (not available on this platform)"),
                        null);
            }

            // On Linux, arecord is a valid fallback
            if ((os.contains("linux") || os.contains("nix") || os.contains("nux")) && hasCommand("arecord")) {
                return new VoiceDependencyResult(true, List.of(), null);
            }

            List<String> missing = new ArrayList<>();
            if (!hasCommand("rec")) {
                missing.add("sox (rec command)");
            }

            PackageManagerInfo pm = missing.isEmpty() ? null : detectPackageManager();
            return new VoiceDependencyResult(
                    missing.isEmpty(),
                    missing,
                    pm != null ? pm.displayCommand() : null);
        });
    }

    /**
     * Probe whether Linux has any ALSA sound cards by reading
     * {@code /proc/asound/cards}.
     * Translated from linuxHasAlsaCards() in voice.ts
     */
    public CompletableFuture<Boolean> linuxHasAlsaCards() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String content = Files.readString(Path.of("/proc/asound/cards")).trim();
                return !content.isEmpty() && !content.contains("no soundcards");
            } catch (IOException e) {
                return false;
            }
        });
    }

    /**
     * Check full recording availability (remote-env detection, platform, backend probing).
     * Translated from checkRecordingAvailability() in voice.ts
     *
     * <p>Returns an {@link RecordingAvailability} with {@code available=false} and a
     * human-readable reason when recording cannot be started, or {@code available=true}
     * with a {@code null} reason when at least one backend is usable.</p>
     */
    public CompletableFuture<RecordingAvailability> checkRecordingAvailability() {
        return CompletableFuture.supplyAsync(() -> {
            String os = System.getProperty("os.name", "").toLowerCase();

            // Windows has no supported fallback
            if (os.contains("win")) {
                return new RecordingAvailability(
                        false,
                        "Voice recording requires a native audio module, which is not supported on Windows via this service.");
            }

            // Linux: check arecord first
            if ((os.contains("linux") || os.contains("nix") || os.contains("nux")) && hasCommand("arecord")) {
                return new RecordingAvailability(true, null);
            }

            // Fallback: SoX rec
            if (!hasCommand("rec")) {
                PackageManagerInfo pm = detectPackageManager();
                String reason = pm != null
                        ? "Voice mode requires SoX for audio recording. Install it with: " + pm.displayCommand()
                        : "Voice mode requires SoX for audio recording. Install SoX manually:\n"
                          + "  macOS: brew install sox\n"
                          + "  Ubuntu/Debian: sudo apt-get install sox\n"
                          + "  Fedora: sudo dnf install sox";
                return new RecordingAvailability(false, reason);
            }

            return new RecordingAvailability(true, null);
        });
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    /**
     * Start recording audio using the best available backend.
     * Translated from startRecording() in voice.ts
     *
     * <p>Tries arecord on Linux first, then SoX. Returns {@code true} when a
     * backend started successfully, {@code false} otherwise.</p>
     *
     * @param onData     callback receiving raw 16-kHz / 16-bit / mono PCM chunks
     * @param onEnd      callback invoked when recording ends (silence or explicit stop)
     * @param silenceDetection whether to enable SoX silence-detection auto-stop
     */
    public CompletableFuture<Boolean> startRecording(
            Consumer<byte[]> onData,
            Runnable onEnd,
            boolean silenceDetection) {

        log.debug("[voice] startRecording called, os={}", System.getProperty("os.name"));

        String os = System.getProperty("os.name", "").toLowerCase();

        // Windows: no fallback
        if (os.contains("win")) {
            log.debug("[voice] Windows recording unavailable, no fallback");
            return CompletableFuture.completedFuture(false);
        }

        // Linux: prefer arecord
        if ((os.contains("linux") || os.contains("nix") || os.contains("nux")) && hasCommand("arecord")) {
            return CompletableFuture.supplyAsync(() -> startArecordRecording(onData, onEnd));
        }

        // Fallback: SoX
        return CompletableFuture.supplyAsync(() -> startSoxRecording(onData, onEnd, silenceDetection));
    }

    /**
     * Start recording with silence detection enabled (default).
     * Convenience overload.
     */
    public CompletableFuture<Boolean> startRecording(Consumer<byte[]> onData, Runnable onEnd) {
        return startRecording(onData, onEnd, true);
    }

    private boolean startSoxRecording(Consumer<byte[]> onData, Runnable onEnd, boolean silenceDetection) {
        // Record raw PCM: 16 kHz, 16-bit signed, mono, to stdout.
        // --buffer 1024 forces SoX to flush in small chunks.
        List<String> args = new ArrayList<>(List.of(
                "rec",
                "-q",
                "--buffer", "1024",
                "-t", "raw",
                "-r", String.valueOf(RECORDING_SAMPLE_RATE),
                "-e", "signed",
                "-b", "16",
                "-c", String.valueOf(RECORDING_CHANNELS),
                "-"  // stdout
        ));

        // Add silence detection filter
        if (silenceDetection) {
            args.addAll(List.of(
                    "silence", "1", "0.1", SILENCE_THRESHOLD,
                    "1", SILENCE_DURATION_SECS, SILENCE_THRESHOLD));
        }

        return spawnRecorder(args, onData, onEnd);
    }

    private boolean startArecordRecording(Consumer<byte[]> onData, Runnable onEnd) {
        // Record raw PCM: 16 kHz, 16-bit signed little-endian, mono, to stdout.
        List<String> args = new ArrayList<>(List.of(
                "arecord",
                "-f", "S16_LE",
                "-r", String.valueOf(RECORDING_SAMPLE_RATE),
                "-c", String.valueOf(RECORDING_CHANNELS),
                "-t", "raw",
                "-q",
                "-"  // stdout
        ));
        return spawnRecorder(args, onData, onEnd);
    }

    private boolean spawnRecorder(List<String> args, Consumer<byte[]> onData, Runnable onEnd) {
        try {
            Process child = new ProcessBuilder(args)
                    .redirectErrorStream(false)
                    .start();
            activeRecorder.set(child);

            // Async: pipe stdout to onData callback
            Thread dataThread = Thread.ofVirtual().start(() -> {
                try (var in = child.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int read;
                    while ((read = in.read(buf)) != -1) {
                        if (read > 0) {
                            byte[] chunk = new byte[read];
                            System.arraycopy(buf, 0, chunk, 0, read);
                            onData.accept(chunk);
                        }
                    }
                } catch (IOException e) {
                    log.debug("[voice] recorder stdout read error: {}", e.getMessage());
                } finally {
                    activeRecorder.compareAndSet(child, null);
                    onEnd.run();
                }
            });
            dataThread.setName("voice-recorder-out");

            // Drain stderr to prevent backpressure
            Thread.ofVirtual().start(() -> {
                try (var err = child.getErrorStream()) {
                    err.transferTo(java.io.OutputStream.nullOutputStream());
                } catch (IOException ignored) {}
            });

            return true;
        } catch (IOException e) {
            log.error("[voice] Failed to spawn recorder: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Stop the active recording process.
     * Translated from stopRecording() in voice.ts
     */
    public void stopRecording() {
        nativeRecordingActive.set(false);
        Process proc = activeRecorder.getAndSet(null);
        if (proc != null) {
            proc.destroy();
        }
    }

    /**
     * Request microphone permission (macOS/Windows prompt).
     * Returns true if permission is granted.
     */
    public boolean requestMicrophonePermission() {
        return true;
    }

    /** Type alias for backward compatibility. */
    public record DependencyCheck(boolean available, List<String> missing, String installCommand) {}
}
