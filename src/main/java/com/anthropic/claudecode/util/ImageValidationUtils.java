package com.anthropic.claudecode.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Image validation utilities.
 * Translated from src/utils/imageValidation.ts
 *
 * Validates that all images in API message payloads are within the base64
 * size limit. Works with raw message structures (List of Maps) as well as
 * typed message objects that expose getType() / getContent().
 */
public class ImageValidationUtils {

    // =========================================================================
    // Constants
    // =========================================================================

    /**
     * Maximum base64-encoded string length accepted by the Anthropic API.
     * Note: This is the base64 *string* length, not the decoded byte count.
     * Translated from API_IMAGE_MAX_BASE64_SIZE in apiLimits.ts
     */
    public static final long API_IMAGE_MAX_BASE64_SIZE = 5L * 1024 * 1024; // 5 MB

    // =========================================================================
    // Types
    // =========================================================================

    /**
     * Information about an oversized image.
     * Translated from OversizedImage in imageValidation.ts
     */
    public record OversizedImage(int index, long size) {}

    /**
     * Error thrown when one or more images exceed the API size limit.
     * Translated from ImageSizeError in imageValidation.ts
     */
    public static class ImageSizeError extends RuntimeException {
        private final List<OversizedImage> oversizedImages;
        private final long maxSize;

        public ImageSizeError(List<OversizedImage> oversizedImages, long maxSize) {
            super(buildMessage(oversizedImages, maxSize));
            this.oversizedImages = List.copyOf(oversizedImages);
            this.maxSize = maxSize;
        }

        public List<OversizedImage> getOversizedImages() { return oversizedImages; }
        public long getMaxSize() { return maxSize; }

        private static String buildMessage(List<OversizedImage> images, long maxSize) {
            String maxSizeStr = formatFileSize(maxSize);
            if (images.size() == 1) {
                OversizedImage img = images.get(0);
                return "Image base64 size (" + formatFileSize(img.size())
                    + ") exceeds API limit (" + maxSizeStr
                    + "). Please resize the image before sending.";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(images.size())
              .append(" images exceed the API limit (")
              .append(maxSizeStr)
              .append("): ");
            for (int i = 0; i < images.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("Image ").append(images.get(i).index())
                  .append(": ").append(formatFileSize(images.get(i).size()));
            }
            sb.append(". Please resize these images before sending.");
            return sb.toString();
        }

        private static String formatFileSize(long bytes) {
            if (bytes >= 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
            if (bytes >= 1024) return String.format("%.1f KB", bytes / 1024.0);
            return bytes + " B";
        }
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Validates that all images in messages are within the API size limit.
     *
     * Accepts a list of raw message objects. Each message is expected to be a
     * Map with at least "type" and "message" keys (wrapped format), where the
     * inner "message" has "content" as a List of blocks. Blocks of
     * type "image" with source.type "base64" are checked.
     *
     * Only "user" messages are inspected, matching the TS implementation.
     *
     * Translated from validateImagesForAPI() in imageValidation.ts
     *
     * @param messages List of message objects (Map or typed)
     * @throws ImageSizeError if any image exceeds the API limit
     */
    @SuppressWarnings("unchecked")
    public static void validateImagesForAPI(List<?> messages) {
        List<OversizedImage> oversizedImages = new ArrayList<>();
        int imageIndex = 0;

        for (Object msg : messages) {
            if (!(msg instanceof Map<?, ?> msgMap)) continue;

            // Only check user messages
            Object type = msgMap.get("type");
            if (!"user".equals(type)) continue;

            Object innerMessageObj = msgMap.get("message");
            if (!(innerMessageObj instanceof Map<?, ?> innerMessage)) continue;

            Object contentObj = innerMessage.get("content");
            if (!(contentObj instanceof List<?> content)) continue;

            for (Object block : content) {
                if (!isBase64ImageBlock(block)) continue;

                imageIndex++;
                Map<?, ?> blockMap = (Map<?, ?>) block;
                Map<?, ?> source = (Map<?, ?>) blockMap.get("source");
                String data = (String) source.get("data");
                long base64Size = data.length();

                if (base64Size > API_IMAGE_MAX_BASE64_SIZE) {
                    oversizedImages.add(new OversizedImage(imageIndex, base64Size));
                }
            }
        }

        if (!oversizedImages.isEmpty()) {
            throw new ImageSizeError(oversizedImages, API_IMAGE_MAX_BASE64_SIZE);
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Type guard: check if a block is a base64 image block.
     * Translated from isBase64ImageBlock() in imageValidation.ts
     */
    @SuppressWarnings("unchecked")
    private static boolean isBase64ImageBlock(Object block) {
        if (!(block instanceof Map<?, ?> b)) return false;
        if (!"image".equals(b.get("type"))) return false;
        Object sourceObj = b.get("source");
        if (!(sourceObj instanceof Map<?, ?> source)) return false;
        return "base64".equals(source.get("type")) && source.get("data") instanceof String;
    }

    private ImageValidationUtils() {}
}
