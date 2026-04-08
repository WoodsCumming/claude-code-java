package com.anthropic.claudecode.util.ink;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Java equivalent of screen.ts.
 *
 * Provides the packed cell-buffer (Screen), shared interning pools
 * (CharPool, HyperlinkPool, StylePool) and all mutation / query helpers
 * that the render and diff pipelines depend on.
 *
 * The TypeScript source stores each cell as two consecutive Int32 elements:
 *   word0 = charId
 *   word1 = styleId[31:17] | hyperlinkId[16:2] | width[1:0]
 *
 * Java uses a flat int[] with the same 2-int-per-cell layout.
 */
public final class InkScreen {

    private InkScreen() {}

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    static final int STYLE_SHIFT   = 17;
    static final int HYPERLINK_SHIFT = 2;
    static final int HYPERLINK_MASK = 0x7FFF; // 15 bits
    static final int WIDTH_MASK    = 3;       // 2 bits

    // -----------------------------------------------------------------------
    // CellWidth
    // -----------------------------------------------------------------------

    /** Terminal cell-width classification. Mirrors the TS const-enum CellWidth. */
    public enum CellWidth {
        NARROW(0), WIDE(1), SPACER_TAIL(2), SPACER_HEAD(3);

        public final int value;
        CellWidth(int v) { this.value = v; }

        public static CellWidth of(int v) {
            return switch (v & WIDTH_MASK) {
                case 0 -> NARROW;
                case 1 -> WIDE;
                case 2 -> SPACER_TAIL;
                default -> SPACER_HEAD;
            };
        }
    }

    // -----------------------------------------------------------------------
    // Cell
    // -----------------------------------------------------------------------

    /** View object for one cell. Created on-demand; not stored in the buffer. */
    public static final class Cell {
        public String    charStr;
        public int       styleId;
        public CellWidth width;
        @Nullable public String hyperlink;

        Cell() { charStr = " "; styleId = 0; width = CellWidth.NARROW; }

        /** Mutable copy constructor used by diff(). */
        public Cell copy() {
            Cell c = new Cell();
            c.charStr = charStr; c.styleId = styleId;
            c.width = width;     c.hyperlink = hyperlink;
            return c;
        }
    }

    // -----------------------------------------------------------------------
    // CharPool
    // -----------------------------------------------------------------------

    /**
     * Shared string-interning pool for cell characters.
     * Index 0 = space (' '), index 1 = empty string (spacer).
     */
    public static final class CharPool {
        private final List<String> strings = new ArrayList<>(List.of(" ", ""));
        private final Map<String, Integer> map = new HashMap<>(Map.of(" ", 0, "", 1));
        private final int[] ascii = new int[128]; // charCode -> index, -1 = not interned

        public CharPool() { Arrays.fill(ascii, -1); ascii[32] = 0; }

        public int intern(String ch) {
            if (ch.length() == 1) {
                char c = ch.charAt(0);
                if (c < 128) {
                    int cached = ascii[c];
                    if (cached != -1) return cached;
                    int idx = strings.size();
                    strings.add(ch);
                    ascii[c] = idx;
                    return idx;
                }
            }
            Integer existing = map.get(ch);
            if (existing != null) return existing;
            int idx = strings.size();
            strings.add(ch);
            map.put(ch, idx);
            return idx;
        }

        public String get(int index) {
            return (index >= 0 && index < strings.size()) ? strings.get(index) : " ";
        }
    }

    // -----------------------------------------------------------------------
    // HyperlinkPool
    // -----------------------------------------------------------------------

    /** Shared interning pool for hyperlink URIs. Index 0 = no hyperlink. */
    public static final class HyperlinkPool {
        private final List<String> strings = new ArrayList<>(Collections.singletonList(""));
        private final Map<String, Integer> map = new HashMap<>();

        public int intern(@Nullable String hyperlink) {
            if (hyperlink == null || hyperlink.isEmpty()) return 0;
            Integer id = map.get(hyperlink);
            if (id != null) return id;
            id = strings.size();
            strings.add(hyperlink);
            map.put(hyperlink, id);
            return id;
        }

