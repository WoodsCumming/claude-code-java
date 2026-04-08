package com.anthropic.claudecode.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Settings Sync Service.
 * Translated from src/services/settingsSync/index.ts
 *
 * Syncs user settings and memory files across Claude Code environments.
 * - Interactive CLI: uploads local settings to remote (incremental, only changed entries)
 * - CCR: downloads remote settings to local before plugin installation
 */
@Slf4j
@Service
public class SettingsSyncService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SettingsSyncService.class);


    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final long SETTINGS_SYNC_TIMEOUT_MS = 10_000L;
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long MAX_FILE_SIZE_BYTES = 500L * 1024; // 500 KB

    // -------------------------------------------------------------------------
    // Sync keys (mirrors SYNC_KEYS in types.ts)
    // -------------------------------------------------------------------------

    public static final class SyncKeys {
        public static final String USER_SETTINGS  = "user_settings";
        public static final String USER_MEMORY    = "user_memory";

        public static String projectSettings(String projectId) {
            return "project_settings_" + projectId;
        }

        public static String projectMemory(String projectId) {
            return "project_memory_" + projectId;
        }

        private SyncKeys() {}
    }

    // -------------------------------------------------------------------------
    // Result types
    // -------------------------------------------------------------------------

    /**
     * Result of a settings fetch.
     * Translated from SettingsSyncFetchResult in types.ts
     */
    public sealed interface FetchResult permits
        FetchResult.Success,
        FetchResult.Empty,
        FetchResult.Failure {

        record Success(UserSyncData data) implements FetchResult {}
        record Empty() implements FetchResult {}
        record Failure(String error, boolean skipRetry) implements FetchResult {
            public Failure(String error) { this(error, false); }
        }
    }

    /**
     * Result of a settings upload.
     * Translated from SettingsSyncUploadResult in types.ts
     */
    public sealed interface UploadResult permits
        UploadResult.Success,
        UploadResult.Failure {

        record Success(String checksum, String lastModified) implements UploadResult {}
        record Failure(String error) implements UploadResult {}
    }

    /**
     * The synced content from the remote.
     * Translated from UserSyncData in types.ts
     */
    public record UserSyncData(SyncContent content) {}

    /**
     * The entries map inside UserSyncData.
     */
    public record SyncContent(Map<String, String> entries) {}

    // -------------------------------------------------------------------------
    // Collaborators
    // -------------------------------------------------------------------------

    private final OAuthService oauthService;
    private final GrowthBookService growthBookService;
    private final ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    /** Cached download promise — first call starts the fetch; subsequent calls join it. */
    private final AtomicReference<CompletableFuture<Boolean>> downloadPromise =
        new AtomicReference<>();

    @Autowired
    public SettingsSyncService(
            OAuthService oauthService,
            GrowthBookService growthBookService,
            ObjectMapper objectMapper) {
        this.oauthService = oauthService;
        this.growthBookService = growthBookService;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Upload (interactive CLI)
    // -------------------------------------------------------------------------

    /**
     * Upload local settings to remote in the background.
     * Runs fire-and-forget; caller should not await unless needed.
     * Translated from uploadUserSettingsInBackground() in index.ts
     */
    public CompletableFuture<Void> uploadUserSettingsInBackground() {
        return CompletableFuture.runAsync(() -> {
            try {
                if (!isUploadEnabled() || !isUsingOAuth()) {
                    log.debug("[settingsSync] upload skipped — ineligible");
                    return;
                }

                log.debug("[settingsSync] upload starting");
                FetchResult result = fetchUserSettings(DEFAULT_MAX_RETRIES);
                if (result instanceof FetchResult.Failure f) {
                    log.warn("[settingsSync] upload fetch failed: {}", f.error());
                    return;
                }

                Map<String, String> remoteEntries =
                    result instanceof FetchResult.Success s
                        ? s.data().content().entries() : Map.of();

                Map<String, String> localEntries = buildEntriesFromLocalFiles(getProjectId());

                // Only upload changed entries
                Map<String, String> changedEntries = new HashMap<>();
                localEntries.forEach((k, v) -> {
                    if (!v.equals(remoteEntries.get(k))) {
                        changedEntries.put(k, v);
                    }
                });

                if (changedEntries.isEmpty()) {
                    log.debug("[settingsSync] no changes to upload");
                    return;
                }

                UploadResult uploadResult = uploadUserSettings(changedEntries);
                if (uploadResult instanceof UploadResult.Success) {
                    log.debug("[settingsSync] upload success ({} entries)", changedEntries.size());
                } else {
                    log.warn("[settingsSync] upload failed");
                }
            } catch (Exception e) {
                log.debug("[settingsSync] unexpected upload error: {}", e.getMessage());
            }
        });
    }

    // -------------------------------------------------------------------------
    // Download (CCR mode)
    // -------------------------------------------------------------------------

    /**
     * Download settings from remote for CCR mode.
     * First call starts the fetch; subsequent calls join the same promise.
     * Translated from downloadUserSettings() in index.ts
     */
    public CompletableFuture<Boolean> downloadUserSettings() {
        CompletableFuture<Boolean> existing = downloadPromise.get();
        if (existing != null) return existing;

        CompletableFuture<Boolean> promise = doDownloadUserSettings(DEFAULT_MAX_RETRIES);
        if (downloadPromise.compareAndSet(null, promise)) {
            return promise;
        }
        // Another thread won the race
        return downloadPromise.get();
    }

    /**
     * Force a fresh download, bypassing the cached startup promise.
     * Called by /reload-plugins in CCR.
     * Translated from redownloadUserSettings() in index.ts
     */
    public CompletableFuture<Boolean> redownloadUserSettings() {
        CompletableFuture<Boolean> fresh = doDownloadUserSettings(0);
        downloadPromise.set(fresh);
        return fresh;
    }

    /**
     * Reset the cached download promise (for testing).
     * Translated from _resetDownloadPromiseForTesting() in index.ts
     */
    public void resetDownloadPromiseForTesting() {
        downloadPromise.set(null);
    }

    private CompletableFuture<Boolean> doDownloadUserSettings(int maxRetries) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!isDownloadEnabled() || !isUsingOAuth()) {
                    log.debug("[settingsSync] download skipped");
                    return false;
                }

                log.debug("[settingsSync] download starting");
                FetchResult result = fetchUserSettings(maxRetries);

                if (result instanceof FetchResult.Failure f) {
                    log.warn("[settingsSync] download fetch failed: {}", f.error());
                    return false;
                }

                if (result instanceof FetchResult.Empty) {
                    log.debug("[settingsSync] download empty");
                    return false;
                }

                FetchResult.Success success = (FetchResult.Success) result;
                Map<String, String> entries = success.data().content().entries();
                String projectId = getProjectId();
                applyRemoteEntriesToLocal(entries, projectId);
                log.debug("[settingsSync] download applied {} entries", entries.size());
                return true;
            } catch (Exception e) {
                log.debug("[settingsSync] download error: {}", e.getMessage());
                return false;
            }
        });
    }

    // -------------------------------------------------------------------------
    // HTTP helpers
    // -------------------------------------------------------------------------

    private FetchResult fetchUserSettings(int maxRetries) {
        FetchResult lastResult = null;
        for (int attempt = 1; attempt <= maxRetries + 1; attempt++) {
            lastResult = fetchUserSettingsOnce();

            if (lastResult instanceof FetchResult.Success || lastResult instanceof FetchResult.Empty) {
                return lastResult;
            }
            FetchResult.Failure failure = (FetchResult.Failure) lastResult;
            if (failure.skipRetry() || attempt > maxRetries) return lastResult;

            long delayMs = getRetryDelay(attempt);
            log.debug("[settingsSync] retry {}/{} after {}ms", attempt, maxRetries, delayMs);
            try { Thread.sleep(delayMs); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new FetchResult.Failure("Interrupted");
            }
        }
        return lastResult != null ? lastResult : new FetchResult.Failure("No result");
    }

    @SuppressWarnings("unchecked")
    private FetchResult fetchUserSettingsOnce() {
        try {
            Map<String, String> authHeaders = getAuthHeaders();
            if (authHeaders.isEmpty()) {
                return new FetchResult.Failure("No OAuth token available", true);
            }

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(getSettingsSyncEndpoint()))
                .timeout(Duration.ofMillis(SETTINGS_SYNC_TIMEOUT_MS))
                .GET();

            authHeaders.forEach(builder::header);
            builder.header("User-Agent", getUserAgent());

            HttpResponse<String> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                return new FetchResult.Empty();
            }

            if (response.statusCode() != 200) {
                return new FetchResult.Failure("HTTP " + response.statusCode());
            }

            Map<String, Object> raw = objectMapper.readValue(
                response.body(), new TypeReference<>() {});

            Object entriesObj = ((Map<?, ?>) raw.getOrDefault("content", Map.of()))
                .get("entries");
            Map<String, String> entries = entriesObj instanceof Map<?, ?>
                ? (Map<String, String>) (Map<?, ?>) entriesObj : Map.of();

            return new FetchResult.Success(
                new UserSyncData(new SyncContent(entries)));

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            String msg = e.getMessage();
            if (msg != null && (msg.contains("401") || msg.contains("403"))) {
                return new FetchResult.Failure("Not authorized for settings sync", true);
            }
            return new FetchResult.Failure(msg != null ? msg : "Unknown error");
        }
    }

    private UploadResult uploadUserSettings(Map<String, String> entries) {
        try {
            Map<String, String> authHeaders = getAuthHeaders();
            if (authHeaders.isEmpty()) {
                return new UploadResult.Failure("No OAuth token available");
            }

            String body = objectMapper.writeValueAsString(Map.of("entries", entries));

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(getSettingsSyncEndpoint()))
                .timeout(Duration.ofMillis(SETTINGS_SYNC_TIMEOUT_MS))
                .PUT(HttpRequest.BodyPublishers.ofString(body));

            authHeaders.forEach(builder::header);
            builder.header("User-Agent", getUserAgent());
            builder.header("Content-Type", "application/json");

            HttpResponse<String> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new UploadResult.Success(null, null);
            }
            return new UploadResult.Failure("HTTP " + response.statusCode());

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new UploadResult.Failure(e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Local file I/O
    // -------------------------------------------------------------------------

    private Map<String, String> buildEntriesFromLocalFiles(String projectId) {
        Map<String, String> entries = new HashMap<>();

        // Global user settings
        String userSettingsPath = getUserSettingsPath();
        if (userSettingsPath != null) {
            String content = tryReadFile(userSettingsPath);
            if (content != null) entries.put(SyncKeys.USER_SETTINGS, content);
        }

        // Global user memory
        String userMemoryPath = getUserMemoryPath();
        if (userMemoryPath != null) {
            String content = tryReadFile(userMemoryPath);
            if (content != null) entries.put(SyncKeys.USER_MEMORY, content);
        }

        // Project-specific files
        if (projectId != null) {
            String localSettingsPath = getLocalSettingsPath();
            if (localSettingsPath != null) {
                String content = tryReadFile(localSettingsPath);
                if (content != null) entries.put(SyncKeys.projectSettings(projectId), content);
            }

            String localMemoryPath = getLocalMemoryPath();
            if (localMemoryPath != null) {
                String content = tryReadFile(localMemoryPath);
                if (content != null) entries.put(SyncKeys.projectMemory(projectId), content);
            }
        }

        return entries;
    }

    private void applyRemoteEntriesToLocal(Map<String, String> entries, String projectId) {
        // Apply global user settings
        String userSettingsContent = entries.get(SyncKeys.USER_SETTINGS);
        if (userSettingsContent != null) {
            String path = getUserSettingsPath();
            if (path != null && !exceedsSizeLimit(userSettingsContent)) {
                writeFile(path, userSettingsContent);
            }
        }

        // Apply global user memory
        String userMemoryContent = entries.get(SyncKeys.USER_MEMORY);
        if (userMemoryContent != null) {
            String path = getUserMemoryPath();
            if (path != null && !exceedsSizeLimit(userMemoryContent)) {
                writeFile(path, userMemoryContent);
            }
        }

        if (projectId != null) {
            String projectSettingsContent = entries.get(SyncKeys.projectSettings(projectId));
            if (projectSettingsContent != null) {
                String path = getLocalSettingsPath();
                if (path != null && !exceedsSizeLimit(projectSettingsContent)) {
                    writeFile(path, projectSettingsContent);
                }
            }

            String projectMemoryContent = entries.get(SyncKeys.projectMemory(projectId));
            if (projectMemoryContent != null) {
                String path = getLocalMemoryPath();
                if (path != null && !exceedsSizeLimit(projectMemoryContent)) {
                    writeFile(path, projectMemoryContent);
                }
            }
        }

        log.debug("[settingsSync] applied {} entries", entries.size());
    }

    private String tryReadFile(String filePath) {
        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) return null;

            long size = Files.size(path);
            if (size > MAX_FILE_SIZE_BYTES) {
                log.debug("[settingsSync] file too large: {}", filePath);
                return null;
            }

            String content = Files.readString(path);
            if (content == null || content.isBlank()) return null;
            return content;
        } catch (IOException e) {
            return null;
        }
    }

    private boolean writeFile(String filePath, String content) {
        try {
            Path path = Path.of(filePath);
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.debug("[settingsSync] wrote {}", filePath);
            return true;
        } catch (IOException e) {
            log.warn("[settingsSync] could not write {}: {}", filePath, e.getMessage());
            return false;
        }
    }

    private boolean exceedsSizeLimit(String content) {
        long sizeBytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        return sizeBytes > MAX_FILE_SIZE_BYTES;
    }

    // -------------------------------------------------------------------------
    // Auth & config helpers
    // -------------------------------------------------------------------------

    private boolean isUsingOAuth() {
        OAuthService.OAuthTokens tokens = oauthService.getCurrentTokens();
        return tokens != null && tokens.getAccessToken() != null;
    }

    private Map<String, String> getAuthHeaders() {
        OAuthService.OAuthTokens tokens = oauthService.getCurrentTokens();
        if (tokens != null && tokens.getAccessToken() != null) {
            return Map.of(
                "Authorization", "Bearer " + tokens.getAccessToken(),
                "anthropic-beta", "oauth-2025-04-20"
            );
        }
        return Map.of();
    }

    private String getSettingsSyncEndpoint() {
        String baseUrl = System.getenv().getOrDefault(
            "ANTHROPIC_BASE_URL", "https://api.anthropic.com");
        return baseUrl + "/api/claude_code/user_settings";
    }

    private String getUserAgent() {
        return "ClaudeCode/Java";
    }

    private boolean isUploadEnabled() {
        try {
            return Boolean.TRUE.equals(
                growthBookService.getFeatureValueCachedMayBeStale(
                    "tengu_enable_settings_sync_push", false));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isDownloadEnabled() {
        try {
            return Boolean.TRUE.equals(
                growthBookService.getFeatureValueCachedMayBeStale("tengu_strap_foyer", false));
        } catch (Exception e) {
            return false;
        }
    }

    private long getRetryDelay(int attempt) {
        return Math.min(1_000L * (1L << (attempt - 1)), 30_000L);
    }

    // -------------------------------------------------------------------------
    // Path helpers (stubs — full impl would use config/git utils)
    // -------------------------------------------------------------------------

    private String getProjectId() {
        // Full implementation: getRepoRemoteHash()
        return null;
    }

    private String getUserSettingsPath() {
        String home = System.getProperty("user.home");
        return home != null ? home + "/.claude/settings.json" : null;
    }

    private String getUserMemoryPath() {
        String home = System.getProperty("user.home");
        return home != null ? home + "/.claude/CLAUDE.md" : null;
    }

    private String getLocalSettingsPath() {
        return ".claude/settings.local.json";
    }

    private String getLocalMemoryPath() {
        return ".claude/CLAUDE.local.md";
    }
}
