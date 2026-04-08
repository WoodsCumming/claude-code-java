package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Platform detection utilities.
 * Translated from src/utils/platform.ts
 *
 * <p>Detects the current OS (macOS, Windows, WSL, Linux, or unknown), WSL
 * version, Linux distro info, and VCS markers in a directory.
 */
@Slf4j
@Component
public class PlatformUtils {



    // ── Platform enum ─────────────────────────────────────────────────────────

    public enum Platform {
        MACOS("macos"),
        WINDOWS("windows"),
        WSL("wsl"),
        LINUX("linux"),
        UNKNOWN("unknown");

        private final String value;

        Platform(String value) { this.value = value; }

        public String getValue() { return value; }
    }

    /** Platforms officially supported by Claude Code (mirrors TypeScript SUPPORTED_PLATFORMS). */
    public static final List<Platform> SUPPORTED_PLATFORMS = List.of(Platform.MACOS, Platform.WSL);

    // ── VCS marker table (mirrors TypeScript VCS_MARKERS) ────────────────────

    private static final List<String[]> VCS_MARKERS = List.of(
            new String[]{".git",      "git"},
            new String[]{".hg",       "mercurial"},
            new String[]{".svn",      "svn"},
            new String[]{".p4config", "perforce"},
            new String[]{"$tf",       "tfs"},
            new String[]{".tfvc",     "tfs"},
            new String[]{".jj",       "jujutsu"},
            new String[]{".sl",       "sapling"}
    );

    // ── Cached state ──────────────────────────────────────────────────────────

    private static volatile Platform cachedPlatform = null;
    private static volatile Optional<String> cachedWslVersion = null;

    // ── getPlatform ───────────────────────────────────────────────────────────

    /**
     * Get the current platform, memoized after first call.
     * Translated from {@code getPlatform()} in platform.ts.
     */
    public static synchronized Platform getPlatform() {
        if (cachedPlatform != null) return cachedPlatform;

        try {
            String os = System.getProperty("os.name", "").toLowerCase();

            if (os.contains("mac") || os.contains("darwin")) {
                cachedPlatform = Platform.MACOS;
            } else if (os.contains("win")) {
                cachedPlatform = Platform.WINDOWS;
            } else if (os.contains("linux") || os.isEmpty()) {
                // Check for WSL by reading /proc/version
                try {
                    String procVersion = Files.readString(Paths.get("/proc/version"));
                    if (procVersion.toLowerCase().contains("microsoft") ||
                            procVersion.toLowerCase().contains("wsl")) {
                        cachedPlatform = Platform.WSL;
                    } else {
                        cachedPlatform = Platform.LINUX;
                    }
                } catch (IOException e) {
                    log.debug("Could not read /proc/version, assuming regular Linux: {}", e.getMessage());
                    cachedPlatform = Platform.LINUX;
                }
            } else {
                cachedPlatform = Platform.UNKNOWN;
            }
        } catch (Exception e) {
            log.error("Error detecting platform: {}", e.getMessage());
            cachedPlatform = Platform.UNKNOWN;
        }

        return cachedPlatform;
    }

    // ── getWslVersion ─────────────────────────────────────────────────────────

    /**
     * Detect the WSL version from {@code /proc/version}, memoized after first call.
     * Only meaningful on Linux systems; returns empty on other platforms.
     * Translated from {@code getWslVersion()} in platform.ts.
     *
     * @return WSL version string (e.g. "2"), or empty if not WSL or unknown
     */
    public static synchronized Optional<String> getWslVersion() {
        if (cachedWslVersion != null) return cachedWslVersion;

        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("linux") && !os.isEmpty()) {
            cachedWslVersion = Optional.empty();
            return cachedWslVersion;
        }