        @Nullable public String get(int id) {
            return id == 0 ? null : (id < strings.size() ? strings.get(id) : null);
        }
    }

    // -----------------------------------------------------------------------
    // StylePool
    // -----------------------------------------------------------------------

    /**
     * Shared pool for ANSI style codes.
     *
     * Styles are stored as opaque String keys (the concatenation of SGR codes).
     * The pool returns integer IDs with bit-0 indicating visible-on-space effect.
     * Transition strings (fromId -> toId) are cached to avoid repeated ANSI math.
     *
     * In the Java translation we use String-keyed caches.
     * The full AnsiCode diff logic from @alcalzone/ansi-tokenize is out of scope —
     * callers supply pre-computed transition strings.
     */
    public static final class StylePool {
        /** Opaque ID for "no style" / empty. Always 0. */
        public final int none;

        private final Map<String, Integer> ids = new LinkedHashMap<>();
        private final List<String> styles = new ArrayList<>();
        private final Map<Long, String> transitionCache = new HashMap<>();

        public StylePool() {
            none = intern("", false);
        }

        /**
         * Intern a style key.
         * @param key   opaque string key (e.g. SGR codes joined by \0)
         * @param visibleOnSpace true if this style has visible effect on spaces
         * @return encoded ID (bit-0 = visibleOnSpace flag)
         */
        public int intern(String key, boolean visibleOnSpace) {
            Integer id = ids.get(key);
            if (id != null) return id;
            int rawId = styles.size();
            styles.add(key);
            id = (rawId << 1) | (visibleOnSpace ? 1 : 0);
            ids.put(key, id);
            return id;
        }

        /** Recover the style key from an encoded ID. */
        public String get(int id) {
            int rawId = id >>> 1;
            return (rawId >= 0 && rawId < styles.size()) ? styles.get(rawId) : "";
        }

        /**
         * Return the ANSI transition string from {@code fromId} to {@code toId}.
         * Cached per (fromId, toId) pair. Callers may override to provide computed diffs.
         * Default implementation returns empty string (no-op transition).
         */
        public String transition(int fromId, int toId) {
            if (fromId == toId) return "";
            long key = ((long) fromId << 32) | (toId & 0xFFFFFFFFL);
            return transitionCache.getOrDefault(key, "");
        }

        /** Register a pre-computed transition string. */
        public void putTransition(int fromId, int toId, String str) {
            long key = ((long) fromId << 32) | (toId & 0xFFFFFFFFL);
            transitionCache.put(key, str);
        }

        /** Return a new style ID that adds inverse (SGR 7) to {@code baseId}. */
        public int withInverse(int baseId) {
            String base = get(baseId);
            return intern(base + "\u001b[7m", true);
        }

        /** Return a new style ID for the current search match highlight. */
        public int withCurrentMatch(int baseId) {
            String base = get(baseId);
            return intern(base + "\u001b[33m\u001b[7m\u001b[1m\u001b[4m", true);
        }

        /** Return a new style ID with the selection background applied. */
        public int withSelectionBg(int baseId) {
            return withInverse(baseId);
        }
    }

    // -----------------------------------------------------------------------
    // Screen
    // -----------------------------------------------------------------------

    /**
     * Packed cell buffer for one rendered frame.
     * Mirrors the TypeScript Screen type. Cells are stored as int[size*2]:
     *   cells[ci]   = charId
     *   cells[ci+1] = (styleId << STYLE_SHIFT) | (hyperlinkId << HYPERLINK_SHIFT) | width
     */
    public static final class Screen {
        public int width;
        public int height;
        public int[] cells;       // 2 ints per cell
        public byte[] noSelect;   // 1 byte per cell
        public int[] softWrap;    // 1 int per row
        public CharPool charPool;
        public HyperlinkPool hyperlinkPool;
        public int emptyStyleId;
        @Nullable public Rectangle damage;

        Screen(int width, int height, StylePool styles, CharPool charPool, HyperlinkPool hyperlinkPool) {
            this.width = width; this.height = height;
            this.charPool = charPool; this.hyperlinkPool = hyperlinkPool;
            this.emptyStyleId = styles.none;
            int size = width * height;
            this.cells = new int[size * 2];
            this.noSelect = new byte[size];
            this.softWrap = new int[height];
        }
    }

