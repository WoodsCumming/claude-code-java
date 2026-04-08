package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Resolves file_uuid attachments on inbound bridge user messages.
 *
 * Web composer uploads via cookie-authed /api/{org}/upload, sends file_uuid
 * alongside the message. Here we fetch each via GET /api/oauth/files/{uuid}/content
 * (oauth-authed, same store), write to ~/.claude/uploads/{sessionId}/, and
 * return @path refs to prepend. Claude's Read tool takes it from there.
 *
 * Best-effort: any failure (no token, network, non-2xx, disk) logs debug and
 * skips that attachment. The message still reaches Claude, just without @path.
 *
 * Translated from src/bridge/inboundAttachments.ts
 */
@Slf4j
@Service
public class BridgeInboundAttachmentsService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BridgeInboundAttachmentsService.class);


    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;

    @Autowired
    public BridgeInboundAttachmentsService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ─── Attachment model ─────────────────────────────────────────────────────

    /**
     * A single file attachment carried on an inbound bridge message.
     * Translated from InboundAttachment in inboundAttachments.ts
     */
    public record InboundAttachment(
        String fileUuid,
        String fileName
    ) {}

    // ─── Extraction ───────────────────────────────────────────────────────────

    /**
     * Pull file_attachments off a loosely-typed inbound message map.
     * Returns an empty list when the field is absent or malformed.
     * Translated from extractInboundAttachments() in inboundAttachments.ts
     */
    @SuppressWarnings("unchecked")
    public List<InboundAttachment> extractInboundAttachments(Map<String, Object> msg) {
        if (msg == null || !msg.containsKey("file_attachments")) {
            return List.of();
        }
        Object raw = msg.get("file_attachments");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<InboundAttachment> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> rawMap)) continue;
            Map<String, Object> entry = (Map<String, Object>) rawMap;
            Object uuid = entry.get("file_uuid");
            Object name = entry.get("file_name");
            if (uuid instanceof String fileUuid && name instanceof String fileName) {
                result.add(new InboundAttachment(fileUuid, fileName));
            }
        }
        return result;
    }

    // ─── Sanitization ─────────────────────────────────────────────────────────

    /**
     * Strip path components and keep only filename-safe chars.
     * file_name comes from the network, so treat it as untrusted.
     * Translated from sanitizeFileName() in inboundAttachments.ts
     */
    public static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) return "attachment";
        // Take only the filename part (equivalent of basename)
        String base = Paths.get(name).getFileName().toString();
        // Replace unsafe chars
        String safe = base.replaceAll("[^a-zA-Z0-9._-]", "_");
        return safe.isEmpty() ? "attachment" : safe;
    }

    // ─── Uploads directory ────────────────────────────────────────────────────

    /**
     * Returns the per-session uploads directory path.
     * Mirrors uploadsDir() in inboundAttachments.ts
     */
    public static Path uploadsDir(String claudeConfigHome, String sessionId) {
        return Paths.get(claudeConfigHome, "uploads", sessionId);
    }

    // ─── Single-attachment resolution ─────────────────────────────────────────

    /**
     * Fetch and write one attachment. Returns the absolute path on success,
     * null on any failure (best-effort, never throws).
     * Translated from resolveOne() in inboundAttachments.ts
     */
    public CompletableFuture<String> resolveOne(
            InboundAttachment att,
            String bridgeBaseUrl,
            String oauthToken,
            String claudeConfigHome,
            String sessionId) {

        return CompletableFuture.supplyAsync(() -> {
            if (oauthToken == null || oauthToken.isBlank()) {
                log.debug("[bridge:inbound-attach] skip: no oauth token");
                return null;
            }

            byte[] data;
            try {
                String url = bridgeBaseUrl + "/api/oauth/files/"
                        + URI.create(att.fileUuid()).toASCIIString() + "/content";
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + oauthToken)
                        .timeout(DOWNLOAD_TIMEOUT)
                        .GET()
                        .build();

                HttpResponse<byte[]> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofByteArray());

                if (response.statusCode() != 200) {
                    log.debug("[bridge:inbound-attach] fetch {} failed: status={}",
                            att.fileUuid(), response.statusCode());
                    return null;
                }
                data = response.body();
            } catch (Exception e) {
                log.debug("[bridge:inbound-attach] fetch {} threw: {}", att.fileUuid(), e.getMessage());
                return null;
            }

            // uuid-prefix makes collisions impossible across messages
            String safeName = sanitizeFileName(att.fileName());
            String prefix = (att.fileUuid().length() >= 8 ? att.fileUuid().substring(0, 8) :
                    UUID.randomUUID().toString().substring(0, 8))
                    .replaceAll("[^a-zA-Z0-9_-]", "_");

            Path dir = uploadsDir(claudeConfigHome, sessionId);
            Path outPath = dir.resolve(prefix + "-" + safeName);

            try {
                Files.createDirectories(dir);
                Files.write(outPath, data);
            } catch (IOException e) {
                log.debug("[bridge:inbound-attach] write {} failed: {}", outPath, e.getMessage());
                return null;
            }

            log.debug("[bridge:inbound-attach] resolved {} -> {} ({} bytes)",
                    att.fileUuid(), outPath, data.length);
            return outPath.toString();
        });
    }

    // ─── Multi-attachment resolution ──────────────────────────────────────────

    /**
     * Resolve all attachments on an inbound message to a prefix string of
     * "@path" refs. Empty string if none resolved.
     * Translated from resolveInboundAttachments() in inboundAttachments.ts
     */
    public CompletableFuture<String> resolveInboundAttachments(
            List<InboundAttachment> attachments,
            String bridgeBaseUrl,
            String oauthToken,
            String claudeConfigHome,
            String sessionId) {

        if (attachments.isEmpty()) {
            return CompletableFuture.completedFuture("");
        }
        log.debug("[bridge:inbound-attach] resolving {} attachment(s)", attachments.size());

        List<CompletableFuture<String>> futures = attachments.stream()
                .map(att -> resolveOne(att, bridgeBaseUrl, oauthToken, claudeConfigHome, sessionId))
                .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(ignored -> {
                    List<String> paths = futures.stream()
                            .map(CompletableFuture::join)
                            .filter(p -> p != null)
                            .collect(Collectors.toList());
                    if (paths.isEmpty()) return "";
                    // Quoted form — prevents truncation at first space in paths with spaces
                    return paths.stream()
                            .map(p -> "@\"" + p + "\"")
                            .collect(Collectors.joining(" ")) + " ";
                });
    }

    // ─── Content prepending ───────────────────────────────────────────────────

    /**
     * Prepend @path refs to string content.
     * Translated from prependPathRefs() (string branch) in inboundAttachments.ts
     */
    public static String prependPathRefsToString(String content, String prefix) {
        if (prefix == null || prefix.isEmpty()) return content;
        return prefix + content;
    }

    /**
     * Prepend @path refs to block-list content by prepending to the last text block.
     * If there is no text block, appends one at the end.
     * Translated from prependPathRefs() (array branch) in inboundAttachments.ts
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> prependPathRefsToBlocks(
            List<Map<String, Object>> content, String prefix) {
        if (prefix == null || prefix.isEmpty()) return content;

        // Find last text block
        int lastTextIdx = -1;
        for (int i = content.size() - 1; i >= 0; i--) {
            if ("text".equals(content.get(i).get("type"))) {
                lastTextIdx = i;
                break;
            }
        }

        List<Map<String, Object>> result = new ArrayList<>(content);
        if (lastTextIdx != -1) {
            Map<String, Object> orig = content.get(lastTextIdx);
            Map<String, Object> modified = new java.util.LinkedHashMap<>(orig);
            modified.put("text", prefix + orig.get("text"));
            result.set(lastTextIdx, modified);
        } else {
            // No text block — append one at the end
            result.add(Map.of("type", "text", "text", prefix.strip()));
        }
        return result;
    }
}
