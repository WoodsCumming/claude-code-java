package com.anthropic.claudecode.util;

/**
 * Windows path utilities.
 * Translated from src/utils/windowsPaths.ts
 */
public class WindowsPaths {

    /**
     * Convert a POSIX path to a Windows path.
     * Translated from posixPathToWindowsPath() in windowsPaths.ts
     *
     * Example: /c/Users/foo → C:\Users\foo
     */
    public static String posixPathToWindowsPath(String posixPath) {
        if (posixPath == null) return null;
        if (!PlatformUtils.isWindows()) return posixPath;

        // Handle /c/path → C:\path
        if (posixPath.matches("^/[a-z]/.*")) {
            char driveLetter = Character.toUpperCase(posixPath.charAt(1));
            String rest = posixPath.substring(2).replace("/", "\\");
            return driveLetter + ":" + rest;
        }

        return posixPath.replace("/", "\\");
    }

    /**
     * Convert a Windows path to a POSIX path.
     */
    public static String windowsPathToPosixPath(String windowsPath) {
        if (windowsPath == null) return null;

        // Handle C:\path → /c/path
        if (windowsPath.matches("^[A-Za-z]:\\\\.*")) {
            char driveLetter = Character.toLowerCase(windowsPath.charAt(0));
            String rest = windowsPath.substring(2).replace("\\", "/");
            return "/" + driveLetter + rest;
        }

        return windowsPath.replace("\\", "/");
    }

    /**
     * Set the shell for Windows.
     * Translated from setShellIfWindows() in windowsPaths.ts
     */
    public static void setShellIfWindows() {
        if (PlatformUtils.isWindows()) {
            // Set COMSPEC if not set
            if (System.getenv("COMSPEC") == null) {
                System.setProperty("COMSPEC", "C:\\Windows\\System32\\cmd.exe");
            }
        }
    }

    private WindowsPaths() {}
}
