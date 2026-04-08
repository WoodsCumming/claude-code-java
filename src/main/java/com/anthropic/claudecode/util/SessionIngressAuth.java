package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Session ingress authentication utilities.
 * Translated from src/utils/sessionIngressAuth.ts
 *
 * <p>Provides token retrieval for CCR WebSocket session authentication with
 * a priority chain: environment variable → file descriptor → well-known file.
 * Pipe FDs can only be read once and don't cross exec/tmux boundaries; the
 * well-known file fallback covers subprocesses that can't inherit the FD.</p>
 */
@Slf4j
public class SessionIngressAuth {



    /**
     * Sentinel: null = not yet attempted, empty string = cached miss.
     * Mirrored from global state.getSessionIngressToken() / setSessionIngressToken() in TS.
     */
    private static final AtomicReference<String> cachedToken = new AtomicReference<>();

    /**
     * Read the session ingress token from a file descriptor, falling back to
     * the well-known file if the FD read fails or is unavailable.
     * Translated from getTokenFromFileDescriptor() in sessionIngressAuth.ts
     */
    private static String getTokenFromFileDescriptor() {
        // Check cache (sentinel: empty string = previously tried and got nothing)
        String cached = cachedToken.get();
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }

        String fdEnv = System.getenv("CLAUDE_CODE_WEBSOCKET_AUTH_FILE_DESCRIPTOR");
        if (fdEnv == null || fdEnv.isBlank()) {
            // No FD env var — try the well-known file
            String path = System.getenv("CLAUDE_SESSION_INGRESS_TOKEN_FILE");
            if (path == null || path.isBlank()) {
                path = AuthFileDescriptorUtils.CCR_SESSION_INGRESS_TOKEN_PATH;
            }
            String fromFile = AuthFileDescriptorUtils.readTokenFromWellKnownFile(path, "session ingress token");
            cachedToken.set(fromFile != null ? fromFile : "");
            return fromFile;
        }

        int fd;
        try {
            fd = Integer.parseInt(fdEnv.trim());
        } catch (NumberFormatException e) {
            log.error("CLAUDE_CODE_WEBSOCKET_AUTH_FILE_DESCRIPTOR must be a valid file descriptor number, got: {}", fdEnv);
            cachedToken.set("");
            return null;
        }

        try {
            // Use /dev/fd on macOS/BSD, /proc/self/fd on Linux
            String os = System.getProperty("os.name", "").toLowerCase();
            String fdPath = (os.contains("mac") || os.contains("freebsd"))
                    ? "/dev/fd/" + fd
                    : "/proc/self/fd/" + fd;

            String token = java.nio.file.Files.readString(java.nio.file.Paths.get(fdPath)).trim();
            if (token.isEmpty()) {
                log.error("File descriptor contained empty token");
                cachedToken.set("");
                return null;
            }
            log.debug("Successfully read token from file descriptor {}", fd);
            cachedToken.set(token);
            AuthFileDescriptorUtils.maybePersistTokenForSubprocesses(
                    AuthFileDescriptorUtils.CCR_SESSION_INGRESS_TOKEN_PATH,
                    token,
                    "session ingress token"
            );
            return token;
        } catch (java.io.IOException e) {
            log.error("Failed to read token from file descriptor {}: {}", fd, e.getMessage());
            // FD env var was set but read failed (e.g. subprocess that inherited env but not FD).
            // Fall back to well-known file.
            String path = System.getenv("CLAUDE_SESSION_INGRESS_TOKEN_FILE");
            if (path == null || path.isBlank()) {
                path = AuthFileDescriptorUtils.CCR_SESSION_INGRESS_TOKEN_PATH;
            }
            String fromFile = AuthFileDescriptorUtils.readTokenFromWellKnownFile(path, "session ingress token");
            cachedToken.set(fromFile != null ? fromFile : "");
            return fromFile;
        }
    }

    /**
     * Get session ingress authentication token.
     *
     * Priority order:
     *  1. Environment variable (CLAUDE_CODE_SESSION_ACCESS_TOKEN) — set at spawn time,
     *     updated in-process via {@link #updateSessionIngressAuthToken}.
     *  2. File descriptor (legacy path) — CLAUDE_CODE_WEBSOCKET_AUTH_FILE_DESCRIPTOR,
     *     read once and cached.
     *  3. Well-known file — CLAUDE_SESSION_INGRESS_TOKEN_FILE env var path, or
     *     /home/claude/.claude/remote/.session_ingress_token.
     *
     * Translated from getSessionIngressAuthToken() in sessionIngressAuth.ts
     */
    public static String getSessionIngressAuthToken() {
        // 1. Environment variable
        String envToken = System.getenv("CLAUDE_CODE_SESSION_ACCESS_TOKEN");
        if (envToken != null && !envToken.isBlank()) {
            return envToken;
        }

        // 2 & 3. File descriptor with well-known file fallback
        return getTokenFromFileDescriptor();
    }

    /**
     * Build auth headers for the current session token.
     *
     * Session keys (sk-ant-sid) use Cookie auth + X-Organization-Uuid;
     * JWTs use Bearer auth.
     * Translated from getSessionIngressAuthHeaders() in sessionIngressAuth.ts
     */
    public static Map<String, String> getSessionIngressAuthHeaders() {
        String token = getSessionIngressAuthToken();
        if (token == null || token.isBlank()) {
            return Map.of();
        }

        Map<String, String> headers = new HashMap<>();
        if (token.startsWith("sk-ant-sid")) {
            headers.put("Cookie", "sessionKey=" + token);
            String orgUuid = System.getenv("CLAUDE_CODE_ORGANIZATION_UUID");
            if (orgUuid != null && !orgUuid.isBlank()) {
                headers.put("X-Organization-Uuid", orgUuid);
            }
        } else {
            headers.put("Authorization", "Bearer " + token);
        }
        return Map.copyOf(headers);
    }

    /**
     * Update the session ingress auth token in-process by setting the system property.
     * Used by the REPL bridge to inject a fresh token after reconnection
     * without restarting the process.
     * Translated from updateSessionIngressAuthToken() in sessionIngressAuth.ts
     *
     * Note: In Java we cannot set process env vars at runtime, so we use a
     * thread-local / system property mechanism as the closest equivalent.
     * Callers that need env-var semantics should update the cached token directly.
     */
    public static void updateSessionIngressAuthToken(String token) {
        // Store in a system property so it takes priority in getSessionIngressAuthToken()
        if (token != null && !token.isBlank()) {
            System.setProperty("CLAUDE_CODE_SESSION_ACCESS_TOKEN", token);
        }
    }

    /** Reset cached token for testing. */
    public static void resetCacheForTesting() {
        cachedToken.set(null);
    }

    private SessionIngressAuth() {}
}
