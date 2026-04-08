package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.OAuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import java.awt.Desktop;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/**
 * Login command for authenticating with Claude.
 * Translated from src/commands/login/login.tsx
 */
@Slf4j
@Component
@Command(
    name = "login",
    description = "Login to Claude.ai"
)
public class LoginCommand implements Runnable {

    @Option(
        names = {"--provider"},
        description = "Authentication provider: claude_ai (default) or api_key"
    )
    private String provider = "claude_ai";

    private final OAuthService oauthService;

    @Autowired
    public LoginCommand(OAuthService oauthService) {
        this.oauthService = oauthService;
    }

    @Override
    public void run() {
        System.out.println("Logging in to Claude.ai...");

        try {
            // Start a local server to receive the OAuth callback
            int port = findAvailablePort();
            // Generate PKCE code verifier and challenge
            String codeVerifier = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(java.security.SecureRandom.getInstanceStrong().generateSeed(32));
            String codeChallenge = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
            String state = java.util.UUID.randomUUID().toString();
            String redirectUri = "http://localhost:" + port + "/callback";
            String authUrlStr = oauthService.buildAuthUrl(new OAuthService.BuildAuthUrlOptions(
                codeChallenge, state, port, false, true, null, null, null, null));
            OAuthService.AuthUrlResult authUrl = new OAuthService.AuthUrlResult(
                authUrlStr, codeVerifier, state, redirectUri);

            System.out.println("\nOpening browser for authentication...");
            System.out.println("If browser doesn't open, visit: " + authUrl.url());

            // Try to open browser
            openBrowser(authUrl.url());

            // Wait for callback
            String code = waitForOAuthCallback(port, authUrl.state());

            if (code != null) {
                System.out.println("Exchanging authorization code...");
                OAuthService.OAuthTokens tokens = oauthService.exchangeCodeForTokens(
                    code,
                    state,
                    authUrl.codeVerifier(),
                    port,
                    false,
                    null
                ).thenApply(resp -> {
                    OAuthService.OAuthTokens t = new OAuthService.OAuthTokens();
                    t.setAccessToken(resp.access_token());
                    t.setRefreshToken(resp.refresh_token());
                    t.setExpiresAt(System.currentTimeMillis() + resp.expires_in() * 1000L);
                    return t;
                }).get(30, TimeUnit.SECONDS);

                System.out.println("\nLogin successful!");
                System.out.println("You are now authenticated with Claude.ai.");
            } else {
                System.err.println("Login failed: no authorization code received");
            }

        } catch (Exception e) {
            System.err.println("Login failed: " + e.getMessage());
            log.error("Login failed", e);
        }
    }

    private void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(url));
                return;
            }
        } catch (Exception e) {
            // Fall through to manual methods
        }

        // Try OS-specific methods
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
            } else if (os.contains("linux")) {
                new ProcessBuilder("xdg-open", url).start();
            } else if (os.contains("win")) {
                new ProcessBuilder("cmd", "/c", "start", url).start();
            }
        } catch (Exception e) {
            log.debug("Could not open browser: {}", e.getMessage());
        }
    }

    private String waitForOAuthCallback(int port, String expectedState) throws Exception {
        CompletableFuture<String> codeFuture = new CompletableFuture<>();

        // Simple HTTP server to receive callback
        com.sun.net.httpserver.HttpServer server =
            com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/callback", exchange -> {
            try {
                String query = exchange.getRequestURI().getQuery();
                Map<String, String> params = parseQueryString(query);

                String state = params.get("state");
                String code = params.get("code");
                String error = params.get("error");

                String responseBody;
                if (error != null) {
                    responseBody = "<html><body><h1>Login Failed</h1><p>" + error + "</p></body></html>";
                    codeFuture.completeExceptionally(new RuntimeException("OAuth error: " + error));
                } else if (!expectedState.equals(state)) {
                    responseBody = "<html><body><h1>Login Failed</h1><p>Invalid state</p></body></html>";
                    codeFuture.completeExceptionally(new RuntimeException("Invalid OAuth state"));
                } else {
                    responseBody = "<html><body><h1>Login Successful</h1><p>You can close this window.</p></body></html>";
                    codeFuture.complete(code);
                }

                byte[] bytes = responseBody.getBytes();
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.getResponseBody().close();
            } catch (Exception e) {
                codeFuture.completeExceptionally(e);
            } finally {
                server.stop(0);
            }
        });

        server.start();

        try {
            return codeFuture.get(120, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            server.stop(0);
            throw new RuntimeException("Login timed out after 2 minutes");
        }
    }

    private int findAvailablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private Map<String, String> parseQueryString(String query) {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        if (query == null) return params;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                try {
                    String key = URLDecoder.decode(pair.substring(0, idx), "UTF-8");
                    String value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                    params.put(key, value);
                } catch (Exception e) {
                    // Skip malformed pairs
                }
            }
        }
        return params;
    }
}
