package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Processes inbound user messages from the bridge.
 *
 * Extracts content and UUID for enqueueing; supports both string content and
 * ContentBlockParam[] (e.g. messages containing images).
 *
 * Normalizes image blocks from bridge clients that may use camelCase
 * {@code mediaType} instead of snake_case {@code media_type} (mobile-apps#5825).
 *
 * Translated from src/bridge/inboundMessages.ts
 */
@Slf4j
@Service
public class BridgeInboundMessagesService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BridgeInboundMessagesService.class);


    // ─── Result type ──────────────────────────────────────────────────────────

    /**
     * Extracted fields from an inbound bridge user message.
     * Translated from the return type of extractInboundMessageFields() in inboundMessages.ts
     */
    public record InboundMessageFields(
        /** The message content — either a plain String or a List of content-block Maps. */
        Object content,
        /** The optional UUID from the message, null if absent. */
        String uuid
    ) {}

    // ─── Extraction ───────────────────────────────────────────────────────────

    /**
     * Process an inbound user message from the bridge, extracting content
     * and UUID for enqueueing.
     *
     * Returns null if the message should be skipped (non-user type,
     * missing/empty content).
     *
     * Translated from extractInboundMessageFields() in inboundMessages.ts
     */
    @SuppressWarnings("unchecked")
    public InboundMessageFields extractInboundMessageFields(Map<String, Object> msg) {
        if (msg == null) return null;

        // Only process 'user' type messages
        if (!"user".equals(msg.get("type"))) return null;

        Map<String, Object> message = (Map<String, Object>) msg.get("message");
        if (message == null) return null;

        Object content = message.get("content");
        if (content == null) return null;

        // Skip empty arrays
        if (content instanceof List<?> list && list.isEmpty()) return null;

        String uuid = msg.get("uuid") instanceof String s ? s : null;

        // Normalize image blocks if content is a list
        Object normalizedContent;
        if (content instanceof List<?> rawList) {
            List<Map<String, Object>> blocks = new ArrayList<>();
            for (Object item : rawList) {
                if (item instanceof Map<?, ?> rawMap) {
                    blocks.add((Map<String, Object>) rawMap);
                }
            }
            normalizedContent = normalizeImageBlocks(blocks);
        } else {
            normalizedContent = content;
        }

        return new InboundMessageFields(normalizedContent, uuid);
    }

    // ─── Image block normalization ────────────────────────────────────────────

    /**
     * Normalize image content blocks from bridge clients.
     *
     * iOS/web clients may send {@code mediaType} (camelCase) instead of
     * {@code media_type} (snake_case), or omit the field entirely.
     * Without normalization, the bad block poisons the session — every
     * subsequent API call fails with "media_type: Field required".
     *
     * Fast-path scan returns the original list reference when no
     * normalization is needed (zero allocation on the happy path).
     *
     * Translated from normalizeImageBlocks() in inboundMessages.ts
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> normalizeImageBlocks(List<Map<String, Object>> blocks) {
        if (blocks == null) return List.of();

        // Fast-path: check if any block needs normalization
        boolean needsNormalization = blocks.stream().anyMatch(this::isMalformedBase64Image);
        if (!needsNormalization) return blocks;

        List<Map<String, Object>> result = new ArrayList<>(blocks.size());
        for (Map<String, Object> block : blocks) {
            if (!isMalformedBase64Image(block)) {
                result.add(block);
                continue;
            }

            // Normalize the malformed image block
            Map<String, Object> source = (Map<String, Object>) block.get("source");
            String mediaType = null;

            // Try camelCase mediaType first
            if (source.get("mediaType") instanceof String s && !s.isBlank()) {
                mediaType = s;
            } else {
                // Detect from base64 data
                Object data = source.get("data");
                if (data instanceof String b64) {
                    mediaType = detectImageFormatFromBase64(b64);
                }
            }

            Map<String, Object> normalizedSource = new LinkedHashMap<>();
            normalizedSource.put("type", "base64");
            normalizedSource.put("media_type", mediaType != null ? mediaType : "image/jpeg");
            normalizedSource.put("data", source.get("data"));

            Map<String, Object> normalizedBlock = new LinkedHashMap<>(block);
            normalizedBlock.put("source", normalizedSource);
            result.add(normalizedBlock);
        }
        return result;
    }

    /**
     * Returns true if the block is a base64 image with a missing or malformed
     * {@code media_type} field.
     * Translated from isMalformedBase64Image() in inboundMessages.ts
     */
    @SuppressWarnings("unchecked")
    private boolean isMalformedBase64Image(Map<String, Object> block) {
        if (!"image".equals(block.get("type"))) return false;
        Object sourceObj = block.get("source");
        if (!(sourceObj instanceof Map<?, ?> rawSource)) return false;
        Map<String, Object> source = (Map<String, Object>) rawSource;
        if (!"base64".equals(source.get("type"))) return false;
        // Malformed = missing media_type
        Object mediaType = source.get("media_type");
        return mediaType == null || (mediaType instanceof String s && s.isBlank());
    }

    // ─── Image format detection ───────────────────────────────────────────────

    /**
     * Detect image MIME type from the first bytes of a base64-encoded image.
     * Mirrors detectImageFormatFromBase64() in utils/imageResizer.ts
     */
    public static String detectImageFormatFromBase64(String base64Data) {
        if (base64Data == null || base64Data.isBlank()) return "image/jpeg";
        try {
            byte[] bytes = Base64.getDecoder().decode(
                    base64Data.length() > 16 ? base64Data.substring(0, 16) : base64Data);
            if (bytes.length >= 4) {
                // PNG: 89 50 4E 47
                if (bytes[0] == (byte) 0x89 && bytes[1] == 0x50
                        && bytes[2] == 0x4E && bytes[3] == 0x47) {
                    return "image/png";
                }
                // GIF: 47 49 46 38
                if (bytes[0] == 0x47 && bytes[1] == 0x49
                        && bytes[2] == 0x46 && bytes[3] == 0x38) {
                    return "image/gif";
                }
                // WEBP: 52 49 46 46 ... 57 45 42 50
                if (bytes[0] == 0x52 && bytes[1] == 0x49
                        && bytes[2] == 0x46 && bytes[3] == 0x46) {
                    return "image/webp";
                }
            }
            if (bytes.length >= 2) {
                // JPEG: FF D8
                if (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8) {
                    return "image/jpeg";
                }
            }
        } catch (Exception ignored) {}
        return "image/jpeg";
    }
}
