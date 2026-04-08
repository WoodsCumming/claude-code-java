package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Memory directory path utilities.
 * Translated from src/memdir/paths.ts
 *
 * Manages path resolution for the auto-memory system, including environment
 * variable overrides, settings.json overrides, and security validation.
 */
@Slf4j
public class MemdirPaths {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MemdirPaths.class);


    private static final String AUTO_MEM_DIRNAME = "memory";
    private static final String AUTO_MEM_ENTRYPOINT_NAME = "MEMORY.md";
    private static final String SEP = FileSystems.getDefault().getSeparator();

    // Simple memoization cache keyed by project root
    private static final ConcurrentHashMap<String, String> autoMemPathCache = new ConcurrentHashMap<>();

    /**
     * Whether auto-memory features are enabled (memdir, agent memory, past session search).
     * Enabled by default. Priority chain (first defined wins):
     *   1. CLAUDE_CODE_DISABLE_AUTO_MEMORY env var (1/true → OFF, 0/false → ON)
     *   2. CLAUDE_CODE_SIMPLE (--bare) → OFF
     *   3. CCR without persistent storage → OFF (no CLAUDE_CODE_REMOTE_MEMORY_DIR)
     *   4. autoMemoryEnabled in settings.json (supports project-level opt-out)
     *   5. Default: enabled
     * Translated from isAutoMemoryEnabled() in paths.ts
     */
    public static boolean isAutoMemoryEnabled() {
        String envVal = System.getenv("CLAUDE_CODE_DISABLE_AUTO_MEMORY");
        if (EnvUtils.isEnvTruthy(envVal)) return false;
        if (EnvUtils.isEnvDefinedFalsy(envVal)) return true;

        if (EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_SIMPLE"))) return false;

        if (EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_REMOTE"))
                && System.getenv("CLAUDE_CODE_REMOTE_MEMORY_DIR") == null) {
            return false;
        }

        // TODO: check settings.autoMemoryEnabled when SettingsService is available
        return true;
    }

    /**
     * Returns the base directory for persistent memory storage.
     * Resolution order:
     *   1. CLAUDE_CODE_REMOTE_MEMORY_DIR env var (explicit override, set in CCR)
     *   2. ~/.claude (default config home)
     * Translated from getMemoryBaseDir() in paths.ts
     */
    public static String getMemoryBaseDir() {
        String remoteDir = System.getenv("CLAUDE_CODE_REMOTE_MEMORY_DIR");
        if (remoteDir != null && !remoteDir.isBlank()) {
            return remoteDir;
        }
        return EnvUtils.getClaudeConfigHomeDir();
    }

    /**
     * Normalize and validate a candidate auto-memory directory path.
     *
     * SECURITY: Rejects paths that would be dangerous as a read-allowlist root:
     * - relative paths (!isAbsolute)
     * - root/near-root (length &lt; 3)
     * - Windows drive-root (C: only)
     * - UNC paths (\\server\share) — network paths
     * - null byte — survives normalize(), can truncate in syscalls
     *
     * Returns the normalized path with exactly one trailing separator,
     * or null if the path is unset/empty/rejected.
     * Translated from validateMemoryPath() in paths.ts
     */
    public static String validateMemoryPath(String raw, boolean expandTilde) {
        if (raw == null || raw.isBlank()) return null;

        String candidate = raw;

        // Settings.json paths support ~/ expansion (user-friendly).
        // The env var override does not (it's set programmatically).
        // Bare "~", "~/", "~/.", "~/.." are NOT expanded — they'd match all of $HOME.
        if (expandTilde && (candidate.startsWith("~/") || candidate.startsWith("~\\"))) {
            String rest = candidate.substring(2);
            // Reject trivial remainders that would expand to $HOME or an ancestor
            Path restNorm = Paths.get(rest.isEmpty() ? "." : rest).normalize();
            String restNormStr = restNorm.toString();
            if (restNormStr.equals(".") || restNormStr.equals("..")) return null;
            candidate = System.getProperty("user.home") + SEP + rest;
        }

        // Normalize and strip trailing separators
        Path normalized = Paths.get(candidate).normalize();
        String normalizedStr = normalized.toString().replaceAll("[/\\\\]+$", "");

        // Security checks
        File normalizedFile = new File(normalizedStr);
        if (!normalizedFile.isAbsolute()) return null;
        if (normalizedStr.length() < 3) return null;
        if (normalizedStr.matches("^[A-Za-z]:$")) return null;         // Windows drive-root
        if (normalizedStr.startsWith("\\\\")) return null;              // UNC path
        if (normalizedStr.startsWith("//")) return null;                // UNC-style on Unix
        if (normalizedStr.contains("\0")) return null;                  // null byte

        // Add exactly one trailing separator, normalized to NFC
        return java.text.Normalizer.normalize(normalizedStr + SEP, java.text.Normalizer.Form.NFC);
    }

    /**
     * Direct override for the full auto-memory directory path via env var.
     * When set, getAutoMemPath()/getAutoMemEntrypoint() return this path directly.
     * Used by Cowork to redirect memory to a space-scoped mount.
     * Translated from getAutoMemPathOverride() in paths.ts
     */
    private static String getAutoMemPathOverride() {
        return validateMemoryPath(System.getenv("CLAUDE_COWORK_MEMORY_PATH_OVERRIDE"), false);
    }

    /**
     * Check if CLAUDE_COWORK_MEMORY_PATH_OVERRIDE is set to a valid override.
     * Translated from hasAutoMemPathOverride() in paths.ts
     */
    public static boolean hasAutoMemPathOverride() {
        return getAutoMemPathOverride() != null;
    }

    /**
     * Settings.json override for the full auto-memory directory path.
     * Supports ~/ expansion for user convenience.
     * NOTE: projectSettings (.claude/settings.json committed to repo) is
     * intentionally excluded — a malicious repo could otherwise set
     * autoMemoryDirectory: "~/.ssh" and gain silent write access.
     * Translated from getAutoMemPathSetting() in paths.ts
     */
    private static String getAutoMemPathSetting() {
        // TODO: integrate with SettingsService when available
        // Priority: policySettings > flagSettings > localSettings > userSettings
        String fromEnv = System.getenv("CLAUDE_CODE_AUTO_MEMORY_DIR");
        return validateMemoryPath(fromEnv, true);
    }

    /**
     * Returns the auto-memory directory path.
     *
     * Resolution order:
     *   1. CLAUDE_COWORK_MEMORY_PATH_OVERRIDE env var (full-path override, used by Cowork)
     *   2. autoMemoryDirectory in settings.json (trusted sources only)
     *   3. &lt;memoryBase&gt;/projects/&lt;sanitized-git-root&gt;/memory/
     *
     * Memoized by project root.
     * Translated from getAutoMemPath() in paths.ts
     *
     * @param projectRoot the canonical project root (git root if available, else cwd)
     */
    public static String getAutoMemPath(String projectRoot) {
        return autoMemPathCache.computeIfAbsent(projectRoot != null ? projectRoot : "", key -> {
            String override = getAutoMemPathOverride();
            if (override != null) return override;

            String setting = getAutoMemPathSetting();
            if (setting != null) return setting;

            String projectsDir = getMemoryBaseDir() + SEP + "projects";
            String sanitized = SanitizationUtils.sanitizePath(projectRoot != null ? projectRoot : "");
            return java.text.Normalizer.normalize(
                    projectsDir + SEP + sanitized + SEP + AUTO_MEM_DIRNAME + SEP,
                    java.text.Normalizer.Form.NFC);
        });
    }

    /**
     * Returns the daily log file path for the given date (defaults to today).
     * Shape: &lt;autoMemPath&gt;/logs/YYYY/MM/YYYY-MM-DD.md
     * Used by assistant mode (KAIROS feature) for append-only daily logs.
     * Translated from getAutoMemDailyLogPath() in paths.ts
     *
     * @param projectRoot the project root for path resolution
     * @param date        the date (defaults to today if null)
     */
    public static String getAutoMemDailyLogPath(String projectRoot, LocalDate date) {
        if (date == null) date = LocalDate.now();
        String yyyy = String.valueOf(date.getYear());
        String mm = String.format("%02d", date.getMonthValue());
        String dd = String.format("%02d", date.getDayOfMonth());
        String logName = yyyy + "-" + mm + "-" + dd + ".md";
        return getAutoMemPath(projectRoot) + "logs" + SEP + yyyy + SEP + mm + SEP + logName;
    }

    /**
     * Returns the auto-memory entrypoint (MEMORY.md inside the auto-memory dir).
     * Follows the same resolution order as getAutoMemPath().
     * Translated from getAutoMemEntrypoint() in paths.ts
     *
     * @param projectRoot the project root for path resolution
     */
    public static String getAutoMemEntrypoint(String projectRoot) {
        return getAutoMemPath(projectRoot) + AUTO_MEM_ENTRYPOINT_NAME;
    }

    /**
     * Check if an absolute path is within the auto-memory directory.
     *
     * When CLAUDE_COWORK_MEMORY_PATH_OVERRIDE is set, this matches against the
     * env-var override directory. Note: a true return does NOT imply write
     * permission in that case — the filesystem write carve-out is gated on
     * !hasAutoMemPathOverride().
     *
     * SECURITY: Normalizes to prevent path traversal bypasses via .. segments.
     * Translated from isAutoMemPath() in paths.ts
     *
     * @param absolutePath the path to check
     * @param projectRoot  the project root for auto-mem path resolution
     */
    public static boolean isAutoMemPath(String absolutePath, String projectRoot) {
        if (absolutePath == null) return false;
        String normalizedPath = Paths.get(absolutePath).normalize().toString();
        return normalizedPath.startsWith(getAutoMemPath(projectRoot));
    }

    /**
     * Clear the memoization cache. Useful for testing when project root changes.
     */
    public static void clearCache() {
        autoMemPathCache.clear();
    }

    private MemdirPaths() {}
}
