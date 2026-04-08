package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.CompletableFuture;

/**
 * Portable authentication utilities.
 * Translated from src/utils/authPortable.ts
 */
@Slf4j
public class AuthPortable {



    /**
     * Remove API key from macOS keychain.
     * Translated from maybeRemoveApiKeyFromMacOSKeychainThrows() in authPortable.ts
     */
    public static CompletableFuture<Void> maybeRemoveApiKeyFromMacOSKeychain() {
        if (!PlatformUtils.isMacOS()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                String serviceName = "claude-code";
                ProcessBuilder pb = new ProcessBuilder(
                    "security", "delete-generic-password",
                    "-a", System.getenv("USER"),
                    "-s", serviceName
                );
                pb.redirectErrorStream(true);
                Process process = pb.start();
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    throw new RuntimeException("Failed to delete keychain entry");
                }
            } catch (Exception e) {
                log.debug("Could not remove API key from keychain: {}", e.getMessage());
            }
        });
    }

    /**
     * Normalize an API key for config storage (last 20 chars).
     * Translated from normalizeApiKeyForConfig() in authPortable.ts
     */
    public static String normalizeApiKeyForConfig(String apiKey) {
        if (apiKey == null || apiKey.length() <= 20) return apiKey;
        return apiKey.substring(apiKey.length() - 20);
    }

    private AuthPortable() {}
}
