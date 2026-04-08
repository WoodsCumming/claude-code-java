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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Remote Managed Settings Service.
 * Translated from src/services/remoteManagedSettings/index.ts
 *
 * Manages fetching, caching, and validation of remote-managed settings
 * for enterprise customers. Uses checksum-based validation to minimise
 * network traffic and provides graceful degradation on failures.
 *
 * Eligibility:
 * - Console users (API key): all eligible
 * - OAuth users (Claude.ai): only Enterprise/C4E and Team subscribers
 * - API fails open — if fetch fails, continues without remote settings
 */
@Slf4j
@Service
public class RemoteManagedSettingsService {



    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final long SETTINGS_TIMEOUT_MS = 10_000L;
    private static final int DEFAULT_MAX_RETRIES = 5;
    private static final long POLLING_INTERVAL_MS = 60L * 60 * 1_000;
    private static final long LOADING_PROMISE_TIMEOUT_MS = 30_000L;

    // -------------------------------------------------------------------------
    // Result type (sealed hierarchy)
    // -------------------------------------------------------------------------

    /**
     * Fetch result.
     * Translated from RemoteManagedSettingsFetchResult in types.ts
     */
    public sealed interface FetchResult permits
        FetchResult.Success,
        FetchResult.Failure {

        record Success(
            Map<String, Object> settings,  // null means 304 / cache still valid
            String checksum
        ) implements FetchResult {}

        record Failure(String error, boolean skipRetry) implements FetchResult {
            public Failure(String error) { this(error, false); }
        }
    }

    // -------------------------------------------------------------------------
    // Collaborators
    // -------------------------------------------------------------------------

    private final OAuthService oauthService;
    private final ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    /** Current session cache. */
    private final AtomicReference<Map<String, Object>> sessionCache = new AtomicReference<>();

    /** Promise-like flag for the initial load. */
    private volatile CompletableFuture<Void> loadingCompletePromise;

