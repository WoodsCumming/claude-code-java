package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilities for managing shell configuration files (.bashrc, .zshrc, etc.).
 * Translated from src/utils/shellConfig.ts
 *
 * Used for managing claude aliases and PATH entries written by the installer.
 */
@Slf4j
public final class ShellConfigUtils {



    /**
     * Regex that matches any {@code alias claude=...} line in a shell config file.
     * Translated from CLAUDE_ALIAS_REGEX in shellConfig.ts
     */
    public static final Pattern CLAUDE_ALIAS_REGEX =
            Pattern.compile("^\\s*alias\\s+claude\\s*=", Pattern.MULTILINE);

    private ShellConfigUtils() {}

    /**
     * Options for overriding the environment and home directory in tests.
     * Translated from ShellConfigOptions in shellConfig.ts
     */
    public record ShellConfigOptions(Map<String, String> env, String homedir) {

        /** Convenience constructor with defaults. */
        public static ShellConfigOptions defaults() {
            return new ShellConfigOptions(null, null);
        }
    }

    /**
     * Return the paths to the user's shell configuration files.
     * Respects {@code $ZDOTDIR} for zsh users.
     * Translated from getShellConfigPaths() in shellConfig.ts
     *
     * @param options Optional env / homedir overrides (may be null).
     * @return Map of shell name → config file path.
     */
    public static Map<String, String> getShellConfigPaths(ShellConfigOptions options) {
        String home = (options != null && options.homedir() != null)
                ? options.homedir()
                : System.getProperty("user.home");
        Map<String, String> env = (options != null && options.env() != null)
                ? options.env()
                : System.getenv();

        String zdotdir = env.getOrDefault("ZDOTDIR", home);
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("zsh",  zdotdir + "/.zshrc");
        paths.put("bash", home + "/.bashrc");
        paths.put("fish", home + "/.config/fish/config.fish");
        return paths;
    }

    /**
     * Filter out installer-created claude aliases from a list of shell config lines.
     * Only removes aliases pointing to {@code $HOME/.claude/local/claude}.
     * Preserves custom user aliases that point to other locations.
     * Translated from filterClaudeAliases() in shellConfig.ts
     *
     * @param lines Lines from a shell config file.
     * @return Filtered lines and a flag indicating whether the installer alias was found.
     */
    public static FilterResult filterClaudeAliases(List<String> lines) {
        boolean hadAlias = false;
        List<String> filtered = new ArrayList<>();
        String installerPath = getLocalClaudePath();

        for (String line : lines) {
            if (CLAUDE_ALIAS_REGEX.matcher(line).find()) {
                // Try with quotes first
                String target = extractAliasTarget(line);
                if (target != null && target.equals(installerPath)) {
                    hadAlias = true;
                    continue; // Remove this line
                }
                // Keep aliases pointing to other locations
            }
            filtered.add(line);
        }
        return new FilterResult(filtered, hadAlias);
    }

    /**
     * Result of {@link #filterClaudeAliases}.
     */
    public record FilterResult(List<String> filtered, boolean hadAlias) {}

    /**
     * Read a file and split it into lines.
     * Returns null if the file does not exist or cannot be read.
     * Translated from readFileLines() in shellConfig.ts
     */
    public static List<String> readFileLines(String filePath) {
        try {
            String content = Files.readString(Paths.get(filePath));
            return Arrays.asList(content.split("\n", -1));
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            if (isFsInaccessible(e)) return null;
            log.debug("readFileLines failed for {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    /**
     * Write lines back to a file with an fsync-equivalent datasync.
     * Translated from writeFileLines() in shellConfig.ts
     */
    public static void writeFileLines(String filePath, List<String> lines) throws IOException {
        Path path = Paths.get(filePath);
        String content = String.join("\n", lines);
        // Write to temp file then atomically move
        Path tmp = Files.createTempFile(path.getParent(), ".claude-tmp-", ".tmp");
        try {
            Files.writeString(tmp, content);
            // Flush and sync
            try (FileOutputStream fos = new FileOutputStream(tmp.toFile(), true)) {
                fos.getFD().sync();
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    /**
     * Check if a claude alias exists in any shell config file.
     * Returns the alias target if found, null otherwise.
     * Translated from findClaudeAlias() in shellConfig.ts
     *
     * @param options Optional env / homedir overrides (may be null).
     */
    public static String findClaudeAlias(ShellConfigOptions options) {
        Map<String, String> configs = getShellConfigPaths(options);
        for (String configPath : configs.values()) {
            List<String> lines = readFileLines(configPath);
            if (lines == null) continue;
            for (String line : lines) {
                if (CLAUDE_ALIAS_REGEX.matcher(line).find()) {
                    // Pattern: alias claude=["']?<target>
                    Matcher m = Pattern.compile("alias\\s+claude=[\"']?([^\"'\\s]+)").matcher(line);
                    if (m.find()) {
                        return m.group(1);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Check if a claude alias exists AND points to a valid executable.
     * Returns the alias target if valid, null otherwise.
     * Translated from findValidClaudeAlias() in shellConfig.ts
     *
     * @param options Optional env / homedir overrides (may be null).
     */
    public static String findValidClaudeAlias(ShellConfigOptions options) {
        String aliasTarget = findClaudeAlias(options);
        if (aliasTarget == null) return null;

        String home = (options != null && options.homedir() != null)
                ? options.homedir()
                : System.getProperty("user.home");

        // Expand ~ to home directory
        String expandedPath = aliasTarget.startsWith("~")
                ? home + aliasTarget.substring(1)
                : aliasTarget;

        try {
            Path p = Paths.get(expandedPath);
            if (Files.isRegularFile(p) || Files.isSymbolicLink(p)) {
                return aliasTarget;
            }
        } catch (Exception ignored) {
            // Target doesn't exist or can't be accessed
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Extract the alias target from a shell alias line.
     * Handles quoted and unquoted forms.
     */
    private static String extractAliasTarget(String line) {
        // With quotes
        Matcher m = Pattern.compile("alias\\s+claude\\s*=\\s*[\"']([^\"']+)[\"']").matcher(line);
        if (m.find()) return m.group(1).strip();
        // Without quotes
        m = Pattern.compile("alias\\s+claude\\s*=\\s*([^#\\n]+)").matcher(line);
        if (m.find()) return m.group(1).strip();
        return null;
    }

    /**
     * Return the default local Claude installation path.
     * Mirrors getLocalClaudePath() in localInstaller.ts
     */
    private static String getLocalClaudePath() {
        return System.getProperty("user.home") + "/.claude/local/claude";
    }

    /**
     * Return true when the IOException indicates an inaccessible filesystem path
     * (ENOENT, EACCES, etc.) rather than a structural I/O error.
     */
    private static boolean isFsInaccessible(IOException e) {
        return e instanceof NoSuchFileException
                || e instanceof AccessDeniedException
                || e instanceof FileSystemException;
    }
}
