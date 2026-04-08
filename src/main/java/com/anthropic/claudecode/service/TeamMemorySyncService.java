package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Team Memory Sync Service.
 * Translated from src/services/teamMemorySync/index.ts
 *
 * Syncs team memory files between the local filesystem and the server API.
 * Team memory is scoped per-repo (identified by git remote hash) and shared
 * across all authenticated org members.
 *
 * API contract:
 *   GET  /api/claude_code/team_memory?repo={owner/repo}            → TeamMemoryData
 *   GET  /api/claude_code/team_memory?repo={owner/repo}&view=hashes → metadata + checksums only
 *   PUT  /api/claude_code/team_memory?repo={owner/repo}            → upload entries (upsert semantics)
 *   404 = no data exists yet
 *
 * Sync semantics:
 *   - Pull overwrites local files with server content (server wins per-key).
 *   - Push uploads only keys whose content hash differs from serverChecksums
 *     (delta upload). Server uses upsert: keys not in the PUT are preserved.
 *   - File deletions do NOT propagate.
 */
@Slf4j
@Service
public class TeamMemorySyncService {

    /** Per-entry size cap — server default. Pre-filtering oversized entries saves bandwidth. */
    private static final int MAX_FILE_SIZE_BYTES = 250_000;
    /** Gateway body-size cap. Batches larger than this are split into sequential PUTs. */
    private static final int MAX_PUT_BODY_BYTES = 200_000;
    private static final int MAX_RETRIES = 3;
    private static final int MAX_CONFLICT_RETRIES = 2;
    private static final long SYNC_TIMEOUT_MS = 30_000;

    private final OAuthService oauthService;
    private final SecretScannerService secretScannerService;
    private final TeamMemPathsService teamMemPathsService;
    private final RestTemplate restTemplate;

    @Autowired
    public TeamMemorySyncService(
            OAuthService oauthService,
            SecretScannerService secretScannerService,
            TeamMemPathsService teamMemPathsService,
            RestTemplate restTemplate) {
        this.oauthService = oauthService;
        this.secretScannerService = secretScannerService;
        this.teamMemPathsService = teamMemPathsService;
        this.restTemplate = restTemplate;
    }

    // ─── Sync state ──────────────────────────────────────────────

    /**
     * Mutable state for the team memory sync service.
     * Created once per session by the watcher and passed to all sync functions.
     * Translated from SyncState type in index.ts
     */
    public static class SyncState {
        private String lastKnownChecksum;
        private final Map<String, String> serverChecksums = new HashMap<>();
        private Integer serverMaxEntries;

        public SyncState() {}
        public String getLastKnownChecksum() { return lastKnownChecksum; }
        public void setLastKnownChecksum(String v) { lastKnownChecksum = v; }
        public Map<String, String> getServerChecksums() { return serverChecksums; }
        public Integer getServerMaxEntries() { return serverMaxEntries; }
        public void setServerMaxEntries(Integer v) { serverMaxEntries = v; }
    }

    /**
     * Create a new SyncState instance.
     * Translated from createSyncState() in index.ts
     */
    public SyncState createSyncState() {
        return new SyncState();
    }

    /**
     * Compute sha256:hex over the UTF-8 bytes of the given content.
     * Format matches the server's entryChecksums values so local-vs-server
     * comparison works by direct string equality.
     * Translated from hashContent() in index.ts
     */
    public String hashContent(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hashBytes) {
                hex.append(String.format("%02x", b));
            }
            return "sha256:" + hex;
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ─── Auth & eligibility ───────────────────────────────────────

    /**
     * Check if user is authenticated with first-party OAuth (required for team memory sync).
     * Translated from isUsingOAuth() in index.ts
     */
    public boolean isTeamMemorySyncAvailable() {
        return oauthService.isAuthenticated();
    }

    // ─── Batch splitting ─────────────────────────────────────────

