package com.anthropic.claudecode.util.ink;

import lombok.Builder;
import lombok.Value;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Java equivalent of frame.ts.
 *
 * Represents a single rendered frame passed to the diff / write pipeline.
 * The TypeScript source exports plain object types; here we use Java records
 * and sealed interfaces where appropriate.
 */
public final class InkFrame {

    private InkFrame() {}

    // -----------------------------------------------------------------------
    // FlickerReason
    // -----------------------------------------------------------------------

    /**
     * Reason for a full screen clear / flicker.
     * Mirrors the TypeScript union {@code 'resize' | 'offscreen' | 'clear'}.
     */
    public enum FlickerReason {
        RESIZE, OFFSCREEN, CLEAR
    }

    // -----------------------------------------------------------------------
    // Viewport / Cursor helpers (mirrors geometry types used in Frame)
    // -----------------------------------------------------------------------

    /** Terminal viewport dimensions. */
    public record Viewport(int width, int height) {}

    /** Terminal cursor position and visibility. */
    public record Cursor(int x, int y, boolean visible) {}

    // -----------------------------------------------------------------------
    // ScrollHint (mirrors ScrollHint from render-node-to-output.ts)
    // -----------------------------------------------------------------------

    /**
     * DECSTBM scroll-optimization hint (alt-screen only).
     * Passed through from the render-node-to-output pipeline.
     */
    public record ScrollHint(int top, int bottom, int delta) {}

    // -----------------------------------------------------------------------
    // Frame
    // -----------------------------------------------------------------------

    /**
     * Immutable snapshot of one rendered frame.
     *
     * <p>Mirrors the TypeScript {@code Frame} type. {@link #screen} is the
     * rendered cell buffer; {@link #viewport} is the terminal size;
     * {@link #cursor} is where the terminal cursor should land after writing.
     */
    public static class Frame {
        private final InkScreen.Screen screen;
        private final Viewport viewport;
        private final Cursor cursor;
        @Nullable private final ScrollHint scrollHint;
        private final boolean scrollDrainPending;

        private Frame(InkScreen.Screen screen, Viewport viewport, Cursor cursor, ScrollHint scrollHint, boolean scrollDrainPending) {
            this.screen = screen; this.viewport = viewport; this.cursor = cursor;
            this.scrollHint = scrollHint; this.scrollDrainPending = scrollDrainPending;
        }

        public InkScreen.Screen getScreen() { return screen; }
        public Viewport getViewport() { return viewport; }
        public Cursor getCursor() { return cursor; }
        public ScrollHint getScrollHint() { return scrollHint; }
        public boolean isScrollDrainPending() { return scrollDrainPending; }

        public static FrameBuilder builder() { return new FrameBuilder(); }
        public static class FrameBuilder {
            private InkScreen.Screen screen; private Viewport viewport; private Cursor cursor;
            private ScrollHint scrollHint; private boolean scrollDrainPending;
            public FrameBuilder screen(InkScreen.Screen v) { this.screen = v; return this; }
            public FrameBuilder viewport(Viewport v) { this.viewport = v; return this; }
            public FrameBuilder cursor(Cursor v) { this.cursor = v; return this; }
            public FrameBuilder scrollHint(ScrollHint v) { this.scrollHint = v; return this; }
            public FrameBuilder scrollDrainPending(boolean v) { this.scrollDrainPending = v; return this; }
            public Frame build() { return new Frame(screen, viewport, cursor, scrollHint, scrollDrainPending); }
        }
    }

    /**
     * Create an empty frame at the given terminal size.
     * Mirrors the TS {@code emptyFrame()} factory function.
     */
    public static Frame emptyFrame(
            int rows,
            int columns,
            InkScreen.StylePool stylePool,
            InkScreen.CharPool charPool,
            InkScreen.HyperlinkPool hyperlinkPool) {
        return Frame.builder()
                .screen(InkScreen.createScreen(0, 0, stylePool, charPool, hyperlinkPool))
                .viewport(new Viewport(columns, rows))
                .cursor(new Cursor(0, 0, true))
                .build();
    }

    // -----------------------------------------------------------------------
    // shouldClearScreen
    // -----------------------------------------------------------------------

