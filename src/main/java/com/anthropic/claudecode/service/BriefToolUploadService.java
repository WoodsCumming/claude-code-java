package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Upload BriefTool attachments to private_api so web viewers can preview them.
 * Translated from src/tools/BriefTool/upload.ts
 *
 * <p>Best-effort: any failure (no token, bridge off, network error, 4xx) logs
 * debug and returns null. The attachment still carries {path, size, isImage},
 * so local-terminal and same-machine-desktop render unaffected.</p>
 */
@Slf4j
@Service
public class BriefToolUploadService {



    // Matches the private_api backend limit: 30 MB
    private static final long MAX_UPLOAD_BYTES = 30L * 1024 * 1024;
    private static final Duration UPLOAD_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Backend dispatches on mime: image/* → upload_image_wrapped (no ORIGINAL),
     * everything else → upload_generic_file (ORIGINAL only).
     * Only whitelist raster formats the transcoder reliably handles.
     */
    private static final Map<String, String> MIME_BY_EXT = Map.of(
            ".png",  "image/png",
            ".jpg",  "image/jpeg",
            ".jpeg", "image/jpeg",
            ".gif",  "image/gif",
            ".webp", "image/webp"
    );

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public BriefToolUploadService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Context required for uploads.
     */
    public record BriefUploadContext(boolean replBridgeEnabled) {}

    /**
     * Upload a single attachment. Returns file_uuid on success, null otherwise.
     * Every early-return is intentional graceful degradation.
     * Translated from uploadBriefAttachment() in upload.ts
     */
    public CompletableFuture<String> uploadBriefAttachment(
            String fullPath,
            long size,
            BriefUploadContext ctx
    ) {
        return CompletableFuture.supplyAsync(() -> {
            if (!ctx.replBridgeEnabled()) {
                return null;
            }
            if (size > MAX_UPLOAD_BYTES) {
                log.debug("[brief:upload] skip {}: {} bytes exceeds {} limit", fullPath, size, MAX_UPLOAD_BYTES);
                return null;
            }
            String token = getBridgeAccessToken();
            if (token == null || token.isBlank()) {
                log.debug("[brief:upload] skip: no oauth token");
                return null;
            }

            byte[] content;
            try {
                content = Files.readAllBytes(Path.of(fullPath));
            } catch (IOException e) {
                log.debug("[brief:upload] read failed for {}: {}", fullPath, e.getMessage());
                return null;
            }

            String baseUrl = getBridgeBaseUrl();
            String url = baseUrl + "/api/oauth/file_upload";
            String filename = Path.of(fullPath).getFileName().toString();
            String mimeType = guessMimeType(filename);
            String boundary = "----FormBoundary" + UUID.randomUUID().toString().replace("-", "");

            // Build multipart/form-data body manually
            byte[] body = buildMultipartBody(boundary, filename, mimeType, content);

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(UPLOAD_TIMEOUT)
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .header("Content-Length", String.valueOf(body.length))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 201) {
                    String snippet = response.body().length() > 200
                            ? response.body().substring(0, 200)
                            : response.body();
                    log.debug("[brief:upload] upload failed for {}: status={} body={}", fullPath, response.statusCode(), snippet);
                    return null;
                }

                JsonNode json = objectMapper.readTree(response.body());
                JsonNode uuidNode = json.get("file_uuid");
                if (uuidNode == null || uuidNode.isNull()) {
                    log.debug("[brief:upload] unexpected response shape for {}: missing file_uuid", fullPath);
                    return null;
                }
                String fileUuid = uuidNode.asText();
                log.debug("[brief:upload] uploaded {} -> {} ({} bytes)", fullPath, fileUuid, size);
                return fileUuid;

            } catch (IOException | InterruptedException e) {
                log.debug("[brief:upload] upload threw for {}: {}", fullPath, e.getMessage());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }
        });
    }

    /**
     * Determine the MIME type from file extension.
     * Translated from guessMimeType() in upload.ts
     */
    private static String guessMimeType(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot >= 0) {
            String ext = filename.substring(dot).toLowerCase();
            return MIME_BY_EXT.getOrDefault(ext, "application/octet-stream");
        }
        return "application/octet-stream";
    }

    /**
     * Build the multipart/form-data body.
     * Mirrors the manual Buffer.concat() approach in upload.ts.
     */
    private static byte[] buildMultipartBody(String boundary, String filename, String mimeType, byte[] content) {
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + mimeType + "\r\n\r\n";
        String footer = "\r\n--" + boundary + "--\r\n";

        byte[] headerBytes = header.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] footerBytes = footer.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        byte[] body = new byte[headerBytes.length + content.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, body, 0, headerBytes.length);
        System.arraycopy(content, 0, body, headerBytes.length, content.length);
        System.arraycopy(footerBytes, 0, body, headerBytes.length + content.length, footerBytes.length);
        return body;
    }

    /**
     * Get the OAuth bearer token for bridge uploads.
     * Translated from getBridgeAccessToken() in bridgeConfig.ts
     */
    private static String getBridgeAccessToken() {
        return System.getenv("CLAUDE_CODE_OAUTH_TOKEN");
    }

    /**
     * Resolve the base URL for uploads, preferring explicit overrides.
     * Translated from getBridgeBaseUrl() in upload.ts
     */
    private static String getBridgeBaseUrl() {
        String override = System.getenv("CLAUDE_CODE_BRIDGE_BASE_URL");
        if (override != null && !override.isBlank()) return override;
        String anthropicBase = System.getenv("ANTHROPIC_BASE_URL");
        if (anthropicBase != null && !anthropicBase.isBlank()) return anthropicBase;
        return "https://api.anthropic.com";
    }
}