    /**
     * Split a delta into PUT-sized batches under MAX_PUT_BODY_BYTES each.
     * Greedy bin-packing over sorted keys — sorting gives deterministic batches.
     * A single entry exceeding MAX_PUT_BODY_BYTES goes into its own solo batch.
     * Translated from batchDeltaByBytes() in index.ts
     */
    public List<Map<String, String>> batchDeltaByBytes(Map<String, String> delta) {
        List<String> keys = new ArrayList<>(delta.keySet());
        Collections.sort(keys);
        if (keys.isEmpty()) return Collections.emptyList();

        // Fixed overhead for {"entries":{}}
        final int emptyBodyBytes = "{\"entries\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

        List<Map<String, String>> batches = new ArrayList<>();
        Map<String, String> current = new LinkedHashMap<>();
        int currentBytes = emptyBodyBytes;

        for (String key : keys) {
            String value = delta.get(key);
            int added = estimateEntryBytes(key, value);
            if (currentBytes + added > MAX_PUT_BODY_BYTES && !current.isEmpty()) {
                batches.add(current);
                current = new LinkedHashMap<>();
                currentBytes = emptyBodyBytes;
            }
            current.put(key, value);
            currentBytes += added;
        }
        batches.add(current);
        return batches;
    }

    private int estimateEntryBytes(String key, String value) {
        // key + colon + value + comma (colon + comma over-counts by 1 on last entry; harmless slack)
        return jsonStringLength(key) + jsonStringLength(value) + 2;
    }

    private int jsonStringLength(String s) {
        // Approximation: JSON-encoded string = 2 quotes + escaped content
        return (int) (s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length * 1.1) + 2;
    }

    // ─── Public API ──────────────────────────────────────────────

    /**
     * Pull team memory from the server and write to local directory.
     * Returns a result with the number of files written.
     * Translated from pullTeamMemory() in index.ts
     */
    public CompletableFuture<PullResult> pullTeamMemory(SyncState state, boolean skipEtagCache) {
        return CompletableFuture.supplyAsync(() -> {
            if (!isTeamMemorySyncAvailable()) {
                return PullResult.failure(0, 0, "OAuth not available");
            }

            String repoSlug = getGithubRepo();
            if (repoSlug == null) {
                return PullResult.failure(0, 0, "No git remote found");
            }

            try {
                String etag = skipEtagCache ? null : state.getLastKnownChecksum();
                TeamMemoryFetchResult fetchResult = fetchTeamMemoryWithRetry(state, repoSlug, etag);

                if (!fetchResult.isSuccess()) {
                    return PullResult.failure(0, 0, fetchResult.getError());
                }
                if (fetchResult.isNotModified()) {
                    return PullResult.notModified();
                }
                if (fetchResult.isEmpty() || fetchResult.getEntries() == null) {
                    state.getServerChecksums().clear();
                    return PullResult.success(0, 0);
                }

                Map<String, String> entries = fetchResult.getEntries();
                Map<String, String> responseChecksums = fetchResult.getEntryChecksums();

                state.getServerChecksums().clear();
                if (responseChecksums != null) {
                    state.getServerChecksums().putAll(responseChecksums);
                }

                int filesWritten = writeRemoteEntriesToLocal(entries);
                log.info("team-memory-sync: pulled {} files", filesWritten);
                return PullResult.success(filesWritten, entries.size());

            } catch (Exception e) {
                log.warn("team-memory-sync: pull failed: {}", e.getMessage());
                return PullResult.failure(0, 0, e.getMessage());
            }
        });
    }

