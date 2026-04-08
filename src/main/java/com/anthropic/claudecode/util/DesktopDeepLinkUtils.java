package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Utilities for building and opening claude:// deep-link URLs that resume a
 * CLI session inside Claude Desktop.
 *
 * Translated from src/utils/desktopDeepLink.ts
 */
@Slf4j
public class DesktopDeepLinkUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DesktopDeepLinkUtils.class);


    private static final String MIN_DESKTOP_VERSION = "1.1.2396";

    // -----------------------------------------------------------------------
    // Domain types
    // -----------------------------------------------------------------------

    /**
     * Sealed hierarchy representing the Desktop installation status.
     * Translated from the TypeScript union type {@code DesktopInstallStatus}.
     */
    public sealed interface DesktopInstallStatus
            permits DesktopInstallStatus.NotInstalled,
                    DesktopInstallStatus.VersionTooOld,
                    DesktopInstallStatus.Ready {

        record NotInstalled() implements DesktopInstallStatus {}
        record VersionTooOld(String version) implements DesktopInstallStatus {}
        record Ready(String version) implements DesktopInstallStatus {}
    }

    /**
     * Result of {@link #openCurrentSessionInDesktop(String, String)}.
     */
    public record OpenSessionResult(boolean success, String error, String deepLinkUrl) {
        public static OpenSessionResult success(String url) {
            return new OpenSessionResult(true, null, url);
        }
        public static OpenSessionResult failure(String error) {
            return new OpenSessionResult(false, error, null);
        }
        public static OpenSessionResult failure(String error, String url) {
            return new OpenSessionResult(false, error, url);
        }
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Check whether Claude Desktop is installed and whether its version meets
     * the minimum requirement.
     * Translated from {@code getDesktopInstallStatus()} in desktopDeepLink.ts
     */
    public static CompletableFuture<DesktopInstallStatus> getDesktopInstallStatus() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!isDesktopInstalled()) {
                    return new DesktopInstallStatus.NotInstalled();
                }

                Optional<String> versionOpt = getDesktopVersion();
                if (versionOpt.isEmpty()) {
                    return new DesktopInstallStatus.Ready("unknown");
                }

                String version = versionOpt.get();
                if (!semverGte(version, MIN_DESKTOP_VERSION)) {
                    return new DesktopInstallStatus.VersionTooOld(version);
                }

                return new DesktopInstallStatus.Ready(version);
            } catch (Exception e) {
                log.warn("Failed to determine Desktop install status", e);
                return new DesktopInstallStatus.Ready("unknown");
            }
        });
    }

    /**
     * Build a deep-link URL and open it so Claude Desktop can resume
     * {@code sessionId} in the given working directory.
     * Translated from {@code openCurrentSessionInDesktop()} in desktopDeepLink.ts
     *
     * @param sessionId the session UUID to resume
     * @param cwd       the working directory of the CLI session
     */
    public static CompletableFuture<OpenSessionResult> openCurrentSessionInDesktop(
            String sessionId, String cwd) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!isDesktopInstalled()) {
                    return OpenSessionResult.failure(
                            "Claude Desktop is not installed. "
                            + "Install it from https://claude.ai/download");
                }

                String deepLinkUrl = buildDesktopDeepLink(sessionId, cwd);
                boolean opened = openDeepLink(deepLinkUrl);

                if (!opened) {
                    return OpenSessionResult.failure(
                            "Failed to open Claude Desktop. Please try opening it manually.",
                            deepLinkUrl);
                }

                return OpenSessionResult.success(deepLinkUrl);
            } catch (Exception e) {
                log.error("Error opening session in Desktop", e);
                return OpenSessionResult.failure("Unexpected error: " + e.getMessage());
            }
        });
    }

    // -----------------------------------------------------------------------
    // Package-private helpers (accessible for testing)
    // -----------------------------------------------------------------------

    /**
     * Build the resume deep-link URL.
     * Uses {@code claude-dev://} in dev mode, {@code claude://} otherwise.
     * Translated from {@code buildDesktopDeepLink()} in desktopDeepLink.ts
     */
    static String buildDesktopDeepLink(String sessionId, String cwd) {
        String protocol = isDevMode() ? "claude-dev" : "claude";
        String encodedSession = URLEncoder.encode(sessionId, StandardCharsets.UTF_8);
        String encodedCwd = URLEncoder.encode(cwd, StandardCharsets.UTF_8);
        return protocol + "://resume?session=" + encodedSession + "&cwd=" + encodedCwd;
    }

    /**
     * Returns {@code true} when running from a development / local build.
     * Translated from {@code isDevMode()} in desktopDeepLink.ts
     */
    static boolean isDevMode() {
        if ("development".equals(System.getenv("NODE_ENV"))) return true;

        // Also treat local build output directories as dev mode
        List<String> buildDirs = List.of(
                "/build-ant/", "/build-ant-native/",
                "/build-external/", "/build-external-native/");
        String javaCmd = ProcessHandle.current().info().command().orElse("");
        return buildDirs.stream().anyMatch(javaCmd::contains);
    }

    // -----------------------------------------------------------------------
    // Platform detection and command dispatch
    // -----------------------------------------------------------------------

    /**
     * Check whether Claude Desktop is installed on this machine.
     * Translated from {@code isDesktopInstalled()} in desktopDeepLink.ts
     */
    static boolean isDesktopInstalled() {
        if (isDevMode()) return true;

        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("mac")) {
            return Files.exists(Path.of("/Applications/Claude.app"));
        } else if (os.contains("linux")) {
            try {
                ProcessResult r = runProcess(5, TimeUnit.SECONDS,
                        "xdg-mime", "query", "default", "x-scheme-handler/claude");
                return r.exitCode() == 0 && !r.stdout().isBlank();
            } catch (Exception e) {
                return false;
            }
        } else if (os.contains("win")) {
            try {
                ProcessResult r = runProcess(5, TimeUnit.SECONDS,
                        "reg", "query", "HKEY_CLASSES_ROOT\\claude", "/ve");
                return r.exitCode() == 0;
            } catch (Exception e) {
                return false;
            }
        }

        return false;
    }

    /**
     * Detect the installed Claude Desktop version string, or {@link Optional#empty()}
     * if it cannot be determined.
     * Translated from {@code getDesktopVersion()} in desktopDeepLink.ts
     */
    static Optional<String> getDesktopVersion() {
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("mac")) {
            try {
                ProcessResult r = runProcess(5, TimeUnit.SECONDS,
                        "defaults", "read",
                        "/Applications/Claude.app/Contents/Info.plist",
                        "CFBundleShortVersionString");
                if (r.exitCode() == 0 && !r.stdout().isBlank()) {
                    return Optional.of(r.stdout().trim());
                }
            } catch (Exception ignored) {}
        } else if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null) {
                Path installDir = Path.of(localAppData, "AnthropicClaude");
                try (Stream<Path> entries = Files.list(installDir)) {
                    Optional<String> highest = entries
                            .map(p -> p.getFileName().toString())
                            .filter(name -> name.startsWith("app-"))
                            .map(name -> name.substring(4))
                            .filter(DesktopDeepLinkUtils::isValidSemver)
                            .max(Comparator.comparing(DesktopDeepLinkUtils::parseSemver,
                                    (a, b) -> {
                                        for (int i = 0; i < Math.min(a.length, b.length); i++) {
                                            int c = Long.compare(a[i], b[i]);
                                            if (c != 0) return c;
                                        }
                                        return Integer.compare(a.length, b.length);
                                    }));
                    return highest;
                } catch (IOException ignored) {}
            }
        }

        return Optional.empty();
    }

    /**
     * Open {@code deepLinkUrl} using the platform's native mechanism.
     * Returns {@code true} if the command exited successfully.
     * Translated from {@code openDeepLink()} in desktopDeepLink.ts
     */
    static boolean openDeepLink(String deepLinkUrl) {
        log.debug("Opening deep link: {}", deepLinkUrl);
        String os = System.getProperty("os.name", "").toLowerCase();

        try {
            if (os.contains("mac")) {
                if (isDevMode()) {
                    ProcessResult r = runProcess(5, TimeUnit.SECONDS,
                            "osascript", "-e",
                            "tell application \"Electron\" to open location \""
                            + deepLinkUrl + "\"");
                    return r.exitCode() == 0;
                }
                ProcessResult r = runProcess(5, TimeUnit.SECONDS, "open", deepLinkUrl);
                return r.exitCode() == 0;
            } else if (os.contains("linux")) {
                ProcessResult r = runProcess(5, TimeUnit.SECONDS, "xdg-open", deepLinkUrl);
                return r.exitCode() == 0;
            } else if (os.contains("win")) {
                ProcessResult r = runProcess(5, TimeUnit.SECONDS,
                        "cmd", "/c", "start", "", deepLinkUrl);
                return r.exitCode() == 0;
            }
        } catch (Exception e) {
            log.warn("Failed to open deep link", e);
        }

        return false;
    }

    // -----------------------------------------------------------------------
    // Semver helpers (minimal — no external library dependency)
    // -----------------------------------------------------------------------

    private static boolean isValidSemver(String v) {
        return parseSemver(v) != null;
    }

    /**
     * Parse a version string into a comparable triple [major, minor, patch].
     * Returns {@code null} for unparseable strings.
     */
    private static long[] parseSemver(String v) {
        if (v == null) return null;
        String[] parts = v.split("\\.");
        if (parts.length < 3) return null;
        try {
            return new long[]{
                    Long.parseLong(parts[0]),
                    Long.parseLong(parts[1]),
                    Long.parseLong(parts[2].split("[^0-9]")[0])
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean semverGte(String version, String minimum) {
        long[] v = parseSemver(version);
        long[] m = parseSemver(minimum);
        if (v == null || m == null) return true; // assume ok if we can't parse
        for (int i = 0; i < 3; i++) {
            if (v[i] > m[i]) return true;
            if (v[i] < m[i]) return false;
        }
        return true; // equal
    }

    // -----------------------------------------------------------------------
    // Internal process runner
    // -----------------------------------------------------------------------

    private record ProcessResult(int exitCode, String stdout, String stderr) {}

    private static ProcessResult runProcess(long timeout, TimeUnit unit, String... command)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).start();
        boolean finished = process.waitFor(timeout, unit);
        if (!finished) {
            process.destroyForcibly();
            return new ProcessResult(-1, "", "Process timed out");
        }
        String stdout = new String(process.getInputStream().readAllBytes()).trim();
        String stderr = new String(process.getErrorStream().readAllBytes()).trim();
        return new ProcessResult(process.exitValue(), stdout, stderr);
    }
}
