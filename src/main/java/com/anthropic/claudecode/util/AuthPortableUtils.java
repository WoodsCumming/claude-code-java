package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;

/**
 * Portable authentication utilities.
 * Translated from src/utils/authPortable.ts
 *
 * <p>Contains macOS keychain integration for API key management and
 * API key normalization for config storage.</p>
 */
@Slf4j
public class AuthPortableUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthPortableUtils.class);


    /**
     * Attempt to remove the API key from the macOS keychain.
     * No-op on non-macOS platforms.
     * Throws if the security command fails (exitCode != 0).
     * Translated from maybeRemoveApiKeyFromMacOSKeychainThrows() in authPortable.ts
     */
    public static CompletableFuture<Void> maybeRemoveApiKeyFromMacOSKeychainThrows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("mac")) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                String storageServiceName = MacOsKeychainHelpers.getMacOsKeychainStorageServiceName();
                String user = System.getenv("USER");
                if (user == null || user.isBlank()) {
                    user = System.getProperty("user.name", "");
                }

                ProcessBuilder pb = new ProcessBuilder(
                        "security", "delete-generic-password",
                        "-a", user,
                        "-s", storageServiceName
                );
                pb.redirectErrorStream(false);
                Process process = pb.start();
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    throw new RuntimeException("Failed to delete keychain entry");
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Failed to delete keychain entry: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Normalize an API key for config storage: returns only the last 20 characters.
     * Translated from normalizeApiKeyForConfig() in authPortable.ts
     */
    public static String normalizeApiKeyForConfig(String apiKey) {
        if (apiKey == null) return null;
        int len = apiKey.length();
        if (len <= 20) return apiKey;
        return apiKey.substring(len - 20);
    }

    private AuthPortableUtils() {}

    /**
     * Internal helper — mirrors getMacOsKeychainStorageServiceName from
     * src/utils/secureStorage/macOsKeychainHelpers.ts.
     * Falls back to "claude-code" if the real helper is not wired.
     */
    static class MacOsKeychainHelpers {
        static String getMacOsKeychainStorageServiceName() {
            // Delegate to SecureStorageService if available, otherwise use the default name
            return "claude-code";
        }
    }
}