    // -----------------------------------------------------------------------
    // Rectangle / geometry helpers
    // -----------------------------------------------------------------------

    public record Rectangle(int x, int y, int width, int height) {
        public Rectangle union(Rectangle other) {
            int x1 = Math.min(x, other.x);
            int y1 = Math.min(y, other.y);
            int x2 = Math.max(x + width, other.x + other.width);
            int y2 = Math.max(y + height, other.y + other.height);
            return new Rectangle(x1, y1, x2 - x1, y2 - y1);
        }
    }

    // -----------------------------------------------------------------------
    // Factory
    // -----------------------------------------------------------------------

    public static Screen createScreen(int width, int height, StylePool styles,
                                      CharPool charPool, HyperlinkPool hyperlinkPool) {
        width  = Math.max(0, width);
        height = Math.max(0, height);
        return new Screen(width, height, styles, charPool, hyperlinkPool);
    }

    public static void resetScreen(Screen screen, int width, int height) {
        width  = Math.max(0, width);
        height = Math.max(0, height);
        int size = width * height;
        if (screen.cells.length < size * 2)   screen.cells     = new int[size * 2];
        if (screen.noSelect.length < size)     screen.noSelect  = new byte[size];
        if (screen.softWrap.length < height)   screen.softWrap  = new int[height];
        Arrays.fill(screen.cells, 0, size * 2, 0);
        Arrays.fill(screen.noSelect, 0, size, (byte) 0);
        Arrays.fill(screen.softWrap, 0, height, 0);
        screen.width = width; screen.height = height; screen.damage = null;
    }

    // -----------------------------------------------------------------------
    // Cell accessors
    // -----------------------------------------------------------------------

    private static int packWord1(int styleId, int hyperlinkId, int width) {
        return (styleId << STYLE_SHIFT) | (hyperlinkId << HYPERLINK_SHIFT) | width;
    }

    @Nullable public static Cell cellAt(Screen screen, int x, int y) {
        if (x < 0 || y < 0 || x >= screen.width || y >= screen.height) return null;
        return cellAtIndex(screen, y * screen.width + x);
    }

    public static Cell cellAtIndex(Screen screen, int index) {
        Cell c = new Cell();
        fillCellAtIndex(screen, index, c);
        return c;
    }

    static void fillCellAtIndex(Screen screen, int index, Cell out) {
        int ci = index << 1;
        int word1 = screen.cells[ci + 1];
        int hid = (word1 >>> HYPERLINK_SHIFT) & HYPERLINK_MASK;
        out.charStr   = screen.charPool.get(screen.cells[ci]);
        out.styleId   = word1 >>> STYLE_SHIFT;
        out.width     = CellWidth.of(word1);
        out.hyperlink = hid == 0 ? null : screen.hyperlinkPool.get(hid);
    }

    @Nullable public static String charInCellAt(Screen screen, int x, int y) {
        if (x < 0 || y < 0 || x >= screen.width || y >= screen.height) return null;
        int ci = (y * screen.width + x) << 1;
        return screen.charPool.get(screen.cells[ci]);
    }

    public static boolean isEmptyCellAt(Screen screen, int x, int y) {
        if (x < 0 || y < 0 || x >= screen.width || y >= screen.height) return true;
        int ci = (y * screen.width + x) << 1;
        return screen.cells[ci] == 0 && screen.cells[ci + 1] == 0;
    }

    // -----------------------------------------------------------------------
    // setCellAt
    // -----------------------------------------------------------------------