    /**
     * Determine whether the terminal screen must be fully cleared before
     * writing the next frame.
     *
     * <p>Mirrors the TypeScript {@code shouldClearScreen()} function:
     * <ul>
     *   <li>{@link FlickerReason#RESIZE} — viewport dimensions changed.</li>
     *   <li>{@link FlickerReason#OFFSCREEN} — current or previous frame
     *       overflows the available rows.</li>
     *   <li>{@code null} — no clear needed.</li>
     * </ul>
     */
    @Nullable
    public static FlickerReason shouldClearScreen(Frame prevFrame, Frame frame) {
        boolean didResize = frame.viewport.height() != prevFrame.viewport.height()
                || frame.viewport.width() != prevFrame.viewport.width();
        if (didResize) return FlickerReason.RESIZE;

        boolean currentOverflows = frame.screen.height >= frame.viewport.height();
        boolean previousOverflowed = prevFrame.screen.height >= prevFrame.viewport.height();
        if (currentOverflows || previousOverflowed) return FlickerReason.OFFSCREEN;

        return null;
    }

    // -----------------------------------------------------------------------
    // FrameEvent (timing / flicker diagnostics)
    // -----------------------------------------------------------------------

    /**
     * Per-frame timing and flicker information.
     * Mirrors the TypeScript {@code FrameEvent} type.
     */
    @Value
    @Builder
    public static class FrameEvent {
        double durationMs;
        @Nullable Phases phases;
        List<FlickerInfo> flickers;
    }

    /**
     * Detailed phase breakdown for one frame.
     * Mirrors the {@code phases} field inside {@code FrameEvent}.
     */
    @Value
    @Builder
    public static class Phases {
        double renderer;
        double diff;
        double optimize;
        double write;
        int patches;
        double yoga;
        double commit;
        int yogaVisited;
        int yogaMeasured;
        int yogaCacheHits;
        int yogaLive;
    }

    /**
     * Information about a single flicker event (screen overflow).
     * Mirrors the array element inside {@code FrameEvent.flickers}.
     */
    public record FlickerInfo(int desiredHeight, int availableHeight, FlickerReason reason) {}

    // -----------------------------------------------------------------------
    // Patch — sealed hierarchy
    // -----------------------------------------------------------------------

    /**
     * A single terminal-write operation produced by the diff pipeline.
     * Mirrors the TypeScript discriminated union {@code Patch}.
     */
    public sealed interface Patch permits
            InkFrame.StdoutPatch,
            InkFrame.ClearPatch,
            InkFrame.ClearTerminalPatch,
            InkFrame.CursorHidePatch,
            InkFrame.CursorShowPatch,
            InkFrame.CursorMovePatch,
            InkFrame.CursorToPatch,
            InkFrame.CarriageReturnPatch,
            InkFrame.HyperlinkPatch,
            InkFrame.StyleStrPatch {
    }

    /** Write raw content to stdout. */
    public record StdoutPatch(String content) implements Patch {}

    /** Erase {@code count} lines upward from the current cursor position. */
    public record ClearPatch(int count) implements Patch {}

    /**
     * Full terminal clear (causes flicker). Optionally carries a debug payload
     * with the Y-coordinate and line content that triggered the reset.
     */
    public record ClearTerminalPatch(FlickerReason reason, @Nullable ClearTerminalDebug debug)
            implements Patch {}

    /** Debug payload for {@link ClearTerminalPatch}. */
    public record ClearTerminalDebug(int triggerY, String prevLine, String nextLine) {}

    /** Hide the terminal cursor (CSI ?25l). */
    public record CursorHidePatch() implements Patch {}

    /** Show the terminal cursor (CSI ?25h). */
    public record CursorShowPatch() implements Patch {}

    /** Move cursor by a relative (dx, dy) offset. */
    public record CursorMovePatch(int x, int y) implements Patch {}

    /** Move cursor to an absolute column (CHA, 1-based in the terminal). */
    public record CursorToPatch(int col) implements Patch {}

    /** Emit a carriage-return ({@code \r}) character. */
    public record CarriageReturnPatch() implements Patch {}

    /**
     * Emit an OSC 8 hyperlink sequence. An empty {@code uri} closes the link
     * (equivalent to {@code LINK_END} in the TS source).
     */
    public record HyperlinkPatch(String uri) implements Patch {}

    /**
     * Pre-serialized style-transition ANSI string from
     * {@link InkScreen.StylePool#transition}. Zero allocations after warm-up.
     */
    public record StyleStrPatch(String str) implements Patch {}
}
