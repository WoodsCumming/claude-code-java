package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * Image processing and resizing service.
 * Translated from src/utils/imageResizer.ts (and src/tools/FileReadTool/imageProcessor.ts).
 *
 * <p>Mirrors the resize / compress pipeline from the TypeScript source which uses the
 * 'sharp' native library. Here we use the JDK's built-in {@link ImageIO} and
 * {@link BufferedImage} APIs.</p>
 */
@Slf4j
@Service
public class ImageProcessorService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ImageProcessorService.class);

    // -------------------------------------------------------------------------
    // API limits (mirror src/constants/apiLimits.ts)
    // -------------------------------------------------------------------------

    private static final int IMAGE_MAX_WIDTH        = 8000;
    private static final int IMAGE_MAX_HEIGHT       = 8000;
    private static final long IMAGE_TARGET_RAW_SIZE = 5 * 1024 * 1024;   // 5 MB
    private static final long API_IMAGE_MAX_BASE64_SIZE = 6_700_000L;     // ~5 MB base64

    // -------------------------------------------------------------------------
    // Error type constants (analytics, mirrors TS numeric constants)
    // -------------------------------------------------------------------------

    public static final int ERROR_TYPE_MODULE_LOAD  = 1;
    public static final int ERROR_TYPE_PROCESSING   = 2;
    public static final int ERROR_TYPE_UNKNOWN      = 3;
    public static final int ERROR_TYPE_PIXEL_LIMIT  = 4;
    public static final int ERROR_TYPE_MEMORY       = 5;
    public static final int ERROR_TYPE_TIMEOUT      = 6;
    public static final int ERROR_TYPE_VIPS         = 7;
    public static final int ERROR_TYPE_PERMISSION   = 8;

    // -------------------------------------------------------------------------
    // Public data types
    // -------------------------------------------------------------------------

    /**
     * Thrown when image resizing fails and the image still exceeds the API limit.
     * Translated from {@code ImageResizeError} in imageResizer.ts.
     */
    public static class ImageResizeException extends RuntimeException {
        public ImageResizeException(String message) {
            super(message);
        }
    }

    /**
     * Optional dimension information attached to a resize result.
     * Translated from {@code ImageDimensions} in imageResizer.ts.
     */
    public record ImageDimensions(
            Integer originalWidth,
            Integer originalHeight,
            Integer displayWidth,
            Integer displayHeight) {}

    /**
     * Result of a resize/downsample operation.
     * Translated from {@code ResizeResult} in imageResizer.ts.
     */
    public record ResizeResult(byte[] buffer, String mediaType, ImageDimensions dimensions) {}

    /**
     * Result of a compress operation.
     * Translated from {@code CompressedImageResult} in imageResizer.ts.
     */
    public record CompressedImageResult(String base64, String mediaType, long originalSize) {}

    /**
     * Metadata returned for an image buffer.
     * Translated from the Sharp {@code metadata()} return type.
     */
    public record ImageMetadata(int width, int height, String format) {}

    /**
     * Options for resize operations.
     */
    public record ResizeOptions(boolean withoutEnlargement) {
        public static ResizeOptions defaults() { return new ResizeOptions(false); }
    }

    // -------------------------------------------------------------------------
    // maybeResizeAndDownsampleImageBuffer
    // -------------------------------------------------------------------------

    /**
     * Resizes an image buffer to meet size and dimension constraints.
     * Translated from {@code maybeResizeAndDownsampleImageBuffer()} in imageResizer.ts.
     *
     * @param imageBytes   raw image bytes
     * @param originalSize original file size (may differ from buffer length after decode)
     * @param ext          file extension hint (e.g. "png", "jpg")
     */
    public CompletableFuture<ResizeResult> maybeResizeAndDownsampleImageBuffer(
            byte[] imageBytes, long originalSize, String ext) {

        return CompletableFuture.supplyAsync(() -> {
            if (imageBytes == null || imageBytes.length == 0) {
                throw new ImageResizeException("Image file is empty (0 bytes)");
            }
            try {
                BufferedImage src = decodeImage(imageBytes);
                String detectedFormat = detectFormat(imageBytes, ext);
                String normalizedMediaType = "jpg".equals(detectedFormat) ? "jpeg" : detectedFormat;

                int originalWidth  = src.getWidth();
                int originalHeight = src.getHeight();
                int width  = originalWidth;
                int height = originalHeight;

                // Fast path: original image is already within all constraints
                if (originalSize <= IMAGE_TARGET_RAW_SIZE
                        && width  <= IMAGE_MAX_WIDTH
                        && height <= IMAGE_MAX_HEIGHT) {
                    return new ResizeResult(imageBytes, normalizedMediaType,
                            new ImageDimensions(originalWidth, originalHeight, width, height));
                }

                boolean needsDimensionResize = width > IMAGE_MAX_WIDTH || height > IMAGE_MAX_HEIGHT;
                boolean isPng = "png".equals(normalizedMediaType);

                // --- Compression-only path (dimensions OK but file too large) ---
                if (!needsDimensionResize && originalSize > IMAGE_TARGET_RAW_SIZE) {
                    if (isPng) {
                        byte[] pngComp = encodePng(src);
                        if (pngComp.length <= IMAGE_TARGET_RAW_SIZE) {
                            return new ResizeResult(pngComp, "png",
                                    new ImageDimensions(originalWidth, originalHeight, width, height));
                        }
                    }
                    for (int quality : new int[]{80, 60, 40, 20}) {
                        byte[] jpegComp = encodeJpeg(src, quality);
                        if (jpegComp.length <= IMAGE_TARGET_RAW_SIZE) {
                            return new ResizeResult(jpegComp, "jpeg",
                                    new ImageDimensions(originalWidth, originalHeight, width, height));
                        }
                    }
                    // Fall through to resize
                }

                // --- Constrain dimensions ---
                if (width > IMAGE_MAX_WIDTH) {
                    height = Math.round((float) height * IMAGE_MAX_WIDTH / width);
                    width  = IMAGE_MAX_WIDTH;
                }
                if (height > IMAGE_MAX_HEIGHT) {
                    width  = Math.round((float) width * IMAGE_MAX_HEIGHT / height);
                    height = IMAGE_MAX_HEIGHT;
                }

                log.debug("Resizing to {}x{}", width, height);
                BufferedImage resizedImg = resize(src, width, height, ResizeOptions.defaults());
                byte[] resizedBytes = encodePng(resizedImg);

                // If still too large after resize, try compression
                if (resizedBytes.length > IMAGE_TARGET_RAW_SIZE) {
                    if (isPng) {
                        byte[] pngComp = encodePng(resizedImg);
                        if (pngComp.length <= IMAGE_TARGET_RAW_SIZE) {
                            return new ResizeResult(pngComp, "png",
                                    new ImageDimensions(originalWidth, originalHeight, width, height));
                        }
                    }
                    for (int quality : new int[]{80, 60, 40, 20}) {
                        byte[] jpegComp = encodeJpeg(resizedImg, quality);
                        if (jpegComp.length <= IMAGE_TARGET_RAW_SIZE) {
                            return new ResizeResult(jpegComp, "jpeg",
                                    new ImageDimensions(originalWidth, originalHeight, width, height));
                        }
                    }
                    // Last resort: shrink further and aggressively compress
                    int smallerWidth  = Math.min(width, 1000);
                    int smallerHeight = Math.round((float) height * smallerWidth / Math.max(width, 1));
                    log.debug("Still too large, compressing with JPEG at 20");
                    BufferedImage smaller = resize(src, smallerWidth, smallerHeight, ResizeOptions.defaults());
                    byte[] compressedBytes = encodeJpeg(smaller, 20);
                    log.debug("JPEG compressed buffer size: {}", compressedBytes.length);
                    return new ResizeResult(compressedBytes, "jpeg",
                            new ImageDimensions(originalWidth, originalHeight, smallerWidth, smallerHeight));
                }

                return new ResizeResult(resizedBytes, normalizedMediaType,
                        new ImageDimensions(originalWidth, originalHeight, width, height));

            } catch (ImageResizeException e) {
                throw e;
            } catch (Exception error) {
                log.error("Image resize error", error);

                // Detect actual format from magic bytes
                String detected = detectImageMediaTypeFromBytes(imageBytes);

                // Calculate the base64 size
                long base64Size = (long) Math.ceil((originalSize * 4.0) / 3.0);

                // Check for over-dimension PNG via raw header bytes
                boolean overDim = imageBytes.length >= 24
                        && (imageBytes[0] & 0xFF) == 0x89
                        && (imageBytes[1] & 0xFF) == 0x50
                        && (imageBytes[2] & 0xFF) == 0x4e
                        && (imageBytes[3] & 0xFF) == 0x47
                        && (readUInt32BE(imageBytes, 16) > IMAGE_MAX_WIDTH
                                || readUInt32BE(imageBytes, 20) > IMAGE_MAX_HEIGHT);

                if (base64Size <= API_IMAGE_MAX_BASE64_SIZE && !overDim) {
                    // Strip "image/" prefix to get bare extension
                    return new ResizeResult(imageBytes, detected.substring(6), null);
                }

                String sizeLabel = formatFileSize(originalSize);
                String b64Label  = formatFileSize(base64Size);
                throw new ImageResizeException(overDim
                        ? "Unable to resize image — dimensions exceed the "
                                + IMAGE_MAX_WIDTH + "x" + IMAGE_MAX_HEIGHT
                                + "px limit and image processing failed. "
                                + "Please resize the image to reduce its pixel dimensions."
                        : "Unable to resize image (" + sizeLabel + " raw, " + b64Label + " base64). "
                                + "The image exceeds the 5MB API limit and compression failed. "
                                + "Please resize the image manually or use a smaller image.");
            }
        });
    }

    // -------------------------------------------------------------------------
    // maybeResizeAndDownsampleImageBlock (base64 wrapper)
    // -------------------------------------------------------------------------

    /**
     * Resizes a base64-encoded image if needed.
     * Translated from {@code maybeResizeAndDownsampleImageBlock()} in imageResizer.ts.
     *
     * @param base64Data  base64-encoded image data
     * @param mediaType   declared media type (e.g. "image/png")
     * @return resize result (buffer is base64-encoded in {@link ResizeResult#buffer()})
     */
    public CompletableFuture<ResizeResult> maybeResizeAndDownsampleImageBlock(
            String base64Data, String mediaType) {

        byte[] imageBuffer = Base64.getDecoder().decode(base64Data);
        long originalSize  = imageBuffer.length;
        String ext = mediaType != null ? mediaType.replaceFirst("image/", "") : "png";
        return maybeResizeAndDownsampleImageBuffer(imageBuffer, originalSize, ext);
    }

    // -------------------------------------------------------------------------
    // compressImageBuffer
    // -------------------------------------------------------------------------

    /**
     * Compresses an image buffer to fit within {@code maxBytes}.
     * Translated from {@code compressImageBuffer()} in imageResizer.ts.
     */
    public CompletableFuture<CompressedImageResult> compressImageBuffer(
            byte[] imageBuffer, long maxBytes, String originalMediaType) {

        return CompletableFuture.supplyAsync(() -> {
            String fallbackFormat = originalMediaType != null
                    ? originalMediaType.replaceFirst("image/", "") : "jpeg";
            String normalizedFallback = "jpg".equals(fallbackFormat) ? "jpeg" : fallbackFormat;

            try {
                long originalSize = imageBuffer.length;

                // Already within limit
                if (originalSize <= maxBytes) {
                    return createCompressedResult(imageBuffer, normalizedFallback, originalSize);
                }

                BufferedImage src = decodeImage(imageBuffer);

                // Progressive resizing
                double[] scales = {1.0, 0.75, 0.5, 0.25};
                for (double scale : scales) {
                    int w = Math.max(1, (int) (src.getWidth()  * scale));
                    int h = Math.max(1, (int) (src.getHeight() * scale));
                    BufferedImage resized = resize(src, w, h, new ResizeOptions(true));
                    byte[] buf = encodeWithFormat(resized, normalizedFallback);
                    if (buf.length <= maxBytes) {
                        return createCompressedResult(buf, normalizedFallback, originalSize);
                    }
                }

                // PNG palette optimisation
                if ("png".equals(normalizedFallback)) {
                    BufferedImage r = resize(src, 800, 800, new ResizeOptions(true));
                    byte[] palette = encodePng(r);
                    if (palette.length <= maxBytes) {
                        return createCompressedResult(palette, "png", originalSize);
                    }
                }

                // JPEG at moderate quality
                BufferedImage r600 = resize(src, 600, 600, new ResizeOptions(true));
                byte[] jpeg50 = encodeJpeg(r600, 50);
                if (jpeg50.length <= maxBytes) {
                    return createCompressedResult(jpeg50, "jpeg", originalSize);
                }

                // Last resort: ultra-compressed JPEG
                BufferedImage r400 = resize(src, 400, 400, new ResizeOptions(true));
                byte[] ultra = encodeJpeg(r400, 20);
                return createCompressedResult(ultra, "jpeg", originalSize);

            } catch (Exception error) {
                log.error("Image compression error", error);
                if (imageBuffer.length <= maxBytes) {
                    String detected = detectImageMediaTypeFromBytes(imageBuffer);
                    return new CompressedImageResult(
                            Base64.getEncoder().encodeToString(imageBuffer),
                            detected,
                            imageBuffer.length);
                }
                throw new ImageResizeException(
                        "Unable to compress image (" + formatFileSize(imageBuffer.length)
                        + ") to fit within " + formatFileSize(maxBytes)
                        + ". Please use a smaller image.");
            }
        });
    }

    /**
     * Compresses an image buffer to fit within a token-based limit.
     * Translated from {@code compressImageBufferWithTokenLimit()} in imageResizer.ts.
     */
    public CompletableFuture<CompressedImageResult> compressImageBufferWithTokenLimit(
            byte[] imageBuffer, int maxTokens, String originalMediaType) {
        // base64 chars = maxTokens / 0.125; raw bytes = base64Chars * 0.75
        long maxBase64Chars = (long) (maxTokens / 0.125);
        long maxBytes       = (long) (maxBase64Chars * 0.75);
        return compressImageBuffer(imageBuffer, maxBytes, originalMediaType);
    }

    // -------------------------------------------------------------------------
    // detectImageFormatFromBuffer / detectImageFormatFromBase64
    // -------------------------------------------------------------------------

    /**
     * Detects image media type from magic bytes in the buffer.
     * Translated from {@code detectImageFormatFromBuffer()} in imageResizer.ts.
     */
    public String detectImageMediaTypeFromBytes(byte[] buffer) {
        if (buffer == null || buffer.length < 4) return "image/png";

        int b0 = buffer[0] & 0xFF;
        int b1 = buffer[1] & 0xFF;
        int b2 = buffer[2] & 0xFF;
        int b3 = buffer[3] & 0xFF;

        // PNG: 89 50 4E 47
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4e && b3 == 0x47) return "image/png";

        // JPEG: FF D8 FF
        if (b0 == 0xff && b1 == 0xd8 && b2 == 0xff) return "image/jpeg";

        // GIF: 47 49 46
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) return "image/gif";

        // WebP: RIFF....WEBP
        if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46
                && buffer.length >= 12
                && (buffer[8] & 0xFF) == 0x57
                && (buffer[9] & 0xFF) == 0x45
                && (buffer[10] & 0xFF) == 0x42
                && (buffer[11] & 0xFF) == 0x50) {
            return "image/webp";
        }

        return "image/png";
    }

    /**
     * Detects image media type from a base64-encoded string.
     * Translated from {@code detectImageFormatFromBase64()} in imageResizer.ts.
     */
    public String detectImageMediaTypeFromBase64(String base64Data) {
        try {
            byte[] buffer = Base64.getDecoder().decode(base64Data);
            return detectImageMediaTypeFromBytes(buffer);
        } catch (Exception e) {
            return "image/png";
        }
    }

    // -------------------------------------------------------------------------
    // createImageMetadataText
    // -------------------------------------------------------------------------

    /**
     * Creates a textual description of image metadata suitable for inclusion in prompts.
     * Translated from {@code createImageMetadataText()} in imageResizer.ts.
     *
     * @return metadata string, or {@code null} if no useful info is available
     */
    public String createImageMetadataText(ImageDimensions dims, String sourcePath) {
        Integer ow = dims.originalWidth();
        Integer oh = dims.originalHeight();
        Integer dw = dims.displayWidth();
        Integer dh = dims.displayHeight();

        if (ow == null || oh == null || dw == null || dh == null
                || dw <= 0 || dh <= 0) {
            return sourcePath != null ? "[Image source: " + sourcePath + "]" : null;
        }

        boolean wasResized = !ow.equals(dw) || !oh.equals(dh);
        if (!wasResized && sourcePath == null) return null;

        StringBuilder sb = new StringBuilder("[Image: ");
        boolean needsComma = false;

        if (sourcePath != null) {
            sb.append("source: ").append(sourcePath);
            needsComma = true;
        }

        if (wasResized) {
            if (needsComma) sb.append(", ");
            double scaleFactor = (double) ow / dw;
            sb.append("original ").append(ow).append('x').append(oh)
              .append(", displayed at ").append(dw).append('x').append(dh)
              .append(". Multiply coordinates by ")
              .append(String.format("%.2f", scaleFactor))
              .append(" to map to original image.");
        }

        sb.append(']');
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Low-level image operations (also used by createImage path)
    // -------------------------------------------------------------------------

    /** Reads an image from raw bytes and returns its metadata asynchronously. */
    public CompletableFuture<ImageMetadata> getMetadata(byte[] imageBytes) {
        return CompletableFuture.supplyAsync(() -> {
            try (ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes)) {
                BufferedImage img = ImageIO.read(bais);
                if (img == null) throw new IllegalArgumentException("Unable to decode image data");
                return new ImageMetadata(img.getWidth(), img.getHeight(), "unknown");
            } catch (IOException e) {
                throw new RuntimeException("Failed to read image metadata", e);
            }
        });
    }

    /** Resize to JPEG asynchronously. */
    public CompletableFuture<byte[]> resizeToJpeg(
            byte[] imageBytes, int targetWidth, int targetHeight,
            ResizeOptions options, Integer quality) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                BufferedImage src = decodeImage(imageBytes);
                BufferedImage res = resize(src, targetWidth, targetHeight, options);
                return encodeJpeg(res, quality == null ? 80 : quality);
            } catch (IOException e) {
                throw new RuntimeException("Failed to resize image to JPEG", e);
            }
        });
    }

    /** Resize to PNG asynchronously. */
    public CompletableFuture<byte[]> resizeToPng(
            byte[] imageBytes, int targetWidth, int targetHeight, ResizeOptions options) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                BufferedImage src = decodeImage(imageBytes);
                BufferedImage res = resize(src, targetWidth, targetHeight, options);
                return encodePng(res);
            } catch (IOException e) {
                throw new RuntimeException("Failed to resize image to PNG", e);
            }
        });
    }

    /** Create a solid-colour image. */
    public CompletableFuture<byte[]> createImage(
            int width, int height, int channels, int r, int g, int b) {
        return CompletableFuture.supplyAsync(() -> {
            int imageType = (channels == 4) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
            BufferedImage img = new BufferedImage(width, height, imageType);
            Graphics2D g2 = img.createGraphics();
            try {
                g2.setColor(new java.awt.Color(r, g, b));
                g2.fillRect(0, 0, width, height);
            } finally {
                g2.dispose();
            }
            try {
                return encodePng(img);
            } catch (IOException e) {
                throw new RuntimeException("Failed to encode created image", e);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private BufferedImage decodeImage(byte[] bytes) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
            BufferedImage img = ImageIO.read(bais);
            if (img == null) throw new IllegalArgumentException("Unable to decode image data");
            return img;
        }
    }

    private BufferedImage resize(BufferedImage src, int targetWidth, int targetHeight,
                                  ResizeOptions options) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        if (options != null && options.withoutEnlargement() && srcW <= targetWidth && srcH <= targetHeight) {
            return src;
        }
        double scale = Math.min((double) targetWidth / srcW, (double) targetHeight / srcH);
        int newW = Math.max(1, (int) (srcW * scale));
        int newH = Math.max(1, (int) (srcH * scale));
        Image scaled = src.getScaledInstance(newW, newH, Image.SCALE_SMOOTH);
        BufferedImage out = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = out.createGraphics();
        try {
            g2.drawImage(scaled, 0, 0, null);
        } finally {
            g2.dispose();
        }
        return out;
    }

    private byte[] encodeWithFormat(BufferedImage img, String format) throws IOException {
        return switch (format) {
            case "jpeg", "jpg" -> encodeJpeg(img, 80);
            default            -> encodePng(img);
        };
    }

    private byte[] encodeJpeg(BufferedImage img, int quality) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam params = writer.getDefaultWriteParam();
        params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        params.setCompressionQuality(quality / 100.0f);
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), params);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }

    private byte[] encodePng(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private CompressedImageResult createCompressedResult(byte[] buffer, String format, long originalSize) {
        String normalized = "jpg".equals(format) ? "jpeg" : format;
        return new CompressedImageResult(
                Base64.getEncoder().encodeToString(buffer),
                "image/" + normalized,
                originalSize);
    }

    private String detectFormat(byte[] imageBytes, String ext) {
        String detected = detectImageMediaTypeFromBytes(imageBytes);
        // If detection returned default "image/png" and we have an explicit ext, use that
        String stripped = detected.replaceFirst("image/", "");
        return "png".equals(stripped) && ext != null && !ext.isEmpty() ? ext : stripped;
    }

    private long readUInt32BE(byte[] buf, int offset) {
        return (((long)(buf[offset]     & 0xFF)) << 24)
             | (((long)(buf[offset + 1] & 0xFF)) << 16)
             | (((long)(buf[offset + 2] & 0xFF)) <<  8)
             |  ((long)(buf[offset + 3] & 0xFF));
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
