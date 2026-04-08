package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;

/**
 * Image processing utilities.
 * Translated from src/utils/imageResizer.ts
 *
 * Handles image resizing and processing for API submission.
 */
@Slf4j
public class ImageUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ImageUtils.class);


    // API limits (from constants/apiLimits.ts)
    public static final int IMAGE_MAX_WIDTH = 8000;
    public static final int IMAGE_MAX_HEIGHT = 8000;
    public static final long API_IMAGE_MAX_BASE64_SIZE = 5 * 1024 * 1024; // 5MB base64
    public static final long IMAGE_TARGET_RAW_SIZE = 1 * 1024 * 1024; // 1MB

    /**
     * Resize an image if it exceeds API limits.
     * Translated from maybeResizeAndDownsampleImageBuffer() in imageResizer.ts
     *
     * @param imageBytes Raw image bytes
     * @param mediaType  Image media type
     * @return Processed image bytes (possibly resized)
     */
    public static byte[] maybeResizeImage(byte[] imageBytes, String mediaType) {
        if (imageBytes == null) return imageBytes;

        try {
            // Check if image needs resizing
            ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes);
            BufferedImage img = ImageIO.read(bais);

            if (img == null) {
                log.warn("Could not read image for resizing");
                return imageBytes;
            }

            int width = img.getWidth();
            int height = img.getHeight();

            // Check if within limits
            if (width <= IMAGE_MAX_WIDTH && height <= IMAGE_MAX_HEIGHT
                && imageBytes.length <= IMAGE_TARGET_RAW_SIZE) {
                return imageBytes;
            }

            // Calculate scale factor
            double scale = 1.0;
            if (width > IMAGE_MAX_WIDTH) scale = Math.min(scale, (double) IMAGE_MAX_WIDTH / width);
            if (height > IMAGE_MAX_HEIGHT) scale = Math.min(scale, (double) IMAGE_MAX_HEIGHT / height);

            // Also scale down if too large in bytes
            if (imageBytes.length > IMAGE_TARGET_RAW_SIZE) {
                double bytesScale = Math.sqrt((double) IMAGE_TARGET_RAW_SIZE / imageBytes.length);
                scale = Math.min(scale, bytesScale);
            }

            int newWidth = (int) (width * scale);
            int newHeight = (int) (height * scale);

            // Resize
            BufferedImage resized = new BufferedImage(newWidth, newHeight, img.getType());
            Graphics2D g2d = resized.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.drawImage(img, 0, 0, newWidth, newHeight, null);
            g2d.dispose();

            // Convert back to bytes
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            String format = getFormatName(mediaType);
            ImageIO.write(resized, format, baos);

            byte[] resizedBytes = baos.toByteArray();
            log.debug("Resized image from {}x{} to {}x{} ({} -> {} bytes)",
                width, height, newWidth, newHeight, imageBytes.length, resizedBytes.length);

            return resizedBytes;

        } catch (Exception e) {
            log.warn("Image resizing failed: {}", e.getMessage());
            return imageBytes;
        }
    }

    /**
     * Convert image bytes to base64 string.
     */
    public static String toBase64(byte[] imageBytes) {
        return Base64.getEncoder().encodeToString(imageBytes);
    }

    /**
     * Detect image format from bytes.
     * Translated from detectImageFormatFromBuffer() in imageResizer.ts
     */
    public static String detectMediaType(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length < 4) return "image/png";

        // Check magic bytes
        if (imageBytes[0] == (byte) 0x89 && imageBytes[1] == 0x50) return "image/png";
        if (imageBytes[0] == (byte) 0xFF && imageBytes[1] == (byte) 0xD8) return "image/jpeg";
        if (imageBytes[0] == 0x47 && imageBytes[1] == 0x49) return "image/gif";
        if (imageBytes[0] == 0x52 && imageBytes[1] == 0x49) return "image/webp";

        return "image/png"; // default
    }

    private static String getFormatName(String mediaType) {
        if (mediaType == null) return "png";
        return switch (mediaType) {
            case "image/jpeg", "image/jpg" -> "jpeg";
            case "image/gif" -> "gif";
            case "image/webp" -> "png"; // webp not always supported, fallback to png
            default -> "png";
        };
    }

    public static class ImageResizeError extends RuntimeException {
        public ImageResizeError(String message) {
            super(message);
        }
    }

    private ImageUtils() {}
}
