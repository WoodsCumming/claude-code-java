package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.nio.file.*;
import java.util.Optional;

/**
 * Auth file descriptor utilities for CCR container environments.
 * Translated from src/utils/authFileDescriptor.ts
 */
@Slf4j
public class AuthFileDescriptor {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthFileDescriptor.class);


    public static final String CCR_TOKEN_DIR = "/home/claude/.claude/remote";
    public static final String CCR_OAUTH_TOKEN_PATH = CCR_TOKEN_DIR + "/.oauth_token";
    public static final String CCR_API_KEY_PATH = CCR_TOKEN_DIR + "/.api_key";
    public static final String CCR_SESSION_INGRESS_TOKEN_PATH = CCR_TOKEN_DIR + "/.session_ingress_token";

    /**
     * Read a token from a well-known file location.
     * Translated from readTokenFromWellKnownFile() in authFileDescriptor.ts
     */
    public static Optional<String> readTokenFromWellKnownFile(String path, String description) {
        try {
            String token = Files.readString(Paths.get(path)).trim();
            if (!token.isBlank()) {
                log.debug("Read {} from {}", description, path);
                return Optional.of(token);
            }
        } catch (java.nio.file.NoSuchFileException e) {
            // File doesn't exist - that's ok
        } catch (Exception e) {
            log.debug("Could not read {} from {}: {}", description, path, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Persist a token for subprocess access.
     * Translated from maybePersistTokenForSubprocesses() in authFileDescriptor.ts
     */
    public static void maybePersistTokenForSubprocesses(String token, String tokenPath) {
        try {
            new File(CCR_TOKEN_DIR).mkdirs();
            Files.writeString(Paths.get(tokenPath), token);
            // Set restrictive permissions
            new File(tokenPath).setReadable(false, false);
            new File(tokenPath).setReadable(true, true);
        } catch (Exception e) {
            log.debug("Could not persist token to {}: {}", tokenPath, e.getMessage());
        }
    }

    /**
     * Get the API key from an environment variable file descriptor.
     * Translated from getApiKeyFromFileDescriptor() in authFileDescriptor.ts
     */
    public static Optional<String> getApiKeyFromFileDescriptor() {
        String fdEnv = System.getenv("ANTHROPIC_API_KEY_FILE_DESCRIPTOR");
        if (fdEnv == null) return Optional.empty();

        // In Java, we can't easily read from a file descriptor number
        // Fall back to reading from well-known file
        return readTokenFromWellKnownFile(CCR_API_KEY_PATH, "API key");
    }

    /**
     * Get the OAuth token from an environment variable file descriptor.
     * Translated from getOAuthTokenFromFileDescriptor() in authFileDescriptor.ts
     */
    public static Optional<String> getOAuthTokenFromFileDescriptor() {
        String fdEnv = System.getenv("CLAUDE_CODE_OAUTH_TOKEN_FILE_DESCRIPTOR");
        if (fdEnv == null) return Optional.empty();

        // Fall back to reading from well-known file
        return readTokenFromWellKnownFile(CCR_OAUTH_TOKEN_PATH, "OAuth token");
    }

    private AuthFileDescriptor() {}
}
