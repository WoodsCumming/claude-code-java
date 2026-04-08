package com.anthropic.claudecode.service;

import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * OAuth authorization code listener service.
 * Translated from src/services/oauth/auth-code-listener.ts
 *
 * Temporary localhost HTTP server that listens for OAuth authorization code redirects.
 */
@Slf4j
@Service
public class AuthCodeListenerService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthCodeListenerService.class);


    private volatile HttpServer server;
    private volatile int port;
    private volatile String expectedState;

    /**
     * Start the listener.
     * Translated from AuthCodeListener.start() in auth-code-listener.ts
     */
    public CompletableFuture<Integer> start(String state) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                server = HttpServer.create(new InetSocketAddress(0), 0);
                port = ((InetSocketAddress) server.getAddress()).getPort();
                expectedState = state;
                server.start();
                log.debug("OAuth callback server started on port: {}", port);
                return port;
            } catch (Exception e) {
                throw new RuntimeException("Failed to start OAuth callback server: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Wait for the authorization code.
     * Translated from AuthCodeListener.waitForCode() in auth-code-listener.ts
     */
    public CompletableFuture<String> waitForCode() {
        CompletableFuture<String> future = new CompletableFuture<>();

        if (server == null) {
            future.completeExceptionally(new RuntimeException("Server not started"));
            return future;
        }

        server.createContext("/callback", exchange -> {
            try {
                String query = exchange.getRequestURI().getQuery();
                Map<String, String> params = parseQuery(query);

                String code = params.get("code");
                String state = params.get("state");
                String error = params.get("error");

                String responseBody;
                if (error != null) {
                    responseBody = "<html><body><h1>Authorization Failed</h1><p>" + error + "</p></body></html>";
                    future.completeExceptionally(new RuntimeException("OAuth error: " + error));
                } else if (code != null && (expectedState == null || expectedState.equals(state))) {
                    responseBody = "<html><body><h1>Authorization Successful</h1><p>You can close this window.</p></body></html>";
                    future.complete(code);
                } else {
                    responseBody = "<html><body><h1>Invalid State</h1></body></html>";
                    future.completeExceptionally(new RuntimeException("Invalid state parameter"));
                }

                byte[] bytes = responseBody.getBytes();
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.getResponseBody().close();

                // Stop the server after handling the callback
                server.stop(0);

            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Stop the listener.
     */
    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    public int getPort() {
        return port;
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null) return params;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                try {
                    params.put(
                        URLDecoder.decode(pair.substring(0, idx), "UTF-8"),
                        URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                    );
                } catch (Exception e) {
                    // Skip malformed pairs
                }
            }
        }
        return params;
    }
}
