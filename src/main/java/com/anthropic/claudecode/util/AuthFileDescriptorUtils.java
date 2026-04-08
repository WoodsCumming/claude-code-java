package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Auth file descriptor utilities for CCR container environments.
 * Translated from src/utils/authFileDescriptor.ts
 *
 * <p>Well-known token file locations in CCR. The Go environment-manager creates
 * /home/claude/.claude/remote/ and will (eventually) write these files too.
 * Until then, this module writes them on successful FD read so subprocesses
 * spawned inside the CCR container can find the token without inheriting
 * the FD — which they can't: pipe FDs don't cross tmux/shell boundaries.</p>
 */
@Slf4j
public class AuthFileDescriptorUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthFileDescriptorUtils.class);


    public static final String CCR_TOKEN_DIR = "/home/claude/.claude/remote";
    public static final String CCR_OAUTH_TOKEN_PATH = CCR_TOKEN_DIR + "/.oauth_token";
    public static final String CCR_API_KEY_PATH = CCR_TOKEN_DIR + "/.api_key";
    public static final String CCR_SESSION_INGRESS_TOKEN_PATH = CCR_TOKEN_DIR + "/.session_ingress_token";

    // Cached credentials (null = not yet attempted, empty string = cached miss)
    private static final AtomicReference<String> cachedOauthToken = new AtomicReference<>();
    private static final AtomicReference<String> cachedApiKey = new AtomicReference<>();

    /**
     * Best-effort write of the token to a well-known location for subprocess access.
     * CCR-gated: outside CCR there's no /home/claude/ and no reason to put a token
     * on disk that the FD was meant to keep off disk.
     * Translated from maybePersistTokenForSubprocesses() in authFileDescriptor.ts
     */
    public static void maybePersistTokenForSubprocesses(String path, String token, String tokenName) {
        String claudeCodeRemote = System.getenv("CLAUDE_CODE_REMOTE");
        if (!EnvUtils.isEnvTruthy(claudeCodeRemote)) {
            return;
        }
        try {
            Path dir = Paths.get(CCR_TOKEN_DIR);
            Files.createDirectories(dir);
            // Set dir permissions to 700
            try {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystem (e.g. Windows)
            }

            Path tokenPath = Paths.get(path);
            Files.writeString(tokenPath, token);
            // Set file permissions to 600
            try {
                Files.setPosixFilePermissions(tokenPath, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {}

            log.debug("Persisted {} to {} for subprocess access", tokenName, path);
        } catch (Exception e) {
            log.error("Failed to persist {} to disk (non-fatal): {}", tokenName, e.getMessage());
        }
    }

    /**
     * Fallback read from a well-known file. The path only exists in CCR (env-manager
     * creates the directory), so file-not-found is the expected outcome everywhere
     * else — treated as "no fallback", not an error.
     * Translated from readTokenFromWellKnownFile() in authFileDescriptor.ts
     */
    public static String readTokenFromWellKnownFile(String path, String tokenName) {
        try {
            String token = Files.readString(Paths.get(path)).trim();
            if (token.isEmpty()) {
                return null;
            }
            log.debug("Read {} from well-known file {}", tokenName, path);
            return token;
        } catch (NoSuchFileException e) {
            // ENOENT is the expected outcome outside CCR — stay silent.
            return null;
        } catch (Exception e) {
            // Anything else (EACCES from perm misconfig, etc.) is worth surfacing.
            log.debug("Failed to read {} from {}: {}", tokenName, path, e.getMessage());
            return null;
        }
    }

    /**
     * Shared FD-or-well-known-file credential reader.
     *
     * Priority order:
     *  1. File descriptor — env var points at a pipe FD passed by the Go env-manager.
     *  2. Well-known file — written by this function on successful FD read.
     *
     * Returns null if neither source has a credential. Cached in atomic references.
     * Translated from getCredentialFromFd() in authFileDescriptor.ts
     */
    private static String getCredentialFromFd(
            String envVar,
            String wellKnownPath,
            String label,
            AtomicReference<String> cache
    ) {
        // Sentinel: empty string means "we tried and got nothing"
        String cached = cache.get();
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }

        String fdEnv = System.getenv(envVar);
        if (fdEnv == null || fdEnv.isBlank()) {
            // No FD env var — try the well-known file
            String fromFile = readTokenFromWellKnownFile(wellKnownPath, label);
            cache.set(fromFile != null ? fromFile : "");
            return fromFile;
        }

        int fd;
        try {
            fd = Integer.parseInt(fdEnv.trim());
        } catch (NumberFormatException e) {
            log.error("{} must be a valid file descriptor number, got: {}", envVar, fdEnv);
            cache.set("");
            return null;
        }

        try {
            // Use /dev/fd on macOS/BSD, /proc/self/fd on Linux
            String os = System.getProperty("os.name", "").toLowerCase();
            String fdPath = (os.contains("mac") || os.contains("freebsd"))
                    ? "/dev/fd/" + fd
                    : "/proc/self/fd/" + fd;

            String token = Files.readString(Paths.get(fdPath)).trim();
            if (token.isEmpty()) {
                log.error("File descriptor contained empty {}", label);
                cache.set("");
                return null;
            }
            log.debug("Successfully read {} from file descriptor {}", label, fd);
            cache.set(token);
            maybePersistTokenForSubprocesses(wellKnownPath, token, label);
            return token;
        } catch (IOException e) {
            log.error("Failed to read {} from file descriptor {}: {}", label, fd, e.getMessage());
            // FD env var was set but read failed — try the well-known file
            String fromFile = readTokenFromWellKnownFile(wellKnownPath, label);
            cache.set(fromFile != null ? fromFile : "");
            return fromFile;
        }
    }

    /**
     * Get the CCR-injected OAuth token.
     * Env var: CLAUDE_CODE_OAUTH_TOKEN_FILE_DESCRIPTOR.
     * Well-known file: /home/claude/.claude/remote/.oauth_token.
     * Translated from getOAuthTokenFromFileDescriptor() in authFileDescriptor.ts
     */
    public static String getOAuthTokenFromFileDescriptor() {
        return getCredentialFromFd(
                "CLAUDE_CODE_OAUTH_TOKEN_FILE_DESCRIPTOR",
                CCR_OAUTH_TOKEN_PATH,
                "OAuth token",
                cachedOauthToken
        );
    }

    /**
     * Get the CCR-injected API key.
     * Env var: CLAUDE_CODE_API_KEY_FILE_DESCRIPTOR.
     * Well-known file: /home/claude/.claude/remote/.api_key.
     * Translated from getApiKeyFromFileDescriptor() in authFileDescriptor.ts
     */
    public static String getApiKeyFromFileDescriptor() {
        return getCredentialFromFd(
                "CLAUDE_CODE_API_KEY_FILE_DESCRIPTOR",
                CCR_API_KEY_PATH,
                "API key",
                cachedApiKey
        );
    }

    /** Reset cached credentials for testing. */
    public static void resetCacheForTesting() {
        cachedOauthToken.set(null);
        cachedApiKey.set(null);
    }

    private AuthFileDescriptorUtils() {}
}