    /** Background polling handle. */
    private ScheduledFuture<?> pollingHandle;
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "remote-settings-poller");
            t.setDaemon(true);
            return t;
        });

    @Autowired
    public RemoteManagedSettingsService(OAuthService oauthService, ObjectMapper objectMapper) {
        this.oauthService = oauthService;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    /**
     * Create the loading promise early (from init) so other systems can await it.
     * Translated from initializeRemoteManagedSettingsLoadingPromise() in index.ts
     */
    public synchronized void initializeRemoteManagedSettingsLoadingPromise() {
        if (loadingCompletePromise != null) return;

        if (!isEligibleForRemoteManagedSettings()) return;

        loadingCompletePromise = new CompletableFuture<>();
        // Auto-resolve after timeout to prevent deadlocks
        scheduler.schedule(() -> {
            if (loadingCompletePromise != null && !loadingCompletePromise.isDone()) {
                log.debug("[remoteManagedSettings] loading promise timed out, resolving anyway");
                loadingCompletePromise.complete(null);
            }
        }, LOADING_PROMISE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Load remote settings during CLI initialization.
     * Fails open — if fetch fails, continues without remote settings.
     * Also starts background polling.
     * Translated from loadRemoteManagedSettings() in index.ts
     */
    public CompletableFuture<Void> loadRemoteManagedSettings() {
        if (isEligibleForRemoteManagedSettings() && loadingCompletePromise == null) {
            loadingCompletePromise = new CompletableFuture<>();
        }

        // Cache-first: apply cached settings immediately and unblock waiters
        Map<String, Object> cached = sessionCache.get();
        if (cached == null) {
            cached = loadCachedSettingsFromDisk();
            if (cached != null) {
                sessionCache.set(cached);
            }
        }
        if (cached != null && loadingCompletePromise != null && !loadingCompletePromise.isDone()) {
            loadingCompletePromise.complete(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                fetchAndLoadRemoteManagedSettings();

                if (isEligibleForRemoteManagedSettings()) {
                    startBackgroundPolling();
                }
            } finally {
                if (loadingCompletePromise != null && !loadingCompletePromise.isDone()) {
                    loadingCompletePromise.complete(null);
                }
            }
        });
    }

    /**
     * Wait for the initial remote settings loading to complete.
     * Translated from waitForRemoteManagedSettingsToLoad() in index.ts
     */
    public void waitForRemoteManagedSettingsToLoad() {
        CompletableFuture<Void> promise = loadingCompletePromise;
        if (promise != null) {
            try {
                promise.get(LOADING_PROMISE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.debug("[remoteManagedSettings] wait interrupted: {}", e.getMessage());
            }
        }
    }

    /**
     * Refresh after an auth state change (login/logout).
     * Translated from refreshRemoteManagedSettings() in index.ts
     */
    public CompletableFuture<Void> refreshRemoteManagedSettings() {
        return CompletableFuture.runAsync(() -> {
            clearRemoteManagedSettingsCache();

            if (!isEligibleForRemoteManagedSettings()) {
                return;
            }

            fetchAndLoadRemoteManagedSettings();
            log.debug("[remoteManagedSettings] refreshed after auth change");
        });
    }

    /**
     * Clear all caches (session, persistent) and stop polling.
     * Translated from clearRemoteManagedSettingsCache() in index.ts
     */
    public void clearRemoteManagedSettingsCache() {
        stopBackgroundPolling();
        sessionCache.set(null);
        loadingCompletePromise = null;

        Path settingsPath = getSettingsPath();
        if (settingsPath != null) {
            try {
                Files.deleteIfExists(settingsPath);
            } catch (IOException e) {
                log.debug("[remoteManagedSettings] could not delete cached file: {}", e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Eligibility & public accessors
    // -------------------------------------------------------------------------

    /**
     * Check if the current user is eligible for remote managed settings.
     * Translated from isEligibleForRemoteManagedSettings() in index.ts
     */
    public boolean isEligibleForRemoteManagedSettings() {
        return oauthService.isAuthenticated();
    }

    /**
     * Return the currently cached settings, or null if none.
     */
    public Optional<Map<String, Object>> getCachedSettings() {
        Map<String, Object> cached = sessionCache.get();
        return Optional.ofNullable(cached);
    }

    // -------------------------------------------------------------------------
    // Checksum
    // -------------------------------------------------------------------------

    /**
     * Compute SHA-256 checksum from settings JSON.
     * Must match the server's Python: json.dumps(settings, sort_keys=True, separators=(",",":"))
     * Translated from computeChecksumFromSettings() in index.ts
     */
    public String computeChecksumFromSettings(Map<String, Object> settings) {
        try {
            // Deep-sort keys to match Python's sort_keys=True
            Object sorted = sortKeysDeep(settings);
            // Compact JSON (no spaces) to match Python's separators=(",",":")
            String json = objectMapper.writeValueAsString(sorted);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "sha256:" + bytesToHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute checksum", e);
        }
    }

    // -------------------------------------------------------------------------
    // Background polling
    // -------------------------------------------------------------------------

    /**
     * Start hourly background polling.
     * Translated from startBackgroundPolling() in index.ts
     */
    public synchronized void startBackgroundPolling() {
        if (pollingHandle != null) return;
        if (!isEligibleForRemoteManagedSettings()) return;

        pollingHandle = scheduler.scheduleAtFixedRate(
            this::pollRemoteSettings,
            POLLING_INTERVAL_MS,
            POLLING_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
        log.debug("[remoteManagedSettings] background polling started");
    }

    /**
     * Stop background polling.
     * Translated from stopBackgroundPolling() in index.ts
     */
    public synchronized void stopBackgroundPolling() {
        if (pollingHandle != null) {
            pollingHandle.cancel(false);
            pollingHandle = null;
            log.debug("[remoteManagedSettings] background polling stopped");
        }
    }

    // -------------------------------------------------------------------------
    // Internal fetch / retry logic
    // -------------------------------------------------------------------------

    /** Background poll: fetch and hot-reload if changed. */
    private void pollRemoteSettings() {
        if (!isEligibleForRemoteManagedSettings()) return;

        String previousJson = jsonSerialize(sessionCache.get());
        try {
            fetchAndLoadRemoteManagedSettings();
            String newJson = jsonSerialize(sessionCache.get());
            if (!Objects.equals(newJson, previousJson)) {
                log.debug("[remoteManagedSettings] changed during background poll");
                // In a full implementation: notify settingsChangeDetector
            }
        } catch (Exception e) {
            // Don't fail closed for background polling
            log.debug("[remoteManagedSettings] poll failed: {}", e.getMessage());
        }
    }

    /**
     * Full fetch-and-load flow with cache-first fallback.
     * Translated from fetchAndLoadRemoteManagedSettings() in index.ts
     */
    private void fetchAndLoadRemoteManagedSettings() {
        if (!isEligibleForRemoteManagedSettings()) return;

        Map<String, Object> cachedSettings = sessionCache.get();
        if (cachedSettings == null) {
            cachedSettings = loadCachedSettingsFromDisk();
        }

        String cachedChecksum = cachedSettings != null
            ? computeChecksumFromSettings(cachedSettings) : null;

        FetchResult result = fetchWithRetry(cachedChecksum);

        if (result instanceof FetchResult.Failure failure) {
            if (cachedSettings != null) {
                log.debug("[remoteManagedSettings] using stale cache after fetch failure");
                sessionCache.set(cachedSettings);
            }
            return;
        }

        FetchResult.Success success = (FetchResult.Success) result;

        // 304 — cached settings still valid
        if (success.settings() == null && cachedSettings != null) {
            log.debug("[remoteManagedSettings] cache still valid (304)");
            sessionCache.set(cachedSettings);
            return;
        }

        Map<String, Object> newSettings = success.settings() != null ? success.settings() : Map.of();
        boolean hasContent = !newSettings.isEmpty();

        if (hasContent) {
            sessionCache.set(newSettings);
            saveSettings(newSettings);
            log.debug("[remoteManagedSettings] applied new settings");
        } else {
            // Empty / 404 — delete cached file
            sessionCache.set(newSettings);
            Path settingsPath = getSettingsPath();
            if (settingsPath != null) {
                try {
                    Files.deleteIfExists(settingsPath);
                    log.debug("[remoteManagedSettings] deleted cached file (404 response)");
                } catch (IOException e) {
                    log.debug("[remoteManagedSettings] could not delete cached file: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Fetch with exponential-backoff retry.
     * Translated from fetchWithRetry() in index.ts
     */
    private FetchResult fetchWithRetry(String cachedChecksum) {
        FetchResult lastResult = null;
        for (int attempt = 1; attempt <= DEFAULT_MAX_RETRIES + 1; attempt++) {
            lastResult = fetchRemoteManagedSettings(cachedChecksum);

            if (lastResult instanceof FetchResult.Success) return lastResult;

            FetchResult.Failure failure = (FetchResult.Failure) lastResult;
            if (failure.skipRetry()) return lastResult;
            if (attempt > DEFAULT_MAX_RETRIES) return lastResult;

            long delayMs = getRetryDelay(attempt);
            log.debug("[remoteManagedSettings] retry {}/{} after {}ms",
                      attempt, DEFAULT_MAX_RETRIES, delayMs);
            try { Thread.sleep(delayMs); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new FetchResult.Failure("Interrupted");
            }
        }
        return lastResult != null ? lastResult : new FetchResult.Failure("No result");
    }

    /**
     * Single fetch attempt (no retries).
     * Translated from fetchRemoteManagedSettings() in index.ts
     */
    @SuppressWarnings("unchecked")
    private FetchResult fetchRemoteManagedSettings(String cachedChecksum) {
        try {
            Map<String, String> authHeaders = getAuthHeaders();
            if (authHeaders.isEmpty()) {
                return new FetchResult.Failure("Authentication required for remote settings", true);
            }

            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(SETTINGS_TIMEOUT_MS))
                .build();

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(getSettingsEndpoint()))
                .timeout(Duration.ofMillis(SETTINGS_TIMEOUT_MS))
                .GET();

            authHeaders.forEach(builder::header);
            builder.header("User-Agent", getUserAgent());

            if (cachedChecksum != null) {
                builder.header("If-None-Match", "\"" + cachedChecksum + "\"");
            }

            HttpResponse<String> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());

            return switch (response.statusCode()) {
                case 304 -> {
                    log.debug("[remoteManagedSettings] 304 — using cached settings");
                    yield new FetchResult.Success(null, cachedChecksum);
                }
                case 204, 404 -> {
                    log.debug("[remoteManagedSettings] {} — no settings found", response.statusCode());
                    yield new FetchResult.Success(Map.of(), null);
                }
                case 200 -> {
                    Map<String, Object> data = objectMapper.readValue(
                        response.body(), new TypeReference<>() {});
                    Object settingsObj = data.get("settings");
                    Map<String, Object> settings = settingsObj instanceof Map<?, ?>
                        ? (Map<String, Object>) settingsObj : Map.of();
                    String checksum = (String) data.get("checksum");
                    log.debug("[remoteManagedSettings] fetched successfully");
                    yield new FetchResult.Success(settings, checksum);
                }
                default -> new FetchResult.Failure("Unexpected status: " + response.statusCode());
            };

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new FetchResult.Failure(e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Persistence helpers
    // -------------------------------------------------------------------------

    private void saveSettings(Map<String, Object> settings) {
        Path path = getSettingsPath();
        if (path == null) return;
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(settings);
            Files.writeString(path, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.debug("[remoteManagedSettings] saved to {}", path);
        } catch (IOException e) {
            log.debug("[remoteManagedSettings] failed to save: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadCachedSettingsFromDisk() {
        Path path = getSettingsPath();
        if (path == null || !Files.exists(path)) return null;
        try {
            String json = Files.readString(path);
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (IOException e) {
            log.debug("[remoteManagedSettings] could not load cached settings: {}", e.getMessage());
            return null;
        }
    }

    private Path getSettingsPath() {
        String home = System.getProperty("user.home");
        if (home == null) return null;
        return Path.of(home, ".claude", "remote-managed-settings.json");
    }

    // -------------------------------------------------------------------------
    // Auth helpers
    // -------------------------------------------------------------------------

    private Map<String, String> getAuthHeaders() {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            return Map.of("x-api-key", apiKey);
        }

        OAuthService.OAuthTokens tokens = oauthService.getCurrentTokens();
        if (tokens != null && tokens.getAccessToken() != null) {
            return Map.of(
                "Authorization", "Bearer " + tokens.getAccessToken(),
                "anthropic-beta", "oauth-2025-04-20"
            );
        }

        return Map.of();
    }

    private String getSettingsEndpoint() {
        String baseUrl = System.getenv().getOrDefault(
            "ANTHROPIC_BASE_URL", "https://api.anthropic.com");
        return baseUrl + "/api/claude_code/settings";
    }

    private String getUserAgent() {
        return "ClaudeCode/Java";
    }

    // -------------------------------------------------------------------------
    // Utility helpers
    // -------------------------------------------------------------------------

    private long getRetryDelay(int attempt) {
        // Exponential backoff: 1s, 2s, 4s, 8s, …
        return Math.min(1_000L * (1L << (attempt - 1)), 30_000L);
    }

    @SuppressWarnings("unchecked")
    private Object sortKeysDeep(Object obj) {
        if (obj instanceof List<?> list) {
            return list.stream().map(this::sortKeysDeep).toList();
        }
        if (obj instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), sortKeysDeep(entry.getValue()));
            }
            return sorted;
        }
        return obj;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String jsonSerialize(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return null;
        }
    }

    /** Alias for initializeRemoteManagedSettingsLoadingPromise. */
    public void initialize() {
        initializeRemoteManagedSettingsLoadingPromise();
    }
}