    public static void setCellAt(Screen screen, int x, int y, Cell cell) {
        if (x < 0 || y < 0 || x >= screen.width || y >= screen.height) return;
        int ci = (y * screen.width + x) << 1;
        int[] cells = screen.cells;

        // Clear orphaned SpacerTail if overwriting Wide with non-Wide
        int prevWidth = cells[ci + 1] & WIDTH_MASK;
        if (prevWidth == CellWidth.WIDE.value && cell.width != CellWidth.WIDE) {
            int spacerX = x + 1;
            if (spacerX < screen.width) {
                int spacerCI = ci + 2;
                if ((cells[spacerCI + 1] & WIDTH_MASK) == CellWidth.SPACER_TAIL.value) {
                    cells[spacerCI] = 0;
                    cells[spacerCI + 1] = packWord1(screen.emptyStyleId, 0, CellWidth.NARROW.value);
                }
            }
        }

        // Clear orphaned Wide if overwriting SpacerTail with non-SpacerTail
        int clearedWideX = -1;
        if (prevWidth == CellWidth.SPACER_TAIL.value && cell.width != CellWidth.SPACER_TAIL) {
            if (x > 0) {
                int wideCI = ci - 2;
                if ((cells[wideCI + 1] & WIDTH_MASK) == CellWidth.WIDE.value) {
                    cells[wideCI] = 0;
                    cells[wideCI + 1] = packWord1(screen.emptyStyleId, 0, CellWidth.NARROW.value);
                    clearedWideX = x - 1;
                }
            }
        }

        cells[ci]     = screen.charPool.intern(cell.charStr);
        cells[ci + 1] = packWord1(cell.styleId,
                                   screen.hyperlinkPool.intern(cell.hyperlink),
                                   cell.width.value);

        // Expand damage
        int minX = clearedWideX >= 0 ? Math.min(x, clearedWideX) : x;
        Rectangle d = screen.damage;
        if (d != null) {
            screen.damage = d.union(new Rectangle(minX, y, x - minX + 1, 1));
        } else {
            screen.damage = new Rectangle(minX, y, x - minX + 1, 1);
        }

        // Create SpacerTail for Wide chars
        if (cell.width == CellWidth.WIDE) {
            int spacerX = x + 1;
            if (spacerX < screen.width) {
                int spacerCI = ci + 2;
                cells[spacerCI] = 1; // SPACER_CHAR_INDEX
                cells[spacerCI + 1] = packWord1(screen.emptyStyleId, 0, CellWidth.SPACER_TAIL.value);
                Rectangle dam = screen.damage;
                if (dam != null && spacerX >= dam.x() + dam.width()) {
                    screen.damage = new Rectangle(dam.x(), dam.y(), spacerX - dam.x() + 1, dam.height());
                }
            }
        }
    }

    public static void setCellStyleId(Screen screen, int x, int y, int styleId) {
        if (x < 0 || y < 0 || x >= screen.width || y >= screen.height) return;
        int ci = (y * screen.width + x) << 1;
        int word1 = screen.cells[ci + 1];
        int width = word1 & WIDTH_MASK;
        if (width == CellWidth.SPACER_TAIL.value || width == CellWidth.SPACER_HEAD.value) return;
        int hid = (word1 >>> HYPERLINK_SHIFT) & HYPERLINK_MASK;
        screen.cells[ci + 1] = packWord1(styleId, hid, width);
        Rectangle d = screen.damage;
        Rectangle r = new Rectangle(x, y, 1, 1);
        screen.damage = (d != null) ? d.union(r) : r;
    }

    // -----------------------------------------------------------------------
    // blitRegion
    // -----------------------------------------------------------------------

    public static void blitRegion(Screen dst, Screen src,
                                  int regionX, int regionY, int maxX, int maxY) {
        regionX = Math.max(0, regionX);
        regionY = Math.max(0, regionY);
        if (regionX >= maxX || regionY >= maxY) return;

        int rowLen = maxX - regionX;
        int srcStride = src.width * 2;
        int dstStride = dst.width * 2;
        int rowInts = rowLen * 2;

        // softWrap is per-row
        System.arraycopy(src.softWrap, regionY, dst.softWrap, regionY, maxY - regionY);

        // Full-width fast path
        if (regionX == 0 && maxX == src.width && src.width == dst.width) {
            int srcStart = regionY * srcStride;
            int totalInts = (maxY - regionY) * srcStride;
            System.arraycopy(src.cells, srcStart, dst.cells, srcStart, totalInts);
            int nsStart = regionY * src.width;
            int nsLen   = (maxY - regionY) * src.width;
            System.arraycopy(src.noSelect, nsStart, dst.noSelect, nsStart, nsLen);
        } else {
            int srcRowCI  = regionY * srcStride + regionX * 2;
            int dstRowCI  = regionY * dstStride + regionX * 2;
            int srcRowNS  = regionY * src.width + regionX;
            int dstRowNS  = regionY * dst.width + regionX;
            for (int y = regionY; y < maxY; y++) {
                System.arraycopy(src.cells,    srcRowCI, dst.cells,    dstRowCI, rowInts);
                System.arraycopy(src.noSelect, srcRowNS, dst.noSelect, dstRowNS, rowLen);
                srcRowCI += srcStride; dstRowCI += dstStride;
                srcRowNS += src.width; dstRowNS += dst.width;
            }
        }

        Rectangle regionRect = new Rectangle(regionX, regionY, rowLen, maxY - regionY);
        screen_expandDamage(dst, regionRect);
    }

