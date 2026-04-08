package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * OAuth redirect port helpers.
 * Translated from src/services/mcp/oauthPort.ts
 *
 * <p>Extracted from auth utilities to break circular dependencies between
 * auth and OAuth-login modules.</p>
 *
 * <p>RFC 8252 Section 7.3 (OAuth for Native Apps): loopback redirect URIs
 * match any port as long as the path matches.</p>
 */
@Slf4j
public final class OAuthPortUtils {



    // ── Constants ─────────────────────────────────────────────────────────────

    private static final int REDIRECT_PORT_FALLBACK = 3118;

    /**
     * Windows dynamic-port range 49152–65535 is reserved; use the lower range.
     * Non-Windows uses the full 49152–65535 ephemeral range.
     * Translated from REDIRECT_PORT_RANGE in oauthPort.ts
     */
    private static final int WIN_PORT_MIN  = 39152;
    private static final int WIN_PORT_MAX  = 49151;
    private static final int PORT_MIN      = 49152;
    private static final int PORT_MAX      = 65535;

    private static final int MAX_ATTEMPTS = 100;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Build a redirect URI on localhost with the given port and a fixed
     * {@code /callback} path.
     * Translated from buildRedirectUri() in oauthPort.ts
     */
    public static String buildRedirectUri(int port) {
        return "http://localhost:" + port + "/callback";
    }

    /**
     * Build a redirect URI using the fallback port (3118).
     */
    public static String buildRedirectUri() {
        return buildRedirectUri(REDIRECT_PORT_FALLBACK);
    }

    /**
     * Find an available port in the platform-appropriate range for an OAuth
     * redirect. Uses random selection for better security.
     * Translated from findAvailablePort() in oauthPort.ts
     *
     * <p>Checks {@code MCP_OAUTH_CALLBACK_PORT} env var first. Falls back to
     * random selection in the ephemeral range, then to the fixed fallback port,
     * and finally to any OS-assigned port.</p>
     *
     * @return a {@link CompletableFuture} that resolves to an available port number
     */
    public static CompletableFuture<Integer> findAvailablePort() {
        return CompletableFuture.supplyAsync(() -> {
            // 1. Try the configured port if specified
            int configuredPort = getMcpOAuthCallbackPort();
            if (configuredPort > 0) {
                return configuredPort;
            }

            // 2. Random selection in platform range
            boolean isWindows = isWindows();
            int min = isWindows ? WIN_PORT_MIN  : PORT_MIN;
            int max = isWindows ? WIN_PORT_MAX  : PORT_MAX;
            int range = max - min + 1;
            int attempts = Math.min(range, MAX_ATTEMPTS);

            Random random = new Random();
            for (int attempt = 0; attempt < attempts; attempt++) {
                int port = min + random.nextInt(range);
                if (isPortAvailable(port)) {
                    return port;
                }
            }

            // 3. Try the fixed fallback port
            if (isPortAvailable(REDIRECT_PORT_FALLBACK)) {
                return REDIRECT_PORT_FALLBACK;
            }

            // 4. Let the OS pick any free port
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            } catch (IOException e) {
                throw new RuntimeException("No available ports for OAuth redirect", e);
            }
        });
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Read {@code MCP_OAUTH_CALLBACK_PORT} from the environment.
     * Translated from getMcpOAuthCallbackPort() in oauthPort.ts
     */
    private static int getMcpOAuthCallbackPort() {
        String envVal = System.getenv("MCP_OAUTH_CALLBACK_PORT");
        if (envVal == null || envVal.isBlank()) return 0;
        try {
            int port = Integer.parseInt(envVal.trim());
            return port > 0 ? port : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Check whether the given port is free by briefly binding to it.
     */
    private static boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private OAuthPortUtils() {}
}
