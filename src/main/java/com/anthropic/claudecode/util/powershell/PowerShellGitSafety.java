package com.anthropic.claudecode.util.powershell;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Git safety helpers for PowerShell command validation.
 *
 * Git can be weaponized for sandbox escape via two vectors:
 * 1. Bare-repo attack: if cwd contains HEAD + objects/ + refs/ but no valid
 *    .git/HEAD, Git treats cwd as a bare repository and runs hooks from cwd.
 * 2. Git-internal write + git: a compound command creates HEAD/objects/refs/
 *    hooks/ then runs git — the git subcommand executes the freshly-created
 *    malicious hooks.
 *
 * Translated from src/tools/PowerShellTool/gitSafety.ts
 */
@Slf4j
public final class PowerShellGitSafety {



    /**
     * Alternative dash characters that PowerShell's tokenizer accepts as parameter prefixes.
     * Mirrors PS_TOKENIZER_DASH_CHARS from the TypeScript parser.
     */
    private static final Set<Character> PS_TOKENIZER_DASH_CHARS = Set.of(
            '\u002D', // ASCII hyphen-minus (standard)
            '\u2013', // en-dash
            '\u2014', // em-dash
            '\u2015'  // horizontal bar
    );

    private static final List<String> GIT_INTERNAL_PREFIXES =
            List.of("head", "objects", "refs", "hooks");

    private static final Pattern GIT_SHORT_NAME_PATTERN =
            Pattern.compile("^git~\\d+($|/)");

