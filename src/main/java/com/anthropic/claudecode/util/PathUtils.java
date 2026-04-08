package com.anthropic.claudecode.util;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Path utility functions.
 * Translated from src/utils/path.ts
 */
public class PathUtils {

    /**
     * Expands a path that may contain tilde notation (~) to an absolute path.
     * On Windows, POSIX-style paths (e.g., /c/Users/...) are automatically
     * converted to Windows format. Always returns paths in the native platform format.
     *
     * @param path    The path to expand (may contain ~, be absolute, or relative)
     * @param baseDir The base directory for resolving relative paths (defaults to cwd)
     * @return The expanded absolute path
     * @throws IllegalArgumentException if path contains null bytes
     */
    public static String expandPath(String path, String baseDir) {
        String actualBaseDir = (baseDir != null) ? baseDir : System.getProperty("user.dir");

        if (path == null) {
            throw new IllegalArgumentException("Path must be a string, received null");
        }
        if (actualBaseDir == null) {
            throw new IllegalArgumentException("Base directory must be a string, received null");
        }

        // Security: check for null bytes
        if (path.contains("\0") || actualBaseDir.contains("\0")) {
            throw new IllegalArgumentException("Path contains null bytes");
        }

        String trimmedPath = path.trim();

        // Handle empty or whitespace-only paths
        if (trimmedPath.isEmpty()) {
            return Paths.get(actualBaseDir).normalize().toString();
        }

        // Handle home directory notation
        String home = System.getProperty("user.home");
        if (trimmedPath.equals("~")) {
            return home;
        }
        if (trimmedPath.startsWith("~/")) {
            return Paths.get(home, trimmedPath.substring(2)).normalize().toString();
        }

        // On Windows, convert POSIX-style paths (e.g., /c/Users/...) to Windows format
        String processedPath = trimmedPath;
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win") && trimmedPath.matches("^/[a-zA-Z]/.*")) {
            // Convert /c/foo/bar -> C:\foo\bar
            processedPath = trimmedPath.charAt(1) + ":" + trimmedPath.substring(2).replace('/', '\\');
        }

        // Handle absolute paths
        Path p = Paths.get(processedPath);
        if (p.isAbsolute()) {
            return p.normalize().toString();
        }

        // Handle relative paths
        return Paths.get(actualBaseDir).resolve(processedPath).normalize().toString();
    }

    /**
     * Expands a path using the current working directory as the base.
     */
    public static String expandPath(String path) {
        return expandPath(path, null);
    }

    /**
     * Converts an absolute path to a relative path from cwd, to save tokens in
     * tool output. If the path is outside cwd (relative path would start with ..),
     * returns the absolute path unchanged so it stays unambiguous.
     *
     * @param absolutePath The absolute path to relativize
     * @return Relative path if under cwd, otherwise the original absolute path
     */
    public static String toRelativePath(String absolutePath) {
        if (absolutePath == null) return null;

        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path abs = Paths.get(absolutePath);

        try {
            String relative = cwd.relativize(abs).toString();
            // If the relative path would go outside cwd (starts with ..), keep absolute
            return relative.startsWith("..") ? absolutePath : relative;
        } catch (IllegalArgumentException e) {
            return absolutePath;
        }
    }

    /**
     * Gets the directory path for a given file or directory path.
     * If the path is a directory, returns the path itself.
     * If the path is a file or doesn't exist, returns the parent directory.
     *
     * @param path The file or directory path
     * @return The directory path
     */
    public static String getDirectoryForPath(String path) {
        String absolutePath = expandPath(path);

        // SECURITY: Skip filesystem operations for UNC paths to prevent NTLM credential leaks.
        if (absolutePath.startsWith("\\\\") || absolutePath.startsWith("//")) {
            Path p = Paths.get(absolutePath);
            Path parent = p.getParent();
            return (parent != null) ? parent.toString() : absolutePath;
        }

        try {
            File file = new File(absolutePath);
            if (file.isDirectory()) {
                return absolutePath;
            }
        } catch (Exception e) {
            // Path doesn't exist or can't be accessed
        }

        // If it's not a directory or doesn't exist, return the parent directory
        Path p = Paths.get(absolutePath);
        Path parent = p.getParent();
        return (parent != null) ? parent.toString() : absolutePath;
    }

    /**
     * Checks if a path contains directory traversal patterns that navigate to parent directories.
     *
     * @param path The path to check for traversal patterns
     * @return true if the path contains traversal (e.g., '../', '..\', or ends with '..')
     */
    public static boolean containsPathTraversal(String path) {
        if (path == null) return false;
        // Matches ../ or ..\ at start, in middle, or end of path
        return path.matches("(?s).*(?:^|[/\\\\])\\.\\.[/\\\\].*") ||
               path.endsWith("/..") || path.endsWith("\\..") || path.equals("..");
    }

    /**
     * Normalizes a path for use as a JSON config key.
     * On Windows, paths can have inconsistent separators. This normalizes
     * to forward slashes for consistent JSON serialization.
     *
     * @param path The path to normalize
     * @return The normalized path with consistent forward slashes
     */
    public static String normalizePathForConfigKey(String path) {
        if (path == null) return null;
        // First use Paths.normalize() to resolve . and .. segments
        String normalized = Paths.get(path).normalize().toString();
        // Then convert all backslashes to forward slashes for consistent JSON keys
        return normalized.replace('\\', '/');
    }

    /**
     * Get the file extension.
     */
    public static String getExtension(String path) {
        if (path == null) return "";
        int dot = path.lastIndexOf('.');
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (dot > slash) {
            return path.substring(dot + 1).toLowerCase();
        }
        return "";
    }

    /**
     * Get the filename from a path.
     */
    public static String getFilename(String path) {
        if (path == null) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /**
     * Check if a path is absolute.
     */
    public static boolean isAbsolute(String path) {
        if (path == null) return false;
        return Paths.get(path).isAbsolute();
    }

    /**
     * Join path components.
     */
    public static String join(String... parts) {
        if (parts == null || parts.length == 0) return "";
        Path result = Paths.get(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            result = result.resolve(parts[i]);
        }
        return result.toString();
    }

    private PathUtils() {}
}