    private static void screen_expandDamage(Screen screen, Rectangle r) {
        screen.damage = (screen.damage != null) ? screen.damage.union(r) : r;
    }

    // -----------------------------------------------------------------------
    // clearRegion
    // -----------------------------------------------------------------------

    public static void clearRegion(Screen screen, int regionX, int regionY,
                                   int regionWidth, int regionHeight) {
        int startX = Math.max(0, regionX);
        int startY = Math.max(0, regionY);
        int maxX   = Math.min(regionX + regionWidth,  screen.width);
        int maxY   = Math.min(regionY + regionHeight, screen.height);
        if (startX >= maxX || startY >= maxY) return;

        int[] cells = screen.cells;
        int sw = screen.width;

        if (startX == 0 && maxX == sw) {
            int from = startY * sw * 2;
            int to   = maxY  * sw * 2;
            Arrays.fill(cells, from, to, 0);
        } else {
            int stride = sw * 2;
            int rowLen = maxX - startX;
            int rowInts = rowLen * 2;
            int rowCI = startY * stride + startX * 2;
            for (int y = startY; y < maxY; y++) {
                Arrays.fill(cells, rowCI, rowCI + rowInts, 0);
                rowCI += stride;
            }
        }
        Arrays.fill(screen.noSelect, startY * sw, maxY * sw, (byte) 0);
        Rectangle regionRect = new Rectangle(startX, startY, maxX - startX, maxY - startY);
        screen_expandDamage(screen, regionRect);
    }

    // -----------------------------------------------------------------------
    // shiftRows
    // -----------------------------------------------------------------------

    public static void shiftRows(Screen screen, int top, int bottom, int n) {
        if (n == 0 || top < 0 || bottom >= screen.height || top > bottom) return;
        int w = screen.width;
        int absN = Math.abs(n);
        if (absN > bottom - top) {
            Arrays.fill(screen.cells,    top * w * 2, (bottom + 1) * w * 2, 0);
            Arrays.fill(screen.noSelect, top * w,     (bottom + 1) * w,     (byte) 0);
            Arrays.fill(screen.softWrap, top,          bottom + 1,            0);
            return;
        }
        if (n > 0) {
            // Scroll up: move top+n..bottom -> top..bottom-n
            System.arraycopy(screen.cells, (top + n) * w * 2, screen.cells, top * w * 2,
                             (bottom - top - n + 1) * w * 2);
            System.arraycopy(screen.noSelect, (top + n) * w, screen.noSelect, top * w,
                             (bottom - top - n + 1) * w);
            System.arraycopy(screen.softWrap, top + n, screen.softWrap, top, bottom - top - n + 1);
            Arrays.fill(screen.cells,    (bottom - n + 1) * w * 2, (bottom + 1) * w * 2, 0);
            Arrays.fill(screen.noSelect, (bottom - n + 1) * w,     (bottom + 1) * w,     (byte) 0);
            Arrays.fill(screen.softWrap,  bottom - n + 1,           bottom + 1,            0);
        } else {
            int aN = -n;
            // Scroll down: move top..bottom+n -> top+aN..bottom
            System.arraycopy(screen.cells, top * w * 2, screen.cells, (top + aN) * w * 2,
                             (bottom - top - aN + 1) * w * 2);
            System.arraycopy(screen.noSelect, top * w, screen.noSelect, (top + aN) * w,
                             (bottom - top - aN + 1) * w);
            System.arraycopy(screen.softWrap, top, screen.softWrap, top + aN, bottom - top - aN + 1);
            Arrays.fill(screen.cells,    top * w * 2, (top + aN) * w * 2, 0);
            Arrays.fill(screen.noSelect, top * w,     (top + aN) * w,     (byte) 0);
            Arrays.fill(screen.softWrap, top,          top + aN,           0);
        }
    }

