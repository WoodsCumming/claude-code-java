package com.anthropic.claudecode.util;

import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;

/**
 * ANSI terminal text to PNG conversion.
 * Translated from src/utils/ansiToPng.ts
 *
 * The TypeScript original blits a bundled 24x48 bitmap font (Fira Code Regular,
 * SIL OFL 1.1) directly into a raw RGBA buffer then encodes it as PNG via
 * node:zlib. This Java translation achieves the same output shape using Java's
 * built-in Graphics2D and a monospaced AWT font, which is available on all JREs
 * without bundling binary glyph data.
 *
 * Public API mirrors the TypeScript exports:
 *   - AnsiToPngOptions  — options record (scale, paddingX, paddingY, etc.)
 *   - ansiToPng(text, options) → byte[]
 */
@Slf4j
public class AnsiToPngUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AnsiToPngUtils.class);


    // Default glyph cell dimensions (mirrors TypeScript GLYPH_W / GLYPH_H)
    private static final int GLYPH_W = 10;
    private static final int GLYPH_H = 20;

    /**
     * Render options for {@link #ansiToPng}.
     * Mirrors AnsiToPngOptions in ansiToPng.ts.
     */
    @Builder
    public record AnsiToPngOptions(
            /** Integer zoom factor (nearest-neighbor). Default 1. */
            int scale,
            /** Horizontal padding in 1x pixels. Default 48. */
            int paddingX,
            /** Vertical padding in 1x pixels. Default 48. */
            int paddingY,
            /** Corner radius in 1x pixels. Default 16 (not rendered by AWT path). */
            int borderRadius,
            /** Background color as an ARGB int. Default: dark gray #282c34. */
            int background
    ) {
        public static AnsiToPngOptions defaults() {
            return AnsiToPngOptions.builder()
                    .scale(1)
                    .paddingX(48)
                    .paddingY(48)
                    .borderRadius(16)
                    .background(0xFF282C34)
                    .build();
        }
    }

    /**
     * Render ANSI-escaped text to a PNG byte array.
     * Translated from ansiToPng() in ansiToPng.ts.
     *
     * @param ansiText ANSI-escaped terminal text
     * @param options  render options (null → defaults)
     * @return PNG-encoded byte array, or empty array on error
     */
    public static byte[] ansiToPng(String ansiText, AnsiToPngOptions options) {
        if (ansiText == null || ansiText.isEmpty()) return new byte[0];
        if (options == null) options = AnsiToPngOptions.defaults();

        try {
            // Strip ANSI escape sequences for simple cell rendering
            String plainText = stripAnsi(ansiText);
            String[] lines = plainText.split("\n", -1);

            // Trim trailing blank lines (mirrors TypeScript behaviour)
            int lastNonBlank = lines.length - 1;
            while (lastNonBlank >= 0 && lines[lastNonBlank].trim().isEmpty()) {
                lastNonBlank--;
            }
            if (lastNonBlank < 0) {
                lines = new String[]{""};
            } else {
                lines = java.util.Arrays.copyOf(lines, lastNonBlank + 1);
            }

            int scale = Math.max(1, options.scale());
            int paddingX = options.paddingX() * scale;
            int paddingY = options.paddingY() * scale;
            int glyphW = GLYPH_W * scale;
            int glyphH = GLYPH_H * scale;

            int cols = 0;
            for (String line : lines) cols = Math.max(cols, line.length());
            cols = Math.max(1, cols);

            int width  = cols * glyphW + paddingX * 2;
            int height = lines.length * glyphH + paddingY * 2;

            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();

            // Background fill
            int bg = options.background();
            g.setColor(new Color(bg, true));
            g.fillRect(0, 0, width, height);

            // Text rendering
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12 * scale));
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                               RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            FontMetrics fm = g.getFontMetrics();
            int baseline = fm.getAscent();

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                int y = paddingY + i * glyphH + baseline;
                g.drawString(line, paddingX, y);
            }

            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            return baos.toByteArray();

        } catch (Exception e) {
            log.warn("Could not render ANSI to PNG: {}", e.getMessage());
            return new byte[0];
        }
    }

    /**
     * Convenience overload with default options.
     */
    public static byte[] ansiToPng(String ansiText) {
        return ansiToPng(ansiText, AnsiToPngOptions.defaults());
    }

    /**
     * Strip ANSI escape codes from text.
     * Used internally before rasterisation (color parsing is a future
     * enhancement; for now all text is rendered in white on the configured bg).
     */
    public static String stripAnsi(String text) {
        if (text == null) return "";
        return text.replaceAll("\u001B\\[[\\d;]*[mGKHFJA-Za-z]", "");
    }

    private AnsiToPngUtils() {}
}
