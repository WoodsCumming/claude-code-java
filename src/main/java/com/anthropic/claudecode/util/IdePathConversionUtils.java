package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Path conversion utilities for IDE communication.
 * Handles conversions between Claude's environment and the IDE's environment.
 * Translated from src/utils/idePathConversion.ts
 */
@Slf4j
public class IdePathConversionUtils {



    private static final Pattern WSL_UNC_PATTERN =
            Pattern.compile("^\\\\\\\\wsl(?:\\.localhost|\\$)\\\\([^\\\\]+)(.*)$");

    private IdePathConversionUtils() {}

    /**
     * Interface for IDE path converters.
     * Translated from IDEPathConverter in idePathConversion.ts
     */
    public interface IDEPathConverter {
        /**
         * Convert path from IDE format to Claude's local format.
         * Used when reading workspace folders from IDE lockfile.
         */
        String toLocalPath(String idePath);

        /**
         * Convert path from Claude's local format to IDE format.
         * Used when sending paths to IDE (showDiffInIDE, etc.).
         */
        String toIDEPath(String localPath);
    }

    /**
     * Converter for Windows IDE + WSL Claude scenario.
     * Translated from WindowsToWSLConverter in idePathConversion.ts
     */
    public static class WindowsToWSLConverter implements IDEPathConverter {

        private final String wslDistroName;

        public WindowsToWSLConverter(String wslDistroName) {
            this.wslDistroName = wslDistroName;
        }

        /**
         * Convert a Windows path to a WSL local path.
         * Translated from toLocalPath() in WindowsToWSLConverter
         */
        @Override
        public String toLocalPath(String windowsPath) {
            if (windowsPath == null || windowsPath.isEmpty()) return windowsPath;

            // Check if this is a path from a different WSL distro
            if (wslDistroName != null) {
                Matcher wslUncMatch = WSL_UNC_PATTERN.matcher(windowsPath);
                if (wslUncMatch.matches() && !wslUncMatch.group(1).equals(wslDistroName)) {
                    // Different distro - wslpath will fail, so return original path
                    return windowsPath;
                }
            }

            try {
                // Use wslpath to convert Windows paths to WSL paths
                ProcessBuilder pb = new ProcessBuilder("wslpath", "-u", windowsPath);
                pb.redirectErrorStream(false);
                Process process = pb.start();
                String result = new String(process.getInputStream().readAllBytes()).trim();
                int exitCode = process.waitFor();
                if (exitCode == 0 && !result.isEmpty()) {
                    return result;
                }
            } catch (Exception e) {
                log.debug("wslpath failed for path '{}': {}", windowsPath, e.getMessage());
            }

            // If wslpath fails, fall back to manual conversion
            String result = windowsPath.replace('\\', '/');
            result = java.util.regex.Pattern.compile("^([A-Za-z]):")
                    .matcher(result)
                    .replaceAll(m -> {
                        String letter = m.group(1).toLowerCase();
                        return "/mnt/" + letter;
                    });
            return result;
        }

        /**
         * Convert a WSL path to a Windows path.
         * Translated from toIDEPath() in WindowsToWSLConverter
         */
        @Override
        public String toIDEPath(String wslPath) {
            if (wslPath == null || wslPath.isEmpty()) return wslPath;

            try {
                // Use wslpath to convert WSL paths to Windows paths
                ProcessBuilder pb = new ProcessBuilder("wslpath", "-w", wslPath);
                pb.redirectErrorStream(false);
                Process process = pb.start();
                String result = new String(process.getInputStream().readAllBytes()).trim();
                int exitCode = process.waitFor();
                if (exitCode == 0 && !result.isEmpty()) {
                    return result;
                }
            } catch (Exception e) {
                log.debug("wslpath failed for path '{}': {}", wslPath, e.getMessage());
            }

            // If wslpath fails, return the original path
            return wslPath;
        }
    }

    /**
     * Identity converter — no-op, returns paths unchanged.
     * Used for non-WSL environments.
     */
    public static class IdentityConverter implements IDEPathConverter {
        @Override
        public String toLocalPath(String idePath) {
            return idePath;
        }

        @Override
        public String toIDEPath(String localPath) {
            return localPath;
        }
    }

    /**
     * Check if distro names match for WSL UNC paths.
     * Translated from checkWSLDistroMatch() in idePathConversion.ts
     *
     * @param windowsPath  The Windows path to check
     * @param wslDistroName The expected WSL distro name
     * @return true if path is not a WSL UNC path, or distro matches; false on mismatch
     */
    public static boolean checkWSLDistroMatch(String windowsPath, String wslDistroName) {
        Matcher wslUncMatch = WSL_UNC_PATTERN.matcher(windowsPath);
        if (wslUncMatch.matches()) {
            return wslUncMatch.group(1).equals(wslDistroName);
        }
        return true; // Not a WSL UNC path, so no distro mismatch
    }

    /**
     * Manual Windows-to-WSL path conversion fallback.
     * Converts C:\foo\bar to /mnt/c/foo/bar.
     */
    public static String windowsPathToWsl(String windowsPath) {
        if (windowsPath == null || windowsPath.isEmpty()) return windowsPath;
        String result = windowsPath.replace('\\', '/');
        // Replace drive letter C: → /mnt/c
        if (result.length() >= 2 && Character.isLetter(result.charAt(0)) && result.charAt(1) == ':') {
            result = "/mnt/" + Character.toLowerCase(result.charAt(0)) + result.substring(2);
        }
        return result;
    }
}
