package com.anthropic.claudecode.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Files API service for managing files with the Anthropic Files API.
 * Translated from src/services/api/filesApi.ts
 *
 * Provides:
 * - downloadFile / downloadAndSaveFile / downloadSessionFiles
 * - uploadFile / uploadSessionFiles
 * - listFilesCreatedAfter
 * - parseFileSpecs
 *
 * API Reference: https://docs.anthropic.com/en/api/files-content
 */
@Slf4j
@Service
public class FilesApiService {

    // Files API is currently in beta.
    private static final String FILES_API_BETA_HEADER = "files-api-2025-04-14,oauth-2025-04-20";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int MAX_RETRIES = 3;
    private static final long BASE_DELAY_MS = 500;
    private static final long MAX_FILE_SIZE_BYTES = 500L * 1024 * 1024; // 500 MB
    private static final int DEFAULT_CONCURRENCY = 5;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public FilesApiService(OkHttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    // -----------------------------------------------------------------------
    // Domain types
    // -----------------------------------------------------------------------

    /** A file specification parsed from CLI args (fileId:relativePath). */
    public static class FileSpec {
        private final String fileId;
        private final String relativePath;
        public FileSpec(String fileId, String relativePath) { this.fileId = fileId; this.relativePath = relativePath; }
        public String getFileId() { return fileId; }
        public String getRelativePath() { return relativePath; }
    }

    /** Configuration for Files API requests. */
    public static class FilesApiConfig {
        private final String oauthToken;
        private String baseUrl;
        private final String sessionId;
        public FilesApiConfig(String oauthToken, String sessionId) { this.oauthToken = oauthToken; this.sessionId = sessionId; }
        public FilesApiConfig(String oauthToken, String baseUrl, String sessionId) { this.oauthToken = oauthToken; this.baseUrl = baseUrl; this.sessionId = sessionId; }
        public String getOauthToken() { return oauthToken; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }
        public String getSessionId() { return sessionId; }
    }

    /** Result of a single file download. */
    public static class DownloadResult {
        private final String fileId;
        private final String path;
        private final boolean success;
        private String error;
        private Long bytesWritten;
        public DownloadResult(String fileId, String path, boolean success) { this.fileId = fileId; this.path = path; this.success = success; }
        public String getPath() { return path; }
        public boolean isSuccess() { return success; }
        public String getError() { return error; }
        public void setError(String v) { this.error = v; }
        public Long getBytesWritten() { return bytesWritten; }
        public void setBytesWritten(Long v) { this.bytesWritten = v; }
    }

    /** Result of a single file upload. */
    public sealed interface UploadResult {
        record Success(String path, String fileId, long size) implements UploadResult {}
        record Failure(String path, String error) implements UploadResult {}

        /** True if this result represents a successful upload. */
        default boolean isSuccess() { return this instanceof Success; }

        /** Returns the uploaded file ID on success, null on failure. */
        default String getFileId() {
            return this instanceof Success s ? s.fileId() : null;
        }
    }

    /** File metadata returned by listFilesCreatedAfter. */
    public static class FileMetadata {
        private final String filename;
        private final String fileId;
        private final long size;
        public FileMetadata(String filename, String fileId, long size) { this.filename = filename; this.fileId = fileId; this.size = size; }
        public String getFilename() { return filename; }
        public long getSize() { return size; }
    }

    // -----------------------------------------------------------------------
    // Helper: base URL
    // -----------------------------------------------------------------------

    private String getDefaultApiBaseUrl() {
        String url = System.getenv("ANTHROPIC_BASE_URL");
        if (url != null && !url.isBlank()) return url;
        url = System.getenv("CLAUDE_CODE_API_BASE_URL");
        if (url != null && !url.isBlank()) return url;
        return "https://api.anthropic.com";
    }

    private String resolveBaseUrl(FilesApiConfig config) {
        String override = config.getBaseUrl();
        return (override != null && !override.isBlank()) ? override : getDefaultApiBaseUrl();
    }

    // -----------------------------------------------------------------------
    // downloadFile
    // -----------------------------------------------------------------------

    /**
     * Download a file from the Files API and return its content as a byte array.
     * Translated from downloadFile() in filesApi.ts
     */
    public CompletableFuture<byte[]> downloadFile(String fileId, FilesApiConfig config) {
        return CompletableFuture.supplyAsync(() -> {
            String baseUrl = resolveBaseUrl(config);
            String url = baseUrl + "/v1/files/" + fileId + "/content";
            log.debug("[files-api] Downloading file {} from {}", fileId, url);

            return retryWithBackoff("Download file " + fileId, attempt -> {
                Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + config.getOauthToken())
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("anthropic-beta", FILES_API_BETA_HEADER)
                    .get()
                    .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.code() == 200) {
                        byte[] bytes = response.body() != null ? response.body().bytes() : new byte[0];
                        log.debug("[files-api] Downloaded file {} ({} bytes)", fileId, bytes.length);
                        return RetryResult.done(bytes);
                    }
                    if (response.code() == 404) throw new RuntimeException("File not found: " + fileId);
                    if (response.code() == 401) throw new RuntimeException("Authentication failed: invalid or missing API key");
                    if (response.code() == 403) throw new RuntimeException("Access denied to file: " + fileId);
                    return RetryResult.retry("status " + response.code());
                } catch (RuntimeException e) {
                    throw e;
                } catch (IOException e) {
                    return RetryResult.retry(e.getMessage());
                }
            });
        });
    }

    // -----------------------------------------------------------------------
    // buildDownloadPath
    // -----------------------------------------------------------------------

    /**
     * Build the full download path under {basePath}/{sessionId}/uploads/.
     * Returns null if the path is invalid (e.g., path traversal).
     * Translated from buildDownloadPath() in filesApi.ts
     */
    public String buildDownloadPath(String basePath, String sessionId, String relativePath) {
        Path normalized = Path.of(relativePath).normalize();
        if (normalized.startsWith("..")) {
            log.error("[files-api] Invalid file path: {}. Path must not traverse above workspace", relativePath);
            return null;
        }

        Path uploadsBase = Path.of(basePath, sessionId, "uploads");

        // Strip redundant prefix if the caller already included the full path
        String normalizedStr = normalized.toString();
        String redundant1 = uploadsBase + File.separator;
        String redundant2 = File.separator + "uploads" + File.separator;

        String cleanPath = normalizedStr;
        if (normalizedStr.startsWith(redundant1)) {
            cleanPath = normalizedStr.substring(redundant1.length());
        } else if (normalizedStr.startsWith(redundant2)) {
            cleanPath = normalizedStr.substring(redundant2.length());
        }

        return uploadsBase.resolve(cleanPath).toString();
    }

    // -----------------------------------------------------------------------
    // downloadAndSaveFile
    // -----------------------------------------------------------------------

    /**
     * Download a file and save it to the session-specific workspace directory.
     * Translated from downloadAndSaveFile() in filesApi.ts
     */
    public CompletableFuture<DownloadResult> downloadAndSaveFile(
            FileSpec attachment, FilesApiConfig config, String cwd) {

        return CompletableFuture.supplyAsync(() -> {
            String fullPath = buildDownloadPath(cwd, config.getSessionId(), attachment.getRelativePath());
            if (fullPath == null) {
                DownloadResult r = new DownloadResult(attachment.getFileId(), "", false);
                r.setError("Invalid file path: " + attachment.getRelativePath());
                return r;
            }

            try {
                byte[] content = downloadFile(attachment.getFileId(), config).get();

                // Ensure parent directory exists
                Files.createDirectories(Path.of(fullPath).getParent());
                Files.write(Path.of(fullPath), content);

                log.debug("[files-api] Saved file {} to {} ({} bytes)",
                    attachment.getFileId(), fullPath, content.length);

                DownloadResult r = new DownloadResult(attachment.getFileId(), fullPath, true);
                r.setBytesWritten((long) content.length);
                return r;

            } catch (Exception e) {
                log.error("[files-api] Failed to download file {}: {}", attachment.getFileId(), e.getMessage());
                DownloadResult r = new DownloadResult(attachment.getFileId(), fullPath, false);
                r.setError(e.getMessage());
                return r;
            }
        });
    }

    // -----------------------------------------------------------------------
    // downloadSessionFiles
    // -----------------------------------------------------------------------

    /**
     * Download all file attachments for a session in parallel (up to DEFAULT_CONCURRENCY).
     * Translated from downloadSessionFiles() in filesApi.ts
     */
    public CompletableFuture<List<DownloadResult>> downloadSessionFiles(
            List<FileSpec> files, FilesApiConfig config, String cwd) {

        return downloadSessionFiles(files, config, cwd, DEFAULT_CONCURRENCY);
    }

    public CompletableFuture<List<DownloadResult>> downloadSessionFiles(
            List<FileSpec> files, FilesApiConfig config, String cwd, int concurrency) {

        if (files.isEmpty()) return CompletableFuture.completedFuture(List.of());

        log.debug("[files-api] Downloading {} file(s) for session {}", files.size(), config.getSessionId());
        long start = System.currentTimeMillis();

        return parallelWithLimit(files,
            (file, idx) -> downloadAndSaveFile(file, config, cwd),
            concurrency)
            .thenApply(results -> {
                long elapsed = System.currentTimeMillis() - start;
                long successes = results.stream().filter(DownloadResult::isSuccess).count();
                log.debug("[files-api] Downloaded {}/{} file(s) in {}ms", successes, files.size(), elapsed);
                return results;
            });
    }

    // -----------------------------------------------------------------------
    // uploadFile
    // -----------------------------------------------------------------------

    /**
     * Upload a single file to the Files API.
     * Translated from uploadFile() in filesApi.ts
     */
    public CompletableFuture<UploadResult> uploadFile(
            String filePath, String relativePath, FilesApiConfig config) {

        return CompletableFuture.supplyAsync(() -> {
            String baseUrl = resolveBaseUrl(config);
            String url = baseUrl + "/v1/files";
            log.debug("[files-api] Uploading file {} as {}", filePath, relativePath);

            // Read file outside retry loop
            byte[] content;
            try {
                content = Files.readAllBytes(Path.of(filePath));
            } catch (IOException e) {
                return new UploadResult.Failure(relativePath, e.getMessage());
            }

            long fileSize = content.length;
            if (fileSize > MAX_FILE_SIZE_BYTES) {
                return new UploadResult.Failure(relativePath,
                    "File exceeds maximum size of " + MAX_FILE_SIZE_BYTES + " bytes (actual: " + fileSize + ")");
            }

            String boundary = "----FormBoundary" + UUID.randomUUID().toString().replace("-", "");
            String filename = Path.of(relativePath).getFileName().toString();

            // Build multipart body
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try {
                byte[] headerPart = ("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n").getBytes();
                baos.write(headerPart);
                baos.write(content);
                baos.write("\r\n".getBytes());
                byte[] purposePart = ("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"purpose\"\r\n\r\n"
                    + "user_data\r\n").getBytes();
                baos.write(purposePart);
                baos.write(("--" + boundary + "--\r\n").getBytes());
            } catch (IOException e) {
                return new UploadResult.Failure(relativePath, e.getMessage());
            }

            byte[] body = baos.toByteArray();
            final byte[] finalContent = content;

            try {
                return retryWithBackoff("Upload file " + relativePath, attempt -> {
                    RequestBody requestBody = RequestBody.create(body,
                        MediaType.parse("multipart/form-data; boundary=" + boundary));

                    Request request = new Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer " + config.getOauthToken())
                        .header("anthropic-version", ANTHROPIC_VERSION)
                        .header("anthropic-beta", FILES_API_BETA_HEADER)
                        .header("Content-Length", String.valueOf(body.length))
                        .post(requestBody)
                        .build();

                    try (Response response = httpClient.newCall(request).execute()) {
                        int status = response.code();
                        String responseBody = response.body() != null ? response.body().string() : "";

                        if (status == 200 || status == 201) {
                            JsonNode json = objectMapper.readTree(responseBody);
                            String fileId = json.path("id").asText(null);
                            if (fileId == null || fileId.isBlank()) {
                                return RetryResult.retry("Upload succeeded but no file ID returned");
                            }
                            log.debug("[files-api] Uploaded {} -> {} ({} bytes)", filePath, fileId, finalContent.length);
                            return RetryResult.done(new UploadResult.Success(relativePath, fileId, finalContent.length));
                        }
                        if (status == 401) throw new NonRetriableException("Authentication failed: invalid or missing API key");
                        if (status == 403) throw new NonRetriableException("Access denied for upload");
                        if (status == 413) throw new NonRetriableException("File too large for upload");
                        return RetryResult.retry("status " + status);
                    } catch (NonRetriableException e) {
                        throw e;
                    } catch (IOException e) {
                        return RetryResult.retry(e.getMessage());
                    }
                });
            } catch (NonRetriableException e) {
                return new UploadResult.Failure(relativePath, e.getMessage());
            } catch (Exception e) {
                return new UploadResult.Failure(relativePath, e.getMessage());
            }
        });
    }

    // -----------------------------------------------------------------------
    // uploadSessionFiles
    // -----------------------------------------------------------------------

    public CompletableFuture<List<UploadResult>> uploadSessionFiles(
            List<FileSpec> files, FilesApiConfig config) {
        return uploadSessionFiles(files, config, DEFAULT_CONCURRENCY);
    }

    public CompletableFuture<List<UploadResult>> uploadSessionFiles(
            List<FileSpec> files, FilesApiConfig config, int concurrency) {

        if (files.isEmpty()) return CompletableFuture.completedFuture(List.of());

        log.debug("[files-api] Uploading {} file(s) for session {}", files.size(), config.getSessionId());
        long start = System.currentTimeMillis();

        return parallelWithLimit(files,
            (file, idx) -> uploadFile(file.getRelativePath(), file.getRelativePath(), config),
            concurrency)
            .thenApply(results -> {
                long elapsed = System.currentTimeMillis() - start;
                long successes = results.stream().filter(r -> r instanceof UploadResult.Success).count();
                log.debug("[files-api] Uploaded {}/{} file(s) in {}ms", successes, files.size(), elapsed);
                return results;
            });
    }

    // -----------------------------------------------------------------------
    // listFilesCreatedAfter
    // -----------------------------------------------------------------------

    /**
     * List files created after a given ISO-8601 timestamp. Handles pagination.
     * Translated from listFilesCreatedAfter() in filesApi.ts
     */
    public CompletableFuture<List<FileMetadata>> listFilesCreatedAfter(
            String afterCreatedAt, FilesApiConfig config) {

        return CompletableFuture.supplyAsync(() -> {
            String baseUrl = resolveBaseUrl(config);
            log.debug("[files-api] Listing files created after {}", afterCreatedAt);

            List<FileMetadata> allFiles = new ArrayList<>();
            String afterId = null;

            while (true) {
                String afterIdFinal = afterId;
                JsonNode page = retryWithBackoff("List files after " + afterCreatedAt, attempt -> {
                    HttpUrl.Builder urlBuilder = Objects.requireNonNull(HttpUrl.parse(baseUrl + "/v1/files"))
                        .newBuilder()
                        .addQueryParameter("after_created_at", afterCreatedAt);
                    if (afterIdFinal != null) urlBuilder.addQueryParameter("after_id", afterIdFinal);

                    Request request = new Request.Builder()
                        .url(urlBuilder.build())
                        .header("Authorization", "Bearer " + config.getOauthToken())
                        .header("anthropic-version", ANTHROPIC_VERSION)
                        .header("anthropic-beta", FILES_API_BETA_HEADER)
                        .get()
                        .build();

                    try (Response response = httpClient.newCall(request).execute()) {
                        if (response.code() == 200) {
                            String body = response.body() != null ? response.body().string() : "{}";
                            return RetryResult.done(objectMapper.readTree(body));
                        }
                        if (response.code() == 401) throw new RuntimeException("Authentication failed: invalid or missing API key");
                        if (response.code() == 403) throw new RuntimeException("Access denied to list files");
                        return RetryResult.retry("status " + response.code());
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (IOException e) {
                        return RetryResult.retry(e.getMessage());
                    }
                });

                JsonNode data = page.path("data");
                if (data.isArray()) {
                    for (JsonNode f : data) {
                        allFiles.add(new FileMetadata(
                            f.path("filename").asText(""),
                            f.path("id").asText(""),
                            f.path("size_bytes").asLong(0)));
                    }
                }

                if (!page.path("has_more").asBoolean(false)) break;

                if (data.isArray() && data.size() > 0) {
                    afterId = data.get(data.size() - 1).path("id").asText(null);
                    if (afterId == null) break;
                } else {
                    break;
                }
            }

            log.debug("[files-api] Listed {} files created after {}", allFiles.size(), afterCreatedAt);
            return allFiles;
        });
    }

    // -----------------------------------------------------------------------
    // parseFileSpecs
    // -----------------------------------------------------------------------

    /**
     * Parse file specs from CLI arguments. Format: {@code <fileId>:<relativePath>}
     * Translated from parseFileSpecs() in filesApi.ts
     */
    public List<FileSpec> parseFileSpecs(List<String> fileSpecs) {
        List<FileSpec> files = new ArrayList<>();

        // Gateway may pass multiple specs as a single space-separated string
        List<String> expanded = new ArrayList<>();
        for (String spec : fileSpecs) {
            for (String part : spec.split("\\s+")) {
                if (!part.isBlank()) expanded.add(part);
            }
        }

        for (String spec : expanded) {
            int colonIdx = spec.indexOf(':');
            if (colonIdx == -1) continue;

            String fileId = spec.substring(0, colonIdx);
            String relativePath = spec.substring(colonIdx + 1);

            if (fileId.isBlank() || relativePath.isBlank()) {
                log.error("[files-api] Invalid file spec: {}. Both file_id and path are required", spec);
                continue;
            }
            files.add(new FileSpec(fileId, relativePath));
        }
        return files;
    }

    // -----------------------------------------------------------------------
    // Internal retry helper
    // -----------------------------------------------------------------------

    /** Signals to the retry loop: either a final value or "keep retrying". */
    private sealed interface RetryResult<T> {
        record Done<T>(T value) implements RetryResult<T> {}
        record Retry<T>(String error) implements RetryResult<T> {}

        static <T> RetryResult<T> done(T value) { return new Done<>(value); }
        static <T> RetryResult<T> retry(String error) { return new Retry<>(error); }
    }

    @FunctionalInterface
    private interface AttemptFunction<T> {
        RetryResult<T> attempt(int attempt) throws Exception;
    }

    private static class NonRetriableException extends RuntimeException {
        NonRetriableException(String message) { super(message); }
    }

    private <T> T retryWithBackoff(String operation, AttemptFunction<T> fn) {
        String lastError = "";
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                RetryResult<T> result = fn.attempt(attempt);
                if (result instanceof RetryResult.Done<T> done) {
                    return done.value();
                }
                lastError = ((RetryResult.Retry<T>) result).error();
                log.debug("[files-api] {} attempt {}/{} failed: {}", operation, attempt, MAX_RETRIES, lastError);
            } catch (NonRetriableException e) {
                throw e;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                lastError = e.getMessage();
                log.debug("[files-api] {} attempt {}/{} error: {}", operation, attempt, MAX_RETRIES, lastError);
            }

            if (attempt < MAX_RETRIES) {
                long delayMs = BASE_DELAY_MS * (1L << (attempt - 1));
                log.debug("[files-api] Retrying {} in {}ms…", operation, delayMs);
                try { Thread.sleep(delayMs); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        throw new RuntimeException(lastError + " after " + MAX_RETRIES + " attempts");
    }

    // -----------------------------------------------------------------------
    // Parallel execution with concurrency limit
    // -----------------------------------------------------------------------

    @FunctionalInterface
    private interface IndexedFunction<T, R> {
        CompletableFuture<R> apply(T item, int index);
    }

    private <T, R> CompletableFuture<List<R>> parallelWithLimit(
            List<T> items, IndexedFunction<T, R> fn, int concurrency) {

        if (items.isEmpty()) return CompletableFuture.completedFuture(List.of());

        @SuppressWarnings("unchecked")
        R[] results = (R[]) new Object[items.size()];
        AtomicInteger idx = new AtomicInteger(0);

        int workerCount = Math.min(concurrency, items.size());
        List<CompletableFuture<Void>> workers = new ArrayList<>(workerCount);

        for (int w = 0; w < workerCount; w++) {
            workers.add(CompletableFuture.runAsync(() -> {
                int i;
                while ((i = idx.getAndIncrement()) < items.size()) {
                    try {
                        results[i] = fn.apply(items.get(i), i).get();
                    } catch (Exception e) {
                        // Store null on error; callers inspect individual result fields
                        results[i] = null;
                    }
                }
            }));
        }

        return CompletableFuture.allOf(workers.toArray(new CompletableFuture[0]))
            .thenApply(v -> Arrays.asList(results));
    }
}