    private static final Pattern ABSOLUTE_OR_DRIVE_PATTERN =
            Pattern.compile("^[a-z]:");

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if {@code arg} (raw PS arg text) resolves to a
     * git-internal path in cwd. Covers both bare-repo paths
     * ({@code hooks/}, {@code refs/}) and standard-repo paths
     * ({@code .git/hooks/}, {@code .git/config}).
     *
     * @param arg raw PowerShell argument text
     * @param cwd current working directory
     */
    public static boolean isGitInternalPathPS(String arg, String cwd) {
        String n = resolveCwdReentry(normalizeGitPathArg(arg), cwd);
        if (matchesGitInternalPrefix(n)) return true;
        // SECURITY: leading `../` or absolute paths that resolveCwdReentry and
        // posix.normalize couldn't fully resolve. Resolve against actual cwd.
        if (n.startsWith("../") || n.startsWith("/") || ABSOLUTE_OR_DRIVE_PATTERN.matcher(n).find()) {
            String rel = resolveEscapingPathToCwdRelative(n, cwd);
            if (rel != null && matchesGitInternalPrefix(rel)) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if {@code arg} resolves to a path inside {@code .git/}
     * (standard-repo metadata dir). Unlike {@link #isGitInternalPathPS}, does NOT
     * match bare-repo-style root-level {@code hooks/}, {@code refs/} etc.
     *
     * @param arg raw PowerShell argument text
     * @param cwd current working directory
     */
    public static boolean isDotGitPathPS(String arg, String cwd) {
        String n = resolveCwdReentry(normalizeGitPathArg(arg), cwd);
        if (matchesDotGitPrefix(n)) return true;
        // SECURITY: same cwd-resolution as isGitInternalPathPS
        if (n.startsWith("../") || n.startsWith("/") || ABSOLUTE_OR_DRIVE_PATTERN.matcher(n).find()) {
            String rel = resolveEscapingPathToCwdRelative(n, cwd);
            if (rel != null && matchesDotGitPrefix(rel)) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * If a normalized path starts with {@code ../<cwd-basename>/}, it re-enters cwd
     * via the parent — resolve it to the cwd-relative form.
     */
    static String resolveCwdReentry(String normalized, String cwd) {
        if (!normalized.startsWith("../")) return normalized;
        String cwdBase = Paths.get(cwd).getFileName() != null
                ? Paths.get(cwd).getFileName().toString().toLowerCase()
                : "";
        if (cwdBase.isEmpty()) return normalized;
        String prefix = "../" + cwdBase + "/";
        String s = normalized;
        while (s.startsWith(prefix)) {
            s = s.substring(prefix.length());
        }
        // Handle exact `../<cwd-basename>` (no trailing slash)
        if (s.equals("../" + cwdBase)) return ".";
        return s;
    }

    /**
     * Normalize a PS arg text to a canonical path for git-internal matching.
     *
     * Order: structural strips first (colon-bound param, quotes, backtick escapes,
     * provider prefix, drive-relative prefix), then NTFS per-component trailing-strip
     * (spaces; dots only if not {@code ./..} after space-strip), then path normalization,
     * then case-fold.
     */
    static String normalizeGitPathArg(String arg) {
        if (arg == null) return "";
        String s = arg;

        // Normalize parameter prefixes: PS dash chars and forward-slash (PS 5.1).
        // /Path:hooks/pre-commit → extract colon-bound value.
        if (!s.isEmpty() && (PS_TOKENIZER_DASH_CHARS.contains(s.charAt(0)) || s.charAt(0) == '/')) {
            int c = s.indexOf(':', 1);
            if (c > 0) s = s.substring(c + 1);
        }

        // Strip surrounding quotes and backtick escapes
        s = s.replaceAll("^['\"]|['\"]$", "");
        s = s.replace("`", "");

        // PS provider-qualified path: FileSystem::hooks/pre-commit → hooks/pre-commit
        // Also handles: Microsoft.PowerShell.Core\FileSystem::path
        s = s.replaceAll("(?i)^(?:[A-Za-z0-9_.]+\\\\){0,3}FileSystem::", "");

        // Drive-relative C:foo (no separator after colon) → cwd-relative
        // C:\foo (WITH separator) must NOT be stripped — negative lookahead preserved
        s = s.replaceAll("^[A-Za-z]:(?![/\\\\])", "");

        // Normalize backslashes to forward slashes
        s = s.replace('\\', '/');

        // Win32 CreateFileW per-component: strip trailing spaces then trailing dots,
        // stopping if the result is `.` or `..`.
        String[] components = s.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < components.length; i++) {
            String comp = stripTrailingSpacesAndDots(components[i]);
            sb.append(comp);
            if (i < components.length - 1) sb.append('/');
        }
        s = sb.toString();

        // Normalize path (resolve . and ..)
        s = normalizePosixPath(s);

        // Strip leading ./
        if (s.startsWith("./")) s = s.substring(2);

        return s.toLowerCase();
    }

    /**
     * SECURITY: Resolve a normalized path that escapes cwd (leading {@code ../} or
     * absolute) against the actual cwd, then check if it lands back INSIDE cwd.
     * If so, strip cwd and return the cwd-relative remainder for prefix matching.
     * Returns {@code null} if the path is genuinely external.
     *
     * This is the SOLE guard for the bare-repo HEAD attack.
     */
    static String resolveEscapingPathToCwdRelative(String n, String cwd) {
        Path cwdPath = Paths.get(cwd);
        // Reconstruct a platform-resolvable path from the posix-normalized form.
        // n has forward slashes; resolve() handles forward slashes on Windows.
        Path abs = cwdPath.resolve(n).normalize();
        String absLower = abs.toString().replace('\\', '/').toLowerCase();
        String cwdLower = cwdPath.toString().replace('\\', '/').toLowerCase();
        String cwdWithSepLower = cwdLower.endsWith("/") ? cwdLower : cwdLower + "/";

        if (absLower.equals(cwdLower)) return ".";
        if (!absLower.startsWith(cwdWithSepLower)) return null;

        // Return the cwd-relative remainder (forward-slash separated, lowercased)
        return absLower.substring(cwdWithSepLower.length());
    }

    private static boolean matchesGitInternalPrefix(String n) {
        if ("head".equals(n) || ".git".equals(n)) return true;
        if (n.startsWith(".git/") || GIT_SHORT_NAME_PATTERN.matcher(n).find()) return true;
        for (String p : GIT_INTERNAL_PREFIXES) {
            if ("head".equals(p)) continue;
            if (n.equals(p) || n.startsWith(p + "/")) return true;
        }
        return false;
    }

    private static boolean matchesDotGitPrefix(String n) {
        if (".git".equals(n) || n.startsWith(".git/")) return true;
        // NTFS 8.3 short names: .git becomes GIT~1 (or GIT~2, etc.)
        return GIT_SHORT_NAME_PATTERN.matcher(n).find();
    }

    /**
     * Strip trailing spaces, then trailing dots, from a single path component,
     * stopping if the result is {@code .} or {@code ..}.
     * Originally-empty (leading slash split) stays empty.
     */
    private static String stripTrailingSpacesAndDots(String component) {
        if (component.isEmpty()) return component;
        String c = component;
        String prev;
        do {
            prev = c;
            c = c.replaceAll(" +$", "");
            if (".".equals(c) || "..".equals(c)) return c;
            c = c.replaceAll("\\.+$", "");
        } while (!c.equals(prev));
        return c.isEmpty() ? "." : c;
    }

    /**
     * Simplified POSIX path normalization (resolves {@code .} and {@code ..} segments).
     * Handles both absolute (/foo/bar) and relative (foo/../bar) forms.
     */
    private static String normalizePosixPath(String path) {
        if (path == null || path.isEmpty()) return ".";
        boolean absolute = path.startsWith("/");
        String[] parts = path.split("/", -1);
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                // skip
            } else if ("..".equals(part)) {
                if (!stack.isEmpty() && !"..".equals(stack.peek())) {
                    stack.pop();
                } else if (!absolute) {
                    stack.push("..");
                }
            } else {
                stack.push(part);
            }
        }
        // Rebuild (stack is in reverse order)
        String[] segments = stack.toArray(new String[0]);
        // Reverse
        for (int i = 0, j = segments.length - 1; i < j; i++, j--) {
            String tmp = segments[i]; segments[i] = segments[j]; segments[j] = tmp;
        }
        String result = String.join("/", segments);
        if (absolute) result = "/" + result;
        return result.isEmpty() ? "." : result;
    }

    private PowerShellGitSafety() {}
}
