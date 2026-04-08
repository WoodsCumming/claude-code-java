package com.anthropic.claudecode.util;

import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cross-platform system directory resolution.
 * Translated from src/utils/systemDirectories.ts
 *
 * <p>Handles differences between Windows, macOS, Linux, and WSL.
 * On Linux/WSL the XDG Base Directory specification is honoured.
 * On Windows the {@code USERPROFILE} env var is preferred (handles localized folder names).
 */
@Slf4j
public class SystemDirectories {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SystemDirectories.class);


    // ── Options ───────────────────────────────────────────────────────────────

    /**
     * Options for overriding defaults, primarily for testing.
     * Translated from {@code SystemDirectoriesOptions} in systemDirectories.ts.
     */
    @Builder
    public record SystemDirectoriesOptions(
            /** Override environment variable map (defaults to {@link System#getenv()}). */
            Map<String, String> env,
            /** Override home directory (defaults to {@code user.home} system property). */
            String homeDir,
            /** Override platform detection (defaults to {@link PlatformUtils#getPlatform()}). */
            PlatformUtils.Platform platform
    ) {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Get cross-platform system directories using the real environment.
     * Translated from {@code getSystemDirectories()} in systemDirectories.ts.
     */
    public static Map<String, String> getSystemDirectories() {
        return getSystemDirectories(null);
    }

    /**
     * Get cross-platform system directories with optional overrides for testing.
     *
     * @param options optional overrides; pass {@code null} to use real environment
     */
    public static Map<String, String> getSystemDirectories(SystemDirectoriesOptions options) {
        PlatformUtils.Platform platform =
                (options != null && options.platform() != null)
                        ? options.platform()
                        : PlatformUtils.getPlatform();

        String homeDir =
                (options != null && options.homeDir() != null)
                        ? options.homeDir()
                        : System.getProperty("user.home");

        // Helper to resolve an env var, falling back to a default path
        java.util.function.BiFunction<String, String, String> resolve = (envKey, defaultPath) -> {
            if (options != null && options.env() != null) {
                String v = options.env().get(envKey);
                return (v != null) ? v : defaultPath;
            }
            String v = System.getenv(envKey);
            return (v != null) ? v : defaultPath;
        };

        String sep = File.separator;
        String defaultDesktop   = homeDir + sep + "Desktop";
        String defaultDocuments = homeDir + sep + "Documents";
        String defaultDownloads = homeDir + sep + "Downloads";

        Map<String, String> dirs = new LinkedHashMap<>();

        switch (platform) {
            case WINDOWS -> {
                // Windows: prefer USERPROFILE (handles localized folder names)
                String userProfile = resolve.apply("USERPROFILE", homeDir);
                dirs.put("HOME",      homeDir);
                dirs.put("DESKTOP",   userProfile + "\\Desktop");
                dirs.put("DOCUMENTS", userProfile + "\\Documents");
                dirs.put("DOWNLOADS", userProfile + "\\Downloads");
            }
            case LINUX, WSL -> {
                // Linux / WSL: honour XDG Base Directory specification
                dirs.put("HOME",      homeDir);
                dirs.put("DESKTOP",   resolve.apply("XDG_DESKTOP_DIR",  defaultDesktop));
                dirs.put("DOCUMENTS", resolve.apply("XDG_DOCUMENTS_DIR", defaultDocuments));
                dirs.put("DOWNLOADS", resolve.apply("XDG_DOWNLOAD_DIR",  defaultDownloads));
            }
            default -> {
                // macOS and unknown platforms use standard paths
                if (platform == PlatformUtils.Platform.UNKNOWN) {
                    log.debug("Unknown platform detected, using default paths");
                }
                dirs.put("HOME",      homeDir);
                dirs.put("DESKTOP",   defaultDesktop);
                dirs.put("DOCUMENTS", defaultDocuments);
                dirs.put("DOWNLOADS", defaultDownloads);
            }
        }

        return dirs;
    }

    private SystemDirectories() {}
}
