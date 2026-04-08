package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Secure storage service for API keys and OAuth tokens.
 * Translated from src/utils/secureStorage/index.ts
 *
 * On macOS, uses the system keychain.
 * On other platforms, uses plain text file storage.
 */
@Slf4j
@Service
public class SecureStorageService implements BridgeTrustedDeviceService.SecureStorageService {

    private static final String KEYS_FILE = "credentials.json";

    /**
     * Get a stored value.
     * Translated from SecureStorage.get() in types.ts
     */
    public Optional<String> get(String key) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return getFromKeychain(key);
        }
        return getFromFile(key);
    }

    /**
     * Store a value.
     * Translated from SecureStorage.set() in types.ts
     */
    public boolean set(String key, String value) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return setInKeychain(key, value);
        }
        return setInFile(key, value);
    }

    /**
     * Delete a stored value.
     */
    public boolean delete(String key) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return deleteFromKeychain(key);
        }
        return deleteFromFile(key);
    }

    // =========================================================================
    // macOS Keychain
    // =========================================================================

    private Optional<String> getFromKeychain(String key) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "security", "find-generic-password",
                "-s", "claude-code",
                "-a", key,
                "-w"
            );
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            int exitCode = p.waitFor();
            if (exitCode == 0 && !output.isEmpty()) {
                return Optional.of(output);
            }
        } catch (Exception e) {
            log.debug("Keychain read failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private boolean setInKeychain(String key, String value) {
        try {
            // First delete existing entry
            deleteFromKeychain(key);

            ProcessBuilder pb = new ProcessBuilder(
                "security", "add-generic-password",
                "-s", "claude-code",
                "-a", key,
                "-w", value
            );
            Process p = pb.start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            log.debug("Keychain write failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean deleteFromKeychain(String key) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "security", "delete-generic-password",
                "-s", "claude-code",
                "-a", key
            );
            Process p = pb.start();
            p.waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // =========================================================================
    // Plain text file storage (fallback)
    // =========================================================================

    private Map<String, String> loadCredentials() {
        String path = EnvUtils.getClaudeConfigHomeDir() + "/" + KEYS_FILE;
        File file = new File(path);
        if (!file.exists()) return new LinkedHashMap<>();

        try {
            String content = Files.readString(file.toPath());
            // Simple key=value format
            Map<String, String> map = new LinkedHashMap<>();
            for (String line : content.split("\n")) {
                int idx = line.indexOf('=');
                if (idx > 0) {
                    map.put(line.substring(0, idx), line.substring(idx + 1));
                }
            }
            return map;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private void saveCredentials(Map<String, String> credentials) {
        String path = EnvUtils.getClaudeConfigHomeDir() + "/" + KEYS_FILE;
        try {
            new File(path).getParentFile().mkdirs();
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : credentials.entrySet()) {
                sb.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
            }
            Files.writeString(Paths.get(path), sb.toString());
            // Set file permissions to 600
            new File(path).setReadable(false, false);
            new File(path).setReadable(true, true);
            new File(path).setWritable(false, false);
            new File(path).setWritable(true, true);
        } catch (Exception e) {
            log.warn("Could not save credentials: {}", e.getMessage());
        }
    }

    private Optional<String> getFromFile(String key) {
        return Optional.ofNullable(loadCredentials().get(key));
    }

    private boolean setInFile(String key, String value) {
        Map<String, String> credentials = loadCredentials();
        credentials.put(key, value);
        saveCredentials(credentials);
        return true;
    }

    private boolean deleteFromFile(String key) {
        Map<String, String> credentials = loadCredentials();
        credentials.remove(key);
        saveCredentials(credentials);
        return true;
    }

    // =========================================================================
    // BridgeTrustedDeviceService.SecureStorageService implementation
    // =========================================================================

    private static final String TRUSTED_DEVICE_TOKEN_KEY = "trusted_device_token";

    @Override
    public String readTrustedDeviceToken() {
        return get(TRUSTED_DEVICE_TOKEN_KEY).orElse(null);
    }

    @Override
    public boolean storeTrustedDeviceToken(String token) {
        return set(TRUSTED_DEVICE_TOKEN_KEY, token);
    }

    @Override
    public void clearTrustedDeviceToken() {
        delete(TRUSTED_DEVICE_TOKEN_KEY);
    }
}
