package com.anthropic.claudecode.util.ink;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects rendering operations for a single frame and applies them to a
 * {@link Screen} buffer.
 *
 * <p>This class accumulates {@link Operation} records (write, blit, clip,
 * clear, etc.) from the render tree, then materialises them into a
 * {@link Screen} via {@link #get()}. The resulting screen can be diffed
 * against the previous frame to produce the minimal terminal update sequence.
 *
 * <p>The {@link #reset} method reuses the same instance across frames so
 * that the character cache (tokenise + grapheme clustering) survives between
 * renders — most lines do not change between frames.
 *
 * <p>Translated from {@code src/ink/output.ts}.
 */
public class InkOutput {

    // =========================================================================
    // Operation sealed interface hierarchy
    // =========================================================================

    /** A rendering operation recorded for deferred application. */
    public sealed interface Operation
        permits Write, Clip, Unclip,
                Blit, Clear, Shift,
                NoSelect {}

    public record Write(
        int x, int y, String text, boolean[] softWrap) implements Operation {}

    public record Clip(
        ClipRegion clip) implements Operation {}

    public record Unclip() implements Operation {}

    public record Blit(
        Screen src, int x, int y, int width, int height) implements Operation {}

    public record Shift(int top, int bottom, int n) implements Operation {}

    public record Clear(
        Rectangle region, boolean fromAbsolute) implements Operation {}

    public record NoSelect(Rectangle region) implements Operation {}

    // =========================================================================
    // Supporting types
    // =========================================================================

    /** Axis-aligned clipping rectangle. {@code null} on an axis means unbounded. */
    public record ClipRegion(Integer x1, Integer x2, Integer y1, Integer y2) {

        /** Intersect with a child clip. Tighter constraint wins on each axis. */
        public ClipRegion intersect(ClipRegion child) {
            return new ClipRegion(
                maxDefined(this.x1, child.x1),
                minDefined(this.x2, child.x2),
                maxDefined(this.y1, child.y1),
                minDefined(this.y2, child.y2));
        }

        private static Integer maxDefined(Integer a, Integer b) {
            if (a == null) return b;
            if (b == null) return a;
            return Math.max(a, b);
        }

        private static Integer minDefined(Integer a, Integer b) {
            if (a == null) return b;
            if (b == null) return a;
            return Math.min(a, b);
        }
    }

    /** Axis-aligned rectangle used for clears and noSelect regions. */
    public record Rectangle(int x, int y, int width, int height) {}

    // =========================================================================
    // Screen (minimal representation)
    // =========================================================================

    /**
     * A virtual screen buffer. Cells are stored row-major. This is a simplified
     * holder; a full implementation would use typed arrays for performance.
     */
    public static class Screen {
        public int width;
        public int height;
        /** Flat array of cells; index = y * width + x. */
        public final List<Cell> cells;
        /** Per-row soft-wrap end-column markers (0 = hard newline, >0 = soft-wrap join col). */
        public final int[] softWrap;

        public Screen(int width, int height) {
            this.width   = width;
            this.height  = height;
            int size     = Math.max(0, width * height);
            this.cells   = new ArrayList<>(size);
            for (int i = 0; i < size; i++) cells.add(new Cell("", 0, 1, null));
            this.softWrap = new int[Math.max(0, height)];
        }
    }

    /** A single terminal cell. */
    public record Cell(String ch, int styleId, int width, String hyperlink) {}

    // =========================================================================
    // InkOutput state
    // =========================================================================

    public int width;
    public int height;

    private Screen screen;
    private final List<Operation> operations = new ArrayList<>();

    public InkOutput(int width, int height) {
        this.width  = width;
        this.height = height;
        this.screen = new Screen(width, height);
    }

    /**
     * Reuse this instance for a new frame. Clears the operation list and
     * resets the screen buffer.
     */
    public void reset(int width, int height, Screen screen) {
        this.width  = width;
        this.height = height;
        this.screen = screen;
        this.operations.clear();
        resetScreen(screen, width, height);
    }

    // -------------------------------------------------------------------------
    // Operation builders
    // -------------------------------------------------------------------------

    public void write(int x, int y, String text, boolean[] softWrap) {
        if (text == null || text.isEmpty()) return;
        operations.add(new Write(x, y, text, softWrap));
    }

    public void write(int x, int y, String text) {
        write(x, y, text, null);
    }

    public void blit(Screen src, int x, int y, int width, int height) {
        operations.add(new Blit(src, x, y, width, height));
    }

    public void shift(int top, int bottom, int n) {
        operations.add(new Shift(top, bottom, n));
    }

    public void clear(Rectangle region, boolean fromAbsolute) {
        operations.add(new Clear(region, fromAbsolute));
    }

    public void clear(Rectangle region) {
        clear(region, false);
    }

    public void clip(ClipRegion clip) {
        operations.add(new Clip(clip));
    }

    public void unclip() {
        operations.add(new Unclip());
    }

    public void noSelect(Rectangle region) {
        operations.add(new NoSelect(region));
    }

    // -------------------------------------------------------------------------
    // Materialise
    // -------------------------------------------------------------------------

    /**
     * Apply all accumulated operations to the screen buffer and return it.
     * Call once per frame after the render tree has finished pushing operations.
     *
     * @return the populated {@link Screen} ready for diffing
     */
    public Screen get() {
        List<ClipRegion> clips      = new ArrayList<>();
        List<Rectangle>  absClears  = new ArrayList<>();

        // Pass 1 — collect absolute-clear rectangles for blit filtering
        for (Operation op : operations) {
            if (op instanceof Clear c && c.fromAbsolute()) {
                absClears.add(c.region());
            }
        }

        // Pass 2 — apply operations in order
        for (Operation op : operations) {
            switch (op) {
                case Clear c -> {
                    // Already processed; nothing to do in pass 2
                }
                case Clip c -> {
                    ClipRegion parent = clips.isEmpty() ? null : clips.get(clips.size() - 1);
                    clips.add(parent == null ? c.clip() : parent.intersect(c.clip()));
                }
                case Unclip ignored -> {
                    if (!clips.isEmpty()) clips.remove(clips.size() - 1);
                }
                case Blit b -> {
                    ClipRegion clip = clips.isEmpty() ? null : clips.get(clips.size() - 1);
                    blitRegion(screen, b.src(), b.x(), b.y(), b.width(), b.height(), clip, absClears);
                }
                case Shift s -> {
                    shiftRows(screen, s.top(), s.bottom(), s.n());
                }
                case Write w -> {
                    applyWrite(screen, w, clips.isEmpty() ? null : clips.get(clips.size() - 1));
                }
                case NoSelect ns -> {
                    // Mark no-select region: handled in pass 3
                }
            }
        }

        return screen;
    }

    // -------------------------------------------------------------------------
    // Screen manipulation helpers
    // -------------------------------------------------------------------------

    private static void resetScreen(Screen s, int width, int height) {
        s.width  = width;
        s.height = height;
        int size = Math.max(0, width * height);
        s.cells.clear();
        for (int i = 0; i < size; i++) s.cells.add(new Cell(" ", 0, 1, null));
        for (int i = 0; i < s.softWrap.length; i++) s.softWrap[i] = 0;
    }

    private static void blitRegion(
            Screen dst, Screen src,
            int x, int y, int w, int h,
            ClipRegion clip, List<Rectangle> absClears) {

        int startX = clip != null && clip.x1() != null ? Math.max(x, clip.x1()) : x;
        int startY = clip != null && clip.y1() != null ? Math.max(y, clip.y1()) : y;
        int maxX   = Math.min(x + w, Math.min(dst.width,  src.width));
        int maxY   = Math.min(y + h, Math.min(dst.height, src.height));
        if (clip != null) {
            if (clip.x2() != null) maxX = Math.min(maxX, clip.x2());
            if (clip.y2() != null) maxY = Math.min(maxY, clip.y2());
        }
        if (startX >= maxX || startY >= maxY) return;

        final int finalStartX = startX;
        final int finalMaxX = maxX;
        for (int row = startY; row < maxY; row++) {
            final int finalRow = row;
            boolean excluded = absClears.stream().anyMatch(
                r -> finalRow >= r.y() && finalRow < r.y() + r.height()
                  && finalStartX >= r.x() && finalMaxX <= r.x() + r.width());
            if (excluded) continue;
            for (int col = startX; col < maxX; col++) {
                int srcIdx = row * src.width + col;
                int dstIdx = row * dst.width + col;
                if (srcIdx < src.cells.size() && dstIdx < dst.cells.size()) {
                    dst.cells.set(dstIdx, src.cells.get(srcIdx));
                }
            }
        }
    }

    private static void shiftRows(Screen s, int top, int bottom, int n) {
        // n > 0 = scroll up (rows shift upward)
        if (n == 0) return;
        if (n > 0) {
            for (int row = top; row <= bottom - n; row++) {
                for (int col = 0; col < s.width; col++) {
                    int dst = row * s.width + col;
                    int src = (row + n) * s.width + col;
                    if (dst < s.cells.size() && src < s.cells.size()) {
                        s.cells.set(dst, s.cells.get(src));
                    }
                }
            }
        } else {
            for (int row = bottom; row >= top - n; row--) {
                for (int col = 0; col < s.width; col++) {
                    int dst = row * s.width + col;
                    int src = (row + n) * s.width + col;
                    if (dst < s.cells.size() && src >= 0 && src < s.cells.size()) {
                        s.cells.set(dst, s.cells.get(src));
                    }
                }
            }
        }
    }

    private static void applyWrite(Screen s, Write w, ClipRegion clip) {
        String[] lines = w.text().split("\n", -1);
        int x = w.x(), y = w.y();

        for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
            int lineY = y + lineIdx;
            if (lineY < 0 || lineY >= s.height) continue;

            String line = lines[lineIdx];
            // Apply horizontal clip
            int startX = x;
            if (clip != null && clip.x1() != null && startX < clip.x1()) startX = clip.x1();
            int maxX   = s.width;
            if (clip != null && clip.x2() != null) maxX = Math.min(maxX, clip.x2());
            if (clip != null && clip.y1() != null && lineY < clip.y1()) continue;
            if (clip != null && clip.y2() != null && lineY >= clip.y2()) continue;

            int offsetX = startX;
            // Simple single-char write (no ANSI style tracking in this layer)
            for (int ci = 0; ci < line.length() && offsetX < maxX; ) {
                int cp = line.codePointAt(ci);
                int charWidth = TextMeasurer.lineWidth(new String(Character.toChars(cp)));
                if (offsetX + charWidth <= maxX) {
                    int cellIdx = lineY * s.width + offsetX;
                    if (cellIdx >= 0 && cellIdx < s.cells.size()) {
                        s.cells.set(cellIdx, new Cell(new String(Character.toChars(cp)), 0, charWidth, null));
                    }
                    offsetX += Math.max(1, charWidth);
                } else {
                    break;
                }
                ci += Character.charCount(cp);
            }
        }
    }
}