    /**
     * Push local team memory files to the server with optimistic locking.
     * Translated from pushTeamMemory() in index.ts
     */
    public CompletableFuture<PushResult> pushTeamMemory(SyncState state) {
        return CompletableFuture.supplyAsync(() -> {
            if (!isTeamMemorySyncAvailable()) {
                return PushResult.failure(0, "OAuth not available", "no_oauth");
            }

            String repoSlug = getGithubRepo();
            if (repoSlug == null) {
                return PushResult.failure(0, "No git remote found", "no_repo");
            }

            try {
                LocalReadResult localRead = readLocalTeamMemory(state.getServerMaxEntries());
                Map<String, String> entries = localRead.getEntries();
                List<SkippedSecretFile> skippedSecrets = localRead.getSkippedSecrets();

                if (!skippedSecrets.isEmpty()) {
                    String summary = skippedSecrets.stream()
                        .map(s -> "\"" + s.getPath() + "\" (" + s.getLabel() + ")")
                        .collect(Collectors.joining(", "));
                    log.warn("team-memory-sync: {} file(s) skipped due to detected secrets: {}. " +
                        "Remove the secret(s) to enable sync for these files.", skippedSecrets.size(), summary);
                }

                // Hash each local entry once
                Map<String, String> localHashes = new HashMap<>();
                for (Map.Entry<String, String> e : entries.entrySet()) {
                    localHashes.put(e.getKey(), hashContent(e.getValue()));
                }

                boolean sawConflict = false;
                for (int conflictAttempt = 0; conflictAttempt <= MAX_CONFLICT_RETRIES; conflictAttempt++) {
                    // Compute delta: only upload keys whose content hash differs from server
                    Map<String, String> delta = new LinkedHashMap<>();
                    for (Map.Entry<String, String> e : localHashes.entrySet()) {
                        String serverHash = state.getServerChecksums().get(e.getKey());
                        if (!e.getValue().equals(serverHash)) {
                            delta.put(e.getKey(), entries.get(e.getKey()));
                        }
                    }

                    if (delta.isEmpty()) {
                        return PushResult.success(0, null, skippedSecrets);
                    }

                    List<Map<String, String>> batches = batchDeltaByBytes(delta);
                    int filesUploaded = 0;
                    UploadResult lastResult = null;

                    for (Map<String, String> batch : batches) {
                        lastResult = uploadTeamMemory(state, repoSlug, batch, state.getLastKnownChecksum());
                        if (!lastResult.isSuccess()) break;

                        for (String key : batch.keySet()) {
                            state.getServerChecksums().put(key, localHashes.get(key));
                        }
                        filesUploaded += batch.size();
                    }

                    if (lastResult != null && lastResult.isSuccess()) {
                        log.info("team-memory-sync: pushed {} of {} files (delta)", filesUploaded, localHashes.size());
                        return PushResult.success(filesUploaded, lastResult.getChecksum(), skippedSecrets);
                    }

                    if (lastResult == null || !lastResult.isConflict()) {
                        if (lastResult != null && lastResult.getServerMaxEntries() != null) {
                            state.setServerMaxEntries(lastResult.getServerMaxEntries());
                        }
                        String err = lastResult != null ? lastResult.getError() : "Upload failed";
                        return PushResult.failure(filesUploaded, err, "upload_error");
                    }

                    // 412 conflict — refresh serverChecksums and retry
                    sawConflict = true;
                    if (conflictAttempt >= MAX_CONFLICT_RETRIES) {
                        log.warn("team-memory-sync: giving up after {} conflict retries", MAX_CONFLICT_RETRIES);
                        return PushResult.failure(0, "Conflict after max retries", "conflict");
                    }

                    log.info("team-memory-sync: 412 conflict, refreshing checksums (attempt {}/{})",
                        conflictAttempt + 1, MAX_CONFLICT_RETRIES);
                    refreshServerChecksums(state, repoSlug);
                }

                return PushResult.failure(0, "Push exhausted retries", "unknown");
            } catch (Exception e) {
                log.warn("team-memory-sync: push failed: {}", e.getMessage());
                return PushResult.failure(0, e.getMessage(), "unknown");
            }
        });
    }

    // ─── Internal helpers ────────────────────────────────────────

