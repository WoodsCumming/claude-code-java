package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Upstream proxy service for CCR container sessions.
 * Translated from src/upstreamproxy/upstreamproxy.ts
 *
 * Handles proxy configuration for CCR session containers.
 */
@Slf4j
@Service
public class UpstreamProxyService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UpstreamProxyService.class);


    private static final String SESSION_TOKEN_PATH = "/run/ccr/session_token";
    private static final String PROXY_CA_CERT_PATH = "/run/ccr/ca.crt";

    /**
     * Initialize the upstream proxy.
     * Translated from initUpstreamProxy() in upstreamproxy.ts
     */
    public CompletableFuture<Void> initUpstreamProxy() {
        return CompletableFuture.runAsync(() -> {
            if (!isRunningInCCR()) {
                return;
            }

            try {
                log.info("Initializing upstream proxy for CCR session");

                // Read session token
                String sessionToken = readSessionToken();
                if (sessionToken == null) {
                    log.warn("No CCR session token found");
                    return;
                }

                // Configure proxy environment variables
                String httpsProxy = System.getenv("UPSTREAM_PROXY_URL");
                if (httpsProxy != null) {
                    System.setProperty("https.proxyHost", extractHost(httpsProxy));
                    System.setProperty("https.proxyPort", extractPort(httpsProxy));
                    log.info("Upstream proxy configured: {}", httpsProxy);
                }

                // Remove token file after reading (security)
                Files.deleteIfExists(Paths.get(SESSION_TOKEN_PATH));

            } catch (Exception e) {
                log.warn("Upstream proxy initialization failed (continuing without proxy): {}", e.getMessage());
            }
        });
    }

    private boolean isRunningInCCR() {
        return new File(SESSION_TOKEN_PATH).exists()
            || EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_REMOTE"));
    }

    private String readSessionToken() {
        try {
            return Files.readString(Paths.get(SESSION_TOKEN_PATH)).trim();
        } catch (Exception e) {
            return null;
        }
    }

    private String extractHost(String proxyUrl) {
        try {
            java.net.URI uri = new java.net.URI(proxyUrl);
            return uri.getHost();
        } catch (Exception e) {
            return proxyUrl;
        }
    }

    private String extractPort(String proxyUrl) {
        try {
            java.net.URI uri = new java.net.URI(proxyUrl);
            int port = uri.getPort();
            return port > 0 ? String.valueOf(port) : "443";
        } catch (Exception e) {
            return "443";
        }
    }
}
