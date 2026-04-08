package com.anthropic.claudecode.util.ink;

import lombok.extern.slf4j.Slf4j;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Java equivalent of log-update.ts.
 *
 * {@code LogUpdate} is the diff engine that converts two consecutive rendered
 * frames into a list of terminal-write patches ({@link InkFrame.Diff}). It
 * maintains a minimal amount of state ({@code previousOutput} flag) and
 * delegates heavy lifting to a {@link VirtualScreen} cursor tracker.
 *
 * <p>The TypeScript source is ~750 lines; this translation captures the public
 * API ({@link #render}, {@link #reset}, {@link #renderPreviousOutputDEPRECATED})
 * and the key algorithmic decisions (shrink/grow handling, DECSTBM scroll
 * optimization, scrollback detection, cursor restore). Internal helper functions
 * are translated as private static methods.
 */
@Slf4j
public final class LogUpdate {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LogUpdate.class);


    // -----------------------------------------------------------------------
    // State / options
    // -----------------------------------------------------------------------

    private boolean previousOutputEmpty = true;
    private final boolean isTTY;
    private final InkScreen.StylePool stylePool;

    // Reusable patch constants
    private static final InkFrame.CarriageReturnPatch CARRIAGE_RETURN = new InkFrame.CarriageReturnPatch();
    private static final InkFrame.StdoutPatch NEWLINE = new InkFrame.StdoutPatch("\n");

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public LogUpdate(boolean isTTY, InkScreen.StylePool stylePool) {
        this.isTTY = isTTY;
        this.stylePool = stylePool;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Called when the process resumes from SIGCONT suspension.
     * Mirrors the TS {@code reset()} method.
     */
    public void reset() {
        previousOutputEmpty = true;
    }

    /**
     * Deprecated: render the previous frame for "done" state output.
     * Mirrors the TS {@code renderPreviousOutput_DEPRECATED} method.
     */
    public List<InkFrame.Patch> renderPreviousOutputDEPRECATED(InkFrame.Frame prevFrame) {
        if (!isTTY) {
            return List.of(NEWLINE);
        }
        return getRenderOpsForDone(prevFrame);
    }

    /**
     * Compute the diff from {@code prev} to {@code next} as a list of terminal patches.
     *
     * @param prev      previous frame
     * @param next      next frame to render
     * @param altScreen true when rendering into the alt-screen buffer
     * @param decstbmSafe true when DECSTBM scroll optimization is safe to use
     * @return list of patches to apply to the terminal
     */
    public List<InkFrame.Patch> render(InkFrame.Frame prev, InkFrame.Frame next,
                                        boolean altScreen, boolean decstbmSafe) {
        if (!isTTY) {
            return renderFullFrame(next);
        }

        long startTime = System.currentTimeMillis();

        // Viewport resize: full reset
        if (next.getViewport().height() < prev.getViewport().height()
                || (prev.getViewport().width() != 0
                    && next.getViewport().width() != prev.getViewport().width())) {
            return fullResetSequence(next, InkFrame.FlickerReason.RESIZE, stylePool, null);
        }

        // DECSTBM scroll optimisation
        List<InkFrame.Patch> scrollPatch = new ArrayList<>();
        if (altScreen && next.getScrollHint() != null && decstbmSafe) {
            InkFrame.ScrollHint hint = next.getScrollHint();
            int top = hint.top(), bottom = hint.bottom(), delta = hint.delta();
            if (top >= 0 && bottom < prev.getScreen().height && bottom < next.getScreen().height) {
                InkScreen.shiftRows(prev.getScreen(), top, bottom, delta);
                // Emit CSI scroll sequence
                scrollPatch.add(new InkFrame.StdoutPatch(
                    "\u001b[" + (top + 1) + ";" + (bottom + 1) + "r"
                    + (delta > 0 ? "\u001b[" + delta + "S" : "\u001b[" + (-delta) + "T")
                    + "\u001b[r"
                    + "\u001b[H"));
            }
        }

        boolean cursorAtBottom = prev.getCursor().y() >= prev.getScreen().height;
        boolean isGrowing = next.getScreen().height > prev.getScreen().height;
        boolean prevHadScrollback =
                cursorAtBottom && prev.getScreen().height >= prev.getViewport().height();
        boolean isShrinking = next.getScreen().height < prev.getScreen().height;
        boolean nextFitsViewport = next.getScreen().height <= prev.getViewport().height();

        if (prevHadScrollback && nextFitsViewport && isShrinking) {
            log.debug("Full reset (shrink->below): prevH={} nextH={} vp={}",
                      prev.getScreen().height, next.getScreen().height, prev.getViewport().height());
            return fullResetSequence(next, InkFrame.FlickerReason.OFFSCREEN, stylePool, null);
        }

        if (prev.getScreen().height >= prev.getViewport().height()
                && prev.getScreen().height > 0
                && cursorAtBottom && !isGrowing) {
            int viewportY = prev.getScreen().height - prev.getViewport().height();
            int scrollbackRows = viewportY + 1;
            int[] scrollbackChangeY = {-1};
            InkScreen.diffEach(prev.getScreen(), next.getScreen(), (x, y, removed, added) -> {
                if (y < scrollbackRows) {
                    scrollbackChangeY[0] = y;
                    return true;
                }
                return false;
            });
            if (scrollbackChangeY[0] >= 0) {
                int ty = scrollbackChangeY[0];
                InkFrame.ClearTerminalDebug dbg = new InkFrame.ClearTerminalDebug(
                    ty, readLine(prev.getScreen(), ty), readLine(next.getScreen(), ty));
                return fullResetSequence(next, InkFrame.FlickerReason.OFFSCREEN, stylePool, dbg);
            }
        }

        VirtualScreen screen = new VirtualScreen(prev.getCursor().x(), prev.getCursor().y(),
                                                  next.getViewport().width());

        int heightDelta = Math.max(next.getScreen().height, 1) - Math.max(prev.getScreen().height, 1);
        boolean shrinking = heightDelta < 0;
        boolean growing   = heightDelta > 0;

        if (shrinking) {
            int linesToClear = prev.getScreen().height - next.getScreen().height;
            if (linesToClear > prev.getViewport().height()) {
                return fullResetSequence(next, InkFrame.FlickerReason.OFFSCREEN, stylePool, null);
            }
            int finalLinesToClear = linesToClear;
            screen.txn(prev2 -> {
                List<InkFrame.Patch> patches = new ArrayList<>();
                patches.add(new InkFrame.ClearPatch(finalLinesToClear));
                patches.add(new InkFrame.CursorMovePatch(0, -1));
                return new TxnResult(patches, -prev2[0], -finalLinesToClear);
            });
        }

        int cursorRestoreScroll = prevHadScrollback ? 1 : 0;
        int viewportY = growing
            ? Math.max(0, prev.getScreen().height - prev.getViewport().height() + cursorRestoreScroll)
            : Math.max(prev.getScreen().height, next.getScreen().height)
              - next.getViewport().height() + cursorRestoreScroll;

        int[] currentStyleId = {stylePool.none};
        @Nullable String[] currentHyperlink = {null};

        boolean[] needsFullReset = {false};
        int[] resetTriggerY = {-1};

        InkScreen.diffEach(prev.getScreen(), next.getScreen(), (x, y, removed, added) -> {
            if (growing && y >= prev.getScreen().height) return false;

            if (added != null && (added.width == InkScreen.CellWidth.SPACER_TAIL
                               || added.width == InkScreen.CellWidth.SPACER_HEAD)) return false;
            if (removed != null && (removed.width == InkScreen.CellWidth.SPACER_TAIL
                                 || removed.width == InkScreen.CellWidth.SPACER_HEAD)
                    && added == null) return false;

            if (added != null && InkScreen.isEmptyCellAt(next.getScreen(), x, y) && removed == null)
                return false;

            if (y < viewportY) {
                needsFullReset[0] = true;
                resetTriggerY[0] = y;
                return true;
            }

            moveCursorTo(screen, x, y);

            if (added != null) {
                currentHyperlink[0] = transitionHyperlink(screen.diff, currentHyperlink[0], added.hyperlink);
                String styleStr = stylePool.transition(currentStyleId[0], added.styleId);
                if (writeCellWithStyleStr(screen, added, styleStr)) {
                    currentStyleId[0] = added.styleId;
                }
            } else if (removed != null) {
                int styleToReset = currentStyleId[0];
                String hlToReset = currentHyperlink[0];
                currentStyleId[0] = stylePool.none;
                currentHyperlink[0] = null;
                screen.txn(prev2 -> {
                    List<InkFrame.Patch> patches = new ArrayList<>();
                    transitionStyle(patches, stylePool, styleToReset, stylePool.none);
                    transitionHyperlink(patches, hlToReset, null);
                    patches.add(new InkFrame.StdoutPatch(" "));
                    return new TxnResult(patches, 1, 0);
                });
            }
            return false;
        });

        if (needsFullReset[0]) {
            InkFrame.ClearTerminalDebug dbg = new InkFrame.ClearTerminalDebug(
                resetTriggerY[0],
                readLine(prev.getScreen(), resetTriggerY[0]),
                readLine(next.getScreen(), resetTriggerY[0]));
            return fullResetSequence(next, InkFrame.FlickerReason.OFFSCREEN, stylePool, dbg);
        }

        currentStyleId[0] = transitionStyle(screen.diff, stylePool, currentStyleId[0], stylePool.none);
        currentHyperlink[0] = transitionHyperlink(screen.diff, currentHyperlink[0], null);

        if (growing) {
            renderFrameSlice(screen, next, prev.getScreen().height, next.getScreen().height, stylePool);
        }

        if (!altScreen) {
            if (next.getCursor().y() >= next.getScreen().height) {
                int[] cy = {screen.cursorY};
                int[] cx = {screen.cursorX};
                screen.txn(prev2 -> {
                    int rowsToCreate = next.getCursor().y() - cy[0];
                    if (rowsToCreate > 0) {
                        List<InkFrame.Patch> patches = new ArrayList<>();
                        patches.add(CARRIAGE_RETURN);
                        for (int i = 0; i < rowsToCreate; i++) patches.add(NEWLINE);
                        return new TxnResult(patches, -cx[0], rowsToCreate);
                    }
                    int dy = next.getCursor().y() - cy[0];
                    if (dy != 0 || cx[0] != next.getCursor().x()) {
                        List<InkFrame.Patch> patches = new ArrayList<>();
                        patches.add(CARRIAGE_RETURN);
                        patches.add(new InkFrame.CursorMovePatch(next.getCursor().x(), dy));
                        return new TxnResult(patches, next.getCursor().x() - cx[0], dy);
                    }
                    return new TxnResult(List.of(), 0, 0);
                });
            } else {
                moveCursorTo(screen, next.getCursor().x(), next.getCursor().y());
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed > 50) {
            log.debug("Slow render: {}ms, screen: {}x{}, changes: {}",
                      elapsed, next.getScreen().height, next.getScreen().width, screen.diff.size());
        }

        if (!scrollPatch.isEmpty()) {
            List<InkFrame.Patch> combined = new ArrayList<>(scrollPatch);
            combined.addAll(screen.diff);
            return combined;
        }
        return screen.diff;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private List<InkFrame.Patch> getRenderOpsForDone(InkFrame.Frame prev) {
        previousOutputEmpty = true;
        if (!prev.getCursor().visible()) {
            return List.of(new InkFrame.CursorShowPatch());
        }
        return List.of();
    }

    private List<InkFrame.Patch> renderFullFrame(InkFrame.Frame frame) {
        InkScreen.Screen screen = frame.getScreen();
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < screen.height; y++) {
            StringBuilder line = new StringBuilder();
            for (int x = 0; x < screen.width; x++) {
                InkScreen.Cell cell = InkScreen.cellAt(screen, x, y);
                if (cell != null && cell.width != InkScreen.CellWidth.SPACER_TAIL) {
                    line.append(cell.charStr);
                }
            }
            // trim trailing spaces
            int end = line.length();
            while (end > 0 && line.charAt(end - 1) == ' ') end--;
            sb.append(line, 0, end);
            if (y < screen.height - 1) sb.append('\n');
        }
        if (sb.length() == 0) return List.of();
        return List.of(new InkFrame.StdoutPatch(sb.toString()));
    }

    private static List<InkFrame.Patch> fullResetSequence(
            InkFrame.Frame frame, InkFrame.FlickerReason reason,
            InkScreen.StylePool stylePool,
            @Nullable InkFrame.ClearTerminalDebug debug) {
        VirtualScreen screen = new VirtualScreen(0, 0, frame.getViewport().width());
        renderFrame(screen, frame, stylePool);
        List<InkFrame.Patch> result = new ArrayList<>();
        result.add(new InkFrame.ClearTerminalPatch(reason, debug));
        result.addAll(screen.diff);
        return result;
    }

    private static void renderFrame(VirtualScreen screen, InkFrame.Frame frame,
                                     InkScreen.StylePool stylePool) {
        renderFrameSlice(screen, frame, 0, frame.getScreen().height, stylePool);
    }

    private static void renderFrameSlice(VirtualScreen screen, InkFrame.Frame frame,
                                          int startY, int endY,
                                          InkScreen.StylePool stylePool) {
        int[] currentStyleId = {stylePool.none};
        @Nullable String[] currentHyperlink = {null};

        InkScreen.Screen s = frame.getScreen();
        for (int y = startY; y < endY; y++) {
            if (screen.cursorY < y) {
                int rowsToAdvance = y - screen.cursorY;
                int cx = screen.cursorX;
                screen.txn(prev -> {
                    List<InkFrame.Patch> patches = new ArrayList<>();
                    patches.add(CARRIAGE_RETURN);
                    for (int i = 0; i < rowsToAdvance; i++) patches.add(NEWLINE);
                    return new TxnResult(patches, -cx, rowsToAdvance);
                });
            }
            for (int x = 0; x < s.width; x++) {
                InkScreen.Cell cell = InkScreen.cellAt(s, x, y);
                if (cell == null
                        || cell.width == InkScreen.CellWidth.SPACER_TAIL
                        || cell.width == InkScreen.CellWidth.SPACER_HEAD) continue;
                if (cell.charStr.equals(" ") && (cell.styleId & 1) == 0
                        && cell.hyperlink == null) continue;
                moveCursorTo(screen, x, y);
                currentHyperlink[0] = transitionHyperlink(screen.diff, currentHyperlink[0], cell.hyperlink);
                String styleStr = stylePool.transition(currentStyleId[0], cell.styleId);
                if (writeCellWithStyleStr(screen, cell, styleStr)) {
                    currentStyleId[0] = cell.styleId;
                }
            }
            currentStyleId[0] = transitionStyle(screen.diff, stylePool, currentStyleId[0], stylePool.none);
            currentHyperlink[0] = transitionHyperlink(screen.diff, currentHyperlink[0], null);
            int cx = screen.cursorX;
            screen.txn(prev -> new TxnResult(List.of(CARRIAGE_RETURN, NEWLINE), -cx, 1));
        }
        transitionStyle(screen.diff, stylePool, currentStyleId[0], stylePool.none);
        transitionHyperlink(screen.diff, currentHyperlink[0], null);
    }

    private static @Nullable String transitionHyperlink(List<InkFrame.Patch> diff,
                                                         @Nullable String current,
                                                         @Nullable String target) {
        if (!java.util.Objects.equals(current, target)) {
            diff.add(new InkFrame.HyperlinkPatch(target != null ? target : ""));
            return target;
        }
        return current;
    }

    private static int transitionStyle(List<InkFrame.Patch> diff, InkScreen.StylePool stylePool,
                                        int currentId, int targetId) {
        String str = stylePool.transition(currentId, targetId);
        if (!str.isEmpty()) diff.add(new InkFrame.StyleStrPatch(str));
        return targetId;
    }

    private static String readLine(InkScreen.Screen screen, int y) {
        StringBuilder sb = new StringBuilder();
        for (int x = 0; x < screen.width; x++) {
            String ch = InkScreen.charInCellAt(screen, x, y);
            sb.append(ch != null ? ch : " ");
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') end--;
        return sb.substring(0, end);
    }

    private static boolean writeCellWithStyleStr(VirtualScreen screen, InkScreen.Cell cell, String styleStr) {
        int cellWidth = cell.width == InkScreen.CellWidth.WIDE ? 2 : 1;
        int px = screen.cursorX;
        int vw = screen.viewportWidth;
        if (cellWidth == 2 && px < vw) {
            int threshold = cell.charStr.length() > 2 ? vw : vw + 1;
            if (px + 2 >= threshold) return false;
        }
        if (!styleStr.isEmpty()) screen.diff.add(new InkFrame.StyleStrPatch(styleStr));
        screen.diff.add(new InkFrame.StdoutPatch(cell.charStr));
        if (px >= vw) {
            screen.cursorX = cellWidth;
            screen.cursorY++;
        } else {
            screen.cursorX = px + cellWidth;
        }
        return true;
    }

    private static void moveCursorTo(VirtualScreen screen, int targetX, int targetY) {
        screen.txn(prev -> {
            int dx = targetX - prev[0];
            int dy = targetY - prev[1];
            boolean inPendingWrap = prev[0] >= screen.viewportWidth;
            if (inPendingWrap || dy != 0) {
                return new TxnResult(
                    List.of(CARRIAGE_RETURN, new InkFrame.CursorMovePatch(targetX, dy)),
                    dx, dy);
            }
            return new TxnResult(List.of(new InkFrame.CursorMovePatch(dx, 0)), dx, 0);
        });
    }

    // -----------------------------------------------------------------------
    // VirtualScreen
    // -----------------------------------------------------------------------

    /** Tracks cursor position and accumulates diff patches. */
    static final class VirtualScreen {
        int cursorX;
        int cursorY;
        final int viewportWidth;
        final List<InkFrame.Patch> diff = new ArrayList<>();

        VirtualScreen(int x, int y, int vw) {
            cursorX = x; cursorY = y; viewportWidth = vw;
        }

        @FunctionalInterface
        interface TxnFn { TxnResult apply(int[] prev); }

        void txn(TxnFn fn) {
            TxnResult r = fn.apply(new int[]{cursorX, cursorY});
            diff.addAll(r.patches);
            cursorX += r.dx;
            cursorY += r.dy;
        }
    }

    /** Result of a VirtualScreen transaction. */
    record TxnResult(List<InkFrame.Patch> patches, int dx, int dy) {}
}