    private TeamMemoryFetchResult fetchTeamMemoryWithRetry(SyncState state, String repoSlug, String etag) {
        TeamMemoryFetchResult lastResult = null;
        for (int attempt = 1; attempt <= MAX_RETRIES + 1; attempt++) {
            lastResult = fetchTeamMemoryOnce(state, repoSlug, etag);
            if (lastResult.isSuccess() || lastResult.isSkipRetry()) return lastResult;
            if (attempt > MAX_RETRIES) return lastResult;
            log.debug("team-memory-sync: retry {}/{}", attempt, MAX_RETRIES);
            sleep(getRetryDelayMs(attempt));
        }
        return lastResult;
    }

    @SuppressWarnings("unchecked")
    private TeamMemoryFetchResult fetchTeamMemoryOnce(SyncState state, String repoSlug, String etag) {
        try {
            OAuthService.OAuthTokens tokens = oauthService.getCurrentTokens();
            if (tokens == null) {
                return TeamMemoryFetchResult.authFailure("No OAuth token available for team memory sync");
            }

            String endpoint = getTeamMemorySyncEndpoint(repoSlug);
            log.debug("team-memory-sync: fetching {}", endpoint);

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("Authorization", "Bearer " + tokens.getAccessToken());
            if (etag != null) {
                headers.set("If-None-Match", etag);
            }

            org.springframework.http.HttpEntity<Void> requestEntity = new org.springframework.http.HttpEntity<>(headers);

            try {
                org.springframework.http.ResponseEntity<Map> response = restTemplate.exchange(
                        endpoint,
                        org.springframework.http.HttpMethod.GET,
                        requestEntity,
                        Map.class
                );

                if (response.getStatusCode() == org.springframework.http.HttpStatus.NOT_MODIFIED) {
                    return new TeamMemoryFetchResult(true, true, false, false,
                            etag, null, null, null);
                }

                if (response.getStatusCode() == org.springframework.http.HttpStatus.NOT_FOUND) {
                    return TeamMemoryFetchResult.empty();
                }

                Map<String, Object> body = response.getBody();
                if (body == null) return TeamMemoryFetchResult.empty();

                // Extract checksum from response header
                String serverChecksum = response.getHeaders().getFirst("ETag");
                if (serverChecksum != null) state.setLastKnownChecksum(serverChecksum);

                Object entriesObj = body.get("entries");
                Map<String, String> entries = new LinkedHashMap<>();
                if (entriesObj instanceof Map<?, ?> entriesMap) {
                    for (Map.Entry<?, ?> e : entriesMap.entrySet()) {
                        if (e.getValue() instanceof String s) {
                            entries.put(e.getKey().toString(), s);
                        }
                    }
                }

                log.debug("team-memory-sync: fetch complete ({} entries)", entries.size());
                return new TeamMemoryFetchResult(true, false, entries.isEmpty(), false,
                        serverChecksum, entries, null, null);

            } catch (org.springframework.web.client.HttpClientErrorException.Unauthorized e) {
                return TeamMemoryFetchResult.authFailure("Unauthorized: " + e.getMessage());
            } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
                return TeamMemoryFetchResult.empty();
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                    return TeamMemoryFetchResult.authFailure(e.getMessage());
                }
                return TeamMemoryFetchResult.networkFailure("HTTP " + e.getStatusCode() + ": " + e.getMessage());
            }
        } catch (Exception e) {
            return TeamMemoryFetchResult.networkFailure(e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private UploadResult uploadTeamMemory(SyncState state, String repoSlug,
                                           Map<String, String> entries, String ifMatchChecksum) {
        try {
            OAuthService.OAuthTokens tokens = oauthService.getCurrentTokens();
            if (tokens == null) {
                return UploadResult.authFailure("No OAuth token available");
            }

            log.debug("team-memory-sync: uploading {} entries to {}", entries.size(), repoSlug);
            String endpoint = getTeamMemorySyncEndpoint(repoSlug);

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("Authorization", "Bearer " + tokens.getAccessToken());
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            if (ifMatchChecksum != null) {
                headers.set("If-Match", ifMatchChecksum);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("entries", entries);

            org.springframework.http.HttpEntity<Map<String, Object>> requestEntity =
                    new org.springframework.http.HttpEntity<>(body, headers);

            try {
                org.springframework.http.ResponseEntity<Map> response = restTemplate.exchange(
                        endpoint,
                        org.springframework.http.HttpMethod.PUT,
                        requestEntity,
                        Map.class
                );

                String newChecksum = response.getHeaders().getFirst("ETag");
                if (newChecksum != null) state.setLastKnownChecksum(newChecksum);

                log.debug("team-memory-sync: upload complete (checksum={})", newChecksum);
                return UploadResult.success(newChecksum);

            } catch (org.springframework.web.client.HttpClientErrorException e) {
                if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                    return UploadResult.authFailure(e.getMessage());
                }
                if (e.getStatusCode().value() == 412) {
                    return UploadResult.conflict(); // Precondition Failed = ETag mismatch
                }
                return UploadResult.failure("HTTP " + e.getStatusCode() + ": " + e.getMessage());
            }
        } catch (Exception e) {
            return UploadResult.failure(e.getMessage());
        }
    }

    private void refreshServerChecksums(SyncState state, String repoSlug) {
        try {
            OAuthService.OAuthTokens tokens = oauthService.getCurrentTokens();
            if (tokens == null) return;

            // Real implementation: GET ?view=hashes and update state.serverChecksums
            log.debug("team-memory-sync: refreshing server checksums for {}", repoSlug);
        } catch (Exception e) {
            log.debug("team-memory-sync: failed to refresh checksums: {}", e.getMessage());
        }
    }

    private LocalReadResult readLocalTeamMemory(Integer maxEntries) {
        Map<String, String> entries = new LinkedHashMap<>();
        List<SkippedSecretFile> skippedSecrets = new ArrayList<>();

        Path teamDir = teamMemPathsService.getTeamMemPath();
        if (teamDir == null || !Files.exists(teamDir)) {
            return new LocalReadResult(entries, skippedSecrets);
        }

        try {
            Files.walkFileTree(teamDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        if (attrs.size() > MAX_FILE_SIZE_BYTES) {
                            log.info("team-memory-sync: skipping oversized file {} ({} > {} bytes)",
                                file.getFileName(), attrs.size(), MAX_FILE_SIZE_BYTES);
                            return FileVisitResult.CONTINUE;
                        }
                        String content = Files.readString(file);
                        String relPath = teamDir.relativize(file).toString().replace('\\', '/');

                        List<SecretScannerService.SecretMatch> secretMatches =
                            secretScannerService.scanForSecrets(content);
                        if (!secretMatches.isEmpty()) {
                            SecretScannerService.SecretMatch first = secretMatches.get(0);
                            skippedSecrets.add(new SkippedSecretFile(relPath, first.getRuleId(), first.getLabel()));
                            log.warn("team-memory-sync: skipping \"{}\" — detected {}", relPath, first.getLabel());
                            return FileVisitResult.CONTINUE;
                        }

                        entries.put(relPath, content);
                    } catch (IOException e) {
                        log.debug("team-memory-sync: skipping unreadable file {}: {}", file, e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("team-memory-sync: error reading team memory directory: {}", e.getMessage());
        }

        // Deterministic truncation to server-learned cap
        if (maxEntries != null && entries.size() > maxEntries) {
            List<String> sortedKeys = new ArrayList<>(entries.keySet());
            Collections.sort(sortedKeys);
            List<String> dropped = sortedKeys.subList(maxEntries, sortedKeys.size());
            log.warn("team-memory-sync: {} local entries exceeds server cap of {}; {} file(s) will NOT sync: {}. " +
                "Consider consolidating or removing some team memory files.",
                sortedKeys.size(), maxEntries, dropped.size(), dropped);
            Map<String, String> truncated = new LinkedHashMap<>();
            for (String key : sortedKeys.subList(0, maxEntries)) {
                truncated.put(key, entries.get(key));
            }
            return new LocalReadResult(truncated, skippedSecrets);
        }

        return new LocalReadResult(entries, skippedSecrets);
    }

    private int writeRemoteEntriesToLocal(Map<String, String> entries) {
        int written = 0;
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            try {
                Path dest = teamMemPathsService.validateTeamMemKey(entry.getKey());
                if (dest == null) continue;

                String content = entry.getValue();
                long sizeBytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if (sizeBytes > MAX_FILE_SIZE_BYTES) {
                    log.info("team-memory-sync: skipping oversized remote entry \"{}\"", entry.getKey());
                    continue;
                }

                // Skip if on-disk content already matches
                if (Files.exists(dest)) {
                    String existing = Files.readString(dest);
                    if (existing.equals(content)) continue;
                }

                Files.createDirectories(dest.getParent());
                Files.writeString(dest, content);
                written++;
            } catch (Exception e) {
                log.warn("team-memory-sync: failed to write \"{}\": {}", entry.getKey(), e.getMessage());
            }
        }
        return written;
    }

    private String getGithubRepo() {
        // Real implementation would run git remote get-url origin and parse it
        return System.getenv("GITHUB_REPO");
    }

    private String getTeamMemorySyncEndpoint(String repoSlug) {
        String baseUrl = System.getenv("TEAM_MEMORY_SYNC_URL");
        if (baseUrl == null) baseUrl = "https://api.anthropic.com";
        return baseUrl + "/api/claude_code/team_memory?repo=" +
            java.net.URLEncoder.encode(repoSlug, java.nio.charset.StandardCharsets.UTF_8);
    }

    private long getRetryDelayMs(int attempt) {
        return (long) Math.min(1000 * Math.pow(2, attempt - 1), 30_000);
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ─── Result types ────────────────────────────────────────────

    public static class PullResult {
        private final boolean success;
        private final int filesWritten;
        private final int entryCount;
        private final boolean notModified;
        private final String error;

        public PullResult() { this.success = false; this.filesWritten = 0; this.entryCount = 0; this.notModified = false; this.error = null; }
        public PullResult(boolean success, int filesWritten, int entryCount, boolean notModified, String error) {
            this.success = success; this.filesWritten = filesWritten; this.entryCount = entryCount;
            this.notModified = notModified; this.error = error;
        }
        public boolean isSuccess() { return success; }
        public int getFilesWritten() { return filesWritten; }
        public int getEntryCount() { return entryCount; }
        public boolean isNotModified() { return notModified; }
        public String getError() { return error; }

        public static PullResult success(int filesWritten, int entryCount) {
            return new PullResult(true, filesWritten, entryCount, false, null);
        }
        public static PullResult notModified() {
            return new PullResult(true, 0, 0, true, null);
        }
        public static PullResult failure(int filesWritten, int entryCount, String error) {
            return new PullResult(false, filesWritten, entryCount, false, error);
        }
    }

    public static class PushResult {
        private final boolean success;
        private final int filesUploaded;
        private final String checksum;
        private final String error;
        private final String errorType;
        private final List<SkippedSecretFile> skippedSecrets;

        public PushResult() { this.success = false; this.filesUploaded = 0; this.checksum = null; this.error = null; this.errorType = null; this.skippedSecrets = null; }
        public PushResult(boolean success, int filesUploaded, String checksum, String error, String errorType, List<SkippedSecretFile> skippedSecrets) {
            this.success = success; this.filesUploaded = filesUploaded; this.checksum = checksum;
            this.error = error; this.errorType = errorType; this.skippedSecrets = skippedSecrets;
        }
        public int getFilesUploaded() { return filesUploaded; }
        public String getChecksum() { return checksum; }
        public String getErrorType() { return errorType; }
        public List<SkippedSecretFile> getSkippedSecrets() { return skippedSecrets; }

        public static PushResult success(int filesUploaded, String checksum, List<SkippedSecretFile> skipped) {
            return new PushResult(true, filesUploaded, checksum, null, null, skipped);
        }
        public static PushResult failure(int filesUploaded, String error, String errorType) {
            return new PushResult(false, filesUploaded, null, error, errorType, null);
        }
    }

    private static class TeamMemoryFetchResult {
        private final boolean success;
        private final boolean notModified;
        private final boolean isEmpty;
        private final boolean skipRetry;
        private final String checksum;
        private final Map<String, String> entries;
        private final Map<String, String> entryChecksums;
        private final String error;

        TeamMemoryFetchResult(boolean success, boolean notModified, boolean isEmpty, boolean skipRetry,
                              String checksum, Map<String, String> entries, Map<String, String> entryChecksums, String error) {
            this.success = success; this.notModified = notModified; this.isEmpty = isEmpty;
            this.skipRetry = skipRetry; this.checksum = checksum; this.entries = entries;
            this.entryChecksums = entryChecksums; this.error = error;
        }
        boolean isSuccess() { return success; }
        boolean isNotModified() { return notModified; }
        boolean isEmpty() { return isEmpty; }
        boolean isSkipRetry() { return skipRetry; }
        String getChecksum() { return checksum; }
        Map<String, String> getEntries() { return entries; }
        Map<String, String> getEntryChecksums() { return entryChecksums; }
        String getError() { return error; }

        static TeamMemoryFetchResult authFailure(String error) {
            return new TeamMemoryFetchResult(false, false, false, true, null, null, null, error);
        }
        static TeamMemoryFetchResult networkFailure(String error) {
            return new TeamMemoryFetchResult(false, false, false, false, null, null, null, error);
        }
        static TeamMemoryFetchResult empty() {
            return new TeamMemoryFetchResult(true, false, true, false, null, null, null, null);
        }
    }

    private static class UploadResult {
        private final boolean success;
        private final boolean conflict;
        private final String checksum;
        private final String error;
        private final Integer serverMaxEntries;

        UploadResult(boolean success, boolean conflict, String checksum, String error, Integer serverMaxEntries) {
            this.success = success; this.conflict = conflict; this.checksum = checksum;
            this.error = error; this.serverMaxEntries = serverMaxEntries;
        }
        boolean isSuccess() { return success; }
        boolean isConflict() { return conflict; }
        String getChecksum() { return checksum; }
        String getError() { return error; }
        Integer getServerMaxEntries() { return serverMaxEntries; }

        static UploadResult success(String checksum) {
            return new UploadResult(true, false, checksum, null, null);
        }
        static UploadResult authFailure(String error) {
            return new UploadResult(false, false, null, error, null);
        }
        static UploadResult failure(String error) {
            return new UploadResult(false, false, null, error, null);
        }
        static UploadResult conflict() {
            return new UploadResult(false, true, null, "ETag mismatch", null);
        }
    }

    private static class LocalReadResult {
        private final Map<String, String> entries;
        private final List<SkippedSecretFile> skippedSecrets;
        public LocalReadResult(Map<String, String> entries, List<SkippedSecretFile> skippedSecrets) {
            this.entries = entries; this.skippedSecrets = skippedSecrets;
        }
        public Map<String, String> getEntries() { return entries; }
        public List<SkippedSecretFile> getSkippedSecrets() { return skippedSecrets; }
    }

    /**
     * A file that was skipped during upload due to detected secrets.
     */
    public static class SkippedSecretFile {
        private final String path;
        private final String ruleId;
        private final String label;
        public SkippedSecretFile(String path, String ruleId, String label) {
            this.path = path; this.ruleId = ruleId; this.label = label;
        }
        public String getPath() { return path; }
        public String getRuleId() { return ruleId; }
        public String getLabel() { return label; }
    }
}