    // -----------------------------------------------------------------------
    // diff / diffEach
    // -----------------------------------------------------------------------

    @FunctionalInterface
    public interface DiffCallback {
        /** Return true to stop early. */
        boolean onDiff(int x, int y, @Nullable Cell removed, @Nullable Cell added);
    }

    public static boolean diffEach(Screen prev, Screen next, DiffCallback cb) {
        int prevW = prev.width, nextW = next.width;
        int prevH = prev.height, nextH = next.height;

        Rectangle region;
        if (prevW == 0 && prevH == 0) {
            region = new Rectangle(0, 0, nextW, nextH);
        } else if (next.damage != null) {
            region = next.damage;
            if (prev.damage != null) region = region.union(prev.damage);
        } else if (prev.damage != null) {
            region = prev.damage;
        } else {
            region = new Rectangle(0, 0, 0, 0);
        }

        if (prevH > nextH)
            region = region.union(new Rectangle(0, nextH, prevW, prevH - nextH));
        if (prevW > nextW)
            region = region.union(new Rectangle(nextW, 0, prevW - nextW, prevH));

        int maxH   = Math.max(prevH, nextH);
        int maxW   = Math.max(prevW, nextW);
        int endY   = Math.min(region.y() + region.height(), maxH);
        int endX   = Math.min(region.x() + region.width(),  maxW);

        Cell prevCell = new Cell();
        Cell nextCell = new Cell();

        for (int y = region.y(); y < endY; y++) {
            boolean prevIn = y < prevH;
            boolean nextIn = y < nextH;

            for (int x = region.x(); x < endX; x++) {
                boolean prevXIn = prevIn && x < prevW;
                boolean nextXIn = nextIn && x < nextW;

                int prevW0 = 0, prevW1 = 0, nextW0 = 0, nextW1 = 0;
                if (prevXIn) { int ci = (y * prevW + x) << 1; prevW0 = prev.cells[ci]; prevW1 = prev.cells[ci+1]; }
                if (nextXIn) { int ci = (y * nextW + x) << 1; nextW0 = next.cells[ci]; nextW1 = next.cells[ci+1]; }

                if (prevW0 == nextW0 && prevW1 == nextW1 && prevXIn == nextXIn) continue;

                Cell removed = null, added = null;
                if (prevXIn) { fillCellAtIndex(prev, y * prevW + x, prevCell); removed = prevCell; }
                if (nextXIn) { fillCellAtIndex(next, y * nextW + x, nextCell); added   = nextCell; }

                if (cb.onDiff(x, y, removed, added)) return true;
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Hyperlink style extraction helpers
    // -----------------------------------------------------------------------

    /** OSC 8 prefix string. */
    public static final String OSC8_PREFIX = "\u001b]8" + ";";

    @Nullable public static String extractHyperlinkFromStyles(List<String> styles) {
        for (String code : styles) {
            if (code.length() < 5 || !code.startsWith(OSC8_PREFIX)) continue;
            int belIdx = code.lastIndexOf('\u0007');
            if (belIdx > 0) {
                String payload = code.substring(OSC8_PREFIX.length(), belIdx);
                int sep = payload.indexOf(";;");
                if (sep >= 0) {
                    String uri = payload.substring(sep + 2);
                    return uri.isEmpty() ? null : uri;
                }
            }
        }
        return null;
    }

    public static List<String> filterOutHyperlinkStyles(List<String> styles) {
        List<String> result = new ArrayList<>(styles.size());
        for (String s : styles) {
            if (!s.startsWith(OSC8_PREFIX)) result.add(s);
        }
        return result;
    }
}