        try {
            String procVersion = Files.readString(Paths.get("/proc/version"));

            // Check for explicit WSL version marker: "WSL2", "WSL3", etc.
            Matcher wslMatch = Pattern.compile("WSL(\\d+)", Pattern.CASE_INSENSITIVE)
                    .matcher(procVersion);
            if (wslMatch.find()) {
                cachedWslVersion = Optional.of(wslMatch.group(1));
                return cachedWslVersion;
            }

            // Older WSL1 format: "4.4.0-19041-Microsoft"
            if (procVersion.toLowerCase().contains("microsoft")) {
                cachedWslVersion = Optional.of("1");
                return cachedWslVersion;
            }

            cachedWslVersion = Optional.empty();
        } catch (IOException e) {
            log.debug("Could not read /proc/version for WSL detection: {}", e.getMessage());
            cachedWslVersion = Optional.empty();
        }

        return cachedWslVersion;
    }

    // ── LinuxDistroInfo ───────────────────────────────────────────────────────

    /**
     * Linux distribution information.
     * Translated from {@code LinuxDistroInfo} in platform.ts.
     */
    public record LinuxDistroInfo(
            String linuxDistroId,
            String linuxDistroVersion,
            String linuxKernel
    ) {}

    /**
     * Asynchronously detect Linux distro info from {@code /etc/os-release}.
     * Returns empty if not on a Linux system.
     * Translated from {@code getLinuxDistroInfo()} in platform.ts.
     */
    public static CompletableFuture<Optional<LinuxDistroInfo>> getLinuxDistroInfo() {
        return CompletableFuture.supplyAsync(() -> {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (!os.contains("linux") && !os.isEmpty()) {
                return Optional.empty();
            }

            String kernel = System.getProperty("os.version", null);
            String distroId = null;
            String distroVersion = null;

            try {
                String content = Files.readString(Paths.get("/etc/os-release"));
                for (String line : content.split("\n")) {
                    Matcher m = Pattern.compile("^(ID|VERSION_ID)=(.*)$").matcher(line);
                    if (m.matches()) {
                        String val = m.group(2).replaceAll("^\"|\"$", "");
                        if ("ID".equals(m.group(1))) {
                            distroId = val;
                        } else {
                            distroVersion = val;
                        }
                    }
                }
            } catch (IOException e) {
                // /etc/os-release may not exist on all Linux systems
            }

            return Optional.of(new LinuxDistroInfo(distroId, distroVersion, kernel));
        });
    }

    // ── detectVcs ─────────────────────────────────────────────────────────────

    /**
     * Detect which VCS systems are present in a directory.
     * Translated from {@code detectVcs()} in platform.ts.
     *
     * @param dir directory to inspect; defaults to current working directory if null
     * @return list of detected VCS names (e.g. "git", "mercurial")
     */
    public static CompletableFuture<List<String>> detectVcs(String dir) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> detected = new ArrayList<>();

            // Check for Perforce via env var
            if (System.getenv("P4PORT") != null) {
                detected.add("perforce");
            }

            try {
                String targetDir = (dir != null) ? dir : System.getProperty("user.dir");
                java.io.File targetFile = new java.io.File(targetDir);
                String[] entries = targetFile.list();
                if (entries != null) {
                    java.util.Set<String> entrySet = new java.util.HashSet<>(List.of(entries));
                    for (String[] marker : VCS_MARKERS) {
                        if (entrySet.contains(marker[0]) && !detected.contains(marker[1])) {
                            detected.add(marker[1]);
                        }
                    }
                }
            } catch (Exception e) {
                // Directory may not be readable
            }

            return detected;
        });
    }

    // ── Convenience predicates ────────────────────────────────────────────────

    public static boolean isMacOS()   { return getPlatform() == Platform.MACOS; }
    public static boolean isWindows() { return getPlatform() == Platform.WINDOWS; }
    public static boolean isLinux()   { return getPlatform() == Platform.LINUX; }
    public static boolean isWSL()     { return getPlatform() == Platform.WSL; }

    // ── Testing helper ────────────────────────────────────────────────────────

    /** Reset memoized state. For testing only. */
    static synchronized void resetCachedState() {
        cachedPlatform = null;
        cachedWslVersion = null;
    }

    private PlatformUtils() {}
}
